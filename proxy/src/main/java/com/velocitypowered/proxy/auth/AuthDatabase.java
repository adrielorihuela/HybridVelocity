/*
 * Copyright (C) 2026 Velocity Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.velocitypowered.proxy.auth;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Stores offline players' password records in an embedded SQLite database.
 *
 * <p>Every operation runs on a single-threaded executor and returns a future, so no JDBC call ever
 * touches a Netty event loop. That single thread also serialises access to the one connection.</p>
 */
public final class AuthDatabase {

  private static final Logger logger = LogManager.getLogger(AuthDatabase.class);

  private static final String CREATE_TABLE = """
      CREATE TABLE IF NOT EXISTS offline_player_auth (
        uuid TEXT PRIMARY KEY,
        username TEXT NOT NULL,
        password_hash TEXT NOT NULL,
        created_at INTEGER NOT NULL,
        updated_at INTEGER NOT NULL
      )""";
  private static final String SELECT =
      "SELECT username, password_hash, created_at, updated_at FROM offline_player_auth "
          + "WHERE uuid = ?";
  private static final String INSERT =
      "INSERT INTO offline_player_auth (uuid, username, password_hash, created_at, updated_at) "
          + "VALUES (?, ?, ?, ?, ?)";
  private static final String UPDATE =
      "UPDATE offline_player_auth SET password_hash = ?, updated_at = ? WHERE uuid = ?";

  private final Path databaseFile;
  private final ExecutorService executor;
  private @Nullable Connection connection;

  /**
   * Creates a database bound to a file. Nothing is opened until {@link #init()}.
   *
   * @param databaseFile the SQLite file
   */
  public AuthDatabase(final Path databaseFile) {
    this.databaseFile = databaseFile;
    this.executor = Executors.newSingleThreadExecutor(
        new ThreadFactoryBuilder()
            .setNameFormat("Velocity Offline Auth DB")
            .setDaemon(true)
            .build());
  }

  /**
   * Opens the database and creates the schema if it is missing.
   *
   * @throws SQLException if the database cannot be opened or the schema cannot be created
   */
  public void init() throws SQLException {
    final Path parent = databaseFile.toAbsolutePath().getParent();
    if (parent != null) {
      try {
        java.nio.file.Files.createDirectories(parent);
      } catch (java.io.IOException e) {
        throw new SQLException("Could not create the directory for " + databaseFile, e);
      }
    }

    final Connection opened =
        DriverManager.getConnection("jdbc:sqlite:" + databaseFile.toAbsolutePath());
    try (Statement statement = opened.createStatement()) {
      statement.execute(CREATE_TABLE);
    } catch (SQLException e) {
      try {
        opened.close();
      } catch (SQLException suppressed) {
        e.addSuppressed(suppressed);
      }
      throw e;
    }
    this.connection = opened;
    logger.info("Offline authentication database ready at {}", databaseFile.toAbsolutePath());
  }

  /** Returns whether {@link #init()} has succeeded. */
  public boolean isInitialized() {
    return connection != null;
  }

  /**
   * Looks up a player's password record.
   *
   * <p>A query failure resolves to {@link PasswordLookup#failed()}, never to "not registered" —
   * the caller must be able to tell the two apart and refuse the login rather than offer
   * {@code /register} for an account that may already exist.</p>
   *
   * @param uuid the player's offline UUID
   * @return the lookup outcome
   */
  public CompletableFuture<PasswordLookup> getPasswordRecord(final UUID uuid) {
    return supply(() -> {
      final Connection conn = requireConnection();
      try (PreparedStatement statement = conn.prepareStatement(SELECT)) {
        statement.setString(1, uuid.toString());
        try (ResultSet rs = statement.executeQuery()) {
          if (!rs.next()) {
            return PasswordLookup.notRegistered();
          }
          return PasswordLookup.found(new PasswordRecord(
              uuid,
              rs.getString("username"),
              rs.getString("password_hash"),
              Instant.ofEpochMilli(rs.getLong("created_at")),
              Instant.ofEpochMilli(rs.getLong("updated_at"))));
        }
      }
    }, PasswordLookup.failed(), "look up the password record for " + uuid);
  }

  /**
   * Stores a new password record.
   *
   * @param uuid the player's offline UUID
   * @param username the player's dotted username
   * @param passwordHash the bcrypt hash
   * @return whether the record was stored
   */
  public CompletableFuture<Boolean> createPasswordRecord(
      final UUID uuid, final String username, final String passwordHash) {
    return supply(() -> {
      final Connection conn = requireConnection();
      final long now = System.currentTimeMillis();
      try (PreparedStatement statement = conn.prepareStatement(INSERT)) {
        statement.setString(1, uuid.toString());
        statement.setString(2, username);
        statement.setString(3, passwordHash);
        statement.setLong(4, now);
        statement.setLong(5, now);
        return statement.executeUpdate() > 0;
      }
    }, false, "store the password record for " + uuid);
  }

  /**
   * Replaces a player's password.
   *
   * @param uuid the player's offline UUID
   * @param newPasswordHash the new bcrypt hash
   * @return whether a record was updated
   */
  public CompletableFuture<Boolean> updatePassword(
      final UUID uuid, final String newPasswordHash) {
    return supply(() -> {
      final Connection conn = requireConnection();
      try (PreparedStatement statement = conn.prepareStatement(UPDATE)) {
        statement.setString(1, newPasswordHash);
        statement.setLong(2, System.currentTimeMillis());
        statement.setString(3, uuid.toString());
        return statement.executeUpdate() > 0;
      }
    }, false, "update the password for " + uuid);
  }

  /** Closes the database and stops the executor. */
  public void close() {
    executor.shutdown();
    try {
      if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
        executor.shutdownNow();
      }
    } catch (InterruptedException e) {
      executor.shutdownNow();
      Thread.currentThread().interrupt();
    }

    final Connection conn = this.connection;
    this.connection = null;
    if (conn != null) {
      try {
        conn.close();
      } catch (SQLException e) {
        logger.error("Could not close the offline authentication database", e);
      }
    }
  }

  private Connection requireConnection() throws SQLException {
    final Connection conn = this.connection;
    if (conn == null) {
      throw new SQLException("The offline authentication database is not open");
    }
    return conn;
  }

  /**
   * Runs a query off the event loop, logging and substituting a fail-closed value on error.
   */
  private <T> CompletableFuture<T> supply(
      final SqlSupplier<T> query, final T onFailure, final String description) {
    final CompletableFuture<T> future = new CompletableFuture<>();
    try {
      executor.execute(() -> {
        try {
          future.complete(query.get());
        } catch (SQLException e) {
          logger.error("Could not {}", description, e);
          future.complete(onFailure);
        }
      });
    } catch (java.util.concurrent.RejectedExecutionException e) {
      logger.error("Could not {}: the database is shutting down", description);
      future.complete(onFailure);
    }
    return future;
  }

  @FunctionalInterface
  private interface SqlSupplier<T> {
    T get() throws SQLException;
  }
}

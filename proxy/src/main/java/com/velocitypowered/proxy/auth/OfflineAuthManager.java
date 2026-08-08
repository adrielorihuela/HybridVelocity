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
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Owns the offline authentication state: the password database, the bcrypt worker pool and which
 * players have authenticated in the current session.
 *
 * <p>Nothing here blocks a Netty event loop. Database work is serialised on
 * {@link AuthDatabase}'s own thread and bcrypt runs on a small pool of its own, because hashing is
 * deliberately expensive.</p>
 */
public final class OfflineAuthManager {

  private static final Logger logger = LogManager.getLogger(OfflineAuthManager.class);
  private static final int HASHING_THREADS = 2;

  private final AuthDatabase database;
  private final ExecutorService hashingExecutor;
  private final Map<UUID, AuthState> states = new ConcurrentHashMap<>();

  private boolean available;

  /**
   * Creates a manager backed by the given SQLite file. Nothing is opened until {@link #start()}.
   *
   * @param databaseFile the SQLite file
   */
  public OfflineAuthManager(final Path databaseFile) {
    this.database = new AuthDatabase(databaseFile);
    this.hashingExecutor = Executors.newFixedThreadPool(HASHING_THREADS,
        new ThreadFactoryBuilder()
            .setNameFormat("Velocity Offline Auth Hashing #%d")
            .setDaemon(true)
            .build());
  }

  /**
   * Opens the database.
   *
   * @return whether authentication is usable; if this is {@code false} the gate must refuse
   *     offline players rather than let them through unauthenticated
   */
  public boolean start() {
    try {
      database.init();
      available = true;
    } catch (SQLException e) {
      logger.error("Could not open the offline authentication database. Offline players will be "
          + "refused until this is fixed.", e);
      available = false;
    }
    return available;
  }

  /** Whether the database is open and authentication can proceed. */
  public boolean isAvailable() {
    return available && database.isInitialized();
  }

  /** Closes the database and stops the worker pools. */
  public void shutdown() {
    available = false;
    states.clear();
    hashingExecutor.shutdown();
    try {
      if (!hashingExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
        hashingExecutor.shutdownNow();
      }
    } catch (InterruptedException e) {
      hashingExecutor.shutdownNow();
      Thread.currentThread().interrupt();
    }
    database.close();
  }

  /**
   * Looks up whether a player has registered.
   *
   * @param uuid the player's offline UUID
   * @return the lookup outcome; a failure must be treated as "cannot proceed", not "not registered"
   */
  public CompletableFuture<PasswordLookup> lookup(final UUID uuid) {
    return database.getPasswordRecord(uuid);
  }

  /**
   * Registers a new password. The caller must have validated it with
   * {@link PasswordUtil#validatePassword} first.
   *
   * @param uuid the player's offline UUID
   * @param username the player's dotted username
   * @param password the plaintext password
   * @return whether the record was stored
   */
  public CompletableFuture<Boolean> register(
      final UUID uuid, final String username, final String password) {
    return hash(password)
        .thenCompose(hash -> database.createPasswordRecord(uuid, username, hash));
  }

  /**
   * Verifies a password against the stored record.
   *
   * @param record the stored record
   * @param password the plaintext password to check
   * @return whether it matches
   */
  public CompletableFuture<Boolean> verify(final PasswordRecord record, final String password) {
    return CompletableFuture.supplyAsync(
        () -> PasswordUtil.verifyPassword(password, record.passwordHash()), hashingExecutor);
  }

  /**
   * Replaces a player's password. The caller must have validated it first.
   *
   * @param uuid the player's offline UUID
   * @param newPassword the new plaintext password
   * @return whether the record was updated
   */
  public CompletableFuture<Boolean> changePassword(final UUID uuid, final String newPassword) {
    return hash(newPassword).thenCompose(hash -> database.updatePassword(uuid, hash));
  }

  /**
   * Records whether a player has an account, so the synchronous {@code requires} predicate on
   * {@code /register} and {@code /login} can decide which of the two to show without touching the
   * database.
   *
   * @param uuid the player's offline UUID
   * @param registered whether they have a stored password
   */
  public void setKnownRegistered(final UUID uuid, final boolean registered) {
    states.computeIfAbsent(uuid, ignored -> new AuthState()).registered = registered;
  }

  /**
   * Whether the player is known to have an account.
   *
   * @param uuid the player's offline UUID
   * @return {@code TRUE} or {@code FALSE} once looked up, {@code null} while still unknown
   */
  public @Nullable Boolean isKnownRegistered(final UUID uuid) {
    final AuthState state = states.get(uuid);
    return state == null ? null : state.registered;
  }

  /** Marks a player as authenticated for the rest of their session. */
  public void markAuthenticated(final UUID uuid) {
    states.computeIfAbsent(uuid, ignored -> new AuthState()).authenticated = true;
  }

  /** Whether the player has authenticated in this session. */
  public boolean isAuthenticated(final UUID uuid) {
    final AuthState state = states.get(uuid);
    return state != null && state.authenticated;
  }

  /**
   * Records a failed login attempt.
   *
   * @param uuid the player's offline UUID
   * @return the number of failures so far, including this one
   */
  public int recordFailedAttempt(final UUID uuid) {
    return ++states.computeIfAbsent(uuid, ignored -> new AuthState()).failedAttempts;
  }

  /** Forgets a player's session state, on disconnect. */
  public void forget(final UUID uuid) {
    states.remove(uuid);
  }

  private CompletableFuture<String> hash(final String password) {
    return CompletableFuture.supplyAsync(
        () -> PasswordUtil.hashPassword(password), hashingExecutor);
  }

  /**
   * Per-session state. Deliberately not persisted: a proxy restart means everyone authenticates
   * again, which is what the specification calls for.
   */
  private static final class AuthState {
    private volatile boolean authenticated;
    private volatile @Nullable Boolean registered;
    private int failedAttempts;
  }
}

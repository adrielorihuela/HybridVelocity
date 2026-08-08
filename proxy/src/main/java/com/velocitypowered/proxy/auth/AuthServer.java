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

import com.velocitypowered.proxy.config.PlayerInfoForwarding;
import com.velocitypowered.proxy.config.VelocityConfiguration;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.serialize.SerializationException;
import org.spongepowered.configurate.yaml.NodeStyle;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;
import ua.nanit.limbo.server.LimboServer;

/**
 * The authentication server: a limbo world run inside the proxy JVM, bound to loopback, where
 * players wait until they register or log in.
 *
 * <p>{@code auth/settings.yml} is written once from a documented template and then never touched
 * again, so the operator's edits and its comments survive. The two values they must not set — the
 * bind address, which has to stay on loopback, and the forwarding, which has to agree with the
 * proxy's — are merged in at start-up into {@code auth/generated/settings.yml}, which is what
 * actually runs.</p>
 *
 * <p>See {@code docs/auth-server.md}.</p>
 */
public final class AuthServer {

  private static final Logger logger = LogManager.getLogger(AuthServer.class);
  private static final String SETTINGS_FILE = "settings.yml";
  private static final String TEMPLATE_RESOURCE = "/auth-server-settings.yml";
  private static final String LOOPBACK = "127.0.0.1";
  private static final String RUNTIME_DIRECTORY = "generated";
  private static final String GENERATED_HEADER = """
      # Generated on every start by merging settings.yml one directory up with the bind address and
      # player-info forwarding from hybridvelocity.toml. Edits here are lost; edit ../settings.yml.
      """;

  private final Path directory;
  private final VelocityConfiguration configuration;

  private @Nullable LimboServer limbo;
  private @Nullable InetSocketAddress address;

  public AuthServer(final Path directory, final VelocityConfiguration configuration) {
    this.directory = directory;
    this.configuration = configuration;
  }

  /**
   * Writes the settings if needed and starts the authentication server.
   *
   * @param port the port to bind on loopback, or {@code 0} to pick a free one
   * @throws Exception if the settings cannot be written or the server cannot bind
   */
  public void start(final int port) throws Exception {
    final int boundPort = port == 0 ? findFreePort() : port;

    Files.createDirectories(directory);
    final Path runtime = writeSettings(boundPort);

    final LimboServer server = new LimboServer();
    server.startEmbedded(runtime);

    this.limbo = server;
    this.address = new InetSocketAddress(LOOPBACK, boundPort);
    logger.info("Authentication server listening on {}", this.address);
  }

  /** Stops the authentication server if it is running. */
  public void stop() {
    final LimboServer server = this.limbo;
    if (server == null) {
      return;
    }
    this.limbo = null;
    this.address = null;
    try {
      server.stop();
    } catch (Exception e) {
      logger.error("Exception while stopping the authentication server", e);
    }
  }

  /**
   * Returns the address the authentication server is listening on.
   *
   * @return the address, or {@code null} if it is not running
   */
  public @Nullable InetSocketAddress getAddress() {
    return address;
  }

  /**
   * Produces the settings the server actually runs from, and returns the directory holding them.
   *
   * <p>The operator's file never carries the bind address or the forwarding: those are not theirs
   * to set. Binding anywhere but loopback would expose an unauthenticated world to the network, and
   * a forwarding mode that disagrees with the proxy's breaks the handshake. So the editable file is
   * merged with those two values into {@code generated/settings.yml}, which is what
   * {@link LimboServer} reads, and which is rewritten on every start.</p>
   *
   * @param port the loopback port to bind
   * @return the directory containing the generated settings
   */
  private Path writeSettings(final int port) throws IOException {
    final Path settings = directory.resolve(SETTINGS_FILE);
    if (!Files.exists(settings)) {
      writeTemplate(settings);
    }

    final CommentedConfigurationNode root = loader(settings).load();
    try {
      root.node("bind", "ip").set(LOOPBACK);
      root.node("bind", "port").set(port);

      final PlayerInfoForwarding forwarding = configuration.getPlayerInfoForwardingMode();
      final String type = toLimboForwardingType(forwarding);
      final String secret =
          new String(configuration.getForwardingSecret(), StandardCharsets.UTF_8);

      root.node("infoForwarding", "type").set(type);
      root.node("infoForwarding", "secret").set(secret);
      // BUNGEE_GUARD reads the shared secret from the token list instead of `secret`.
      root.node("infoForwarding", "tokens")
          .setList(String.class, List.of("BUNGEE_GUARD".equals(type) ? secret : ""));
    } catch (SerializationException e) {
      throw new IOException("Could not build the authentication server settings", e);
    }

    final Path runtime = directory.resolve(RUNTIME_DIRECTORY);
    Files.createDirectories(runtime);
    final Path runtimeSettings = runtime.resolve(SETTINGS_FILE);
    loader(runtimeSettings).save(root);
    Files.writeString(runtimeSettings,
        GENERATED_HEADER + Files.readString(runtimeSettings, StandardCharsets.UTF_8),
        StandardCharsets.UTF_8);

    return runtime;
  }

  /** Writes the documented template the operator edits. */
  private static void writeTemplate(final Path settings) throws IOException {
    try (InputStream in = AuthServer.class.getResourceAsStream(TEMPLATE_RESOURCE)) {
      if (in == null) {
        throw new IOException(
            "The bundled authentication server settings are missing from the jar");
      }
      Files.copy(in, settings);
    }
  }

  private static YamlConfigurationLoader loader(final Path settings) {
    return YamlConfigurationLoader.builder()
        .path(settings)
        .nodeStyle(NodeStyle.BLOCK)
        .build();
  }

  private static String toLimboForwardingType(final PlayerInfoForwarding forwarding) {
    return switch (forwarding) {
      case NONE -> "NONE";
      case LEGACY -> "LEGACY";
      case BUNGEEGUARD -> "BUNGEE_GUARD";
      case MODERN -> "MODERN";
    };
  }

  /**
   * Asks the OS for a free port by binding and immediately releasing one.
   *
   * <p>There is a small race between releasing the port and the server claiming it, which is
   * the configuration defaults to a fixed port instead.</p>
   */
  private static int findFreePort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }
}

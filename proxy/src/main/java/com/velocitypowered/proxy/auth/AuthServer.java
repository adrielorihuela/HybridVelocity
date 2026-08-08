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
import java.util.Objects;
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
 * <p>The operator never configures this. The settings file is written once from a documented
 * template, and afterwards it is left alone unless the two values the proxy owns — the bind port
 * and the forwarding that secures the loopback hop — no longer match. Rewriting through Configurate
 * would strip every comment, so it only happens when something genuinely changed, and it is logged
 * when it does.</p>
 *
 * <p>See {@code docs/auth-server.md}.</p>
 */
public final class AuthServer {

  private static final Logger logger = LogManager.getLogger(AuthServer.class);
  private static final String SETTINGS_FILE = "settings.yml";
  private static final String TEMPLATE_RESOURCE = "/auth-server-settings.yml";
  private static final String LOOPBACK = "127.0.0.1";

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
    writeSettings(boundPort);

    final LimboServer server = new LimboServer();
    server.startEmbedded(directory);

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
   * Returns the address the limbo is listening on.
   *
   * @return the address, or {@code null} if it is not running
   */
  public @Nullable InetSocketAddress getAddress() {
    return address;
  }

  private void writeSettings(final int port) throws IOException {
    final Path settings = directory.resolve(SETTINGS_FILE);
    final PlayerInfoForwarding forwarding = configuration.getPlayerInfoForwardingMode();
    final String type = toLimboForwardingType(forwarding);
    final String secret =
        new String(configuration.getForwardingSecret(), StandardCharsets.UTF_8);

    if (!Files.exists(settings)) {
      writeTemplate(settings, port, type, secret);
      return;
    }

    if (!managedValuesDiffer(settings, port, type, secret)) {
      return;
    }

    logger.info("The limbo bind port or forwarding mode changed; rewriting {}. Comments in that "
        + "file are not preserved by the rewrite.", settings);
    rewriteManagedValues(settings, port, type, secret);
  }

  /** Writes the documented template, which is the only path that preserves its comments. */
  private static void writeTemplate(final Path settings, final int port, final String type,
      final String secret) throws IOException {
    final String template;
    try (InputStream in = AuthServer.class.getResourceAsStream(TEMPLATE_RESOURCE)) {
      if (in == null) {
        throw new IOException(
            "The bundled authentication server settings are missing from the jar");
      }
      template = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }

    // BUNGEE_GUARD reads the shared secret from the token list instead of `secret`.
    final String tokens = "BUNGEE_GUARD".equals(type)
        ? "    - \"" + secret + "\""
        : "    - \"\"";

    final String rendered = template
        .replace("{{PORT}}", Integer.toString(port))
        .replace("{{FORWARDING_TYPE}}", type)
        .replace("{{FORWARDING_SECRET}}", secret)
        .replace("{{FORWARDING_TOKENS}}", tokens);

    Files.writeString(settings, rendered, StandardCharsets.UTF_8);
  }

  private static boolean managedValuesDiffer(final Path settings, final int port,
      final String type, final String secret) throws IOException {
    final CommentedConfigurationNode root = loader(settings).load();
    return root.node("bind", "port").getInt(-1) != port
        || !LOOPBACK.equals(root.node("bind", "ip").getString())
        || !type.equals(root.node("infoForwarding", "type").getString())
        || !Objects.equals(secret, root.node("infoForwarding", "secret").getString());
  }

  private static void rewriteManagedValues(final Path settings, final int port, final String type,
      final String secret) throws IOException {
    final YamlConfigurationLoader loader = loader(settings);
    final CommentedConfigurationNode root = loader.load();
    try {
      root.node("bind", "ip").set(LOOPBACK);
      root.node("bind", "port").set(port);
      root.node("infoForwarding", "type").set(type);
      root.node("infoForwarding", "secret").set(secret);
      if ("BUNGEE_GUARD".equals(type)) {
        root.node("infoForwarding", "tokens").setList(String.class, java.util.List.of(secret));
      }
    } catch (SerializationException e) {
      throw new IOException("Could not update the authentication server settings", e);
    }
    loader.save(root);
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

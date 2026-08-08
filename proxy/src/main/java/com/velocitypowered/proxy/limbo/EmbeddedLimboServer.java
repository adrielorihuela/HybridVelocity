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

package com.velocitypowered.proxy.limbo;

import com.velocitypowered.proxy.config.PlayerInfoForwarding;
import com.velocitypowered.proxy.config.VelocityConfiguration;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.yaml.NodeStyle;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;
import ua.nanit.limbo.server.LimboServer;

/**
 * Runs a {@link LimboServer} inside the proxy JVM, bound to loopback, as the holding area for
 * players that have not authenticated yet.
 *
 * <p>The operator never configures this: the settings file is created on first run and the keys
 * the proxy owns — the bind address and the player-info forwarding that secures the loopback hop —
 * are rewritten on every start to match the proxy's own configuration. Everything else in that file
 * is left alone so it can be tuned.</p>
 *
 * <p>See {@code docs/limbo.md}.</p>
 */
public final class EmbeddedLimboServer {

  private static final Logger logger = LogManager.getLogger(EmbeddedLimboServer.class);
  private static final String SETTINGS_FILE = "settings.yml";
  private static final String LOOPBACK = "127.0.0.1";

  private final Path directory;
  private final VelocityConfiguration configuration;

  private @Nullable LimboServer limbo;
  private @Nullable InetSocketAddress address;

  public EmbeddedLimboServer(final Path directory, final VelocityConfiguration configuration) {
    this.directory = directory;
    this.configuration = configuration;
  }

  /**
   * Writes the managed settings and starts the limbo server.
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
    logger.info("Embedded limbo listening on {}", this.address);
  }

  /** Stops the limbo server if it is running. */
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
      logger.error("Exception while stopping the embedded limbo", e);
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

  /**
   * Rewrites the keys the proxy owns, creating the file from the bundled template on first run.
   *
   * <p>Only {@code bind} and {@code infoForwarding} are touched. The loopback hop is authenticated
   * with the proxy's own forwarding mode and secret, so an unauthenticated player cannot reach the
   * limbo by connecting to its port directly when a forwarding mode is in use.</p>
   */
  private void writeSettings(final int port) throws IOException {
    final Path settings = directory.resolve(SETTINGS_FILE);
    final boolean firstRun = !Files.exists(settings);
    if (firstRun) {
      copyTemplate(settings);
    }

    final YamlConfigurationLoader loader = YamlConfigurationLoader.builder()
        .path(settings)
        .nodeStyle(NodeStyle.BLOCK)
        .build();
    final CommentedConfigurationNode root = loader.load();

    try {
      root.node("bind", "ip").set(LOOPBACK);
      root.node("bind", "port").set(port);

      final PlayerInfoForwarding forwarding = configuration.getPlayerInfoForwardingMode();
      root.node("infoForwarding", "type").set(toLimboForwardingType(forwarding));
      if (forwarding == PlayerInfoForwarding.MODERN) {
        root.node("infoForwarding", "secret")
            .set(new String(configuration.getForwardingSecret(), StandardCharsets.UTF_8));
      } else if (forwarding == PlayerInfoForwarding.BUNGEEGUARD) {
        root.node("infoForwarding", "tokens").setList(String.class, java.util.List.of(
            new String(configuration.getForwardingSecret(), StandardCharsets.UTF_8)));
      }

      if (firstRun) {
        applyEmbeddedDefaults(root);
      }
    } catch (org.spongepowered.configurate.serialize.SerializationException e) {
      throw new IOException("Could not build the embedded limbo settings", e);
    }

    loader.save(root);
  }

  /**
   * Defaults applied only when the file is created, so later operator edits survive restarts.
   *
   * <p>NIO is used rather than upstream's EPOLL default because the limbo serves a handful of
   * waiting players over loopback; the thread counts are trimmed for the same reason.</p>
   */
  private static void applyEmbeddedDefaults(final CommentedConfigurationNode root)
      throws org.spongepowered.configurate.serialize.SerializationException {
    root.node("netty", "transportType").set("NIO");
    root.node("netty", "threads", "bossGroup").set(1);
    root.node("netty", "threads", "workerGroup").set(1);
    root.node("logPlayersIp").set(false);
  }

  private static void copyTemplate(final Path settings) throws IOException {
    try (InputStream template = LimboServer.class.getResourceAsStream("/" + SETTINGS_FILE)) {
      if (template == null) {
        throw new IOException("The bundled limbo " + SETTINGS_FILE + " is missing from the jar");
      }
      Files.copy(template, settings);
    }
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
   * <p>There is a small race between releasing the port and the limbo claiming it. Set an explicit
   * port in the configuration if that matters.</p>
   */
  private static int findFreePort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }
}

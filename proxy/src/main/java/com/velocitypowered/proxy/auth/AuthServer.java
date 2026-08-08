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
import ua.nanit.limbo.server.LimboServer;
import ua.nanit.limbo.server.data.InfoForwarding;

/**
 * The authentication server: a limbo world run inside the proxy JVM, bound to loopback, where
 * players wait until they register or log in.
 *
 * <p>{@code auth/settings.yml} is written once from a documented template and then never touched
 * again, so the operator's edits and its comments survive. It carries no bind address and no
 * forwarding: binding anywhere but loopback would expose an unauthenticated world to the network,
 * and a forwarding mode that disagrees with the proxy's breaks the handshake, so those two are
 * passed to {@link LimboServer} in code and never appear on disk at all.</p>
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
   * Starts the authentication server, creating its settings file on first run.
   *
   * @param port the port to bind on loopback, or {@code 0} to pick a free one
   * @throws Exception if the settings cannot be written or the server cannot bind
   */
  public void start(final int port) throws Exception {
    final int boundPort = port == 0 ? findFreePort() : port;

    Files.createDirectories(directory);
    ensureSettings();

    final InetSocketAddress bind = new InetSocketAddress(LOOPBACK, boundPort);
    final LimboServer server = new LimboServer();
    server.startEmbedded(directory, bind, buildForwarding());

    this.limbo = server;
    this.address = bind;
    logger.info("Authentication server listening on {}", bind);
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

  /** Writes the documented template on first run, and never touches it again. */
  private void ensureSettings() throws IOException {
    final Path settings = directory.resolve(SETTINGS_FILE);
    if (Files.exists(settings)) {
      return;
    }
    try (InputStream in = AuthServer.class.getResourceAsStream(TEMPLATE_RESOURCE)) {
      if (in == null) {
        throw new IOException(
            "The bundled authentication server settings are missing from the jar");
      }
      Files.copy(in, settings);
    }
  }

  /** Mirrors the proxy's own forwarding, so the loopback hop is authenticated like any backend. */
  private InfoForwarding buildForwarding() {
    final PlayerInfoForwarding mode = configuration.getPlayerInfoForwardingMode();
    final InfoForwarding forwarding = new InfoForwarding();
    forwarding.setType(toLimboForwardingType(mode));

    final byte[] secret = configuration.getForwardingSecret();
    switch (mode) {
      case MODERN -> forwarding.setSecretKey(secret);
      case BUNGEEGUARD ->
          forwarding.setTokens(List.of(new String(secret, StandardCharsets.UTF_8)));
      default -> {
        // NONE and LEGACY carry no shared secret.
      }
    }
    return forwarding;
  }

  private static InfoForwarding.Type toLimboForwardingType(final PlayerInfoForwarding forwarding) {
    return switch (forwarding) {
      case NONE -> InfoForwarding.Type.NONE;
      case LEGACY -> InfoForwarding.Type.LEGACY;
      case BUNGEEGUARD -> InfoForwarding.Type.BUNGEE_GUARD;
      case MODERN -> InfoForwarding.Type.MODERN;
    };
  }

  /**
   * Asks the OS for a free port by binding and immediately releasing one.
   *
   * <p>There is a small race between releasing the port and the server claiming it, which is why
   * the configuration defaults to a fixed port instead.</p>
   */
  private static int findFreePort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }
}

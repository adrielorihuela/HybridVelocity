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

package com.velocitypowered.proxy.server;

import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * Servers the proxy runs itself rather than ones the operator configured.
 *
 * <p>They are registered normally, so they behave like any other backend and plugins that
 * enumerate servers — ViaVersion pings every server in {@code getAllServers()} to learn its
 * protocol version, and will mis-decode the login of one it has never seen — keep working. They are
 * filtered out of command output instead, so players are not offered somewhere they have no reason
 * to go.</p>
 */
public final class InternalServers {

  /** The server that holds players while they authenticate. */
  public static final String AUTH = "Auth";

  private InternalServers() {
  }

  /**
   * Whether a server is run by the proxy itself.
   *
   * @param name the server name
   * @return {@code true} for internal servers
   */
  public static boolean isInternal(final String name) {
    return AUTH.equalsIgnoreCase(name);
  }

  /**
   * Whether a server is run by the proxy itself.
   *
   * @param server the server
   * @return {@code true} for internal servers
   */
  public static boolean isInternal(final RegisteredServer server) {
    return isInternal(server.getServerInfo().getName());
  }

  /**
   * Returns the servers an operator configured, dropping the proxy's own.
   *
   * @param servers the servers to filter
   * @return the configured servers
   */
  public static List<RegisteredServer> filter(final Collection<RegisteredServer> servers) {
    final List<RegisteredServer> filtered = new ArrayList<>(servers.size());
    for (final RegisteredServer server : servers) {
      if (!isInternal(server)) {
        filtered.add(server);
      }
    }
    return filtered;
  }

  /**
   * Lowercases a name for comparison the same way {@link ServerMap} keys it.
   *
   * @param name the server name
   * @return the key
   */
  public static String key(final String name) {
    return name.toLowerCase(Locale.US);
  }
}

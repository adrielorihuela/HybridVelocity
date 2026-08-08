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

package com.velocitypowered.proxy.config.migration;

import static com.velocitypowered.proxy.config.VelocityConfiguration.OfflineAuthConfig.DEFAULT;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import org.apache.logging.log4j.Logger;

/**
 * Configuration migration for the offline authentication options, added by HybridVelocity.
 *
 * <p>They started life as an {@code [offline-auth]} section and are now three top-level keys
 * sitting next to {@code online-mode}, which is the option they belong with. Whatever the operator
 * had chosen in the old section is carried across; only genuinely absent keys get a default.</p>
 */
public final class OfflineAuthMigration implements ConfigurationMigration {

  @Override
  public boolean shouldMigrate(CommentedFileConfig config) {
    return configVersion(config) < 3.1;
  }

  @Override
  public void migrate(CommentedFileConfig config, Logger logger) {
    // Carry over the old [offline-auth] section if this config had one.
    final Boolean previouslyEnabled = config.get("offline-auth.enabled");
    final Integer previousPort = config.get("offline-auth.limbo-port");
    if (previouslyEnabled != null || previousPort != null) {
      logger.info("Moving the [offline-auth] section to the 'offline-auth', 'auth-server-name' "
          + "and 'auth-server-port' options.");
    }
    config.remove("offline-auth");

    config.set("offline-auth",
        previouslyEnabled != null ? previouslyEnabled : DEFAULT.enabled());
    if (!config.contains("auth-server-name")) {
      config.set("auth-server-name", DEFAULT.serverName());
    }
    config.set("auth-server-port",
        previousPort != null ? previousPort : DEFAULT.serverPort());

    config.setComment("offline-auth", """
        Should players that Mojang could not authenticate be asked to register a password?
        They wait on an authentication server that runs inside this proxy - there is nothing extra
        to install - and only reach your real servers once they register or log in.
        Premium players are unaffected.
        Passwords are stored in auth/player-passwords.db. Do not delete that file: it is the only
        copy, and losing it lets anyone claim a name by registering it first.""");

    config.setComment("auth-server-name", """
        The name the authentication server is registered under. It is hidden from /server, /glist,
        /send and tab completion, but plugins can still see it - ViaVersion needs to, to learn its
        protocol version. It must not match any server in [servers].""");

    config.setComment("auth-server-port", """
        Port the authentication server listens on, bound to 127.0.0.1 only, so it is unreachable
        from outside this machine. It needs a port of its own: two listeners cannot share one, and
        a server with no socket could not be pinged by ViaVersion.
        Set 0 to let the system pick a free port on each start.""");

    config.set("config-version", "3.1");
  }
}

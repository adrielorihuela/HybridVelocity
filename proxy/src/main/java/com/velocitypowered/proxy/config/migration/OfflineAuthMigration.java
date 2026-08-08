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
 * Configuration migration for the new [offline-auth] section, added by HybridVelocity.
 *
 * <p>An absent section already yields the defaults, so this migration exists purely so that
 * operators upgrading an existing {@code hybridvelocity.toml} can see and enable the feature. It is
 * written disabled, leaving behaviour unchanged until it is turned on deliberately.</p>
 */
public final class OfflineAuthMigration implements ConfigurationMigration {

  @Override
  public boolean shouldMigrate(CommentedFileConfig config) {
    return configVersion(config) < 3.0;
  }

  @Override
  public void migrate(CommentedFileConfig config, Logger logger) {
    // Only seed keys that are absent: an operator who already enabled this, moved the database or
    // pinned a port must not have those choices reset by an upgrade.
    if (!config.contains("offline-auth.enabled")) {
      config.set("offline-auth.enabled", DEFAULT.enabled());
    }
    if (!config.contains("offline-auth.limbo-port")) {
      config.set("offline-auth.limbo-port", DEFAULT.limboPort());
    }
    if (!config.contains("offline-auth.database-file")) {
      config.set("offline-auth.database-file", DEFAULT.databaseFile());
    }

    config.setComment("offline-auth.enabled", """
        Hold players that Mojang could not authenticate on the proxy until they register or log in.
        They wait in a limbo world that runs inside this proxy - there is no extra server to install.
        Premium players are unaffected. Disabled by default.""");

    config.setComment("offline-auth.limbo-port", """
        Port the embedded limbo listens on. It is bound to 127.0.0.1 only, so it is not reachable
        from outside this machine. Set 0 to let the system pick a free port automatically.""");

    config.setComment("offline-auth.database-file", """
        Where the registered passwords are stored, relative to the proxy directory.

        DO NOT DELETE, MOVE OR EDIT THIS FILE. It is an SQLite database holding every player's
        password, and it is the only copy. Deleting it erases every registration: each player would be
        asked to register again, and until they did, anyone could claim their name by registering it
        first. Back it up along with the rest of your server data.

        The passwords themselves are stored as bcrypt hashes and cannot be read back out of the file.""");

    config.set("config-version", "3.0");
  }
}

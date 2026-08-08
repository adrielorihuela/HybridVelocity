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
    return configVersion(config) < 2.9;
  }

  @Override
  public void migrate(CommentedFileConfig config, Logger logger) {
    config.set("offline-auth.enabled", DEFAULT.enabled());
    config.set("offline-auth.limbo-port", DEFAULT.limboPort());
    config.set("offline-auth.database-file", DEFAULT.databaseFile());

    config.setComment("offline-auth.enabled", """
        Hold players that Mojang could not authenticate on the proxy until they register or log in.
        They wait in a limbo world that runs inside this proxy - there is no extra server to install.
        Premium players are unaffected. Disabled by default.""");

    config.setComment("offline-auth.limbo-port", """
        Port the embedded limbo listens on, bound to 127.0.0.1 only.
        0 picks a free port automatically, which is what you want unless something else needs to know it.""");

    config.setComment("offline-auth.database-file", """
        SQLite file storing the password records, relative to the proxy directory.""");

    config.set("config-version", "2.9");
  }
}

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

package com.velocitypowered.proxy.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Where this fork keeps its configuration.
 *
 * <p>The file is named after the fork rather than after upstream Velocity. An installation that
 * still has upstream's {@code velocity.toml} is renamed on first start — without that, updating the
 * jar would silently start the proxy on stock defaults and lose every configured server, the
 * forwarding mode and the MOTD.</p>
 */
public final class ConfigurationLocation {

  private static final Logger logger = LogManager.getLogger(ConfigurationLocation.class);

  /** The configuration file this fork reads. */
  public static final String FILE_NAME = "hybridvelocity.toml";

  /** The name upstream Velocity uses, migrated away from on first start. */
  public static final String LEGACY_FILE_NAME = "velocity.toml";

  private ConfigurationLocation() {
  }

  /**
   * Returns the path to the configuration, migrating an upstream-named file if one is found.
   *
   * @return the path to read the configuration from
   */
  public static Path resolve() {
    final Path path = Path.of(FILE_NAME);
    if (Files.exists(path)) {
      return path;
    }

    final Path legacy = Path.of(LEGACY_FILE_NAME);
    if (!Files.exists(legacy)) {
      return path;
    }

    try {
      Files.move(legacy, path, StandardCopyOption.ATOMIC_MOVE);
      logger.info("Renamed {} to {}; your configuration was kept as it was.",
          LEGACY_FILE_NAME, FILE_NAME);
    } catch (IOException e) {
      logger.error("Could not rename {} to {}. Reading the old file instead — rename it yourself "
          + "to silence this.", LEGACY_FILE_NAME, FILE_NAME, e);
      return legacy;
    }
    return path;
  }
}

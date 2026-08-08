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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.velocitypowered.proxy.config.VelocityConfiguration.OfflineAuthConfig;
import org.junit.jupiter.api.Test;

class OfflineAuthConfigTest {

  @Test
  void absentSectionYieldsTheDefault() {
    assertSame(OfflineAuthConfig.DEFAULT, OfflineAuthConfig.fromConfig(null));
  }

  @Test
  void offlineAuthIsDisabledByDefault() {
    // Upgrading must not change behaviour for anyone who does not opt in.
    assertFalse(OfflineAuthConfig.DEFAULT.enabled());
  }

  @Test
  void limboPortDefaultsToFixedPort() {
    assertEquals(30065, OfflineAuthConfig.DEFAULT.limboPort());
  }

  @Test
  void databaseLivesInTheAuthDirectory() {
    // Keeping it beside hybridvelocity.toml made it easy to delete by accident, which would wipe
    // every registration.
    assertEquals("auth/player-passwords.db", OfflineAuthConfig.DEFAULT.databaseFile());
  }

  @Test
  void sectionValuesAreRead() {
    final CommentedConfig config = CommentedConfig.inMemory();
    config.set("enabled", true);
    config.set("limbo-port", 30099);
    config.set("database-file", "auth/players.db");

    final OfflineAuthConfig parsed = OfflineAuthConfig.fromConfig(config);

    assertTrue(parsed.enabled());
    assertEquals(30099, parsed.limboPort());
    assertEquals("auth/players.db", parsed.databaseFile());
  }

  @Test
  void missingKeysFallBackToDefaults() {
    final CommentedConfig config = CommentedConfig.inMemory();
    config.set("enabled", true);

    final OfflineAuthConfig parsed = OfflineAuthConfig.fromConfig(config);

    assertTrue(parsed.enabled());
    assertEquals(OfflineAuthConfig.DEFAULT.limboPort(), parsed.limboPort());
    assertEquals(OfflineAuthConfig.DEFAULT.databaseFile(), parsed.databaseFile());
  }
}

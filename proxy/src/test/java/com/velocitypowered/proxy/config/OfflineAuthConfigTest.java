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
  void absentOptionsYieldTheDefault() {
    assertSame(OfflineAuthConfig.DEFAULT, OfflineAuthConfig.fromConfig(null));
  }

  @Test
  void offlineAuthIsOnByDefault() {
    assertTrue(OfflineAuthConfig.DEFAULT.enabled());
  }

  @Test
  void defaultsMatchTheShippedConfiguration() {
    assertEquals("Auth", OfflineAuthConfig.DEFAULT.serverName());
    assertEquals(30065, OfflineAuthConfig.DEFAULT.serverPort());
  }

  @Test
  void databaseLocationIsNotConfigurable() {
    // It is the only copy of every registration; letting it be moved invites losing track of it.
    assertEquals("auth/player-passwords.db", OfflineAuthConfig.DATABASE_FILE);
  }

  @Test
  void optionsAreReadFromTheRootOfTheConfiguration() {
    final CommentedConfig config = CommentedConfig.inMemory();
    config.set("offline-auth", false);
    config.set("auth-server-name", "Login");
    config.set("auth-server-port", 40000);

    final OfflineAuthConfig parsed = OfflineAuthConfig.fromConfig(config);

    assertFalse(parsed.enabled());
    assertEquals("Login", parsed.serverName());
    assertEquals(40000, parsed.serverPort());
  }

  @Test
  void missingOptionsFallBackToDefaults() {
    final CommentedConfig config = CommentedConfig.inMemory();
    config.set("offline-auth", false);

    final OfflineAuthConfig parsed = OfflineAuthConfig.fromConfig(config);

    assertFalse(parsed.enabled());
    assertEquals(OfflineAuthConfig.DEFAULT.serverName(), parsed.serverName());
    assertEquals(OfflineAuthConfig.DEFAULT.serverPort(), parsed.serverPort());
  }
}

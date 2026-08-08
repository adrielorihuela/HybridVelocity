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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OfflineAuthManagerTest {

  @TempDir
  Path directory;

  private OfflineAuthManager manager;

  @BeforeEach
  void startManager() {
    manager = new OfflineAuthManager(directory.resolve("player_auth.db"));
    assertTrue(manager.start());
  }

  @AfterEach
  void stopManager() {
    manager.shutdown();
  }

  @Test
  void managerIsAvailableAfterStart() {
    assertTrue(manager.isAvailable());
  }

  @Test
  void registerThenVerifyRoundTrips() throws Exception {
    final UUID uuid = UUID.randomUUID();
    assertTrue(manager.register(uuid, "Steve123.", "hunter2").get());

    final PasswordLookup lookup = manager.lookup(uuid).get();
    assertTrue(lookup.isFound());
    final PasswordRecord record = lookup.record();
    assertNotNull(record);

    assertTrue(manager.verify(record, "hunter2").get());
    assertFalse(manager.verify(record, "hunter3").get());
  }

  @Test
  void storedHashIsNotThePlaintext() throws Exception {
    final UUID uuid = UUID.randomUUID();
    manager.register(uuid, "Steve123.", "hunter2").get();

    final PasswordRecord record = manager.lookup(uuid).get().record();
    assertNotNull(record);
    assertFalse(record.passwordHash().contains("hunter2"));
  }

  @Test
  void changePasswordInvalidatesTheOldOne() throws Exception {
    final UUID uuid = UUID.randomUUID();
    manager.register(uuid, "Steve123.", "hunter2").get();
    assertTrue(manager.changePassword(uuid, "hunter3").get());

    final PasswordRecord record = manager.lookup(uuid).get().record();
    assertNotNull(record);
    assertFalse(manager.verify(record, "hunter2").get());
    assertTrue(manager.verify(record, "hunter3").get());
  }

  @Test
  void playersStartUnauthenticated() {
    assertFalse(manager.isAuthenticated(UUID.randomUUID()));
  }

  @Test
  void authenticationIsRememberedUntilForgotten() {
    final UUID uuid = UUID.randomUUID();

    manager.markAuthenticated(uuid);
    assertTrue(manager.isAuthenticated(uuid));

    manager.forget(uuid);
    assertFalse(manager.isAuthenticated(uuid), "a reconnect must authenticate again");
  }

  @Test
  void failedAttemptsCountUpPerPlayer() {
    final UUID first = UUID.randomUUID();
    final UUID second = UUID.randomUUID();

    assertEquals(1, manager.recordFailedAttempt(first));
    assertEquals(2, manager.recordFailedAttempt(first));
    assertEquals(1, manager.recordFailedAttempt(second));
    assertEquals(3, manager.recordFailedAttempt(first));
  }

  @Test
  void shutdownMakesTheManagerUnavailable() {
    manager.shutdown();
    assertFalse(manager.isAvailable());
  }
}

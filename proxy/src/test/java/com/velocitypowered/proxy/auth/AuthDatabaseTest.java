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

import com.velocitypowered.proxy.auth.PasswordLookup.Status;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AuthDatabaseTest {

  @TempDir
  Path directory;

  private AuthDatabase database;

  @BeforeEach
  void openDatabase() throws Exception {
    database = new AuthDatabase(directory.resolve("player_auth.db"));
    database.init();
  }

  @AfterEach
  void closeDatabase() {
    database.close();
  }

  @Test
  void initCreatesTheDatabaseFileAndSchema() {
    assertTrue(database.isInitialized());
  }

  @Test
  void anUnknownPlayerIsReportedAsNotRegistered() throws Exception {
    final PasswordLookup lookup = database.getPasswordRecord(UUID.randomUUID()).get();

    assertEquals(Status.NOT_REGISTERED, lookup.status());
    assertFalse(lookup.isFailed());
  }

  @Test
  void storedRecordIsReadBack() throws Exception {
    final UUID uuid = UUID.randomUUID();
    assertTrue(database.createPasswordRecord(uuid, "Steve123.", "hash").get());

    final PasswordLookup lookup = database.getPasswordRecord(uuid).get();

    assertTrue(lookup.isFound());
    final PasswordRecord record = lookup.record();
    assertNotNull(record);
    assertEquals(uuid, record.uuid());
    assertEquals("Steve123.", record.username());
    assertEquals("hash", record.passwordHash());
    assertNotNull(record.createdAt());
    assertEquals(record.createdAt(), record.updatedAt());
  }

  @Test
  void registeringTheSameUuidTwiceFails() throws Exception {
    final UUID uuid = UUID.randomUUID();
    assertTrue(database.createPasswordRecord(uuid, "Steve123.", "hash").get());

    // The primary key rejects it, so an existing account cannot be silently overwritten.
    assertFalse(database.createPasswordRecord(uuid, "Steve123.", "other").get());
  }

  @Test
  void updatingReplacesTheHashAndBumpsTheTimestamp() throws Exception {
    final UUID uuid = UUID.randomUUID();
    database.createPasswordRecord(uuid, "Steve123.", "old").get();
    final PasswordRecord before = database.getPasswordRecord(uuid).get().record();
    assertNotNull(before);

    Thread.sleep(5);
    assertTrue(database.updatePassword(uuid, "new").get());

    final PasswordRecord after = database.getPasswordRecord(uuid).get().record();
    assertNotNull(after);
    assertEquals("new", after.passwordHash());
    assertEquals(before.createdAt(), after.createdAt());
    assertTrue(after.updatedAt().isAfter(before.updatedAt()));
  }

  @Test
  void updatingAnUnknownPlayerChangesNothing() throws Exception {
    assertFalse(database.updatePassword(UUID.randomUUID(), "new").get());
  }

  @Test
  void queriesAgainstClosedDatabaseFailRatherThanLookUnregistered() throws Exception {
    final UUID uuid = UUID.randomUUID();
    database.createPasswordRecord(uuid, "Steve123.", "hash").get();
    database.close();

    // The distinction matters: reporting NOT_REGISTERED here would offer /register to a player
    // who already has an account.
    assertEquals(Status.FAILED, database.getPasswordRecord(uuid).get().status());
  }
}

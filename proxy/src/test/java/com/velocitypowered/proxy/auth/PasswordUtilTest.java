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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.velocitypowered.proxy.auth.PasswordUtil.ValidationResult;
import org.junit.jupiter.api.Test;

class PasswordUtilTest {

  @Test
  void passwordsWithinTheLengthRangeAreAccepted() {
    assertTrue(PasswordUtil.validatePassword("abcd").valid());
    assertTrue(PasswordUtil.validatePassword("abcdefghijklmnop").valid());
  }

  @Test
  void passwordsOutsideTheLengthRangeAreRejected() {
    final ValidationResult tooShort = PasswordUtil.validatePassword("abc");
    assertFalse(tooShort.valid());
    assertEquals("Error: Password must be between 4 and 16 characters long.",
        tooShort.errorMessage());

    assertFalse(PasswordUtil.validatePassword("abcdefghijklmnopq").valid());
    assertFalse(PasswordUtil.validatePassword(null).valid());
  }

  @Test
  void printableAsciiAndSpanishEnyeAreAllowed() {
    assertTrue(PasswordUtil.validatePassword("aA1!~ ").valid());
    assertTrue(PasswordUtil.validatePassword("mañana").valid());
    assertTrue(PasswordUtil.validatePassword("PEÑA1").valid());
  }

  @Test
  void invalidCharactersAreListedOnceInOrderOfAppearance() {
    final ValidationResult result = PasswordUtil.validatePassword("aéüé1");

    assertFalse(result.valid());
    assertEquals("Error: Invalid characters detected: é, ü.", result.errorMessage());
  }

  @Test
  void hashingIsSaltedSoEqualPasswordsDifferAndStillVerify() {
    final String first = PasswordUtil.hashPassword("hunter2");
    final String second = PasswordUtil.hashPassword("hunter2");

    assertNotEquals(first, second, "each hash must carry its own salt");
    assertTrue(PasswordUtil.verifyPassword("hunter2", first));
    assertTrue(PasswordUtil.verifyPassword("hunter2", second));
  }

  @Test
  void verificationRejectsTheWrongPasswordAndNulls() {
    final String hash = PasswordUtil.hashPassword("hunter2");

    assertFalse(PasswordUtil.verifyPassword("hunter3", hash));
    assertFalse(PasswordUtil.verifyPassword(null, hash));
    assertFalse(PasswordUtil.verifyPassword("hunter2", null));
  }

  @Test
  void theHashDoesNotContainThePlaintext() {
    assertFalse(PasswordUtil.hashPassword("hunter2").contains("hunter2"));
  }
}

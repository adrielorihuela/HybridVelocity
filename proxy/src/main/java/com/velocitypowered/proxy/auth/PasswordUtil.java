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

import at.favre.lib.crypto.bcrypt.BCrypt;
import java.util.LinkedHashSet;
import java.util.Set;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Password rules, hashing and verification.
 *
 * <p>{@link #hashPassword} and {@link #verifyPassword} are deliberately slow — that is what makes
 * bcrypt worth using — so they must never be called on a Netty event loop. Callers dispatch them
 * off-loop; see {@link OfflineAuthManager}.</p>
 */
public final class PasswordUtil {

  private static final int BCRYPT_COST = 10;
  private static final int MIN_LENGTH = 4;
  private static final int MAX_LENGTH = 16;

  private PasswordUtil() {
  }

  /**
   * The outcome of checking a password against the rules.
   *
   * @param valid whether the password is acceptable
   * @param errorMessage the message to show the player, or {@code null} when valid
   */
  public record ValidationResult(boolean valid, @Nullable String errorMessage) {

    private static final ValidationResult OK = new ValidationResult(true, null);

    public static ValidationResult ok() {
      return OK;
    }

    public static ValidationResult error(final String errorMessage) {
      return new ValidationResult(false, errorMessage);
    }
  }

  /**
   * Checks a password against the length and character rules.
   *
   * <p>Allowed characters are the printable ASCII range plus {@code ñ} and {@code Ñ}. Every
   * distinct disallowed character is reported once, in the order it first appears.</p>
   *
   * @param password the plaintext password
   * @return the result, carrying the message to show the player when invalid
   */
  public static ValidationResult validatePassword(final @Nullable String password) {
    if (password == null || password.length() < MIN_LENGTH || password.length() > MAX_LENGTH) {
      return ValidationResult.error(
          "Error: Password must be between " + MIN_LENGTH + " and " + MAX_LENGTH
              + " characters long.");
    }

    final Set<String> invalidChars = new LinkedHashSet<>();
    for (int i = 0; i < password.length(); i++) {
      final char ch = password.charAt(i);
      final boolean allowed = (ch >= 0x20 && ch <= 0x7E) || ch == 'ñ' || ch == 'Ñ';
      if (!allowed) {
        invalidChars.add(String.valueOf(ch));
      }
    }

    if (!invalidChars.isEmpty()) {
      return ValidationResult.error(
          "Error: Invalid characters detected: " + String.join(", ", invalidChars) + ".");
    }

    return ValidationResult.ok();
  }

  /**
   * Hashes a password with bcrypt. The returned string embeds a unique salt and the cost.
   *
   * <p>Blocking. Never call on an event loop.</p>
   *
   * @param password the plaintext password
   * @return the bcrypt hash
   */
  public static String hashPassword(final String password) {
    return BCrypt.withDefaults().hashToString(BCRYPT_COST, password.toCharArray());
  }

  /**
   * Verifies a plaintext password against a stored hash.
   *
   * <p>Blocking. Never call on an event loop.</p>
   *
   * @param password the plaintext password
   * @param storedHash the stored bcrypt hash
   * @return whether the password matches
   */
  public static boolean verifyPassword(
      final @Nullable String password, final @Nullable String storedHash) {
    if (password == null || storedHash == null) {
      return false;
    }
    return BCrypt.verifyer().verify(password.toCharArray(), storedHash).verified;
  }
}

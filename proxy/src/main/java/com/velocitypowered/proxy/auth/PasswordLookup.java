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

import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The outcome of looking up a player's password record.
 *
 * <p>The three cases are kept distinct on purpose. An earlier implementation returned {@code null}
 * for both "this player has not registered" and "the query failed", which meant a broken database
 * offered {@code /register} to players who already had an account and let it be overwritten. A
 * {@link Status#FAILED} lookup must fail closed — never treat it as an invitation to register.</p>
 *
 * @param status what happened
 * @param record the record, present only when the status is {@link Status#FOUND}
 */
public record PasswordLookup(Status status, @Nullable PasswordRecord record) {

  /** What a lookup found. */
  public enum Status {
    /** The player has a stored password. */
    FOUND,
    /** The query succeeded and the player has no stored password. */
    NOT_REGISTERED,
    /** The query failed. Nothing can be concluded about the player. */
    FAILED
  }

  private static final PasswordLookup NOT_REGISTERED =
      new PasswordLookup(Status.NOT_REGISTERED, null);
  private static final PasswordLookup FAILED = new PasswordLookup(Status.FAILED, null);

  public static PasswordLookup found(final PasswordRecord record) {
    return new PasswordLookup(Status.FOUND, record);
  }

  public static PasswordLookup notRegistered() {
    return NOT_REGISTERED;
  }

  public static PasswordLookup failed() {
    return FAILED;
  }

  public boolean isFound() {
    return status == Status.FOUND;
  }

  public boolean isFailed() {
    return status == Status.FAILED;
  }
}

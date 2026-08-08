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

import java.time.Instant;
import java.util.UUID;

/**
 * A stored password record for an offline player.
 *
 * @param uuid the player's offline UUID, derived from their dotted username
 * @param username the dotted username the UUID was derived from
 * @param passwordHash the bcrypt hash, which embeds its own salt and cost parameters
 * @param createdAt when the password was first registered
 * @param updatedAt when the password was last changed
 */
public record PasswordRecord(
    UUID uuid,
    String username,
    String passwordHash,
    Instant createdAt,
    Instant updatedAt) {
}

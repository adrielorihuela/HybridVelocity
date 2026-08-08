/*
 * Copyright (C) 2020 Velocity Contributors
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

package ua.nanit.limbo;

/**
 * Upstream NanoLimbo generates this class with the com.github.gmazzo.buildconfig Gradle plugin.
 * It is hand-written here instead so that the plugin is not needed in this build; it lives outside
 * {@code upstream/} so the vendored subtree stays pristine.
 *
 * <p>Referenced by {@code ua.nanit.limbo.server.commands.CmdVersion}. Update the version string
 * when pulling a new subtree.</p>
 */
public final class BuildConfig {

  /** The vendored NanoLimbo version. */
  public static final String LIMBO_VERSION = "1.13.0";

  private BuildConfig() {
  }
}

/*
 * Copyright (C) 2018-2023 Velocity Contributors
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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.velocitypowered.proxy.config.VelocityConfiguration.Servers;
import java.util.List;
import org.junit.jupiter.api.Test;

class ServersConfigTest {

  private static CommentedConfig serversConfig(List<String> serverCommands) {
    final CommentedConfig config = CommentedConfig.inMemory();
    config.set("Lobby", "127.0.0.1:30066");
    config.set("Survival", "127.0.0.1:30067");
    config.set("try", List.of("Lobby"));
    if (serverCommands != null) {
      config.set("comandos", serverCommands);
    }
    return config;
  }

  @Test
  void serverCommandsListIsNotTreatedAsServerEntry() {
    final Servers servers = new Servers(serversConfig(List.of("Lobby")));

    assertEquals(List.of("Lobby"), servers.getServerCommands());
    assertFalse(servers.getServers().containsKey("comandos"));
    assertTrue(servers.getServers().containsKey("Lobby"));
    assertEquals(2, servers.getServers().size());
  }

  @Test
  void missingServerCommandsListDefaultsToEmpty() {
    final Servers servers = new Servers(serversConfig(null));

    assertTrue(servers.getServerCommands().isEmpty());
    assertEquals(2, servers.getServers().size());
  }
}

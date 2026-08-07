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

package com.velocitypowered.proxy.command.builtin;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.permission.Tristate;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import java.util.Locale;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.translation.Argument;

/**
 * Implements the per-server shortcut commands configured by the {@code comandos} list in the
 * {@code [servers]} section of {@code velocity.toml}. A server named {@code Lobby} listed there
 * gets a {@code /Lobby} command that connects the player straight to it.
 */
public final class ServerShortcutCommand {

  private ServerShortcutCommand() {
  }

  /**
   * Creates the shortcut command for the given server.
   *
   * <p>The command is available to every player unless a permissions plugin explicitly denies
   * {@code velocity.command.server.<server name>}, matching how {@code /server} behaves.</p>
   *
   * @param proxy      the proxy the player will be moved on
   * @param serverName the name of the server, as configured in {@code [servers]}
   * @return the command to register
   */
  public static BrigadierCommand create(final ProxyServer proxy, final String serverName) {
    final String permission =
        "velocity.command.server." + serverName.toLowerCase(Locale.ROOT);

    final LiteralCommandNode<CommandSource> node = BrigadierCommand
        .literalArgumentBuilder(serverName)
        .requires(src -> src instanceof Player
            && src.getPermissionValue(permission) != Tristate.FALSE)
        .executes(ctx -> {
          final Player player = (Player) ctx.getSource();
          final Optional<RegisteredServer> toConnect = proxy.getServer(serverName);
          if (toConnect.isEmpty()) {
            player.sendMessage(CommandMessages.SERVER_DOES_NOT_EXIST
                .arguments(Argument.string("server", serverName)));
            return -1;
          }

          final String currentServer = player.getCurrentServer()
              .map(ServerConnection::getServerInfo)
              .map(ServerInfo::getName)
              .orElse(null);
          if (toConnect.get().getServerInfo().getName().equals(currentServer)) {
            player.sendMessage(Component.translatable(
                "velocity.command.server-already-connected",
                NamedTextColor.RED,
                Component.text(serverName)));
            return -1;
          }

          player.createConnectionRequest(toConnect.get()).fireAndForget();
          return Command.SINGLE_SUCCESS;
        })
        .build();

    return new BrigadierCommand(node);
  }
}

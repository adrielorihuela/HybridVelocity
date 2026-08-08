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

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/**
 * The {@code /register}, {@code /login} and {@code /changepassword} commands.
 *
 * <p>Registered as real Brigadier commands rather than matched as raw chat strings, so they go
 * through the command system like everything else and {@link OfflineAuthGate} can allow exactly
 * these while blocking the rest.</p>
 */
public final class OfflineAuthCommands {

  private static final String PASSWORD = "password";
  private static final String CONFIRM = "confirm";
  private static final String NEW_PASSWORD = "new-password";
  private static final String CONFIRM_NEW = "confirm-new";

  private OfflineAuthCommands() {
  }

  /**
   * Builds {@code /register <password> <password>}.
   *
   * @param manager the authentication manager
   * @param gate the gate to notify on success
   * @return the command
   */
  public static BrigadierCommand register(
      final OfflineAuthManager manager, final OfflineAuthGate gate) {
    final LiteralCommandNode<CommandSource> node = BrigadierCommand
        .literalArgumentBuilder("register")
        .requires(source -> source instanceof Player player && gate.isGated(player))
        .executes(ctx -> usage(ctx.getSource(), "Usage: /register <password> <password>"))
        .then(BrigadierCommand.requiredArgumentBuilder(PASSWORD, StringArgumentType.word())
            .executes(ctx -> usage(ctx.getSource(), "Usage: /register <password> <password>"))
            .then(BrigadierCommand.requiredArgumentBuilder(CONFIRM, StringArgumentType.word())
                .executes(ctx -> {
                  final Player player = (Player) ctx.getSource();
                  final String password = StringArgumentType.getString(ctx, PASSWORD);
                  final String confirm = StringArgumentType.getString(ctx, CONFIRM);
                  doRegister(manager, gate, player, password, confirm);
                  return Command.SINGLE_SUCCESS;
                })))
        .build();
    return new BrigadierCommand(node);
  }

  /**
   * Builds {@code /login <password>}.
   *
   * @param manager the authentication manager
   * @param gate the gate to notify on success or failure
   * @return the command
   */
  public static BrigadierCommand login(
      final OfflineAuthManager manager, final OfflineAuthGate gate) {
    final LiteralCommandNode<CommandSource> node = BrigadierCommand
        .literalArgumentBuilder("login")
        .requires(source -> source instanceof Player player && gate.isGated(player))
        .executes(ctx -> usage(ctx.getSource(), "Usage: /login <password>"))
        .then(BrigadierCommand.requiredArgumentBuilder(PASSWORD, StringArgumentType.word())
            .executes(ctx -> {
              final Player player = (Player) ctx.getSource();
              doLogin(manager, gate, player, StringArgumentType.getString(ctx, PASSWORD));
              return Command.SINGLE_SUCCESS;
            }))
        .build();
    return new BrigadierCommand(node);
  }

  /**
   * Builds {@code /changepassword <current> <new> <new>}, for players who have already logged in.
   *
   * @param manager the authentication manager
   * @param gate the gate, used to tell authenticated players apart from waiting ones
   * @return the command
   */
  public static BrigadierCommand changePassword(
      final OfflineAuthManager manager, final OfflineAuthGate gate) {
    final String usage = "Usage: /changepassword <current password> <new password> <new password>";
    final LiteralCommandNode<CommandSource> node = BrigadierCommand
        .literalArgumentBuilder("changepassword")
        .requires(source -> source instanceof Player player
            && !player.isOnlineMode() && !gate.isGated(player))
        .executes(ctx -> usage(ctx.getSource(), usage))
        .then(BrigadierCommand.requiredArgumentBuilder(PASSWORD, StringArgumentType.word())
            .executes(ctx -> usage(ctx.getSource(), usage))
            .then(BrigadierCommand.requiredArgumentBuilder(NEW_PASSWORD, StringArgumentType.word())
                .executes(ctx -> usage(ctx.getSource(), usage))
                .then(BrigadierCommand.requiredArgumentBuilder(CONFIRM_NEW, StringArgumentType.word())
                    .executes(ctx -> {
                      final Player player = (Player) ctx.getSource();
                      doChangePassword(manager, player,
                          StringArgumentType.getString(ctx, PASSWORD),
                          StringArgumentType.getString(ctx, NEW_PASSWORD),
                          StringArgumentType.getString(ctx, CONFIRM_NEW));
                      return Command.SINGLE_SUCCESS;
                    }))))
        .build();
    return new BrigadierCommand(node);
  }

  private static void doRegister(final OfflineAuthManager manager, final OfflineAuthGate gate,
      final Player player, final String password, final String confirm) {
    if (!password.equals(confirm)) {
      player.sendMessage(error("Error: Passwords do not match. Please register again."));
      return;
    }

    final PasswordUtil.ValidationResult validation = PasswordUtil.validatePassword(password);
    if (!validation.valid()) {
      player.sendMessage(error(validation.errorMessage()));
      return;
    }

    manager.lookup(player.getUniqueId()).thenAccept(lookup -> {
      if (lookup.isFailed()) {
        player.sendMessage(error("Authentication is unavailable. Please try again later."));
        return;
      }
      if (lookup.isFound()) {
        // Never overwrite an existing account from the register path.
        player.sendMessage(error("You are already registered. Type /login <password> to log in."));
        return;
      }

      manager.register(player.getUniqueId(), player.getUsername(), password)
          .thenAccept(stored -> {
            if (stored) {
              gate.completeAuthentication(player);
            } else {
              player.sendMessage(error("Could not save your password. Please try again."));
            }
          });
    });
  }

  private static void doLogin(final OfflineAuthManager manager, final OfflineAuthGate gate,
      final Player player, final String password) {
    manager.lookup(player.getUniqueId()).thenAccept(lookup -> {
      if (lookup.isFailed()) {
        player.sendMessage(error("Authentication is unavailable. Please try again later."));
        return;
      }
      final PasswordRecord record = lookup.record();
      if (record == null) {
        player.sendMessage(error("Type /register <password> <password> to register."));
        return;
      }

      manager.verify(record, password).thenAccept(matches -> {
        if (matches) {
          gate.completeAuthentication(player);
        } else if (!gate.recordFailedAttempt(player)) {
          player.sendMessage(error("Incorrect password. Please try again."));
        }
      });
    });
  }

  private static void doChangePassword(final OfflineAuthManager manager, final Player player,
      final String current, final String newPassword, final String confirmNew) {
    manager.lookup(player.getUniqueId()).thenAccept(lookup -> {
      if (lookup.isFailed()) {
        player.sendMessage(error("Authentication is unavailable. Please try again later."));
        return;
      }
      final PasswordRecord record = lookup.record();
      if (record == null) {
        player.sendMessage(error("You are not registered."));
        return;
      }

      manager.verify(record, current).thenAccept(matches -> {
        if (!matches) {
          player.sendMessage(error("Incorrect current password. Please try again."));
          return;
        }
        if (!newPassword.equals(confirmNew)) {
          player.sendMessage(error("Error: New passwords do not match. Please try again."));
          return;
        }
        final PasswordUtil.ValidationResult validation =
            PasswordUtil.validatePassword(newPassword);
        if (!validation.valid()) {
          player.sendMessage(error(validation.errorMessage()));
          return;
        }

        manager.changePassword(player.getUniqueId(), newPassword).thenAccept(updated ->
            player.sendMessage(updated
                ? Component.text("Password changed successfully.", NamedTextColor.GREEN)
                : error("Could not change your password. Please try again.")));
      });
    });
  }

  private static int usage(final CommandSource source, final String message) {
    source.sendMessage(error(message));
    return Command.SINGLE_SUCCESS;
  }

  private static Component error(final String message) {
    return Component.text(message, NamedTextColor.RED);
  }
}

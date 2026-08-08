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

import com.mojang.brigadier.tree.CommandNode;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.command.CommandExecuteEvent;
import com.velocitypowered.api.event.command.PlayerAvailableCommandsEvent;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.permission.PermissionsSetupEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.permission.PermissionFunction;
import com.velocitypowered.api.permission.PermissionProvider;
import com.velocitypowered.api.permission.Tristate;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.plugin.virtual.VelocityVirtualPlugin;
import com.velocitypowered.proxy.server.InternalServers;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Keeps unauthenticated offline players in the embedded limbo until they register or log in.
 *
 * <p>Everything is done with events rather than by patching the login handlers. A single
 * {@link ServerPreConnectEvent} listener covers every route to a backend — the initial connect for
 * both the pre-1.20.2 and 1.20.2+ login paths, {@code /server}, this fork's {@code comandos}
 * shortcuts, forced hosts and plugin-initiated redirects — because they all end up in
 * {@code createConnectionRequest}. That keeps {@code PostLoginEvent} firing normally, which the
 * earlier attempt broke.</p>
 *
 * <p>Chat needs no handling: the limbo never registers a serverbound chat packet, so anything a
 * waiting player types is discarded there and cannot reach a real server. Denying
 * {@link com.velocitypowered.api.event.player.PlayerChatEvent} would kick 1.19.1+ clients
 * outright.</p>
 */
public final class OfflineAuthGate {

  private static final Logger logger = LogManager.getLogger(OfflineAuthGate.class);

  /** Commands a waiting player may still run. Compared lowercase, without the leading slash. */
  private static final Set<String> ALLOWED_COMMANDS = Set.of("register", "login");

  private static final int AUTH_TIMEOUT_SECONDS = 60;
  private static final int MAX_FAILED_ATTEMPTS = 3;

  private final VelocityServer server;
  private final OfflineAuthManager manager;
  private final Map<UUID, ScheduledTask> timeouts = new ConcurrentHashMap<>();

  public OfflineAuthGate(final VelocityServer server, final OfflineAuthManager manager) {
    this.server = server;
    this.manager = manager;
  }

  /**
   * Whether this player must authenticate before reaching a real server.
   *
   * @param player the player
   * @return {@code true} for offline players that have not authenticated in this session
   */
  public boolean isGated(final Player player) {
    return !player.isOnlineMode() && !manager.isAuthenticated(player.getUniqueId());
  }

  /** Redirects gated players to the limbo, whatever their intended destination was. */
  @Subscribe(order = PostOrder.FIRST)
  public void onServerPreConnect(final ServerPreConnectEvent event) {
    final Player player = event.getPlayer();
    if (!isGated(player)) {
      // The limbo is a normally registered server so that plugins which enumerate servers — most
      // importantly ViaVersion, which pings each one to learn its protocol — behave. That means an
      // authenticated player could ask to go there by name, which is nothing but a way to get
      // stuck in an empty world.
      final Optional<RegisteredServer> requested = event.getResult().getServer();
      if (requested.isPresent() && InternalServers.isInternal(requested.get())) {
        event.setResult(ServerPreConnectEvent.ServerResult.denied());
      }
      return;
    }

    final RegisteredServer limbo = server.getLimboServer();
    if (limbo == null || !manager.isAvailable()) {
      // Fail closed. Letting them through would put an unauthenticated player on a real server.
      event.setResult(ServerPreConnectEvent.ServerResult.denied());
      player.disconnect(Component.text(
          "Authentication is unavailable. Please try again later.", NamedTextColor.RED));
      return;
    }

    final Optional<RegisteredServer> current = event.getResult().getServer();
    if (current.isPresent() && current.get().equals(limbo)) {
      return;
    }
    event.setResult(ServerPreConnectEvent.ServerResult.allowed(limbo));
  }

  /** Prompts the player once they are actually in the limbo, and starts the timeout. */
  @Subscribe
  public void onServerConnected(final ServerConnectedEvent event) {
    final Player player = event.getPlayer();
    final RegisteredServer limbo = server.getLimboServer();
    if (limbo == null || !event.getServer().equals(limbo) || !isGated(player)) {
      return;
    }

    startTimeout(player);
    promptFor(player);
  }

  /** Blocks every command except the authentication ones while the player is waiting. */
  @Subscribe(order = PostOrder.FIRST)
  public void onCommandExecute(final CommandExecuteEvent event) {
    if (!(event.getCommandSource() instanceof Player player) || !isGated(player)) {
      return;
    }

    final String command = event.getCommand();
    final int space = command.indexOf(' ');
    final String label = (space == -1 ? command : command.substring(0, space))
        .toLowerCase(Locale.ROOT);

    if (ALLOWED_COMMANDS.contains(label)) {
      return;
    }

    event.setResult(CommandExecuteEvent.CommandResult.denied());
    promptFor(player);
  }

  /**
   * Hides every command a waiting player has no business running.
   *
   * <p>Their own {@code requires} predicate already hides {@code /register}, {@code /login} and
   * {@code /changepassword} as appropriate, but that only governs this fork's own commands. Any
   * plugin that registers a command without a predicate the player fails — Geyser, ViaVersion and
   * the like — would still be listed and tab-completable. Stripping the graph here is the one place
   * that covers all of them.</p>
   */
  @Subscribe
  public void onAvailableCommands(final PlayerAvailableCommandsEvent event) {
    if (!isGated(event.getPlayer())) {
      return;
    }

    final var root = event.getRootNode();
    for (final CommandNode<?> child : new ArrayList<>(root.getChildren())) {
      if (!ALLOWED_COMMANDS.contains(child.getName().toLowerCase(Locale.ROOT))) {
        root.removeChildByName(child.getName());
      }
    }
  }

  /**
   * Denies every permission until the player authenticates.
   *
   * <p>A single function instance is installed, and it consults the gate on each call rather than
   * being swapped later — {@link PermissionsSetupEvent} fires once per player and cannot be
   * re-fired when authentication completes.</p>
   */
  @Subscribe
  public void onPermissionsSetup(final PermissionsSetupEvent event) {
    if (!(event.getSubject() instanceof Player player)) {
      return;
    }

    final PermissionProvider original = event.getProvider();
    event.setProvider(subject -> {
      final PermissionFunction delegate = original.createFunction(subject);
      if (delegate == null) {
        return permission -> isGated(player) ? Tristate.FALSE : Tristate.UNDEFINED;
      }
      return permission -> isGated(player)
          ? Tristate.FALSE
          : delegate.getPermissionValue(permission);
    });
  }

  /** Drops the session state and any pending timeout when the player leaves. */
  @Subscribe
  public void onDisconnect(final DisconnectEvent event) {
    final UUID uuid = event.getPlayer().getUniqueId();
    cancelTimeout(uuid);
    manager.forget(uuid);
  }

  /**
   * Sends the player to the server they would have reached had the gate not been there.
   *
   * @param player the newly authenticated player
   */
  public void completeAuthentication(final Player player) {
    manager.markAuthenticated(player.getUniqueId());
    cancelTimeout(player.getUniqueId());

    final Optional<RegisteredServer> destination = server.getConfiguration()
        .getAttemptConnectionOrder().stream()
        .map(server::getServer)
        .filter(Optional::isPresent)
        .map(Optional::get)
        .findFirst();

    if (destination.isEmpty()) {
      player.disconnect(Component.text(
          "There is no server available to send you to.", NamedTextColor.RED));
      return;
    }

    player.createConnectionRequest(destination.get()).fireAndForget();
    player.sendMessage(Component.text("Tip: Use /changepassword to change your password."));
  }

  /**
   * Records a failed login and kicks the player once they run out of attempts.
   *
   * @param player the player that got their password wrong
   * @return whether the player was kicked
   */
  public boolean recordFailedAttempt(final Player player) {
    if (manager.recordFailedAttempt(player.getUniqueId()) < MAX_FAILED_ATTEMPTS) {
      return false;
    }
    cancelTimeout(player.getUniqueId());
    player.disconnect(Component.text("Too many failed login attempts.", NamedTextColor.RED));
    return true;
  }

  /** Tells the player whether to register or log in, based on what is stored for them. */
  public void promptFor(final Player player) {
    manager.lookup(player.getUniqueId()).thenAccept(lookup -> {
      if (lookup.isFailed()) {
        player.disconnect(Component.text(
            "Authentication is unavailable. Please try again later.", NamedTextColor.RED));
        return;
      }
      manager.setKnownRegistered(player.getUniqueId(), lookup.isFound());
      player.sendMessage(Component.text(lookup.isFound()
          ? "Type /login <password> to log in."
          : "Type /register <password> <password> to register."));
    });
  }

  private void startTimeout(final Player player) {
    final UUID uuid = player.getUniqueId();
    cancelTimeout(uuid);
    final ScheduledTask task = server.getScheduler()
        .buildTask(VelocityVirtualPlugin.INSTANCE, () -> {
          timeouts.remove(uuid);
          if (isGated(player) && player.isActive()) {
            player.disconnect(Component.text("Authentication timed out.", NamedTextColor.RED));
          }
        })
        .delay(AUTH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .schedule();
    timeouts.put(uuid, task);
  }

  private void cancelTimeout(final UUID uuid) {
    final ScheduledTask task = timeouts.remove(uuid);
    if (task != null) {
      task.cancel();
    }
  }

  /** Cancels every pending timeout, on shutdown. */
  public void clear() {
    timeouts.values().forEach(ScheduledTask::cancel);
    timeouts.clear();
    logger.debug("Offline authentication gate cleared");
  }
}

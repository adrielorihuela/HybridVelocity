# Per-server shortcut commands

HybridVelocity can create a command for each of your servers, so that a player can type
`/Lobby` to be sent to the `Lobby` server instead of `/server Lobby`.

## Configuration

The servers that get a command are listed in the `comandos` option, inside the `[servers]`
section of `velocity.toml`:

```toml
[servers]
Lobby = "127.0.0.1:30066"
Survival = "127.0.0.1:30067"
Parkour = "127.0.0.1:30068"

# In what order we should try servers when a player logs in or is kicked from a server.
try = [
    "Lobby"
]

# Servers that also get their own command.
comandos = [
    "Lobby",
    "Survival"
]
```

With the configuration above, `/Lobby` and `/Survival` exist, while `/Parkour` does not —
players can still reach Parkour with `/server Parkour`.

Rules:

* Every name in `comandos` must also be a key in `[servers]`. If it is not, the proxy logs
  `Server command '<name>' is not registered in your configuration!` and refuses to start,
  the same way an unknown entry in `try` does.
* The option is optional. Leaving it out, or setting it to `[]`, disables the feature.
* Commands are case-insensitive, like every Velocity command: `/Lobby`, `/lobby` and
  `/LOBBY` all work regardless of how the server name is capitalised in the config.
* A name that collides with an existing command (`server`, `send`, `glist`, `velocity`,
  `stop`, or one registered by a plugin) is skipped with a warning in the console, so a
  server called `send` will not shadow `/send`.

## Permissions

Each command checks the permission `velocity.command.server.<server name in lowercase>` —
for example `velocity.command.server.lobby`.

The check follows the same permissive style as Velocity's own `/server` command: the
command is allowed unless the permission is explicitly set to **false**. That means:

* With no permissions plugin installed, every player can use every configured command.
* A permissions plugin can hide and block a specific command by negating its node, for
  example `-velocity.command.server.lobby` in LuckPerms.
* Granting the node explicitly also works, and is a no-op unless something else denies it.

Because each server has its own node, per-server access can be given to different ranks
without touching `/server`. Denied commands are also removed from the player's command
list, so they do not show up in tab completion.

## Reloading

`/velocity reload` re-reads `comandos`. Commands that were removed from the list are
unregistered and newly added ones are registered, without restarting the proxy.

## Console

These are player commands. Running one from the console does nothing, just like `/server`.

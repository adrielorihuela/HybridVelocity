# HybridVelocity

A fork of [Velocity](https://github.com/PaperMC/Velocity), the Minecraft server proxy,
that lets a single online-mode proxy serve both premium and offline players, and adds
quality-of-life features for server networks.

HybridVelocity is licensed under the GPLv3 license, like Velocity.

## What this fork adds

### Hybrid offline profiles on online-mode proxies

Upstream Velocity kicks any player that Mojang cannot authenticate when the proxy runs in
online mode. HybridVelocity accepts them instead as *hybrid offline profiles*:

* When the session server reports that no paid account owns the username, the player is
  given an offline UUID derived from their name with a `.` appended (`Steve123` becomes
  `Steve123.`). Usernames at the 16-character protocol limit are truncated to make room
  for the marker.
* The dotted name and its offline UUID are the player's identity on the proxy, so an
  offline player can never collide with the premium account of the same name.
* No player public key is retained for these profiles, because the key belongs to the
  Mojang account UUID and would break signed chat and commands.
* Premium players keep the stock Velocity flow, unchanged.

See `proxy/src/main/java/com/velocitypowered/proxy/connection/client/InitialLoginSessionHandler.java`.

### Per-server shortcut commands

Servers listed in the `comandos` option of `velocity.toml` get their own command named
after the server, so players can run `/Lobby` instead of `/server Lobby`. The commands are
available to everyone by default and can be restricted per server with permissions.
See [docs/server-commands.md](docs/server-commands.md).

### Offline player authentication

Players Mojang cannot authenticate can be required to register a password and log in before
they reach any of your servers. They wait in a limbo world that runs **inside the proxy** —
no extra server to install or configure — and passwords are stored as bcrypt hashes in an
embedded SQLite database. Off by default. See [docs/offline-auth.md](docs/offline-auth.md).

## Building

HybridVelocity is built with [Gradle](https://gradle.org). Use the wrapper script
(`./gradlew`, or `./gradlew.bat` on Windows); running `./gradlew build` performs the full
build cycle, including Checkstyle, Spotless and the tests.

## Running

Once built, copy and run the `-all` JAR from `proxy/build/libs`. The proxy generates a
default `velocity.toml` on first startup and you can configure it from there.

## Documentation

Fork-specific documentation lives in [docs/](docs/README.md). For everything inherited
from upstream, the [Velocity documentation](https://docs.papermc.io/velocity) still
applies.

## Upstream

This project is a derivative of Velocity by the PaperMC team. Bugs that also reproduce on
upstream Velocity should be reported to
[PaperMC/Velocity](https://github.com/PaperMC/Velocity), not here.

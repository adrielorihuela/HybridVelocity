# HybridVelocity

A fork of [Velocity](https://github.com/PaperMC/Velocity) that lets one online-mode proxy serve
premium and non-premium players together, with password authentication built in — no extra plugin,
no extra server.

Licensed under the GPLv3, like Velocity.

## What it does

**Non-premium players are welcome, and must prove who they are.** Upstream Velocity kicks anyone
Mojang cannot verify. HybridVelocity gives them an offline identity instead and holds them on a
small authentication server running inside the proxy until they `/register` or `/login`. Only then
do they reach a real server. Premium players are untouched — they connect exactly as before, with no
prompt.

**Passwords are handled properly.** bcrypt with a per-record salt, in an embedded SQLite database.
Nothing to install and nothing to configure. Everything fails closed: if the database or the
authentication server is unavailable, players are refused rather than let through.

**Identities cannot collide.** A non-premium `Steve123` becomes `Steve123.` with a UUID derived from
that name, so they can never be mistaken for the premium account of the same name.

**A command per server.** List a server under `comandos` and players get `/lobby` instead of
`/server lobby`, permission-controlled per server.

Everything else is Velocity: same plugins, same API, same performance. Works with ViaVersion,
Geyser and Floodgate.

## Quick start

Download `HybridVelocity-<version>-all.jar` from the
[releases](https://github.com/adrielorihuela/HybridVelocity/releases) and run it:

```bash
java -Xms512M -Xmx512M -jar HybridVelocity-1.1.0-all.jar
```

Upgrading from stock Velocity? Just swap the jar — your `velocity.toml` is renamed and migrated,
contents intact.

Then read [docs/guide/getting-started.md](docs/guide/getting-started.md).

## Documentation

- **[docs/guide/](docs/guide/README.md)** — running the proxy: installation, the authentication
  gate and its options, the per-server commands.
- **[docs/development/](docs/development/README.md)** — how it was built: the specification, the
  protocol research, the attempt that failed and the architecture that replaced it.

Anything not covered there behaves as upstream, so the
[Velocity documentation](https://docs.papermc.io/velocity) still applies.

## Building

```bash
./gradlew build
```

Uses the Gradle wrapper (`./gradlew.bat` on Windows) and runs Checkstyle, Spotless and the tests.
The runnable jar is `proxy/build/libs/HybridVelocity-<version>-all.jar`.

## Upstream

A derivative of Velocity by the PaperMC team, and it bundles
[NanoLimbo](https://github.com/Nan1t/NanoLimbo) (also GPL-3.0) as the authentication server. Bugs
that also reproduce on upstream Velocity belong at
[PaperMC/Velocity](https://github.com/PaperMC/Velocity), not here.

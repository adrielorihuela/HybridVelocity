# Getting started

HybridVelocity runs like any Velocity proxy. If you have used Velocity before, everything you know
still applies — this page only covers what is different.

## Install

Download `HybridVelocity-<version>-all.jar` from the
[releases](https://github.com/adrielorihuela/HybridVelocity/releases) and run it:

```bash
java -Xms512M -Xmx512M -jar HybridVelocity-1.1.0-all.jar
```

On first start it creates:

| | |
| --- | --- |
| `hybridvelocity.toml` | The configuration. |
| `forwarding.secret` | The shared secret for modern player-info forwarding. |
| `lang/` | Message files. This copy wins over the ones inside the jar, so edit here to change wording. |
| `auth/` | The authentication server's settings and the password database. |

If you are upgrading from stock Velocity, put the jar in place and start it: your existing
`velocity.toml` is renamed to `hybridvelocity.toml` with its contents intact, and the configuration
is migrated in place.

## Configure your servers

Same as Velocity — list them under `[servers]` and pick the connection order with `try`:

```toml
[servers]
lobby = "127.0.0.1:30066"
survival = "127.0.0.1:30067"

try = [
    "lobby"
]

comandos = [
    "lobby"
]
```

`comandos` is this fork's addition: every server listed there also gets a command named after it, so
players can type `/lobby` instead of `/server lobby`. See [server-commands.md](server-commands.md).

## What is on by default

**Offline players are accepted and must authenticate.** A player Mojang cannot verify is not
kicked; they are given an offline identity and held on a small authentication server inside the
proxy until they `/register` or `/login`. Premium players are unaffected.

Turn it off with `enabled = false` under `[offline-auth]` if you want stock Velocity behaviour.

Read [offline-auth.md](offline-auth.md) before running this in production — especially the part
about backing up `auth/player-passwords.db`.

## Backend servers

Backends must be in **offline mode** (`online-mode=false` in their `server.properties`), as with any
proxy, and should only be reachable from the proxy machine. Use `player-info-forwarding-mode =
"modern"` on 1.13+ so backends see real player identities.

## Next

- [offline-auth.md](offline-auth.md) — the register/login gate, its options and its limitations.
- [server-commands.md](server-commands.md) — per-server shortcut commands and permissions.
- [hybrid-offline-profiles.md](hybrid-offline-profiles.md) — how offline identities are built.

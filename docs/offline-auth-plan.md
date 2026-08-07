# Next update: offline authentication with an embedded limbo

This is the adopted plan for delivering the register/login gate specified in
[update-plan.md](update-plan.md). It supersedes
[discarded/offline-auth-roadmap.md](discarded/offline-auth-roadmap.md).

**Approach: vendor [NanoLimbo](https://github.com/Nan1t/NanoLimbo) into this repository as a Gradle
module and run it in-process, inside the proxy JVM, bound to loopback and registered automatically.**

The operator installs one JAR and configures nothing. There is no second process, no second JVM, no
extra port to open, and no backend to set up. Updating the vendored limbo is a `git fetch` plus a
subtree merge.

## Why this option

The deployment constraints were: minimum RAM and CPU on a single physical host, easy to update, and
**no extra backend to configure** — the proxy must handle everything itself or carry an integrated
one.

| Option | Extra backend? | RAM cost | Update effort | Verdict |
| --- | --- | --- | --- | --- |
| Write a limbo inside the proxy from scratch | No | Lowest | ~9 packet classes, ~150 per-version IDs, registry capture, 2-6 h **per Minecraft release forever** | Rejected — 4-6 weeks and permanent protocol maintenance |
| Separate NanoLimbo process | Yes | A second JVM baseline on top | Replace one JAR | Rejected — violates the "no extra backend" constraint |
| **Vendored NanoLimbo, in-process** | **No** | **One JVM; the limbo adds its own Netty groups and a small heap** | **`git fetch` + subtree merge** | **Adopted** |
| Bundle LimboAPI + LimboAuth as shipped plugins | No | One JVM | Track two upstreams | Rejected — see below |

### Why not LimboAPI/LimboAuth

[LimboAPI](https://github.com/Elytrium/LimboAPI) and
[LimboAuth](https://github.com/Elytrium/LimboAuth) implement exactly this feature and are actively
maintained. They were rejected for two reasons:

- **Licence.** They are **AGPL-3.0**; this fork inherits Velocity's **GPL-3.0**. GPLv3 §13 permits
  the combination, but the AGPL's network-use clause would then attach to the combined work.
  NanoLimbo is **GPL-3.0** — the same licence this project already carries, so vendoring it changes
  nothing about the obligations.
- **Coupling.** LimboAPI is a plugin that reaches into Velocity's internals from outside. This
  repository *is* a Velocity fork whose internals differ from upstream, which is precisely the
  surface LimboAPI depends on.

### Precedent

[ElytraProxy](https://github.com/Elytrium/ElytraProxy) was a Velocity fork with exactly this design
— a built-in virtual server plus an SQLite/BCrypt auth system, no external backend. It proves the
architecture works. It was **archived in August 2021** and only ever supported 1.7-1.17.1, because
its authors could not keep maintaining hand-written protocol code and moved to the LimboAPI plugins
instead. That is the failure mode the vendoring strategy is designed to avoid: the protocol work is
tracked upstream rather than owned here.

### On using Paper as an upstream

Paper cannot serve as an upstream for this. Paper is a Minecraft **server** (a Bukkit/Spigot fork);
Velocity is a **proxy**. They share an organisation, not a codebase, so there is nothing for `git
fetch` to bring in and nothing to merge. The vendoring strategy below is the workable form of that
idea, with NanoLimbo as the upstream.

## Upstream tracking

NanoLimbo publishes no Maven artifact, so it is vendored as source. As of this writing: GPL-3.0,
active (last push July 2026), ~2.7 MB, supports 1.7 through 26.2 — the same ceiling as this fork's
`ProtocolVersion`.

```
git remote add limbo-upstream https://github.com/Nan1t/NanoLimbo.git
git subtree add --prefix=limbo limbo-upstream main --squash
```

To update later:

```
git fetch limbo-upstream && git subtree pull --prefix=limbo limbo-upstream main --squash
```

**Keep the patch surface minimal** — every local change is a potential merge conflict on the next
update. The required patches are only these:

| Patch | Why |
| --- | --- |
| `LimboServer` config path is hardcoded to `Paths.get("./")` | Accept a path or a programmatic config object so the proxy can supply settings in memory |
| Standalone-only startup: JVM shutdown hook, interactive command manager, own logger setup | Must not run when embedded; gate them behind an "embedded" flag |
| Netty boss/worker group sizes | Size them to 1 when embedded; the loopback listener serves a handful of waiting players |

Everything else — the protocol code, the per-version registries, the world handling — stays
untouched and updates cleanly. That is the entire point of the approach.

## Design

### Startup

`VelocityServer#start()` already has an ordering constraint: built-in commands are registered before
the config is loaded, and configuration-derived state must be registered after the server loop (this
is where `registerServerCommands` lives). The embedded limbo goes in the same place:

1. If offline auth is enabled, start `LimboServer` on `127.0.0.1` with an ephemeral port (or a
   configured one), with an in-memory config: MODERN forwarding using the proxy's own
   `forwarding.secret`, so the loopback hop is authenticated with no user setup.
2. Register it as a `ServerInfo` under a reserved name that is not offered by `/server`, the
   `comandos` shortcuts (see [server-commands.md](server-commands.md)) or tab completion.
3. Shut it down in `VelocityServer#shutdown`, and restart it on `/velocity reload` if its settings
   changed — the same mirror-image treatment `reloadConfiguration()` gives every other
   configuration-derived subsystem.

### Gating

Both call sites in `AuthSessionHandler` must be closed — `handle(LoginAcknowledgedPacket)` (line
188, the 1.20.2+ path) and `completeLoginProtocolPhaseAndInitialize` (line 249, the pre-1.20.2
path). Closing only one leaves half the version range unprotected.

`PostLoginEvent` must still fire. The previous attempt skipped it, which breaks plugins independently
of any protocol issue.

Unauthenticated players are pinned to the limbo by a single high-priority `ServerPreConnectEvent`
listener, which covers `/server`, the `comandos` shortcuts, forced hosts and plugin redirects in one
hook. Chat and commands are filtered with `PlayerChatEvent` and `CommandExecuteEvent`, letting only
`/register` and `/login` through — no packet-level string parsing, which is how the previous attempt
did it.

On success, resolve the destination with `getNextServerToTry()` and connect with
`createConnectionRequest(...)`, the same calls `AuthSessionHandler` already makes.

### Authentication core

Reuse `AuthDatabase`, `PasswordUtil`, `PasswordRecord` and `ChangePasswordHandler` from the
`codex-attempt-2` branch, with the six defects listed in
[offline-auth-postmortem.md](offline-auth-postmortem.md#defects-unrelated-to-the-protocol) fixed
first. The two that matter most: bcrypt must not run on the Netty event loop, and
`getPasswordRecord` must distinguish "no row" from "query failed" so a broken database cannot offer
`/register` for an account that already exists.

The 60-second auth timeout, the 3-strike lockout, the password rules and the exact English chat
strings all come from [update-plan.md](update-plan.md). Register `/changepassword` as a real
Brigadier command through `VelocityCommandManager`.

Note that the limbo sends its own KeepAlive, so the proxy's 30 s `read-timeout` is satisfied without
any extra ticker — one of the independent failure causes from the first attempt disappears entirely.

### Configuration surface

A new `[offline-auth]` section in `velocity.toml`: `enabled`, an optional fixed `port`, the database
file location, and the limbo's spawn/brand settings worth exposing. Cross-checks go in
`VelocityConfiguration#validate()`, the same way `try` and `comandos` are validated.

## Resource notes

The dominant RAM cost of the rejected separate-process option is a **second JVM baseline** — heap
floor, metaspace, GC structures and thread stacks — paid before a single player connects. Running
in-process avoids all of it. What remains is NanoLimbo's own Netty event loop groups and a small
heap for waiting players; sizing the groups to 1 when embedded keeps that marginal.

Traffic to the limbo crosses the loopback interface rather than staying in-memory. That is a real
cost compared with a from-scratch in-proxy limbo, but it applies only to players sitting at the
login prompt, and it buys away the entire per-release protocol maintenance burden. Passing
Velocity's existing event loop groups into the embedded limbo is a possible later optimisation; it
enlarges the patch surface, so it is deliberately not part of the first version.

These are architectural estimates, not measurements. Measure actual RSS with and without the limbo
enabled before tuning anything.

## Phasing

1. Vendor NanoLimbo as a subtree; apply the three patches; confirm it starts and stops with the
   proxy and that a client can reach it over loopback.
2. Fix the six auth-core defects and land `AuthDatabase` / `PasswordUtil` / `PasswordRecord` with
   tests.
3. Wire the gate: both `AuthSessionHandler` call sites, the `ServerPreConnectEvent` pin, the chat
   and command filter, the handoff after success.
4. `/changepassword` and the post-login tip message.
5. Document the operator-facing behaviour and bump `forkVersion`.

## Verification

Unit tests cover the password rules, hashing and database. The gate logic is testable with the event
listeners in isolation. But the first attempt shipped green tests and a broken protocol: the
acceptance criterion is a **real client of each major version** reaching the login prompt, being
unable to leave the limbo, and landing on the correct server after `/login`.

## Related documents

- [update-plan.md](update-plan.md) — the functional specification this implements.
- [offline-auth-postmortem.md](offline-auth-postmortem.md) — why the first attempt failed.
- [offline-auth-requirements.md](offline-auth-requirements.md) — what the client demands; still the
  reference for anyone tempted to hand-write protocol code.
- [discarded/offline-auth-roadmap.md](discarded/offline-auth-roadmap.md) — the superseded analysis.

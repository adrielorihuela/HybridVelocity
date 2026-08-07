# Offline authentication: implementation roadmap (SUPERSEDED)

> **This document is discarded. It is kept for the reasoning it records, not as a plan to follow.**
>
> It weighed two architectures: writing a limbo inside the proxy from scratch (Route A) and running
> a separate limbo server process (Route B). A later constraint ruled both out — the deployment must
> not require configuring an extra backend, and must minimise RAM on a single physical host.
>
> Route A remains rejected on cost: ~9 packet classes, ~150 per-version packet IDs and perpetual
> per-release maintenance. Route B is rejected on the operational constraint: a second process to
> install, configure and monitor.
>
> The adopted approach — vendoring NanoLimbo into the fork and running it in-process — is in
> [../offline-auth-plan.md](../offline-auth-plan.md). It keeps everything under
> "[What both routes must do](#what-both-routes-must-do)" below, which is still accurate and is
> referenced by the live plan.

How to deliver the register/login gate specified in [update-plan.md](../update-plan.md),
given the failure documented in [offline-auth-postmortem.md](../offline-auth-postmortem.md) and the
client requirements catalogued in [offline-auth-requirements.md](../offline-auth-requirements.md).

There are two viable architectures. They differ only in **where the player waits**; the
authentication logic itself is identical and shared.

> **Recommendation:** build Route B first. It delivers the feature in about a tenth of the effort
> and carries no protocol risk, and it exercises the authentication core against real players. Keep
> Route A as an optional later migration if the dependency on a limbo server becomes unacceptable —
> the auth core does not change when you switch.

## What both routes must do

### Close both gates

`AuthSessionHandler` reaches the backend through `connectToInitialServer` from **two** call sites:

- `handle(LoginAcknowledgedPacket)` — line 188, the 1.20.2+ path
- `completeLoginProtocolPhaseAndInitialize` — line 249, the pre-1.20.2 path

Gating only one leaves half the version range unprotected. `ConnectedPlayer#handleConnectionException`
is the third place a player can end up serverless (kicked from a backend with no fallback).

**Keep firing `PostLoginEvent`.** The first attempt skipped it in both branches, breaking every
plugin that depends on it, and that was a design defect independent of the protocol bugs.

### Reuse the authentication core, with fixes

`AuthDatabase`, `PasswordUtil`, `PasswordRecord` and `ChangePasswordHandler` from
`codex-attempt-2` (`git show codex-attempt-2:proxy/src/main/java/com/velocitypowered/proxy/auth/...`)
are a reasonable starting point. Six defects must be fixed first — they are itemised in the
post-mortem. The two that matter most:

- **bcrypt runs on the Netty event loop.** Hashing must be dispatched off-loop via
  `VelocityScheduler`, like every other blocking operation in this codebase.
- **`getPasswordRecord` returns `null` for both "no row" and "query failed".** A broken database
  therefore presents `/register` to an already-registered player and lets their account be
  overwritten. The result type must distinguish the three cases, and a database error must fail
  closed.

Also carry over: the 60-second timeout (which only works if a KeepAlive tick is running — see
`read-timeout` in the requirements doc), the 3-strike lockout, the password rules, and the exact
English chat strings from [update-plan.md](../update-plan.md). Register `/changepassword` as a real
Brigadier command through `VelocityCommandManager` rather than matching a string prefix.

### Testing

| Layer | How |
| --- | --- |
| Password rules, hashing, database | Plain JUnit — already covered on `codex-attempt-2` |
| Packet sequence and state | `EmbeddedChannel` handler tests asserting the exact clientbound order and, critically, that **no PLAY packet is written before the client's `Finish Configuration` acknowledgement** |
| Auth flow | Timeout fires at 60 s, lockout at 3 failures, non-auth commands blocked, backend connection attempted only after success |
| Everything else | A real client, per version. There is no substitute; the first attempt had green tests and a broken protocol |

---

## Route A — limbo synthesized inside the proxy

Satisfies [update-plan.md](../update-plan.md) §4.1 literally: no backend connection at all. This is
what [LimboAPI](https://github.com/Elytrium/LimboAPI) does.

### Scope

1. **New packet classes**: `GameEvent`, `ChunkData`, `SynchronizePlayerPosition`, `PlayerAbilities`,
   `SetCenterChunk`, `SetDefaultSpawnPosition`, plus supporting types — roughly 9 classes. Because
   `StateRegistry.java:267` disables the clientbound fallback for PLAY, each needs hand-verified
   packet IDs for every supported protocol version: on the order of 150 mappings. Chunk data alone
   spans about six incompatible encoding eras between 1.7 and current.

2. **Registry and tag data per version.** This is the hard part and it cannot be authored by hand —
   the client validates it. The practical capture mechanism is a debug flag **inside this proxy**
   that dumps the raw `registry_data` and `update_tags` payloads that `RegistrySyncPacket` already
   receives from a real backend server, since it holds them as opaque bytes. Store them gzipped
   under `proxy/src/main/resources/limbo/<protocol>/`, with a manifest recording the Minecraft
   version, protocol number, source server hash and capture date. For 1.20.5+ the simplest route is
   to skip the Known Packs exchange and capture a fully inline blob.

3. **State machine.** Replay the captured CONFIGURATION packets in order with the encoder in
   CONFIG; write `FinishedUpdatePacket` last; flip only the encoder to PLAY, mirroring
   `ClientConfigSessionHandler:343-344`; keep the decoder in CONFIG until the client's
   acknowledgement; then install the limbo PLAY handler and send the six-packet spawn sequence.
   Pre-1.20.2 clients skip straight to the spawn sequence.

4. **Unknown protocol ⇒ fail closed.** Disconnect with a clear message. Never approximate with
   another version's registry data, and never send an incomplete `Finish Configuration`.

5. **Handoff after authentication** needs no new code: restore `InitialConnectSessionHandler` and
   let the existing `switchToConfigState` / `doFastClientServerSwitch` machinery run — that path is
   already how Velocity moves a player between two worlds.

### Cost and risk

**~4-6 weeks**, plus **2-6 hours of protocol work for every Minecraft release, indefinitely**. Each
new version needs fresh registry capture and new packet IDs, and a missed update means offline
players cannot log in at all. The failure modes are subtle, client-side, and only reproducible with
a real client of each version.

---

## Route B — dedicated limbo backend server

A minimal limbo server ([NanoLimbo](https://github.com/Nan1t/NanoLimbo): ~5 MB, JRE 21+, supports
1.7 through 26.2, MODERN forwarding with Velocity's secret) registered in `velocity.toml` like any
other backend. The proxy sends unauthenticated players there and keeps them pinned until they
authenticate.

The limbo server supplies the world, the registries and the version compatibility. The proxy writes
no packets it does not already write today.

### Scope

1. **Configuration** in `velocity.toml`: which registered server is the limbo, and whether the gate
   is enabled. Validate in `VelocityConfiguration#validate()` that the named server exists, the same
   way `try` and `comandos` are validated (see [server-commands.md](../server-commands.md)).

2. **Routing**: at both gate points, send unauthenticated players to the limbo server instead of the
   normal initial server.

3. **Pinning**: a single high-priority `ServerPreConnectEvent` listener that denies any destination
   other than the limbo while the player is unauthenticated. One hook covers `/server`, this fork's
   `comandos` shortcut commands, forced hosts and plugin-initiated redirects at once.

4. **Chat gate**: `PlayerChatEvent` and `CommandExecuteEvent` listeners that let only `/register`
   and `/login` through and reply with the specified messages otherwise. Unlike the first attempt,
   this needs no packet-level string parsing.

5. **Handoff**: on success, resolve the real destination with `getNextServerToTry()` and connect via
   `createConnectionRequest(...)` — the same calls `AuthSessionHandler` already makes.

### Failure modes to handle

- **Limbo server down** — the gate must refuse the login with a clear message, never fall through to
  a real server.
- **Forwarding secret mismatch** — the limbo must accept the proxy's forwarding mode.
- **Restart mid-session** — per the spec, session state is not restored; the player re-authenticates
  on their next connection.

### Cost and risk

**~1-1.5 weeks.** Near-zero protocol risk and no per-release maintenance. The trade-off is one extra
process to run and monitor, and a technical deviation from §4.1 of the spec — a limbo server is not
a real backend, and the player still never reaches one before authenticating, so the intent is
satisfied even though the letter is not.

---

## Comparison

| | Route A (in-proxy) | Route B (limbo backend) |
| --- | --- | --- |
| Matches spec §4.1 literally | Yes | No (intent yes, letter no) |
| Effort | 4-6 weeks | 1-1.5 weeks |
| New packet classes | ~9, ~150 version mappings | 0 |
| Registry data to capture and ship | Yes, per version | No |
| Per-Minecraft-release maintenance | 2-6 h, forever | None |
| Extra process to run | No | Yes (~5 MB) |
| Protocol risk | High | Negligible |
| Version coverage | Only versions you capture | Whatever the limbo supports |

## Suggested phasing

1. Fix the six auth-core defects and land `AuthDatabase` / `PasswordUtil` / `PasswordRecord` with
   tests. Independent of both routes.
2. Land Route B: configuration, routing, pinning, chat gate, handoff. Feature complete.
3. Register `/changepassword` properly and add the post-login tip message.
4. *Optional, later:* Route A as a migration, starting with the registry capture tooling — if that
   step does not work reliably, nothing after it will.

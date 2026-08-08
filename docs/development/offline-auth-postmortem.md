# Offline authentication: post-mortem of the first attempt

The register/login gate specified in [update-plan.md](offline-auth-specification.md) was implemented once and it
did not work: the client disconnected instead of waiting on the proxy. This document records what
was built, what the client actually reported, and why each cause matters, so the next attempt does
not repeat any of it.

The attempt was made on a branch that has since been deleted, so **none of it is retrievable and
none of it is on `main`**. This document is the record. File paths below describe where the code
lived on that branch, not files that exist today.

It carried two notes of its own:

- a copy of the specification, byte-identical to
  [offline-auth-specification.md](offline-auth-specification.md);
- a partial failure analysis with a proposed fix, **correct as far as it goes but incomplete** — see
  [What the previous analysis missed](#what-the-previous-analysis-missed). Its findings are quoted
  in full below, which is the only reason they survive.

## What was built

The gate was inserted in `AuthSessionHandler`, keyed on the hybrid offline profile (the dotted
username produced when Mojang returns HTTP 204 — see
[hybrid-offline-profiles.md](../guide/hybrid-offline-profiles.md)):

| Client version | What the attempt did |
| --- | --- |
| 1.20.2+ | On `LoginAcknowledgedPacket`, installed `OfflineAuthConfigSessionHandler` in `StateRegistry.CONFIG` instead of `ClientConfigSessionHandler` |
| < 1.20.2 | Installed `OfflineAuthSessionHandler` in `StateRegistry.PLAY` instead of `InitialConnectSessionHandler` |

In both branches it **skipped `PostLoginEvent` and `connectToInitialServer` entirely**.

`OfflineAuthConfigSessionHandler` (82 lines) was the whole configuration phase:

```java
@Override
public void activated() {
  MinecraftConnection connection = player.getConnection();
  connection.write(FinishedUpdatePacket.INSTANCE);                                    // line 56
  connection.getChannel().pipeline().get(MinecraftEncoder.class)
      .setState(StateRegistry.PLAY);                                                  // line 57
}
```

On the client's `FinishedUpdatePacket` it swapped in `OfflineAuthSessionHandler` (328 lines), which
started a 60-second timeout, a 10-second KeepAlive tick, queried SQLite for a password record, and
sent either the `/login` or the `/register` prompt. All five chat/command packet variants were
funnelled into a string parser; anything that was not `/register` or `/login` got
`Please log in first.` On success it fired `PostLoginEvent` and ran its own copy of
`connectToInitialServer`.

Supporting classes: `OfflineAuthManager`, `AuthDatabase` (SQLite via `org.xerial:sqlite-jdbc`),
`PasswordUtil` (bcrypt via `at.favre.lib:bcrypt`), `PasswordRecord`, `ChangePasswordHandler`. Tests
were added for the database and the password rules — **none for the protocol path, which is exactly
where it broke.**

## What the client reported

Two distinct failures, both quoted verbatim from that branch's own notes:

```text
Missing tag TagKey[minecraft:damage_type / minecraft:is_fire]
```

```text
Received unknown packet id 121
```

The second came from an earlier iteration that skipped the configuration phase and jumped straight
to PLAY.

## Why it failed

### 1. The CONFIGURATION phase was sent empty (the confirmed failure)

Since 1.20.2 the protocol has a CONFIGURATION phase between LOGIN and PLAY. Before the client
accepts `Finish Configuration` the server must send the registry data and tags for that version.
`OfflineAuthConfigSessionHandler.activated()` sent `FinishedUpdatePacket` as the *only* clientbound
packet: no `RegistrySyncPacket`, no `TagsUpdatePacket`, no `KnownPacksPacket`, no
`ActiveFeaturesPacket`.

The vanilla client requires the `damage_type` tags `minecraft:is_fire`, `minecraft:is_explosion`
and `minecraft:bypasses_shield` to be defined, because they back the default values of item
components. Missing them, it refuses the packet — hence the error. The full requirement is
catalogued in [offline-auth-requirements.md](offline-auth-requirements.md).

### 2. PLAY packets were sent to a client still in CONFIGURATION

The earlier iteration produced `Received unknown packet id 121` because a PLAY-state KeepAlive
reached a client whose decoder was still in CONFIGURATION. Packet IDs are per-state; the same byte
means different things in each. This is a permanent hazard for any code that calls
`player.sendMessage(...)` or `sendKeepAlive()` during a state transition.

### 3. No `Login (play)` packet was ever sent — and no world

**This is the cause that analysis does not mention, and it would have sunk the proposed fix too.**

Nothing on that branch writes `JoinGamePacket`, chunk data, or a player position. After the fake
`finish_configuration` the client enters PLAY expecting `Login (play)` as the very first packet and
instead receives a KeepAlive and a chat message. Even with perfect registry data the client would
sit on "Loading terrain" forever, and sending anything before `Login (play)` is a protocol
violation in its own right.

Velocity has never synthesized a world. It always relays the backend's `JoinGamePacket`, and
`RegistrySyncPacket` is an opaque byte holder that only ever gets forwarded from a real server.
A proxy-side limbo needs a whole synthetic world state that does not exist anywhere in this
codebase.

### 4. Packet classes required for a world do not exist in this fork

`GameEvent`, `ChunkData`/`level_chunk_with_light`, `SynchronizePlayerPosition`, `PlayerAbilities`,
`SetCenterChunk` and `SetDefaultSpawnPosition` have no class at all. `StateRegistry` sets
`clientbound.fallback = false` for PLAY (`StateRegistry.java:267`), so each one also needs explicit
packet-ID mappings for every protocol version.

### 5. The proxy's own read timeout fires before the auth timeout

`ServerChannelInitializer.java:65` installs a Netty `ReadTimeoutHandler` using
`read-timeout`, which defaults to **30000 ms** (`VelocityConfiguration.java:792`). The attempt's
authentication timeout was 60 seconds. A client with no world sends almost nothing, so the read
timeout would kill the connection at 30 s regardless of the auth logic — a second, independent
disconnect cause that was never reached because the registry error fired first.

### 6. `PostLoginEvent` was skipped

Both gated branches bypassed `PostLoginEvent`. That breaks every plugin that relies on it, and it
is a design defect independent of the protocol bugs. Whatever gate replaces this must still fire
it.

## What the previous analysis missed

The branch's own analysis correctly identified cause 1 and cause 2, and proposed a real fix
for them: capture a vanilla server's CONFIGURATION packet sequence per version, ship it as an
opaque blob in the JAR, replay it, and only then finish configuration. That reasoning is sound as
far as the configuration phase goes.

But that plan stops at the end of CONFIGURATION. It never addresses causes **3, 4, 5 or 6**. A
client that completes configuration and enters PLAY with no `Login (play)` and no world is still a
broken client. Implementing `to-do.md` as written would have replaced one disconnect with a hang on
the loading screen.

## Defects unrelated to the protocol

The authentication core is reusable, but these must be fixed before it is:

| Defect | Consequence |
| --- | --- |
| bcrypt hashing runs on the Netty event loop (`PasswordUtil` called synchronously from the packet handler) | Blocks the event loop for every login; the codebase's own rule is that blocking work is dispatched off-loop |
| `AuthDatabase.getPasswordRecord` swallows `SQLException` and returns `null` | "database is broken" is indistinguishable from "not registered", so a failing database offers `/register` to an already-registered player and lets their account be overwritten |
| Database path is `Path.of("player_auth.db")` | Relative to the working directory instead of the proxy data directory |
| `/changepassword` implemented as a raw string prefix match in `ClientPlaySessionHandler` | Bypasses the command system entirely: no permissions, no tab completion, no `CommandExecuteEvent` |
| `ConnectedPlayer.teardown()` widened from package-private to `public` | Unnecessary API surface; `teardown()` is at `ConnectedPlayer.java:938` on `main` and should stay package-private |
| `connectToInitialServer` logic duplicated inside the auth handler | Two copies of the initial-server selection rules that will drift |

## Where to go next

- [offline-auth-requirements.md](offline-auth-requirements.md) — what the client demands, per version.
- [offline-auth-plan.md](offline-auth-plan.md) — the adopted plan, which avoids all six causes above
  by embedding an existing limbo server rather than hand-writing protocol code.

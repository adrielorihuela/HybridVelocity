# Offline authentication: what the client requires

Reference for holding a player on the proxy with no backend server, independent of how it gets
implemented. Everything here is what the **vanilla client** demands; violating any of it produces a
disconnect or a permanent loading screen, as documented in
[offline-auth-postmortem.md](offline-auth-postmortem.md).

Protocol facts are from the [Minecraft Wiki protocol pages](https://minecraft.wiki/w/Java_Edition_protocol/Packets)
and [protocol registries page](https://minecraft.wiki/w/Java_Edition_protocol/Registries). Line
references are to this repository on `main`.

## Three flows, by client version

Velocity supports 1.7 through the current release, so a gate that covers "all versions" is really
three code paths.

| Client | Flow |
| --- | --- |
| < 1.20.2 | LOGIN → PLAY directly. No configuration phase. `Login (play)` is the first PLAY packet. |
| 1.20.2 – 1.20.4 | LOGIN → CONFIGURATION → PLAY. Server sends registry data + tags, then `Finish Configuration`. |
| 1.20.5+ | Same, plus the **Known Packs** exchange before registry data. |

The proxy sees the client's protocol version on `MinecraftConnection`; note that ViaVersion and
friends translate packet *IDs* but never supply registry *content*, so version selection must use
the protocol the proxy actually negotiated.

## CONFIGURATION phase (1.20.2+)

Before `Finish Configuration` the server must send, in this order:

1. **Clientbound Known Packs** (1.20.5+) — the server advertises data packs it knows; the client
   answers with the ones it also has, and the server may then omit that NBT from registry data.
   **A server may skip this exchange, but then all NBT must be present inline in Registry Data.**
2. **Registry Data** — one packet per synchronized registry. The client validates these.
3. **Update Tags**.
4. **Feature Flags**, if the version expects them.

Then `Finish Configuration`, and the encoder switches to PLAY. The client acknowledges with its own
`Finish Configuration`; the decoder must stay in CONFIGURATION until that acknowledgement arrives.

### Registries and tags that are not optional

| Registry | Requirement |
| --- | --- |
| `minecraft:dimension_type` | At least one entry — the `Login (play)` packet references it |
| `minecraft:worldgen/biome` | Must include `minecraft:plains` |
| `minecraft:damage_type` | All 24 damage types |
| `minecraft:painting_variant` | Must be non-empty |
| `minecraft:wolf_variant`, chicken/mob variants | Specific named entries, version dependent |
| `minecraft:chat_type` | Needed for chat to render |

In **Update Tags**, these `damage_type` tags must be defined or the client rejects
`Finish Configuration` outright:

- `minecraft:is_fire`
- `minecraft:is_explosion`
- `minecraft:bypasses_shield`

They back the default values of item components. This is the exact cause of the
`Missing tag TagKey[minecraft:damage_type / minecraft:is_fire]` failure in the first attempt.

**This data cannot be invented or hand-written.** It is version-specific, large, and validated by
the client. It has to be captured from a real vanilla server of the matching version.

## PLAY phase: the spawn sequence

Immediately after the client acknowledges `Finish Configuration` (or, pre-1.20.2, right after login
success), the server must send this sequence. Order matters:

| # | Packet | Official name | Why |
| --- | --- | --- | --- |
| 1 | Login (play) | `login` | Must be the **first** PLAY packet. Carries entity id, gamemode, dimension, view distance |
| 2 | Game Event — *start waiting for level chunks* | `game_event` | Without it the client never leaves the loading screen |
| 3 | Synchronize Player Position | `player_position` | Places the player |
| 4 | Set Center Chunk | `set_chunk_cache_center` | Tells the client where to expect chunks |
| 5 | Chunk Data and Update Light | `level_chunk_with_light` | At least the chunks around spawn |
| 6 | Player Abilities | `player_abilities` | Flight/invulnerability, so the player does not fall |

Anything sent before packet 1 — a KeepAlive, a chat message — is a protocol violation.

## KeepAlive and timeouts

Two independent timers must both be satisfied:

- **The client** disconnects with "Timed out" if the server stops sending KeepAlive. A tick around
  every 10 seconds is the norm.
- **The proxy** installs a Netty `ReadTimeoutHandler` in
  [ServerChannelInitializer.java:65](../../proxy/src/main/java/com/velocitypowered/proxy/network/ServerChannelInitializer.java)
  using the `read-timeout` option, default **30000 ms**
  ([VelocityConfiguration.java:792](../../proxy/src/main/java/com/velocitypowered/proxy/config/VelocityConfiguration.java)).
  If the client sends nothing for 30 s the proxy kills the connection. A client answering KeepAlive
  satisfies this; a client with no world otherwise would not.

Any authentication timeout longer than `read-timeout` is only reachable if the KeepAlive tick is
running. The specified 60-second timeout in [update-plan.md](offline-auth-specification.md) is twice
`read-timeout`.

Note that KeepAlive IDs are per-state. A PLAY KeepAlive delivered to a client still in
CONFIGURATION is what produced `Received unknown packet id 121`.

## Packet inventory in this fork

What already exists and is registered clientbound, with its registration line in
[StateRegistry.java](../../proxy/src/main/java/com/velocitypowered/proxy/protocol/StateRegistry.java):

| Packet | State | Line |
| --- | --- | --- |
| `FinishedUpdatePacket` | CONFIG | 215 |
| `RegistrySyncPacket` | CONFIG | 226 |
| `ActiveFeaturesPacket` | CONFIG | 242 |
| `TagsUpdatePacket` | CONFIG | 246 |
| `KnownPacksPacket` | CONFIG | 250 |
| `KeepAlivePacket` | CONFIG / PLAY | 218 / 532 |
| `DisconnectPacket` | CONFIG / PLAY | 211 / 513 |
| `JoinGamePacket` | PLAY | 553 |
| `RespawnPacket` | PLAY | 574 |
| `SystemChatPacket` | PLAY | 775 |
| `StartUpdatePacket` | PLAY | 812 |

`JoinGamePacket` is fully constructible — every field has a setter — and
`RespawnPacket.fromJoinGame(...)` exists. `RegistrySyncPacket` is an opaque
`DeferredByteBufHolder`: it holds raw bytes and is only ever relayed from a backend, never built.
That is convenient for replaying captured data and useless for generating it.

**Missing entirely — no class exists** (verified by search across `proxy/src/main/java`):

- `GameEvent`
- `ChunkData` / `level_chunk_with_light`
- `SynchronizePlayerPosition`
- `PlayerAbilities`
- `SetCenterChunk`
- `SetDefaultSpawnPosition`

`StateRegistry.java:267` sets `clientbound.fallback = false` for PLAY, so each new packet needs an
explicit ID mapping for **every** protocol version it must support — the existing `JoinGamePacket`
registration carries 18 such mappings as a reference for the scale involved.

## Existing machinery worth reusing

| Piece | Location | What it gives you |
| --- | --- | --- |
| `ConnectedPlayer#sendKeepAlive()` | `ConnectedPlayer.java:1311` | Proxy-generated KeepAlive, valid in PLAY and CONFIG |
| `InitialConnectSessionHandler` | `connection/client/` | The only existing backend-less PLAY handler; the skeleton to fork |
| `ClientConfigSessionHandler` | lines 343-344 | The proxy already writes `FinishedUpdatePacket` and flips the encoder itself |
| `ConnectedPlayer#switchToConfigState()` | `ConnectedPlayer.java:1355` | Proxy-driven PLAY → CONFIG transition |
| `ClientPlaySessionHandler#doFastClientServerSwitch` | lines 714-733 | The proven "re-enter a world" recipe: `JoinGame` + `Respawn` |
| `PlayPacketQueueOutboundHandler` | `protocol/netty/` | Buffers PLAY packets automatically while the client is in CONFIG |

## Prior art

- **[LimboAPI](https://github.com/Elytrium/LimboAPI)** (Elytrium) — virtual servers inside Velocity;
  the reference implementation of the in-proxy approach, with per-version registry handling and a
  known-packs handshake for 1.20.5+.
- **[LimboAuth](https://github.com/Elytrium/LimboAuth)** — this exact feature built on LimboAPI:
  BCrypt, H2/MySQL/PostgreSQL, hybrid online/offline mode.
- **[NanoLimbo](https://github.com/Nan1t/NanoLimbo)** — a standalone limbo server, ~5 MB, JRE 21+,
  1.7 through 26.2, with Velocity MODERN forwarding support.

## Sources

- [Java Edition protocol: Packets — Minecraft Wiki](https://minecraft.wiki/w/Java_Edition_protocol/Packets)
- [Java Edition protocol: Registries — Minecraft Wiki](https://minecraft.wiki/w/Java_Edition_protocol/Registries)
- [Elytrium/LimboAPI](https://github.com/Elytrium/LimboAPI), [Elytrium/LimboAuth](https://github.com/Elytrium/LimboAuth), [Nan1t/NanoLimbo](https://github.com/Nan1t/NanoLimbo)

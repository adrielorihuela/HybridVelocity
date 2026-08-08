# Hybrid offline profiles

An online-mode Velocity proxy disconnects any player that Mojang's session server does not
recognise, with the `velocity.error.online-mode-only` message. HybridVelocity accepts those
players instead, giving them an offline identity that cannot collide with a premium one.

## Login flow

1. The player connects and the proxy starts the usual encryption/authentication handshake.
2. The proxy queries the Mojang session server.
   * **200 OK** — a premium account. Nothing changes; the stock Velocity flow continues.
   * **204 No Content** — no paid account owns this username. The hybrid path below runs.
3. A `.` is appended to the username, producing the profile name (`Steve123` →
   `Steve123.`). Usernames already at the 16-character protocol limit are truncated to 15
   characters first, so the result still fits in the Login Success and player-list packets.
4. An offline UUID is generated from the dotted name via `GameProfile.forOfflinePlayer`.
   The generation is deterministic: the same dotted name always yields the same UUID.
5. Any player public key sent by the client is discarded. The key is bound to the Mojang
   account UUID, so keeping it would make signed chat and commands fail against the offline
   UUID.
6. The player continues into `AuthSessionHandler` as an unauthenticated profile and is
   connected to a backend server.

## Identity

The dotted name and its offline UUID are the player's identity everywhere on the proxy:
plugin APIs, the player list, and anything forwarded to the backend. The original,
undotted username is never used as an identifier, which is what keeps `Steve123.` (offline)
distinct from a premium `Steve123` who may join later.

The `.` suffix also makes offline players visible at a glance in the tab list.

## Key authentication

When the proxy is in online mode, the `force-key-authentication` check is skipped for
1.19–1.19.2 clients. Those clients cannot present a Mojang-backed public key when they have
no paid account, so enforcing it would disconnect them before the session server is ever
queried. Offline-mode proxies keep the upstream behaviour.

## Implementation

`proxy/src/main/java/com/velocitypowered/proxy/connection/client/InitialLoginSessionHandler.java`
(`hybridOfflineProfile`), covered by `InitialLoginSessionHandlerTest`.

## Security note

This makes an online-mode proxy reachable by unauthenticated clients, who can pick any
username that is not a premium account. The planned register/login system described in
[update-plan.md](../development/offline-auth-specification.md) is what will tie an offline name to a password; until it
ships, offline names are not protected against being reused by someone else.

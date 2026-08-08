# Offline player authentication

HybridVelocity requires players that Mojang cannot authenticate to register a password and log in
before they reach any of your servers. They wait on an authentication server that runs **inside the
proxy** — there is no extra server to install, configure or monitor.

This is **on by default**. Updating an existing installation turns it on.

Premium players are unaffected: they connect exactly as they do on stock Velocity.

This implements the specification in [update-plan.md](../development/offline-auth-specification.md). For why it is built this way,
see [offline-auth-plan.md](../development/offline-auth-plan.md); for the authentication server itself, see
[auth-server.md](../development/auth-server.md).

## Configuring it

Three options in `hybridvelocity.toml`:

```toml
[offline-auth]
enabled = true
server-name = "auth"
server-port = 30065
```

| Option | Meaning |
| --- | --- |
| `enabled` | On by default. Set to `false` to let unauthenticated players straight through, as stock Velocity does. Premium players are unaffected either way. |
| `server-name` | The name the authentication server is registered under. Hidden from `/server`, `/glist`, `/send` and tab completion, but visible to plugins — ViaVersion needs to see it. Must not match a server in `[servers]`. |
| `server-port` | Loopback port. Bound to `127.0.0.1` only, so it is unreachable from outside the machine. `0` picks a free one on each start. |

The proxy refuses to start if `try` is empty while this is on — there would be nowhere to send
players after they authenticate.

### Why it needs a port of its own

It cannot share the proxy's. Two listeners cannot share a TCP port: `SO_REUSEPORT` load-balances
rather than multiplexes, so roughly half of all players would land on the authentication server
directly, skipping the proxy. A socketless in-JVM transport would avoid the port but could not be
pinged by ViaVersion, which needs to learn the server's protocol version or it mis-decodes the
login.

## The auth/ directory

Everything for this feature lives there:

| File | |
| --- | --- |
| `player-passwords.db` | The password records. **Never delete this.** |
| `settings.yml` | The authentication server's appearance and limits. Yours to edit, with a comment on every option. The proxy writes it once and never touches it again. |

The database path is fixed and not configurable: it is the only copy of every registration, and
being able to move it only invites losing track of it. Deleting it asks every player to register
again, and until they do, anyone can claim their name by registering it first. Back it up with the
rest of your server data. The passwords inside are bcrypt hashes and cannot be read back out.

The bind address and forwarding never appear in that file on purpose: binding anywhere but loopback
would expose an unauthenticated world to the network, and a forwarding mode that disagrees with the
proxy's breaks the handshake. The proxy passes both in directly.

## What a player sees

A player whose name has no paid Mojang account joins and lands on the authentication server
instead of a real one. The screen is the fixed dark backdrop of the End with no HUD, no tab list and no messages,
so it reads as a waiting screen. They see either:

```
Type /register <password> <password> to register.
```

```
Type /login <password> to log in.
```

Passwords must be 4 to 16 characters, using printable ASCII plus `ñ` and `Ñ`. Anything else is
rejected listing the offending characters.

While waiting, no command exists for them but the one they need — the list is stripped to
`/register` or `/login`, whichever applies, so nothing from any plugin is listed or tab-completable,
and every permission is denied. Those two commands carry no permission node, so no permissions
plugin can revoke them; if another plugin tries to block or replace them the proxy overrides it and
says so in the console, because losing them would leave offline players no way in. Chat goes nowhere. Three wrong passwords disconnect with
`Too many failed login attempts.`, and 60 seconds of inactivity with `Authentication timed out.`

After a successful register or login the player is sent to the first available server in `try`, and
told:

```
Tip: Use /changepassword to change your password.
```

`/changepassword <current password> <new password> <new password>` is available to authenticated
offline players.

## How identity works

Offline players get a `.` appended to their username and an offline UUID derived from that dotted
name — see [hybrid-offline-profiles.md](hybrid-offline-profiles.md). That UUID is the key of the
password record, so an offline `Steve123` can never collide with the premium account of the same
name.

Authentication is per session and lives in memory only. A proxy restart means everyone
authenticates again on their next connection, as the specification requires.

## Passwords are never stored in plain text

Records hold a bcrypt hash, which embeds a per-record salt and the cost parameters. The plaintext
cannot be recovered from what is stored. Hashing and verification run off the Netty event loops, on
a small pool of their own.

The table is `offline_player_auth`, keyed by the offline UUID, with the dotted username, the hash,
and creation and update timestamps.

## Failure behaviour

Everything fails closed. If the database cannot be opened, or the limbo cannot start, or a query
fails, the player is refused with a message rather than passed through — an unauthenticated player
never reaches a real server because something broke.

Because the failure is loud and total, check the console after enabling this for the first time.

## Limitations worth knowing

- **The chat window cannot be forced open.** The protocol has no packet that opens the client's chat
  screen or stops it being closed; that GUI is entirely client-side. Players press T as usual.
- **The camera is not locked.** Players can look around, but since the authentication server sends
  no chunks at all, the only thing rendered is the End's sky — a fixed dark backdrop with no sun,
  moon, clouds or horizon — so there is nothing to see moving.
- There is no password recovery. A player who forgets their password needs an operator to delete
  their row from `offline_player_auth`.
- There is no rate limiting on `/register` beyond the three-strike login lockout, and no IP-based
  account limit.

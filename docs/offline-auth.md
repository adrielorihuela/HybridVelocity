# Offline player authentication

HybridVelocity can require players that Mojang cannot authenticate to register a password and log
in before they reach any of your servers. They wait in a limbo world that runs **inside the proxy** —
there is no extra server to install, configure or monitor.

Premium players are unaffected: they connect exactly as they do on stock Velocity.

This implements the specification in [update-plan.md](update-plan.md). For why it is built this way,
see [offline-auth-plan.md](offline-auth-plan.md); for the embedded limbo itself, see
[limbo.md](limbo.md).

## Enabling it

In `velocity.toml`:

```toml
[offline-auth]
enabled = true
limbo-port = 0
database-file = "player_auth.db"
```

| Option | Meaning |
| --- | --- |
| `enabled` | Off by default. Turning it on changes nothing for premium players. |
| `limbo-port` | Loopback port for the embedded limbo. `0` picks a free one automatically. Only set this if something else needs to know the port. |
| `database-file` | SQLite file for the password records, relative to the proxy directory. Created on first start. |

The proxy refuses to start if `try` is empty while this is enabled — there would be nowhere to send
players after they authenticate.

The limbo's own `limbo/settings.yml` is created on first run. The proxy rewrites the bind address
and the player-info forwarding on every start, matching whatever forwarding mode the proxy uses;
everything else in that file is yours to tune.

## What a player sees

A player whose name has no paid Mojang account joins and lands in the limbo instead of a real
server, with either:

```
Type /register <password> <password> to register.
```

```
Type /login <password> to log in.
```

Passwords must be 4 to 16 characters, using printable ASCII plus `ñ` and `Ñ`. Anything else is
rejected listing the offending characters.

While waiting, every command except `/register` and `/login` is refused and the prompt is repeated.
Chat goes nowhere. Three wrong passwords disconnect with `Too many failed login attempts.`, and
60 seconds of inactivity with `Authentication timed out.`

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

- The limbo is reachable only on `127.0.0.1`. It is deliberately not registered as a normal server,
  so it does not appear in `/server` tab completion, `/glist`, `/send` or the BungeeCord plugin
  channel, and `/server` cannot be used to reach it.
- There is no password recovery. A player who forgets their password needs an operator to delete
  their row from `offline_player_auth`.
- There is no rate limiting on `/register` beyond the three-strike login lockout, and no IP-based
  account limit.

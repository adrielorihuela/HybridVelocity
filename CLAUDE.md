# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this repository is

HybridVelocity is a fork of [Velocity](https://github.com/PaperMC/Velocity), the Minecraft
server proxy. Most of the tree is upstream code; `docs/` documents what the fork changes.
Read `docs/README.md` before touching login, configuration or command code.

Fork-specific behaviour:

- **Hybrid offline profiles** (`connection/client/InitialLoginSessionHandler.java`) — when the
  Mojang session server returns 204, the player is accepted with a `.`-suffixed username and
  an offline UUID derived from it, instead of being disconnected. See
  `docs/hybrid-offline-profiles.md`.
- **Offline authentication** (`proxy/.../auth/`, `[offline-auth]`) — unauthenticated offline
  players are held in an embedded limbo until they `/register` or `/login`. See
  `docs/offline-auth.md`.
- **Embedded limbo** (`:limbo` module) — a vendored copy of NanoLimbo run in-process. See
  `docs/limbo.md`.
- **Per-server shortcut commands** (`command/builtin/ServerShortcutCommand.java`, registered
  from `VelocityServer#registerServerCommands`) — the `comandos` list in `[servers]`.
  See `docs/server-commands.md`.

The configuration file is `hybridvelocity.toml`; `ConfigurationLocation` renames an
upstream-named `velocity.toml` on first start. Permission nodes keep the `velocity.` prefix —
they are identifiers operators have already granted, not branding.

Keep diffs against upstream narrow so upstream merges stay manageable.

## Commands

The Gradle wrapper is the entry point; on Windows use `./gradlew.bat`. The build targets a
Java 25 toolchain (CI builds on JDK 21 and lets the toolchain resolve 25).

```bash
./gradlew build
```

Full cycle for all modules: compile, Checkstyle, Spotless check, tests, distributions.

```bash
./gradlew :velocity-proxy:test --tests "com.velocitypowered.proxy.config.ServersConfigTest"
```

Run one test class (drop `--tests` for the whole proxy module). Modules are named
`:velocity-api`, `:velocity-proxy`, `:velocity-native`.

```bash
./gradlew :velocity-proxy:spotlessApply
```

Apply formatting — run before committing, the build fails on style violations.

```bash
./gradlew :velocity-proxy:runShadow
```

Run the proxy locally; it generates `hybridvelocity.toml`, `forwarding.secret` and `lang/` in
`proxy/run/`. The build produces two jars in `proxy/build/libs/`:
`HybridVelocity-<forkVersion>-all.jar` is the runnable distributable and the one to attach to a
release; `HybridVelocity-<forkVersion>.jar` is the thin jar.

### Versioning

Two versions coexist, and they are not interchangeable:

- `version` in `gradle.properties` (`4.1.0-SNAPSHOT`) is the **upstream Velocity API version**.
  It sets the Maven coordinates of `velocity-api`, so plugins compile against it — do not bump
  it to express a fork release.
- `forkName` / `forkVersion` in `gradle.properties` name the **fork's distributable**. The
  proxy's `shadowJar` uses them for the archive name and manifest, and `VelocityServer#getVersion`
  reads that manifest, which is why the proxy logs `Booting up HybridVelocity <forkVersion>`.
  Bump `forkVersion` for a fork release and tag it `v<forkVersion>`.

## Architecture

### Connection lifecycle

A player connection is a Netty channel wrapped in `MinecraftConnection`, which delegates every
packet to a single **active session handler** (`MinecraftSessionHandler`). Progressing through
the protocol means swapping that handler via
`MinecraftConnection#setActiveSessionHandler(StateRegistry, handler)` — this both installs the
handler and switches the packet registry, and must run on the connection's event loop.

The client-side chain is `HandshakeSessionHandler` → `StatusSessionHandler` (ping) or
`InitialLoginSessionHandler` (encryption + Mojang auth) → `AuthSessionHandler` (builds the
`ConnectedPlayer`) → `ClientConfigSessionHandler` / `ClientPlaySessionHandler`. The proxy holds
a second connection per player to the backend server (`VelocityServerConnection`) with its own
chain in `connection/backend/`: `LoginSessionHandler` → `TransitionSessionHandler` →
`BackendPlaySessionHandler`.

`StateRegistry` (`protocol/StateRegistry.java`) is the single source of truth mapping packet
IDs to classes per protocol state and per Minecraft version. Adding or changing a packet means
editing the relevant state's `serverbound`/`clientbound` registrations there, keyed by
`ProtocolVersion` ranges. The Netty pipeline in `protocol/netty/` handles framing, compression
and encryption around it.

Because everything runs on Netty event loops, blocking work must be dispatched off-loop
(see `VelocityScheduler`), and code touching a connection must respect its event loop.

### Wiring

`VelocityServer` is the composition root: it constructs the command manager, event manager,
plugin manager, scheduler, `ServerMap` and `ConnectionManager`, then `start()` registers
built-in commands, loads the configuration, registers servers, loads plugins, fires
`ProxyInitializeEvent` and binds. Note the ordering constraint: built-in commands are
registered *before* the config is loaded, so anything that depends on configuration (like the
server shortcut commands) must be registered after the server-registration loop.
`reloadConfiguration()` is the mirror image for `/velocity reload` and must be updated
whenever startup gains configuration-derived state.

`VelocityConfiguration` deserializes `hybridvelocity.toml` with night-config, using
`proxy/src/main/resources/default-velocity.toml` as the template written on first run.
Sub-tables (`[servers]`, `[forced-hosts]`, `[advanced]`, …) map to private static inner
classes. `validate()` is the place for cross-checks between sections, and
`config/migration/` holds `ConfigurationMigration` implementations that rewrite older configs
in place — add one when an existing key changes shape.

`api/` is the public plugin API and must stay source-compatible; `proxy/` implements it.
`native/` provides libdeflate/OpenSSL acceleration with pure-Java fallbacks, so nothing may
assume the native path is available. `limbo/` is vendored third-party source — see below.

### The embedded limbo and its subtree

`limbo/upstream/` is a pristine `git subtree` of [NanoLimbo](https://github.com/Nan1t/NanoLimbo)
(GPL-3.0, same licence as this fork). The module's build script sits *outside* the subtree and
points `sourceSets` into it, so upstream stays byte-identical and `git subtree pull` does not
conflict. Two upstream files carry local patches, both marked `HybridVelocity patch:` and listed
in `docs/limbo.md` — keep that list accurate and the patch surface small.

**Checkstyle is disabled and Spotless excludes `upstream/**` for that module.** This is a
licensing requirement, not a style preference: `velocity-spotless` applies
`licenseHeaderFile(HEADER.txt)`, which replaces everything above the `package` declaration and
would rewrite NanoLimbo's GPL copyright notices to "Velocity Contributors".

`EmbeddedLimboServer` writes `auth/settings.yml` once from the documented template at
`proxy/src/main/resources/limbo-settings.yml`, then leaves it alone unless the bind port or
forwarding mode changed — reloading and re-saving through Configurate strips every comment.

### Offline authentication

The gate (`auth/OfflineAuthGate.java`) is built entirely from events, not by patching the login
handlers. One `ServerPreConnectEvent` listener covers every route to a backend, because they all
end up in `createConnectionRequest`; `PlayerAvailableCommandsEvent` hides commands;
`CommandExecuteEvent` blocks execution; a `PermissionsSetupEvent` function denies permissions.
`PostLoginEvent` must keep firing — an earlier attempt short-circuited it and broke plugins.

Blocking work stays off the event loops: `AuthDatabase` serialises JDBC on one thread and
`OfflineAuthManager` runs bcrypt on its own pool. `PasswordLookup` deliberately distinguishes
`FAILED` from `NOT_REGISTERED`, so a broken database cannot offer `/register` for an account
that already exists.

**The limbo is a normally registered server.** It was once kept out of the `ServerMap` to hide
it, which broke ViaVersion: Via pings every server in `getAllServers()` to learn its protocol,
and a server it cannot see falls back to a pre-1.16 assumption and mis-decodes the login. It is
hidden from command output through `server/InternalServers.java` instead — add any new
player-facing server listing to that filter rather than un-registering things.

### Commands and permissions

Commands are Brigadier nodes registered through `VelocityCommandManager`, which lowercases
aliases (so commands are case-insensitive regardless of the literal's casing). Permissions are
tri-state: `getPermissionValue` returns `Tristate`, and the codebase uses two deliberate
styles — `!= Tristate.FALSE` for commands everyone should have unless denied (`/server`, the
server shortcuts) and `== Tristate.TRUE` for privileged ones (`/velocity`, `/glist`).

### Translations

User-facing strings are MiniMessage translation keys in
`proxy/src/main/resources/com/velocitypowered/proxy/l10n/messages*.properties`, resolved
through `Component.translatable`. Add new keys to `messages.properties`; the files are copied
to a `lang/` directory next to the proxy on first run, and that copy wins at runtime — delete
it when testing changed strings.

## Conventions

Google Java Style enforced by Checkstyle and Spotless: two-space indentation, braces on the
same line, sorted imports, no unused imports. Preserve nullness annotations (Checker
Framework) and the asynchronous event-loop behaviour of anything you touch.

Tests use JUnit Jupiter, with Mockito available in the proxy module. Place them beside their
package under `*/src/test/java` and name methods by behaviour
(`hybridOfflineProfileUsesDottedNameForUuid`, `serverCommandsListIsNotTreatedAsServerEntry`).

Commits use concise imperative subjects, optionally with a `feat:`/`docs:` scope or an issue
reference. Keep each commit cohesive.

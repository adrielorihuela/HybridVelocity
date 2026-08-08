# The embedded limbo server

HybridVelocity bundles [NanoLimbo](https://github.com/Nan1t/NanoLimbo) (GPL-3.0, the same licence
this fork carries) as the holding area for players who have not authenticated yet. It runs
**in-process** inside the proxy JVM — there is no second process to install, configure or monitor.

The rationale, and the options rejected to get here, are in
[offline-auth-plan.md](offline-auth-plan.md).

## Layout

```
limbo/
├── build.gradle.kts        our build script — declares dependencies against this repo's catalog
├── src/main/java/          our glue code
└── upstream/               pristine git subtree of NanoLimbo
    ├── build.gradle.kts    inert; Gradle only reads the root settings file
    └── src/main/java/ua/nanit/limbo/…
```

The module's build script deliberately sits **outside** the subtree, with `sourceSets` pointing into
`upstream/src/main/java`. Overwriting upstream's own build script would guarantee a conflict on
every update; this way the subtree stays byte-identical to upstream except for the patches listed
below.

Upstream's `build.gradle.kts` could not be used regardless: it declares `repositories { mavenCentral() }`,
which is illegal under this repository's `RepositoriesMode.FAIL_ON_PROJECT_REPOS`.

## Updating the subtree

```bash
git fetch limbo-upstream && git subtree pull --prefix=limbo/upstream limbo-upstream main --squash
```

If the remote is not configured yet:

```bash
git remote add limbo-upstream https://github.com/Nan1t/NanoLimbo.git
```

After pulling, re-apply any of the patches below that conflicted, bump `LIMBO_VERSION` in
`limbo/src/main/java/ua/nanit/limbo/BuildConfig.java`, and check upstream's
`gradle/libs.versions.toml` for dependency bumps that need mirroring into this repository's catalog.

## Local patches

Kept deliberately small — every modified file inside `upstream/` is a potential merge conflict.
Both patches are marked in the source with a `HybridVelocity patch:` comment.

| File | Change | Why |
| --- | --- | --- |
| `server/Log.java` | Use `org.slf4j.Logger` instead of casting to `ch.qos.logback.classic.Logger`; `setLevel` only records the level instead of reconfiguring logback | Upstream logs through logback. The proxy uses log4j2 with `log4j-slf4j2-impl`, so the logback cast would throw `ClassCastException` at startup. Log levels now follow the proxy's own logging configuration. |
| `server/LimboServer.java` | Added `start(Path)` and `startEmbedded(Path)`; `stop()` made public; the interactive `CommandManager`, the JVM shutdown hook and `System.gc()` are skipped when embedded | The command manager reads `System.in` and would fight the proxy console. Lifecycle is driven by `VelocityServer` instead. The working-directory parameter avoids the hardcoded `Paths.get("./")`. |

## Configuration

The limbo runs from `auth/settings.yml`, written once from
`proxy/src/main/resources/limbo-settings.yml` with a comment on every option. Defaults are silent
and cheap: no join message, boss bar, title, brand or tab list, spectator game mode, one Netty
thread each way, and tight traffic limits.

`dimension: THE_END` is load-bearing rather than cosmetic. The limbo sends no chunk data at all, so
the client renders only the dimension's sky, and the End's is a fixed dark backdrop with no sun,
moon, clouds or horizon — looking around shows no movement, which is what makes it read as a
waiting screen. The Overworld would show a moving sky and give it away.

Only two keys belong to the proxy: `bind`, always loopback, and `infoForwarding`, matched to the
proxy's own mode so the loopback hop is authenticated like any other backend. The file is rewritten
only when one of those changes, because reloading and re-saving it through Configurate strips every
comment.

Two things are deliberately **not** patched:

- **`BuildConfig`** — upstream generates it with the `com.github.gmazzo.buildconfig` Gradle plugin.
  Rather than adding that plugin, an equivalent class is hand-written at
  `limbo/src/main/java/ua/nanit/limbo/BuildConfig.java`, outside the subtree.
- **Formatting and copyright headers** — Checkstyle is disabled for this module and Spotless
  excludes `upstream/**`. This is not cosmetic: `velocity-spotless` applies
  `licenseHeaderFile(HEADER.txt)`, which replaces everything above the `package` declaration and
  would silently rewrite Nan1t's GPL notices to "Velocity Contributors".

## Dependencies

Declared against this repository's version catalog so a single version of everything resolves across
the shaded jar. The versions line up closely with upstream's own:

| Library | NanoLimbo 1.13.0 | HybridVelocity |
| --- | --- | --- |
| Netty | 4.2.15.Final | 4.2.16.Final |
| Gson | 2.14.0 | 2.14.0 |
| Configurate | 4.2.0 | 4.2.0 |
| Adventure | 5.1.1 | 5.2.0 |
| logback | 1.5.34 | *excluded* — log4j2 instead |

Most of these arrive transitively from `velocity-api`; only `adventure-nbt`,
`adventure-text-serializer-json-legacy-impl` and the Netty artifacts are declared explicitly, plus
Lombok, which upstream uses as an annotation processor.

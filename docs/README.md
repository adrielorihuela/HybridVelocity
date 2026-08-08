# HybridVelocity documentation

Documentation for the changes this fork makes on top of upstream Velocity. Anything not
covered here behaves exactly like [Velocity](https://docs.papermc.io/velocity).

| Document | Contents |
| --- | --- |
| [server-commands.md](server-commands.md) | The `comandos` option: per-server shortcut commands and their permissions. |
| [hybrid-offline-profiles.md](hybrid-offline-profiles.md) | How offline players are accepted on an online-mode proxy. |
| [offline-auth.md](offline-auth.md) | Offline player authentication: the register/login gate and how to enable it. |
| [limbo.md](limbo.md) | The embedded limbo server, its vendored subtree and local patches. |
| [update-plan.md](update-plan.md) | Functional specification the offline authentication implements. |

## Offline authentication — background

How the feature came to be built this way. Start with [offline-auth.md](offline-auth.md) if you
just want to use it.

| Document | Contents |
| --- | --- |
| [offline-auth-plan.md](offline-auth-plan.md) | The adopted plan: vendor NanoLimbo into the fork and run it in-process, and the options rejected. |
| [offline-auth-postmortem.md](offline-auth-postmortem.md) | What was built on the `codex-attempt-2` branch, how the client failed, and why. |
| [offline-auth-requirements.md](offline-auth-requirements.md) | What the vanilla client demands per version: configuration phase, registries and tags, the PLAY spawn sequence, KeepAlive and timeouts. |

## Discarded

[discarded/](discarded/) holds superseded analysis, kept for the reasoning it records rather than as
plans to follow. Nothing in there should be implemented without checking it against the live plan
above.

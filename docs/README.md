# HybridVelocity documentation

Documentation for the changes this fork makes on top of upstream Velocity. Anything not
covered here behaves exactly like [Velocity](https://docs.papermc.io/velocity).

| Document | Contents |
| --- | --- |
| [server-commands.md](server-commands.md) | The `comandos` option: per-server shortcut commands and their permissions. |
| [hybrid-offline-profiles.md](hybrid-offline-profiles.md) | How offline players are accepted on an online-mode proxy. |
| [update-plan.md](update-plan.md) | Functional specification for the planned offline register/login system. Not implemented yet. |

## Offline authentication — the next update

The register/login gate specified in `update-plan.md` was attempted once and failed. These
documents are the record of that attempt and the adopted plan to finish the work.

| Document | Contents |
| --- | --- |
| **[offline-auth-plan.md](offline-auth-plan.md)** | **The adopted plan: vendor NanoLimbo into the fork and run it in-process. Start here.** |
| [offline-auth-postmortem.md](offline-auth-postmortem.md) | What was built on the `codex-attempt-2` branch, how the client failed, and why. |
| [offline-auth-requirements.md](offline-auth-requirements.md) | What the vanilla client demands per version: configuration phase, registries and tags, the PLAY spawn sequence, KeepAlive and timeouts. |

## Discarded

[discarded/](discarded/) holds superseded analysis, kept for the reasoning it records rather than as
plans to follow. Nothing in there should be implemented without checking it against the live plan
above.

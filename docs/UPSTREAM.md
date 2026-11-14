# Upstream source

The renderer in this repo is forked from RuneLite's stock GPU plugin.

| | |
| --- | --- |
| Upstream repo | https://github.com/runelite/runelite |
| Release tag | `runelite-parent-1.13.6` (`runeLiteVersion` in build.gradle is pinned to `1.13.6` to match) |
| Commit | `d62b158e83450b0708f053c753e3508c762a0803` |

## Paths

| Upstream source | Destination in this repo |
| --- | --- |
| `runelite-client/src/main/java/net/runelite/client/plugins/gpu/**` | `src/main/java/com/runeliteupscaler/renderer/` |
| `runelite-client/src/main/resources/net/runelite/client/plugins/gpu/**` | `src/main/resources/com/runeliteupscaler/renderer/` |

Forked files live under com.runeliteupscaler.renderer. Merge upstream changes by diffing against this commit (G-14).

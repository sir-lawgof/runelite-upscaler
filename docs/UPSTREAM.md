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

# AMD FidelityFX FSR 1

FSR 1 shader code used by the FSR 1 backend (S1-06). MIT license; the notice stays in each file (G-23).

| | |
| --- | --- |
| Upstream repo | https://github.com/GPUOpen-Effects/FidelityFX-FSR |
| Commit | `a21ffb8f6c13233ba336352bdff293894c706575` (2021-11-15; headers are v1.20210629) |

| Upstream source | Destination in this repo |
| --- | --- |
| `ffx-fsr/ffx_a.h`, `ffx-fsr/ffx_fsr1.h` (unmodified) | `src/main/resources/com/runeliteupscaler/upscale/fsr1/` |
| `license.txt` | `src/main/resources/com/runeliteupscaler/upscale/fsr1/LICENSE-FidelityFX-FSR.txt` |

`FsrEasuCon` and `FsrRcasCon` (CPU constant setup) are ported to Java in `upscale/Fsr1Constants.java`.

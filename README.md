# Upscaler GPU

A RuneLite GPU renderer that draws the 3D scene below native resolution and upscales it, while the
interface and RuneLite overlays stay at full resolution. It is a fork of RuneLite's stock GPU plugin
(see [docs/UPSTREAM.md](docs/UPSTREAM.md)).

**Status: Step 1 (spatial upscaling).** Backends: **FSR 1** (AMD FidelityFX Super Resolution 1, EASU +
RCAS) and **Bilinear**. Temporal upscaling (FSR 2/3) and DLSS are later steps. The full plan and the
source of truth is [docs/REQUIREMENTS.md](docs/REQUIREMENTS.md).

> **Developer-mode sideload only.** This plugin is not on the Plugin Hub and isn't reviewed by RuneLite
> (requirement G-22). It only changes how the scene is drawn and gives no gameplay advantage, but use it
> at your own risk. Testing on a non-main account is a sensible precaution.

## Requirements

- Windows (Linux should work where drivers allow; not tested yet)
- JDK 11 (for example `winget install EclipseAdoptium.Temurin.11.JDK`)
- A GPU with OpenGL 3.3 for the renderer, and **OpenGL 4.3** for FSR 1. Without 4.3, FSR 1 falls back to
  Bilinear automatically.

## Build

```
gradlew.bat build
```

This compiles the plugin and runs the unit tests. RuneLite is pinned to **1.13.6**, the release the
renderer was forked from (`runeLiteVersion` in `build.gradle`).

## Run (developer mode)

Either:

- `gradlew.bat run`, or
- in IntelliJ IDEA, run `com.runeliteupscaler.UpscalerPluginTest` (in `src/test/java`) with program
  arguments `--developer-mode --debug` and VM option `-ea`.

To log in with a Jagex account in the development client, follow
[Using Jagex Accounts](https://github.com/runelite/runelite/wiki/Using-Jagex-Accounts).

Then, in the plugin list:

1. Turn on **Upscaler GPU**. RuneLite turns the stock **GPU** plugin (and 117 HD, if installed) off
   automatically, since only one renderer can run at a time. Turning either of them on later turns
   Upscaler GPU off.
2. Open Upscaler GPU's settings. The **Upscaling** section is at the top.

For a standalone jar (needed for RenderDoc, see below): `gradlew.bat shadowJar`, then
`java -ea -jar build\libs\runelite-upscaler-unspecified-all.jar --developer-mode --debug`.

## Settings

### Upscaling

| Setting | Default | What it does |
| --- | --- | --- |
| **Upscaler** | FSR 1 | **Off**: render at native resolution with no upscale pass (same as the stock GPU plugin). **Bilinear**: plain filtered stretch. **FSR 1**: AMD FSR 1 edge-adaptive upscale plus sharpening. If FSR 1 can't run, Bilinear is used and the debug overlay shows why. Changes apply immediately. |
| **Render scale** | 100% (Native) | 3D scene resolution as a percentage of the 3D viewport: 100%, 77%, 67% (Quality), 58% (Balanced), 50% (Performance), 33% (Ultra Performance) or Custom. At 100% nothing is upscaled. |
| **Custom scale** | 75% | Used when Render scale is Custom. 33% to 100%. |
| **Debug overlay** | off | Shows the debug panel described below. |
| **Sharpness** | 90% | FSR 1 RCAS sharpening. 100% is sharpest, 0% turns sharpening off. The default (0.2 stops) is AMD's recommendation. No effect on Bilinear. |
| **High-precision targets** | off | Development option: RGBA16F render targets instead of RGBA8. Should look the same; it exists for the later temporal step. |

Render resolution = 3D viewport size × scale, rounded to whole pixels. The interface, text, minimap and
RuneLite overlays are always drawn at full resolution on top of the upscaled scene.

**Texture mip bias** (requirement S1-12) is not implemented yet: textures use the stock bias. AMD
recommends a negative bias for FSR 1 (`-log2(display / render)`); see section 8.4 of the requirements.

### Other settings

All other settings (draw distance, fog, anti-aliasing, UI scaling, colour blindness, FPS and vsync,
threads, and so on) are the stock GPU plugin's and behave the same. They are stored separately from the
stock plugin's settings, so they start at their defaults. With MSAA on and a scale below 100%, the scene
is anti-aliased at render resolution before upscaling.

## Debug overlay

| Line | Meaning |
| --- | --- |
| Backend | Active upscaler, or "Native (no upscale)" at 100% |
| Scale, Render, Display | Render scale, and render and display resolution of the 3D viewport in pixels |
| MSAA, Targets | Anti-aliasing samples and render target format |
| Fallback | Only shown when a better backend was skipped, with the reason |
| GL objects | OpenGL objects held by the upscaler code, by type. Should return to the same number after resizes, and be 0 after the plugin is turned off (logged). A number that keeps climbing is a leak. |
| GPU memory | Graphics memory in use on the whole card, all programs (NVIDIA only, otherwise "n/a"). Watch the trend, not the value. |
| GPU Scene / Upscale / Present / UI | GPU time per frame stage in ms. Upscale includes the MSAA resolve. "-" means the stage didn't run (for example Upscale at 100%). Measured only while the overlay is shown. |

## Debugging with RenderDoc

RenderDoc can capture RuneLite's OpenGL context. Build the shadow jar, then in RenderDoc use
**File → Launch Application** with your JDK's `java.exe` as the executable, the repo as the working
directory, and `-ea -jar build\libs\runelite-upscaler-unspecified-all.jar --developer-mode --debug` as
arguments. Press F12 in the game to capture. In a frame at a scale below 100% with FSR 1, look for the
MSAA resolve blit, then two full-screen draws (EASU, then RCAS), then the present blit and the UI.

## Licensing

- The renderer is forked from RuneLite's GPU plugin, BSD-2-Clause; the original license headers are kept
  in `com.runeliteupscaler.renderer`.
- New code is BSD-2-Clause.
- FSR 1 shader code (`src/main/resources/com/runeliteupscaler/upscale/fsr1/ffx_*.h`) is
  © 2021 Advanced Micro Devices, Inc., MIT license, included unmodified with its notice
  (`LICENSE-FidelityFX-FSR.txt`).

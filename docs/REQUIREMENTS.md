# RuneLite Upscaler Project: Requirements

Nov 14, 2026 · @Joe

## 0. How to use this document

This is the single source of truth for building an upscaling renderer for RuneLite in three steps. Each step is a milestone that ships on its own and builds on the one before. Read sections 1 to 3 once, then work one step section at a time.

**For AI coding agents (Claude Code):**

- Work on ONE step at a time, in order. Do not start Step N+1 until every Step N requirement marked MUST passes its acceptance check.
- Every requirement has a stable ID. Cite IDs in commit messages, code comments and test names (for example `// S2-07: Halton jitter`).
- IDs: `G-` = global (applies to all steps), `A-` = architecture, `S1-` / `S2-` / `S3-` = step requirements, `T-` = tests, `D-` = decisions, `Q-` = open questions.
- Priority words: **MUST** = required to finish the step. **SHOULD** = expected, may be deferred with a note in section 8. **MAY** = optional.
- Lines marked **VERIFY** are assumptions about RuneLite, OSRS or vendor SDK internals that were not checked against source. Before coding against a VERIFY item, read the actual source or SDK docs, then update this document with what you found.
- If the code and this document disagree, stop and flag it. Update the document (with a decision entry in section 8) before changing direction.
- Each step section has the same layout: Goal, Builds on, Deliverables, Requirements table, Done when.

**Section map:**

1. Overview: goal, scope, roadmap
2. Global requirements: environment, toolchain, compliance
3. Architecture: pipeline and the shared upscaler interface
4. Step 1: spatial upscaling (FSR 1 / NIS)
5. Step 2: temporal upscaling (jitter, depth, camera motion)
6. Step 3: per-object motion vectors and DLSS
7. Testing and validation
8. Risks, open questions, decisions
9. Glossary and references

## 1. Project overview

The goal is a RuneLite GPU renderer that draws the 3D scene at a lower resolution and upscales it. It starts with a simple spatial upscaler and ends with NVIDIA DLSS Super Resolution and DLAA. The 2D interface and RuneLite overlays always draw at full (native) resolution, on top of the upscaled scene.

**Why:** higher FPS when the GPU is the bottleneck (high resolutions, 4K, heavy effects), and better anti-aliasing via DLAA. The stock OSRS scene is usually limited by the CPU, so gains are measured, not assumed (see T- tests).

**In scope:**

- An external RuneLite plugin, forked from the stock GPU plugin, that owns the renderer.
- A pluggable upscaler interface with four backends over time: bilinear (baseline), FSR 1 or NIS (Step 1), FSR 2/3 (Step 2), DLSS (Step 3).
- OpenGL as the main renderer. Vulkan only as a side path for vendor SDKs that require it.
- Windows first. Linux SHOULD work where drivers allow. macOS gets Step 1 only.

**Out of scope (non-goals):**

- Rewriting the whole renderer in Vulkan or WebGPU.
- DLSS Frame Generation, Ray Reconstruction, ray-traced effects.
- Any change to game logic, input, or anything that gives a gameplay advantage.
- Replacing or modifying the RuneLite client itself.

**Roadmap:** each step reuses everything from the step before.

| Step | Adds | Reuses | Hardware |
| --- | --- | --- | --- |
| 1. Spatial upscale | Low-res scene target, upscale pass, UI composite at native res, settings | (foundation) | Any GPU with OpenGL 4.3 |
| 2. Temporal upscale | Jitter, depth, camera motion vectors, history, FSR 2/3 via an OpenGL to Vulkan bridge | All of Step 1 | Any GPU with Vulkan 1.1+ and the interop extensions |
| 3. DLSS | Per-object motion vectors for animated models, DLSS SR and DLAA backend | All of Steps 1 and 2 | NVIDIA RTX only (other GPUs fall back to Step 2) |

Note: this document moves the OpenGL to Vulkan bridge into Step 2 (see D-03). FSR 2/3 has no official OpenGL backend, and building the bridge early means Step 3 only adds DLSS and better motion vectors.

## 2. Global requirements

These apply to every step. A step is not done if it breaks any MUST here.

### 2.1 Environment and toolchain

| ID | Priority | Requirement |
| --- | --- | --- |
| G-01 | MUST | Project is a RuneLite external plugin built with Gradle, started from the official RuneLite plugin template (example-plugin). Use the Java version the template specifies. |
| G-02 | MUST | Develop and test by running RuneLite from the IDE in developer mode (the template's test launcher). Sideloaded jars are for local testing only. |
| G-03 | MUST | Graphics bindings use LWJGL, the same library RuneLite's GPU plugin uses. Do not add a second OpenGL binding library. VERIFY the LWJGL version RuneLite ships. |
| G-04 | MUST | Shaders are GLSL files under `src/main/resources`, loaded the same way the GPU plugin loads its shaders. |
| G-05 | SHOULD | IDE: IntelliJ IDEA with the Gradle project imported. GPU debugging: RenderDoc (OpenGL and Vulkan captures) and, on NVIDIA, Nsight Graphics. |
| G-06 | MUST (Step 2+) | Vulkan SDK installed for validation layers and SPIR-V tools. Vulkan validation layers MUST be on in debug builds. |
| G-07 | MUST (Step 2+) | C++ toolchain for the native bridge: CMake plus MSVC (Windows) or GCC/Clang (Linux). |
| G-08 | MUST | Source in a git repository. One branch per step (`step-1-spatial`, `step-2-temporal`, `step-3-dlss`), merged to `main` when the step's Done-when list passes. |

### 2.2 Coexistence with RuneLite

| ID | Priority | Requirement |
| --- | --- | --- |
| G-10 | MUST | The plugin replaces the stock GPU plugin; only one renderer may own DrawCallbacks at a time. ~~On startup, if the stock GPU plugin or 117 HD is enabled, the plugin MUST refuse to start and show a clear message.~~ (superseded by D-06) The plugin declares RuneLite `conflicts` with the stock GPU plugin and 117 HD, so enabling either side turns the other off. On startup, if any other renderer already owns DrawCallbacks, the plugin MUST refuse to start and show a clear message. ~~VERIFY how DrawCallbacks ownership is set and checked.~~ Verified, see 8.4. |
| G-11 | MUST | Disabling the plugin returns the client to its normal renderer with no restart and no leaked GPU resources (textures, buffers, Vulkan objects, native handles). |
| G-12 | MUST | Fixed, resizable and stretched modes, and UI scaling, keep working. The 3D viewport can be offset inside the window (fixed mode); all passes MUST respect the viewport rectangle. |
| G-13 | MUST | Any failure (shader compile, missing extension, missing DLL, unsupported GPU) falls back to the next simpler backend and logs why. The game MUST never crash or go black because of this plugin. |
| G-14 | SHOULD | Track upstream: keep the forked GPU plugin code in clearly marked files so upstream RuneLite changes can be merged in. |

### 2.3 Compliance and distribution

| ID | Priority | Requirement |
| --- | --- | --- |
| G-20 | MUST | Graphics only. The plugin MUST NOT read or expose game information beyond what the stock GPU plugin uses, and MUST NOT automate input. |
| G-21 | MUST | The plugin runs inside the official RuneLite client. No modified client, no launcher overrides. |
| G-22 | MUST | Before any public release, check the current Plugin Hub rules on native libraries and bundled binaries (see Q-01). Until resolved, distribute only as a developer-mode sideload. |
| G-23 | MUST | Licensing: the stock GPU plugin is BSD-2-Clause, so keep its license header in forked files. FSR (MIT) and NIS (MIT) shader code keep their notices. NVIDIA DLSS runtime DLLs follow NVIDIA's own redistribution terms; read them before shipping any DLL. |
| G-24 | SHOULD | Test on a non-main account until the plugin is stable, as a precaution. |

### 2.4 Quality bar

| ID | Priority | Requirement |
| --- | --- | --- |
| G-30 | MUST | With the upscaler set to Off or a scale of 100%, image and FPS match the stock GPU plugin within measurement noise. |
| G-31 | MUST | Every backend has a debug overlay showing: backend name, render resolution, display resolution, GPU time of the upscale pass, and fallback reason if any. |
| G-32 | SHOULD | Debug views can show each intermediate buffer (color, depth, motion vectors, history) full screen. |
| G-33 | MUST | Config changes (backend, scale, sharpness) apply live, without restarting the client. |

## 3. Architecture

Every frame runs the same five stages. Steps 1 to 3 only add buffers and swap the upscaler backend; the stages themselves never change.

1. **Scene pass:** draw the 3D scene into off-screen targets at render resolution. Step 2 adds jitter, depth output and motion vectors.
2. **Upscale pass:** the active backend turns render-res color into display-res color.
3. **Post pass (optional):** sharpening (RCAS or NIS sharpen), debug views.
4. **UI composite:** draw the client's 2D interface texture and overlays on top, at native resolution. VERIFY: the stock GPU plugin already uploads the 2D interface as a texture and draws it over the scene; confirm in source and reuse it.
5. **Present:** blit to the window, respecting viewport offset and stretched mode.

&#91;embedded content: frame pipeline · 5 stages, what each step adds\]

Only the upscale pass swaps its backend; each step also adds the scene-pass inputs that backend needs.

### 3.1 Requirements

| ID | Priority | Requirement |
| --- | --- | --- |
| A-01 | MUST | All upscalers implement one Java interface (below). The renderer never calls a vendor SDK directly. |
| A-02 | MUST | Render targets live in one `RenderTargets` class that owns creation, resize and deletion. Resizing the window or changing the scale MUST recreate them and reset upscaler history. |
| A-03 | MUST | Render resolution = display viewport size x scale, rounded to whole pixels, minimum 1 pixel. Supported scale presets: 100% (native / DLAA), 77%, 67% (Quality), 58% (Balanced), 50% (Performance), 33% (Ultra Performance), plus a custom slider from 33% to 100%. |
| A-04 | MUST | Backend selection order on failure: DLSS, then FSR 2/3, then FSR 1 or NIS, then bilinear. Each backend reports `isSupported()` with a reason string. |
| A-05 | MUST | A per-frame `FrameInputs` object carries everything a backend may need. Fields a step does not produce yet are null, and backends that need them report unsupported. |
| A-06 | SHOULD | GPU timer queries around each stage, shown in the debug overlay (G-31). |
| A-07 | MUST | Java code never holds raw native pointers across frames without an owner object that frees them in `close()`. |

### 3.2 Upscaler interface (target shape)

```java
interface Upscaler extends AutoCloseable {
    String name();
    Support isSupported(GpuCaps caps);          // supported + reason
    void resize(Size render, Size display);      // recreate internal resources
    void resetHistory();                         // camera cut, teleport, resize
    void evaluate(FrameInputs in, Texture out);  // render-res in, display-res out
    Vec2 jitter(int frameIndex);                 // (0,0) for spatial backends
    float mipBias(Size render, Size display);    // texture LOD bias for the scene pass
}

class FrameInputs {
    Texture color;         // Step 1+  render-res scene color
    Texture depth;         // Step 2+  render-res depth
    Texture motion;        // Step 2+  render-res motion vectors (pixels, current to previous)
    Vec2 jitter;           // Step 2+  sub-pixel offset used this frame
    Mat4 viewProj, prevViewProj; // Step 2+
    boolean reset;         // Step 2+  discard history this frame
    float frameTimeMs;     // Step 2+
    float sharpness;       // all
}
```

### 3.3 Module layout (target)

| Package | Holds | Added in |
| --- | --- | --- |
| `renderer` | Forked GPU plugin: scene pass, UI composite, present | Step 1 |
| `targets` | RenderTargets, FBO and texture helpers | Step 1 |
| `upscale` | Upscaler interface, Bilinear, Fsr1 or Nis backends | Step 1 |
| `temporal` | Jitter sequence, camera motion pass, history reset logic | Step 2 |
| `vkbridge` | Vulkan device, GL to VK shared images and semaphores | Step 2 |
| `upscale.fsr2` | FSR 2/3 backend on the bridge | Step 2 |
| `motion` | Per-object previous-vertex tracking, motion vector shaders | Step 3 |
| `upscale.dlss` + `native/` | DLSS backend (Java) and the C++ bridge library | Step 3 |

## 4. Step 1: Spatial upscaling (FSR 1 or NIS)

**Goal:** render the 3D scene below native resolution and upscale it with a single-frame upscaler, with the UI at native resolution. Works on any GPU the stock GPU plugin supports, including macOS.

**Builds on:** nothing. This step creates the foundation every later step reuses: the forked renderer, render targets, the upscaler interface, UI compositing and settings.

**Deliverables:**

- External plugin "Upscaler GPU" (working name) that renders like the stock GPU plugin.
- Backends: Bilinear (baseline) and FSR 1 (EASU + RCAS). NIS MAY be added as a second spatial backend.
- Config panel, debug overlay, and a short README with build and run steps.

### 4.1 Requirements

| ID | Priority | Requirement | Acceptance check |
| --- | --- | --- | --- |
| S1-01 | MUST | Fork the stock GPU plugin source into the new plugin with renamed packages and plugin name. No functional changes in this first commit. | With scale 100%, screenshots match the stock GPU plugin (T-01). |
| S1-02 | MUST | Scene pass renders into an off-screen framebuffer sized to render resolution (A-03), not straight to the window. | Debug overlay shows render size; changing scale changes it live. |
| S1-03 | MUST | Color target format is set in RenderTargets, RGBA8 for this step. SHOULD support RGBA16F behind a flag, needed in Step 2. | Switching the flag works with no visual change. |
| S1-04 | MUST | If the GPU plugin's MSAA setting is on, resolve MSAA at render resolution before the upscale pass. | No crash or black screen with MSAA 2x to 16x at any scale. |
| S1-05 | MUST | Bilinear backend: a plain filtered blit from render to display resolution. Always supported; final fallback (A-04). | Works on every tested GPU. |
| S1-06 | MUST | FSR 1 backend: port AMD FidelityFX FSR 1 EASU (upscale) and RCAS (sharpen) to GLSL using the official header's GLSL path. Keep AMD's MIT notice (G-23). | Visibly sharper than bilinear at 67% on T-02 scenes; no shader warnings. |
| S1-07 | MUST | EASU runs at display resolution reading the render-res color; RCAS runs after it at display resolution. Either fragment or compute shaders (OpenGL 4.3 has both). | RenderDoc capture shows the two passes in order. |
| S1-08 | MUST | Sharpness slider 0 to 100% maps to RCAS sharpness (FSR 1 measures it in stops, 0 = sharpest). 0% disables RCAS. | Slider changes the image live. |
| S1-09 | MAY | NIS backend: NVIDIA Image Scaling SDK GLSL scaler and sharpener (MIT). | Same checks as S1-06. |
| S1-10 | MUST | UI composite (stage 4) draws the 2D interface texture and RuneLite overlays after the upscale, at native resolution. | Text, minimap, inventory and overlay text are pixel-identical at 50% and 100% scale (T-03). |
| S1-11 | MUST | Viewport handling: in fixed mode the 3D area is offset and smaller than the window. Upscale only the 3D viewport rectangle and place it correctly. | No offset, stretch or seams in fixed, resizable and stretched modes (G-12). |
| S1-12 | SHOULD | Texture mip bias for spatial backends defaults to 0, configurable for testing. VERIFY whether AMD's FSR 1 guide recommends a negative bias. | Setting exists; documented in README. |
| S1-13 | MUST | Config: backend (Off, Bilinear, FSR 1, NIS if built), scale preset, custom scale, sharpness, debug overlay toggle. All live (G-33). | Each option changes output without restart. |
| S1-14 | SHOULD | Upscale plus sharpen GPU time stays under 1 ms at 4K output on a mid-range GPU. Target only: record the real number in section 8 after first measurement. | Debug overlay timer (A-06). |
| S1-15 | MUST | Window resize, scale change, plugin toggle and client minimize do not leak GL objects (G-11). | Toggle the plugin 20 times; GPU memory returns to baseline (T-05). |

### 4.2 Done when

- [ ] All Step 1 MUST rows pass their acceptance checks.
- [ ] T-01 to T-05 pass on at least one NVIDIA and one non-NVIDIA GPU (AMD, Intel, or Apple).
- [ ] Before and after screenshots plus FPS numbers at 1440p and 4K recorded in section 8.
- [ ] README explains build, run in developer mode, and every setting.

## 5. Step 2: Temporal upscaling (jitter, depth, camera motion, FSR 2/3)

**Goal:** add everything a temporal upscaler needs, build the OpenGL to Vulkan bridge, and run AMD FSR 2/3 on any modern GPU. Static scenery should be sharp and stable while the camera moves. Animated models are allowed to ghost in this step (fixed in Step 3).

**Builds on:** Step 1's renderer, render targets, upscaler interface, UI composite and settings. FSR 1 and bilinear stay as fallbacks.

**Deliverables:**

- Temporal inputs in OpenGL: jitter, depth, camera motion vectors, history reset.
- `vkbridge`: a Vulkan device on the same GPU, sharing images and semaphores with OpenGL.
- A native C++ library (`upscaler_native`) wrapping the AMD FidelityFX SDK FSR upscaler, called from Java.
- FSR 2/3 backend with the Step 1 config panel extended.

### 5.1 Temporal inputs (OpenGL)

| ID | Priority | Requirement | Acceptance check |
| --- | --- | --- | --- |
| S2-01 | MUST | Disable MSAA whenever a temporal backend is active; the upscaler does the anti-aliasing. | MSAA setting greyed out or ignored, with a note in the overlay. |
| S2-02 | MUST | Depth target at render resolution that later passes can sample (32-bit float). Record the depth convention: standard or reversed, near and far planes. VERIFY what the GPU plugin's projection uses. | Depth debug view (G-32) shows a sensible gradient. |
| S2-03 | MUST | Jitter: Halton(2,3) sequence, offsets in pixels within -0.5 to 0.5. Phase count = ceil(8 x (display width / render width)^2), per AMD's FSR 2 guide. | Jitter debug readout cycles through the expected count. |
| S2-04 | MUST | Apply jitter to the scene projection only, as a clip-space offset of 2 x jitter / render size. The UI, overlays and any CPU-side projection stay unjittered. | Overlays (tile markers, names) do not shake. |
| S2-05 | MUST | Keep both jittered and unjittered view-projection matrices for this frame and the previous frame. | Values visible in a debug dump. |
| S2-06 | MUST | Camera motion pass: a full-screen pass that rebuilds each pixel's world position from depth and the inverse unjittered view-projection, reprojects it with the previous unjittered view-projection, and writes the difference in pixels (current to previous) into an RG16F target. | Motion debug view: zero on a still camera; smooth gradients when rotating. |
| S2-07 | MUST | Motion vector scale and sign are per-backend settings, not hard-coded, because FSR and DLSS conventions may differ. VERIFY each SDK's expected convention. | Changing the sign visibly breaks the image (proves it is wired). |
| S2-08 | MUST | History reset on: scene or region reload, teleport, camera jump above a threshold, resize, scale change, login and logout, plugin enable, backend switch. | No smearing for one frame after each event (T-12). |
| S2-09 | MUST | Negative texture mip bias for the scene pass while a temporal backend is active, from `Upscaler.mipBias()`. Default log2(render / display) - 1; VERIFY in each vendor guide. | Ground textures stay sharp at 50% scale. |
| S2-10 | SHOULD | Moving-object mask: when drawing dynamic models (players, NPCs, projectiles), also write 1 into an R8 mask target. Used as FSR's reactive mask to reduce ghosting until Step 3. | Mask debug view highlights only moving models. |
| S2-11 | SHOULD | Transparency mask for water, particles and other alpha-blended surfaces, for FSR's transparency and composition mask. | Mask debug view. |
| S2-12 | MUST | Frame index and frame delta time tracked and passed in `FrameInputs`. | Debug readout. |

### 5.2 OpenGL to Vulkan bridge

| ID | Priority | Requirement | Acceptance check |
| --- | --- | --- | --- |
| S2-20 | MUST | Create a Vulkan 1.1+ instance and device on the same physical GPU as the OpenGL context, matched by device UUID (OpenGL `GL_DEVICE_UUID_EXT` vs Vulkan `VkPhysicalDeviceIDProperties.deviceUUID`). | Log shows matching UUIDs; refuse to start on a mismatch. |
| S2-21 | MUST | Required OpenGL extensions: `GL_EXT_memory_object`, `GL_EXT_semaphore`, plus `_win32` (Windows) or `_fd` (Linux). Required Vulkan: external memory and external semaphore, Win32 or FD variants. Missing any = backend unsupported, with reason (G-13). | Unsupported path tested by faking a missing extension. |
| S2-22 | MUST | Shared images are allocated in Vulkan with exportable dedicated memory, then imported into OpenGL (memory import plus `glTexStorageMem2DEXT`). Formats must be supported in both APIs. | RenderDoc shows the same image written by GL and read by VK. |
| S2-23 | MUST | Shared set: color (input), depth copy (input), motion (input), reactive mask (optional input), output color. Copy depth into an R32F color image in GL before sharing; do not share the depth attachment itself. | All five images round-trip with correct contents. |
| S2-24 | MUST | Sync with binary semaphores each frame: GL signals after the scene and motion passes; VK waits, runs the upscaler, signals; GL waits before the UI composite. Image layouts passed on both sides. | No tearing, flicker or validation errors over a 10-minute session (T-13). |
| S2-25 | MUST | No CPU readback of any image. The only copy is the depth copy in S2-23. | GPU trace shows no transfers to host. |
| S2-26 | MUST | Vulkan objects are created once and recreated only on resize or scale change; all are destroyed on plugin disable (G-11). | Validation layers report no leaks at shutdown. |
| S2-27 | MUST | Java side uses LWJGL Vulkan bindings. VERIFY they are available to plugins at runtime (Q-02). | Plugin starts on a clean RuneLite install. |
| S2-28 | MUST | macOS: no bridge (OpenGL and Vulkan interop is not available there). Temporal backends report unsupported; Step 1 backends still work. | Mac falls back to FSR 1 with a clear reason. |

### 5.3 Native library and FSR backend

| ID | Priority | Requirement | Acceptance check |
| --- | --- | --- | --- |
| S2-30 | MUST | C++ library `upscaler_native` with a small C ABI: create, resize, evaluate, reset, destroy, plus a query for supported features. Vulkan handles cross the boundary as 64-bit integers. | Header file is the only contract between Java and C++. |
| S2-31 | MUST | Java calls the library through JNI, or JNA if RuneLite already ships it (VERIFY). No other new runtime dependency. | Works from a plain developer-mode run. |
| S2-32 | MUST | The library uses the Vulkan instance and device that Java created; it never creates its own. | Single device in RenderDoc capture. |
| S2-33 | MUST | Wrap the AMD FidelityFX SDK FSR upscaler (2.x, or 3.x upscaler only, no frame generation) with its Vulkan backend. Keep AMD's MIT notice (G-23). | FSR evaluates every frame with no validation errors. |
| S2-34 | MUST | FSR setup: input is LDR (no HDR flag), depth convention from S2-02, motion vectors at render resolution and not jittered, auto-exposure off with exposure 1.0. Quality modes map to A-03 presets. | Image matches expected FSR quality on T-02 scenes. |
| S2-35 | SHOULD | Pass the S2-10 mask as FSR's reactive mask and the S2-11 mask as its transparency and composition mask. | Less ghosting on walking NPCs than without masks (T-14). |
| S2-36 | MUST | Native binaries for Windows x64 and Linux x64 built with CMake, packed in the plugin jar, extracted to a cache folder and loaded at startup. Load failure falls back gracefully (G-13). | Delete the DLL: plugin falls back to FSR 1 with reason. |
| S2-37 | SHOULD | The native library is designed for more backends: DLSS is added to the same library in Step 3 behind the same C ABI. | ABI has a backend-kind parameter. |

### 5.4 Done when

- [ ] All Step 2 MUST rows pass their acceptance checks.
- [ ] T-10 to T-14 pass on one NVIDIA and one AMD or Intel GPU, on Windows.
- [ ] Rotating and zooming the camera over static scenery is stable, with no shimmer, at 67% scale.
- [ ] Ghosting on animated models is recorded with a short clip in section 8, as the baseline Step 3 must beat.

## 6. Step 3: Per-object motion vectors and DLSS

**Goal:** give animated models correct motion vectors, then add NVIDIA DLSS Super Resolution and DLAA as a backend on the Step 2 bridge. Non-RTX GPUs keep using FSR 2/3.

**Builds on:** Step 2's jitter, depth, motion target, reset logic, Vulkan bridge and native library. The new motion vectors also improve the FSR backend.

**Deliverables:**

- Motion vectors written per vertex during the scene pass, for static and animated geometry.
- DLSS backend in `upscaler_native` behind the same C ABI.
- DLSS quality modes, DLAA, and the Step 2 Done-when ghosting clip re-recorded for comparison.

### 6.1 Per-object motion vectors

| ID | Priority | Requirement | Acceptance check |
| --- | --- | --- | --- |
| S3-01 | MUST | Research first: document how the forked renderer receives dynamic models each frame (players, NPCs, projectiles, animated objects) and which value gives a stable identity for the same instance across frames. VERIFY in source; write findings into section 8 before coding. | Findings entry exists in section 8. |
| S3-02 | MUST | Keep the previous frame's world-space positions for every dynamic model instance in a GPU buffer, keyed by that identity. Double-buffer it (this frame and last frame) and swap each frame. | Buffer debug readout: instance count and bytes used. |
| S3-03 | MUST | The scene pass gets a second color output (RG16F) for motion. The vertex shader outputs current and previous unjittered clip positions; the fragment shader writes the difference in pixels, using the S2-07 convention. | Motion view shows walking NPCs moving against a still background. |
| S3-04 | MUST | Static scene geometry uses its own position as the previous world position, so its motion equals camera motion. The S2-06 full-screen pass stays as a debug comparison and fallback. | Static pixels match S2-06 output within 0.1 pixel. |
| S3-05 | MUST | Fallback when no valid history exists (new instance, vertex count changed, model swapped, teleport): use the current position as previous and write 1 into the moving-object mask (S2-10). | No garbage vectors when equipment changes or an NPC spawns. |
| S3-06 | MUST | Surfaces animated only by texture (flowing water, lava) have no geometry motion; flag them in the transparency mask (S2-11). | No smearing on water. |
| S3-07 | SHOULD | Cost targets: under 0.5 ms extra CPU time per frame and a bounded GPU buffer in a busy scene (for example a crowded bank). Record real numbers in section 8. | Measured in T-20. |
| S3-08 | MUST | Works with the client's smooth animation setting on and off. VERIFY how interpolated animation frames reach the renderer. | No stutter in motion view with either setting. |

### 6.2 DLSS integration

| ID | Priority | Requirement | Acceptance check |
| --- | --- | --- | --- |
| S3-20 | MUST | Choose the integration path (D-04). Default: NVIDIA's DLSS SDK (NGX) Vulkan API directly, for Super Resolution and DLAA only. Streamline only if Frame Generation is ever added, because its interposer wants to own Vulkan device creation. VERIFY NVIDIA's current guidance. | Decision recorded in section 8. |
| S3-21 | MUST | Before creating the Vulkan device (S2-20), ask the DLSS SDK which instance and device extensions it needs and add them. The bridge MUST accept extra extension lists from backends. | DLSS initializes with no missing-extension errors. |
| S3-22 | MUST | Availability check: DLSS supported on this GPU and driver, reported by the SDK. Not supported = backend unsupported with reason, fall back to FSR (A-04). | Tested on an RTX GPU and a non-NVIDIA GPU. |
| S3-23 | MUST | Render size comes from the SDK's optimal-settings query for the chosen quality mode, overriding A-03 presets while DLSS is active. DLAA = render size equals display size. | Overlay shows SDK-chosen sizes per mode. |
| S3-24 | MUST | Feature creation flags: LDR input (not HDR), motion vectors at render resolution and not jittered, depth inverted or not per S2-02, exposure handled by auto-exposure or a fixed 1.0 value. | Image is stable, not over- or under-exposed. |
| S3-25 | MUST | Per-frame evaluate inputs: color, depth copy, motion, output, jitter offset in pixels, motion vector scale, reset flag (S2-08), and the viewport sub-rectangle for fixed mode. VERIFY the jitter sign and motion vector convention in NVIDIA's programming guide. | Wrong sign test: flipping it visibly breaks the image. |
| S3-26 | SHOULD | Expose the DLSS model preset as an advanced setting; default leaves the SDK's choice. | Setting changes output. |
| S3-27 | MUST | DLSS runtime DLL: follow NVIDIA's redistribution terms. If shipping is not allowed or unclear, the user points the plugin at their own copy. Missing DLL = fallback with reason (G-13). | Delete the DLL: falls back to FSR. |
| S3-28 | MUST | DLSS lives in `upscaler_native` behind the S2-30 C ABI; Java sees it as one more backend kind. | No DLSS types in Java code. |
| S3-29 | SHOULD | Linux x64 support where the DLSS SDK and driver allow. | Runs on one Linux RTX setup, or a documented reason why not. |

### 6.3 Done when

- [ ] All Step 3 MUST rows pass their acceptance checks.
- [ ] T-20 to T-24 pass on an RTX GPU; a non-NVIDIA GPU falls back to FSR cleanly.
- [ ] Ghosting clip from Step 2 re-recorded with the same scene: visibly reduced with both FSR and DLSS.
- [ ] FPS and GPU time for DLAA, Quality and Performance at 1440p and 4K recorded in section 8, next to the Step 1 and 2 numbers.

## 7. Testing and validation

Every step is checked with the same scenes and the same measurement method, so numbers compare across steps.

### 7.1 Standard test scenes

Use fixed camera angles and save them (a dev-only camera preset is fine). Same scenes for every backend and step.

| Scene | What it stresses |
| --- | --- |
| Lumbridge castle courtyard | Static geometry, a few walking NPCs, baseline |
| Grand Exchange | Many players, crowd motion, CPU load |
| A fishing spot or any river | Water, texture animation, transparency |
| Dense trees (any forest) | Alpha-tested foliage, thin detail, shimmer |
| A fight with projectiles (ranged or magic) | Fast small moving objects, spot animations |

### 7.2 Measurement method

- FPS cap off (or set well above expected FPS), vsync off.
- 60 seconds per scene, report average FPS, 1% low FPS, and GPU time per stage (A-06).
- Record GPU, driver version, CPU, OS, resolution, scale, backend.
- Results go in the section 8 results log, never overwritten.

### 7.3 Automated tests (JUnit)

| ID | Covers |
| --- | --- |
| T-U1 | Render size math for every preset and odd window sizes (A-03) |
| T-U2 | Halton(2,3) values and phase count formula (S2-03) |
| T-U3 | Mip bias formula per backend (S2-09) |
| T-U4 | Backend fallback order with mocked `isSupported()` results (A-04) |
| T-U5 | History reset triggers fire on each listed event (S2-08), with mocked client events |

### 7.4 Manual and visual tests

| ID | Step | Test | Pass condition |
| --- | --- | --- | --- |
| T-01 | 1 | Parity: plugin at 100% scale vs stock GPU plugin, all scenes | Screenshots match; FPS within noise |
| T-02 | 1 | Screenshot every scene with Bilinear, FSR 1 (and NIS) at 67% and 50% | FSR 1 visibly sharper than bilinear; no artifacts at edges of viewport |
| T-03 | 1 | UI crop compare at 50% vs 100% scale | Interface and overlay pixels identical |
| T-04 | 1 | Mode matrix: fixed, resizable, stretched, each with UI scaling on and off | No offsets, seams or black areas |
| T-05 | 1 | Toggle plugin 20 times, resize window 20 times | GPU memory returns to baseline; no errors in log |
| T-10 | 2 | Debug views for depth, jitter and motion on a still and a rotating camera | Matches S2-02, S2-03, S2-06 checks |
| T-11 | 2 | Slow 360 degree camera rotation over trees and buildings, recorded | No shimmer or crawling edges at 67% |
| T-12 | 2 | Teleport, region change, resize, login, backend switch | No smear frame after any event |
| T-13 | 2 | 10-minute play session with Vulkan validation layers on | Zero validation errors; no flicker |
| T-14 | 2 | Record an NPC walking across the screen, with and without masks | Clip saved; ghosting with masks no worse than without |
| T-20 | 3 | Per-object motion: walking NPCs, running players, projectiles, equipment change, NPC spawn | Motion view correct; no garbage on swaps or spawns |
| T-21 | 3 | DLSS DLAA, Quality, Balanced, Performance, Ultra Performance on all scenes | Each mode works; sizes match SDK query |
| T-22 | 3 | Fallback matrix: RTX GPU, non-NVIDIA GPU, missing DLSS DLL, missing native lib, Mac | Each lands on the expected backend with a reason shown |
| T-23 | 3 | Performance at 1440p and 4K for every backend | Numbers logged next to Steps 1 and 2 |
| T-24 | 3 | 30-minute soak with DLSS on | No leaks, crashes or validation errors |

## 8. Risks, open questions, decisions

This section is the project's living memory. Agents append here; nothing is deleted, superseded entries are marked as such.

### 8.1 Decisions

| ID | Decision | Why | Status |
| --- | --- | --- | --- |
| D-01 | Step 1 ships FSR 1 first; NIS is optional | Open source (MIT), has a GLSL path, widely documented | Proposed |
| D-02 | Fork the stock GPU plugin, not 117 HD | Much smaller codebase to learn. Porting to 117 HD MAY come later, where GPU load and gains are higher | Proposed |
| D-03 | Build the OpenGL to Vulkan bridge and native library in Step 2, using FSR 2/3's Vulkan backend | FSR 2/3 has no official OpenGL backend; front-loads the riskiest plumbing; Step 3 then only adds DLSS. Alternative: hand-port FSR 2 compute shaders to GLSL (keeps Step 2 pure Java and portable, but a large port) | Proposed |
| D-04 | DLSS through NVIDIA's DLSS SDK (NGX) directly, not Streamline | Streamline's interposer expects to own Vulkan device creation, which conflicts with the bridge. Revisit only for Frame Generation | Proposed, VERIFY |
| D-05 | OpenGL stays the main renderer; no full Vulkan or WebGPU rewrite | Keeps upstream compatibility and plugin ecosystem; the bridge cost is small for one pass per frame | Proposed |
| D-06 | G-10 guard (2026-11-14): declare `conflicts = {"GPU", "117 HD"}` so RuneLite turns the other renderer off when either is enabled, plus a startup refusal with a message if any other renderer still owns DrawCallbacks. Shutdown only resets client renderer state when this plugin owns DrawCallbacks. | A refuse-only guard covers just one direction: enabling stock GPU while Upscaler GPU runs would leave both running. RuneLite's `conflicts` is symmetric and is what 117 HD uses. The two can't both be literal: with `conflicts`, RuneLite disables the other plugin before `startUp()`, so the refusal only fires for renderers that don't declare a conflict | Accepted (user) |

### 8.2 Open questions

| ID | Question | Blocks |
| --- | --- | --- |
| Q-01 | Does the Plugin Hub accept plugins that bundle native libraries or vendor DLLs? | Public release (G-22) |
| Q-02 | Are LWJGL's Vulkan bindings available to plugins at runtime, or must the plugin bundle them? | S2-27 |
| Q-03 | Does RuneLite ship JNA, so the native library can be called without JNI glue? | S2-31 |
| Q-04 | Can the DLSS runtime DLL be redistributed inside a plugin jar under NVIDIA's terms? | S3-27 |
| Q-05 | One unofficial 2026 guide claims RuneLite's GPU plugin gained an opt-in Vulkan backend. Is that true? If yes, revisit D-03 and D-05, since the bridge may be unnecessary | Step 2 design |
| Q-06 | What stable per-instance identity does the renderer get for dynamic models across frames? | S3-01 to S3-05 |
| Q-07 | What depth convention and near and far planes does the GPU plugin's projection use? | S2-02, S2-34, S3-24 |

### 8.3 Risks

| Risk | Impact | Mitigation |
| --- | --- | --- |
| OSRS is CPU-bound, so upscaling adds little FPS on the stock renderer | Low payoff for Steps 1 to 3 | Measure early (T-23); treat DLAA image quality as a goal too; consider a 117 HD port (D-02) |
| GL and VK interop bugs on some drivers | Flicker, crashes | Validation layers, strict fallback (G-13), test on 2+ vendors |
| No stable model identity across frames | Step 3 motion vectors impossible for some objects | S3-05 fallback plus reactive mask; accept partial coverage |
| Plugin Hub rejects native code | No easy public distribution | Ship Step 1 (pure GLSL) publicly; keep Steps 2 and 3 as sideload builds |
| Upstream RuneLite changes to the GPU plugin | Merge conflicts in the fork | G-14: keep forked code isolated; merge upstream regularly |
| Learning curve (graphics plus C++ plus Vulkan) | Slow progress | Steps ship independently; Step 1 alone is a useful result |

### 8.4 Findings log

Record VERIFY results here: date, requirement ID, what the source or SDK actually says, file or doc link.

| Date | Req ID | Finding | Source |
| --- | --- | --- | --- |
| 2026-11-14 | S1-01 | Stock GPU plugin from RuneLite 1.13.6 (`d62b158e`) compiles as an external plugin after only the package rename `net.runelite.client.plugins.gpu` → `com.runeliteupscaler.renderer`. No package-private or otherwise unreachable client APIs were needed. Shaders and `regions/regions.txt` load relative to the class, so the resource move needed no code change. | `src/main/java/com/runeliteupscaler/renderer/**`; `renderer/template/Template.java`, `renderer/RegionManager.java` |
| 2026-11-14 | S1-01, G-10 | `PluginManager` stores a plugin's enabled state under `runelite.<configName or class simple name>` (lowercase). The fork keeps the class name `GpuPlugin`, so without a change it would share the `gpuplugin` key with the stock GPU plugin and turn on/off with it. Fixed by `configName = "UpscalerGpuPlugin"` in `@PluginDescriptor`; display name changed to "Upscaler GPU". | upstream `runelite-client/.../plugins/PluginManager.java:511-516`; `renderer/GpuPlugin.java` |
| 2026-11-14 | S1-01 | The fork's config group was `"gpu"`, the same as the stock plugin, so the two would read and write the same settings and both react to each other's changes (`GpuPlugin.onConfigChanged`). Changed `GpuPluginConfig.GROUP` to `"upscalergpu"`. Settings start from defaults rather than copying the user's stock GPU settings. | `renderer/GpuPluginConfig.java` |
| 2026-11-14 | S1-01, T-01 | T-01 passed (user-confirmed in developer mode, RuneLite 1.13.6): the "Upscaler GPU" fork renders the same as the stock GPU plugin, with stock GPU off. FPS has not been measured with the 7.2 method yet, so nothing goes in 8.5. | `step-1-spatial` @ `9cbf985` |
| 2026-11-14 | G-10 (VERIFY) | DrawCallbacks ownership is one unguarded slot on `Client`: the GPU plugin calls `client.setDrawCallbacks(this)` at the end of startup and `setDrawCallbacks(null)` in `shutDown()`, with no check of the current owner, so the last writer wins and any renderer's shutdown clears it for everyone. `Client.getDrawCallbacks()` exists (runelite-api 1.13.6), so ownership can be checked. | upstream `plugins/gpu/GpuPlugin.java` `startUp`/`shutDown`; `javap net.runelite.api.Client` |
| 2026-11-14 | G-10 | RuneLite's `PluginManager.conflictsForPlugin` is symmetric: two plugins conflict if either one's name or `conflicts` entries match the other's name or `conflicts` entries. `startPlugin` and `setPluginEnabled(…, true)` disable and stop conflicting plugins before calling `startUp()`. 117 HD's descriptor is `name = "117 HD"`, `conflicts = "GPU"`. | upstream `plugins/PluginManager.java` (`conflictsForPlugin`, `startPlugin` ~395-416, `setPluginEnabled` ~487-506); github.com/117HD/RLHD `HdPlugin.java` |

### 8.5 Results log

Record every T-23 style measurement here (section 7.2 method).

| Date | Step | Backend and mode | Resolution | Scene | Avg FPS | 1% low FPS | Upscale GPU ms | Hardware |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
|  |  |  |  |  |  |  |  |  |

## 9. Glossary and references

### 9.1 Glossary

| Term | Meaning |
| --- | --- |
| Render resolution | Size the 3D scene is drawn at, before upscaling |
| Display resolution | Final size on screen (the 3D viewport rectangle) |
| Spatial upscaler | Uses one frame only (bilinear, FSR 1, NIS) |
| Temporal upscaler | Combines many frames using jitter, depth and motion vectors (FSR 2/3, DLSS) |
| DLAA | DLSS at 100% scale, used as anti-aliasing only |
| Jitter | Tiny sub-pixel camera shift each frame so successive frames sample different points |
| Motion vector | Per-pixel offset from where a surface is now to where it was last frame, in pixels |
| History | The upscaler's accumulated previous frames; reset discards it |
| Reactive mask | Marks pixels the upscaler should trust history less for (moving or unpredictable objects) |
| Mip bias | Shift in texture detail level; negative values keep textures sharp when rendering below native |
| DrawCallbacks | RuneLite interface a renderer plugin implements to take over scene drawing |
| Interop / bridge | Sharing GPU images and sync objects between OpenGL and Vulkan without copies |
| NGX | NVIDIA's runtime layer that DLSS runs on |
| Streamline | NVIDIA's wrapper SDK for DLSS and related features |
| EASU / RCAS | FSR 1's upscale pass and sharpen pass |

### 9.2 References

Read the relevant ones before each step. VERIFY items in this document point back to these.

**RuneLite and OSRS rendering**

- [RuneLite source](https://github.com/runelite/runelite), including the GPU plugin under `runelite-client/.../plugins/gpu`
- [RuneLite plugin template (example-plugin)](https://github.com/runelite/example-plugin)
- [RuneLite GPU FAQ](https://www.github.com/runelite/runelite/wiki/GPU-FAQ)
- [117 HD (RLHD)](https://github.com/117HD/RLHD)
- [gpu-vulkan: third-party Vulkan renderer for RuneLite with an FSR 1 example](https://identichosting.duckdns.org/dennisdevulder/gpu-vulkan)
- [RuneLite-DLSS5: experimental WebGPU/Vulkan renderer with DLSS via Streamline](https://github.com/SamG-Coder/RuneLite-DLSS5), a reference for viewport handling and Streamline usage, not a base to fork

**Upscalers**

- [AMD FidelityFX FSR 1](https://github.com/GPUOpen-Effects/FidelityFX-FSR)
- [AMD FidelityFX SDK (FSR 2/3)](https://github.com/GPUOpen-LibrariesAndSDKs/FidelityFX-SDK)
- [NVIDIA Image Scaling SDK](https://github.com/NVIDIAGameWorks/NVIDIAImageScaling)
- [NVIDIA DLSS developer page](https://developer.nvidia.com/dlss)
- [NVIDIA DLSS SDK](https://github.com/NVIDIA/DLSS), includes the programming guide
- [NVIDIA Streamline](https://github.com/NVIDIAGameWorks/Streamline)

**Graphics APIs and tools**

- [OpenGL EXT\_external\_objects (memory object and semaphore)](https://registry.khronos.org/OpenGL/extensions/EXT/EXT_external_objects.txt)
- [LWJGL](https://www.lwjgl.org/)
- [RenderDoc](https://renderdoc.org/)

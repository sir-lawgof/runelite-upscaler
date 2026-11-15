/*
 * Copyright (c) 2026, josephtschafer
 * SPDX-License-Identifier: BSD-2-Clause (see the license header in the Java sources)
 *
 * Uses AMD FidelityFX FSR 1 (ffx_a.h, ffx_fsr1.h), Copyright (c) 2021 Advanced Micro Devices, Inc.,
 * MIT license; see LICENSE-FidelityFX-FSR.txt in this folder (G-23).
 */
#version 430

// S1-06, S1-07: FSR 1 EASU (upscale). Runs at display resolution, reading the render-resolution color.
// 32-bit path of the official header's GLSL code (no A_HALF), so no GLSL extensions are needed.
#define A_GPU 1
#define A_GLSL 1
#include "fsr1/ffx_a.h"

uniform sampler2D inputTex;
uniform uvec4 con0;
uniform uvec4 con1;
uniform uvec4 con2;
uniform uvec4 con3;

#define FSR_EASU_F 1
// S1-03: FSR 1 needs input normalized to [0,1]. RGBA8 targets already are; RGBA16F targets don't clamp
// fragment output, so clamp here (no-op for RGBA8).
AF4 FsrEasuRF(AF2 p) { return clamp(textureGather(inputTex, p, 0), 0.0, 1.0); }
AF4 FsrEasuGF(AF2 p) { return clamp(textureGather(inputTex, p, 1), 0.0, 1.0); }
AF4 FsrEasuBF(AF2 p) { return clamp(textureGather(inputTex, p, 2), 0.0, 1.0); }

#include "fsr1/ffx_fsr1.h"

out vec4 fragColor;

void main()
{
	AF3 c;
	FsrEasuF(c, AU2(gl_FragCoord.xy), con0, con1, con2, con3);
	fragColor = vec4(c, 1.0);
}

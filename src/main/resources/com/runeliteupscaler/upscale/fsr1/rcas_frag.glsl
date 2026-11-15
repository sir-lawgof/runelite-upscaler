/*
 * Copyright (c) 2026, josephtschafer
 * SPDX-License-Identifier: BSD-2-Clause (see the license header in the Java sources)
 *
 * Uses AMD FidelityFX FSR 1 (ffx_a.h, ffx_fsr1.h), Copyright (c) 2021 Advanced Micro Devices, Inc.,
 * MIT license; see LICENSE-FidelityFX-FSR.txt in this folder (G-23).
 */
#version 430

// S1-06, S1-07, S1-08: FSR 1 RCAS (sharpen). Runs after EASU at display resolution.
#define A_GPU 1
#define A_GLSL 1
#include "fsr1/ffx_a.h"

uniform sampler2D inputTex;
uniform uvec4 con;

#define FSR_RCAS_F 1
// Clamp the coordinate so the 3x3 neighborhood never reads outside the image (no edge artifacts, T-02).
// S1-03: clamp the value to [0,1] too; negative input makes RCAS output NaN (AMD guide p.13). No-op for RGBA8.
AF4 FsrRcasLoadF(ASU2 p) { return clamp(texelFetch(inputTex, clamp(p, ivec2(0), textureSize(inputTex, 0) - 1), 0), 0.0, 1.0); }
void FsrRcasInputF(inout AF1 r, inout AF1 g, inout AF1 b) {}

#include "fsr1/ffx_fsr1.h"

out vec4 fragColor;

void main()
{
	AF1 r, g, b;
	FsrRcasF(r, g, b, AU2(gl_FragCoord.xy), con);
	fragColor = vec4(r, g, b, 1.0);
}

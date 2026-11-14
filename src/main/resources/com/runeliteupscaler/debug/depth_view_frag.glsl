/*
 * Copyright (c) 2026, josephtschafer
 * SPDX-License-Identifier: BSD-2-Clause (see the license header in the Java sources)
 */
#version 330

// G-32, S2-02: depth debug view. Near is bright, far is dark, sky (depth 0, nothing drawn) is black.
// Depth convention (8.4, Q-07): infinite reversed-Z, depth = 100 / z_view with GL_ZERO_TO_ONE,
// or 0.5 + 50 / z_view with the default -1..1 depth range.
uniform sampler2D depthTex;
uniform vec4 rect;      // 3D viewport on the window: x, y, width, height in pixels
uniform int zeroToOne;  // 1 if glClipControl(ZERO_TO_ONE) is active
uniform float maxZ;     // view distance mapped to black (draw distance in local units)

out vec4 fragColor;

void main()
{
	vec2 uv = (gl_FragCoord.xy - rect.xy) / rect.zw;
	float d = texture(depthTex, uv).r;
	float z = zeroToOne == 1 ? 100.0 / max(d, 1e-7) : 50.0 / max(d - 0.5, 1e-7);
	float g = d <= 0.0 ? 0.0 : 1.0 - clamp(z / maxZ, 0.0, 1.0);
	fragColor = vec4(vec3(g), 1.0);
}

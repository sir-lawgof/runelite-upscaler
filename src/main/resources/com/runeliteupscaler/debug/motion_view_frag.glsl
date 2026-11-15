/*
 * Copyright (c) 2026, josephtschafer
 * SPDX-License-Identifier: BSD-2-Clause (see the license header in the Java sources)
 */
#version 330

// G-32, S2-06: motion debug view. Mid-grey = no motion. Red/green show the x/y offset (pixels, current to
// previous): brighter red = previous position further right, brighter green = further up.
// +/- range pixels map to the full 0..1 colour range.
uniform sampler2D motionTex;
uniform vec4 rect;   // 3D viewport on the window: x, y, width, height in pixels
uniform float range;

out vec4 fragColor;

void main()
{
	vec2 uv = (gl_FragCoord.xy - rect.xy) / rect.zw;
	vec2 mv = texture(motionTex, uv).rg;
	fragColor = vec4(clamp(vec2(0.5) + mv / (2.0 * range), 0.0, 1.0), 0.5, 1.0);
}

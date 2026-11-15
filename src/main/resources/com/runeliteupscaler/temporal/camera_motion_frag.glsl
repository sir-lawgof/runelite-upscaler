/*
 * Copyright (c) 2026, josephtschafer
 * SPDX-License-Identifier: BSD-2-Clause (see the license header in the Java sources)
 */
#version 330

// S2-06: camera motion vectors. For each render-res pixel, rebuild its NDC position from depth, reproject it
// with currentToPrevious = prevViewProj * inverse(viewProj) (both unjittered, computed on the CPU in double),
// and write the screen offset in pixels, current to previous. Works for the sky (depth 0, infinitely far):
// the world position is never divided by w, so those pixels only move with camera rotation.
uniform sampler2D depthTex;
uniform mat4 currentToPrevious;
uniform vec2 renderSize;
uniform int zeroToOne;  // Q-07: 1 if window depth = NDC depth (glClipControl ZERO_TO_ONE)

out vec4 motion;

void main()
{
	vec2 ndcXY = gl_FragCoord.xy / renderSize * 2.0 - 1.0;
	float d = texelFetch(depthTex, ivec2(gl_FragCoord.xy), 0).r;
	float ndcZ = zeroToOne == 1 ? d : d * 2.0 - 1.0;
	vec4 prev = currentToPrevious * vec4(ndcXY, ndcZ, 1.0);
	vec2 mv = vec2(0.0);
	if (prev.w > 1e-6)
	{
		// Behind the previous camera there is no valid previous position: leave 0 (history reset handles cuts)
		mv = (prev.xy / prev.w - ndcXY) * 0.5 * renderSize;
	}
	motion = vec4(mv, 0.0, 0.0);
}

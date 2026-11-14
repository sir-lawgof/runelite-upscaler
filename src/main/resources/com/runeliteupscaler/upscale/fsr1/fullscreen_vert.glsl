/*
 * Copyright (c) 2026, josephtschafer
 * SPDX-License-Identifier: BSD-2-Clause (see the license header in the Java sources)
 */
#version 430

// S1-07: one full-screen triangle, no vertex buffer. Covers the bound viewport.
void main()
{
	vec2 pos = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
	gl_Position = vec4(pos * 2.0 - 1.0, 0.0, 1.0);
}

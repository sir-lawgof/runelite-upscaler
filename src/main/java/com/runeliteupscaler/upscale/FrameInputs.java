/*
 * Copyright (c) 2026, josephtschafer
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.runeliteupscaler.upscale;

import com.runeliteupscaler.targets.Texture;

/**
 * A-05: everything a backend may need for one frame. Fields a step does not produce yet are null (or
 * zero), and backends that need them report unsupported. Reused across frames by the renderer.
 */
public final class FrameInputs
{
	/** Step 1+: render-resolution scene color (single-sample) */
	public Texture color;
	/** Step 2+: render-resolution depth */
	public Texture depth;
	/** Step 2+: render-resolution motion vectors, pixels, current to previous */
	public Texture motion;
	/** Step 2+: sub-pixel offset used this frame */
	public Vec2 jitter;
	/** Step 2+: column-major 4x4 matrices */
	public float[] viewProj;
	public float[] prevViewProj;
	/** Step 2+: discard history this frame */
	public boolean reset;
	/** Step 2+ */
	public float frameTimeMs;
	/** All steps: 0 to 1 */
	public float sharpness;
}

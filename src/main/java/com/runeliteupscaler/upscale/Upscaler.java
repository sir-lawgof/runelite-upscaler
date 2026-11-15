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

import com.runeliteupscaler.targets.Size;
import com.runeliteupscaler.targets.Texture;

/**
 * A-01: every upscaler backend implements this. The renderer only talks to backends through it,
 * never to a vendor SDK directly. All methods are called on the thread that owns the GL context.
 */
public interface Upscaler extends AutoCloseable
{
	/** Shown in the debug overlay (G-31). */
	String name();

	/** A-04: whether this backend can run on this GPU, with a reason when it can't. */
	Support isSupported(GpuCaps caps);

	/** Recreate internal resources for new render and display sizes. Implies {@link #resetHistory()}. */
	void resize(Size render, Size display);

	/** Discard accumulated history (camera cut, teleport, resize). No-op for spatial backends. */
	void resetHistory();

	/**
	 * Upscale {@code in.color} (render resolution) into {@code out} (display resolution).
	 * May change GL framebuffer, texture, program and VAO bindings; the caller rebinds what it needs.
	 */
	void evaluate(FrameInputs in, Texture out);

	/** Sub-pixel jitter for this frame; (0,0) for spatial backends. */
	Vec2 jitter(int frameIndex);

	/** Texture LOD bias for the scene pass. */
	float mipBias(Size render, Size display);

	/**
	 * S2-07: scale (and sign) this backend applies to {@link FrameInputs#motion}, which is always in pixels,
	 * current to previous, GL y-up (S2-06). A temporal backend passes this to its SDK's motion vector scale
	 * instead of the pass hard-coding one convention. VERIFY each SDK's expected convention when adding it.
	 */
	default Vec2 motionVectorScale()
	{
		return new Vec2(1f, 1f);
	}

	/** A-07, G-11: free every GL or native resource this backend owns. */
	@Override
	void close();
}

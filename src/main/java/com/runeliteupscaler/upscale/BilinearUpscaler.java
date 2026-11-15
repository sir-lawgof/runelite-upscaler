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
import static org.lwjgl.opengl.GL33C.*;

/**
 * S1-05: bilinear backend, a plain filtered blit from render to display resolution. Always supported
 * (the GPU plugin already requires OpenGL 3.3, which has glBlitFramebuffer); the final fallback (A-04).
 */
public final class BilinearUpscaler implements Upscaler
{
	public static final String NAME = "Bilinear";

	@Override
	public String name()
	{
		return NAME;
	}

	@Override
	public Support isSupported(GpuCaps caps)
	{
		return Support.ok();
	}

	@Override
	public void resize(Size render, Size display)
	{
		// no internal resources
	}

	@Override
	public void resetHistory()
	{
		// spatial: no history
	}

	@Override
	public void evaluate(FrameInputs in, Texture out)
	{
		Texture color = in.color;
		glBindFramebuffer(GL_READ_FRAMEBUFFER, color.fbo);
		glBindFramebuffer(GL_DRAW_FRAMEBUFFER, out.fbo);
		glBlitFramebuffer(0, 0, color.width, color.height, 0, 0, out.width, out.height,
			GL_COLOR_BUFFER_BIT, GL_LINEAR);
	}

	@Override
	public Vec2 jitter(int frameIndex)
	{
		return Vec2.ZERO;
	}

	@Override
	public float mipBias(Size render, Size display)
	{
		return 0f; // S1-12: spatial backends default to 0
	}

	@Override
	public void close()
	{
		// nothing to free
	}
}

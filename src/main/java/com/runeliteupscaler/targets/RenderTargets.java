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
package com.runeliteupscaler.targets;

import static org.lwjgl.opengl.GL33C.*;

/**
 * A-02: owns the off-screen render targets: creation, resize and deletion.
 * <p>
 * S1-02: the scene target is sized to render resolution (A-03), not to the window. It has a
 * color and a depth renderbuffer, multisampled when MSAA is on (same formats as the stock GPU
 * plugin's scene FBO). S1-04: when MSAA is on, a single-sample color texture at render
 * resolution holds the resolved scene for the upscale pass.
 * <p>
 * All methods must be called on the thread that owns the GL context.
 */
public final class RenderTargets implements AutoCloseable
{
	/** Scene color format. Kept as the stock GPU plugin's GL_RGBA for parity; S1-03 makes it configurable. */
	private static final int SCENE_COLOR_FORMAT = GL_RGBA;
	private static final int SCENE_DEPTH_FORMAT = GL_DEPTH_COMPONENT32F;
	private static final int RESOLVE_COLOR_FORMAT = GL_RGBA8;

	private int width;
	private int height;
	private int samples;

	private int sceneFbo;
	private int sceneColorRbo;
	private int sceneDepthRbo;

	private int resolveFbo;
	private int resolveColorTex;

	/**
	 * A-02: make sure the targets exist with this size and sample count, recreating them if not.
	 * Leaves GL_FRAMEBUFFER and GL_RENDERBUFFER bindings changed; the caller rebinds what it needs.
	 *
	 * @return true if the targets were (re)created
	 */
	public boolean ensure(int width, int height, int samples)
	{
		if (sceneFbo != 0 && width == this.width && height == this.height && samples == this.samples)
		{
			return false;
		}

		close();

		this.width = width;
		this.height = height;
		this.samples = samples;

		if (samples > 0)
		{
			glEnable(GL_MULTISAMPLE);
		}
		else
		{
			glDisable(GL_MULTISAMPLE);
		}

		sceneFbo = glGenFramebuffers();
		glBindFramebuffer(GL_FRAMEBUFFER, sceneFbo);

		sceneColorRbo = glGenRenderbuffers();
		glBindRenderbuffer(GL_RENDERBUFFER, sceneColorRbo);
		glRenderbufferStorageMultisample(GL_RENDERBUFFER, samples, SCENE_COLOR_FORMAT, width, height);
		glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, sceneColorRbo);

		sceneDepthRbo = glGenRenderbuffers();
		glBindRenderbuffer(GL_RENDERBUFFER, sceneDepthRbo);
		glRenderbufferStorageMultisample(GL_RENDERBUFFER, samples, SCENE_DEPTH_FORMAT, width, height);
		glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, sceneDepthRbo);

		checkComplete("scene");

		if (samples > 0)
		{
			resolveColorTex = glGenTextures();
			glBindTexture(GL_TEXTURE_2D, resolveColorTex);
			glTexImage2D(GL_TEXTURE_2D, 0, RESOLVE_COLOR_FORMAT, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, 0);
			glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
			glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
			glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
			glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
			glBindTexture(GL_TEXTURE_2D, 0);

			resolveFbo = glGenFramebuffers();
			glBindFramebuffer(GL_FRAMEBUFFER, resolveFbo);
			glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, resolveColorTex, 0);

			checkComplete("resolve");
		}

		glBindRenderbuffer(GL_RENDERBUFFER, 0);
		return true;
	}

	/**
	 * S1-04: a single-sample framebuffer holding the scene color at render resolution.
	 * With MSAA on, resolves the multisampled scene into it first. Leaves the read and draw
	 * framebuffer bindings changed.
	 */
	public int resolvedColorFbo()
	{
		if (samples == 0)
		{
			return sceneFbo;
		}

		glBindFramebuffer(GL_READ_FRAMEBUFFER, sceneFbo);
		glBindFramebuffer(GL_DRAW_FRAMEBUFFER, resolveFbo);
		glBlitFramebuffer(0, 0, width, height, 0, 0, width, height, GL_COLOR_BUFFER_BIT, GL_NEAREST);
		return resolveFbo;
	}

	public int sceneFbo()
	{
		return sceneFbo;
	}

	public int width()
	{
		return width;
	}

	public int height()
	{
		return height;
	}

	public int samples()
	{
		return samples;
	}

	/** A-02, G-11: delete every GL object this class owns. Safe to call more than once. */
	@Override
	public void close()
	{
		if (resolveFbo != 0)
		{
			glDeleteFramebuffers(resolveFbo);
			resolveFbo = 0;
		}
		if (resolveColorTex != 0)
		{
			glDeleteTextures(resolveColorTex);
			resolveColorTex = 0;
		}
		if (sceneFbo != 0)
		{
			glDeleteFramebuffers(sceneFbo);
			sceneFbo = 0;
		}
		if (sceneColorRbo != 0)
		{
			glDeleteRenderbuffers(sceneColorRbo);
			sceneColorRbo = 0;
		}
		if (sceneDepthRbo != 0)
		{
			glDeleteRenderbuffers(sceneDepthRbo);
			sceneDepthRbo = 0;
		}
		width = height = samples = 0;
	}

	private static void checkComplete(String name)
	{
		int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
		if (status != GL_FRAMEBUFFER_COMPLETE)
		{
			throw new RuntimeException(name + " FBO is incomplete. status: " + status);
		}
	}
}

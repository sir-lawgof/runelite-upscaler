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
 * <ul>
 * <li>S1-02: the scene target (color + depth renderbuffers, multisampled when MSAA is on, same formats
 * as the stock GPU plugin's scene FBO) at render resolution (A-03).</li>
 * <li>S1-05, A-05: when render and display sizes differ, a single-sample RGBA8 color texture at render
 * resolution (the upscaler input; resolves MSAA, S1-04) and an RGBA8 output texture at display
 * resolution (the upscaler output).</li>
 * </ul>
 * All methods must be called on the thread that owns the GL context.
 */
public final class RenderTargets implements AutoCloseable
{
	/** Scene color format. Kept as the stock GPU plugin's GL_RGBA for parity; S1-03 makes it configurable. */
	private static final int SCENE_COLOR_FORMAT = GL_RGBA;
	private static final int SCENE_DEPTH_FORMAT = GL_DEPTH_COMPONENT32F;
	/** S1-03: RGBA8 for Step 1 */
	private static final int COLOR_FORMAT = GL_RGBA8;

	private int renderWidth;
	private int renderHeight;
	private int displayWidth;
	private int displayHeight;
	private int samples;

	private int sceneFbo;
	private int sceneColorRbo;
	private int sceneDepthRbo;

	/** Render-res single-sample scene color; null at native size. */
	private Texture color;
	/** Display-res upscaler output; null at native size. */
	private Texture output;

	/**
	 * A-02: make sure the targets exist for these sizes and sample count, recreating them if not.
	 * Leaves GL_FRAMEBUFFER, GL_RENDERBUFFER and GL_TEXTURE_2D bindings changed; the caller rebinds.
	 *
	 * @return true if the targets were (re)created
	 */
	public boolean ensure(int renderWidth, int renderHeight, int displayWidth, int displayHeight, int samples)
	{
		if (sceneFbo != 0
			&& renderWidth == this.renderWidth && renderHeight == this.renderHeight
			&& displayWidth == this.displayWidth && displayHeight == this.displayHeight
			&& samples == this.samples)
		{
			return false;
		}

		close();

		this.renderWidth = renderWidth;
		this.renderHeight = renderHeight;
		this.displayWidth = displayWidth;
		this.displayHeight = displayHeight;
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
		glRenderbufferStorageMultisample(GL_RENDERBUFFER, samples, SCENE_COLOR_FORMAT, renderWidth, renderHeight);
		glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, sceneColorRbo);

		sceneDepthRbo = glGenRenderbuffers();
		glBindRenderbuffer(GL_RENDERBUFFER, sceneDepthRbo);
		glRenderbufferStorageMultisample(GL_RENDERBUFFER, samples, SCENE_DEPTH_FORMAT, renderWidth, renderHeight);
		glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, sceneDepthRbo);

		checkComplete("scene");
		glBindRenderbuffer(GL_RENDERBUFFER, 0);

		if (isScaled())
		{
			color = createTexture("color", renderWidth, renderHeight);
			output = createTexture("output", displayWidth, displayHeight);
		}

		return true;
	}

	/** True when render and display sizes differ, so the upscale pass runs. */
	public boolean isScaled()
	{
		return renderWidth != displayWidth || renderHeight != displayHeight;
	}

	/**
	 * S1-05, S1-04: copy the scene color into the render-res color texture (resolving MSAA) and return it,
	 * ready for the upscaler. Only valid when {@link #isScaled()}. Leaves read/draw framebuffers changed.
	 */
	public Texture resolveColor()
	{
		glBindFramebuffer(GL_READ_FRAMEBUFFER, sceneFbo);
		glBindFramebuffer(GL_DRAW_FRAMEBUFFER, color.fbo);
		glBlitFramebuffer(0, 0, renderWidth, renderHeight, 0, 0, renderWidth, renderHeight, GL_COLOR_BUFFER_BIT, GL_NEAREST);
		return color;
	}

	/** S1-05: the display-res upscaler output. Only valid when {@link #isScaled()}. */
	public Texture output()
	{
		return output;
	}

	public int sceneFbo()
	{
		return sceneFbo;
	}

	public int renderWidth()
	{
		return renderWidth;
	}

	public int renderHeight()
	{
		return renderHeight;
	}

	public int displayWidth()
	{
		return displayWidth;
	}

	public int displayHeight()
	{
		return displayHeight;
	}

	public int samples()
	{
		return samples;
	}

	/** A-02, G-11: delete every GL object this class owns. Safe to call more than once. */
	@Override
	public void close()
	{
		deleteTexture(color);
		color = null;
		deleteTexture(output);
		output = null;

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
		renderWidth = renderHeight = displayWidth = displayHeight = samples = 0;
	}

	private static Texture createTexture(String name, int width, int height)
	{
		int tex = glGenTextures();
		glBindTexture(GL_TEXTURE_2D, tex);
		glTexImage2D(GL_TEXTURE_2D, 0, COLOR_FORMAT, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, 0);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
		glBindTexture(GL_TEXTURE_2D, 0);

		int fbo = glGenFramebuffers();
		glBindFramebuffer(GL_FRAMEBUFFER, fbo);
		glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tex, 0);
		checkComplete(name);

		return new Texture(tex, fbo, width, height);
	}

	private static void deleteTexture(Texture t)
	{
		if (t != null)
		{
			glDeleteFramebuffers(t.fbo);
			glDeleteTextures(t.id);
		}
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

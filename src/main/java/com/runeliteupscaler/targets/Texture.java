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
 * A-02: a single-sample 2D color texture with a framebuffer that has it as GL_COLOR_ATTACHMENT0 (for blits,
 * or for rendering into it). Whoever calls {@link #create} owns it and must {@link #delete} it (A-07).
 */
public final class Texture
{
	/** GL texture name */
	public final int id;
	/** GL framebuffer name with this texture attached as color 0 */
	public final int fbo;
	public final int width;
	public final int height;
	/** S1-03 */
	public final TargetFormat format;

	private Texture(int id, int fbo, int width, int height, TargetFormat format)
	{
		this.id = id;
		this.fbo = fbo;
		this.width = width;
		this.height = height;
		this.format = format;
	}

	/**
	 * Allocate a texture (linear filtering, clamp to edge) and its framebuffer.
	 * Leaves GL_FRAMEBUFFER bound to the new framebuffer and GL_TEXTURE_2D unbound.
	 */
	public static Texture create(String name, int width, int height, TargetFormat format)
	{
		int tex = glGenTextures();
		glBindTexture(GL_TEXTURE_2D, tex);
		glTexImage2D(GL_TEXTURE_2D, 0, format.textureInternalFormat, width, height, 0, GL_RGBA, format.allocationType(), 0);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
		glBindTexture(GL_TEXTURE_2D, 0);

		int fbo = glGenFramebuffers();
		glBindFramebuffer(GL_FRAMEBUFFER, fbo);
		glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tex, 0);

		Texture t = new Texture(tex, fbo, width, height, format);
		int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
		if (status != GL_FRAMEBUFFER_COMPLETE)
		{
			t.delete();
			throw new RuntimeException(name + " FBO is incomplete. status: " + status);
		}
		return t;
	}

	/** A-07, G-11 */
	public void delete()
	{
		glDeleteFramebuffers(fbo);
		glDeleteTextures(id);
	}

	public Size size()
	{
		return new Size(width, height);
	}
}

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
 * S1-03: color format of the render targets, chosen in one place ({@link RenderTargets}).
 * RGBA8 for Step 1; RGBA16F behind a development flag, needed for Step 2.
 */
public enum TargetFormat
{
	/** Scene renderbuffer keeps the stock GPU plugin's GL_RGBA for parity (G-30); textures are GL_RGBA8. */
	RGBA8("RGBA8", GL_RGBA, GL_RGBA8),
	RGBA16F("RGBA16F", GL_RGBA16F, GL_RGBA16F),
	/** S2-06: two-channel half float, for motion vectors (textures only) */
	RG16F("RG16F", GL_RG16F, GL_RG16F);

	private final String label;
	/** internal format of the (multisampled) scene color renderbuffer */
	public final int sceneInternalFormat;
	/** internal format of single-sample color textures */
	public final int textureInternalFormat;

	TargetFormat(String label, int sceneInternalFormat, int textureInternalFormat)
	{
		this.label = label;
		this.sceneInternalFormat = sceneInternalFormat;
		this.textureInternalFormat = textureInternalFormat;
	}

	/** Pixel type to pass to glTexImage2D when allocating storage with no data. */
	public int allocationType()
	{
		return this == RGBA8 ? GL_UNSIGNED_BYTE : GL_HALF_FLOAT;
	}

	@Override
	public String toString()
	{
		return label;
	}
}

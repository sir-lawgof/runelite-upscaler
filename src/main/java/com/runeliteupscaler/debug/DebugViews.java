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
package com.runeliteupscaler.debug;

import com.runeliteupscaler.renderer.template.Template;
import com.runeliteupscaler.upscale.GlProgram;
import lombok.extern.slf4j.Slf4j;
import static org.lwjgl.opengl.GL33C.*;

/**
 * G-32: draws debug views of intermediate buffers into the 3D viewport rectangle on the window.
 * Shaders compile on first use; if they fail, the view is skipped and the normal image is shown (G-13).
 * All methods must be called on the thread that owns the GL context.
 */
@Slf4j
public final class DebugViews implements AutoCloseable
{
	private final Template template = new Template().addInclude(DebugViews.class);

	private int depthProgram;
	private int uniDepthTex;
	private int uniRect;
	private int uniZeroToOne;
	private int uniMaxZ;
	private int vao;
	private boolean failed;

	/**
	 * S2-02, G-32: draw the depth texture as greyscale into the window's current draw framebuffer.
	 *
	 * @param x,y,width,height the 3D viewport rectangle on the window, in device pixels (GL origin bottom-left)
	 * @return false if the view couldn't be drawn (show the normal image instead)
	 */
	public boolean drawDepth(int depthTex, int x, int y, int width, int height, boolean zeroToOne, float maxZ)
	{
		if (!ensureDepthProgram() || depthTex == 0)
		{
			return false;
		}

		glDisable(GL_BLEND);
		glDisable(GL_DEPTH_TEST);
		glDisable(GL_CULL_FACE);
		glViewport(x, y, width, height);
		glUseProgram(depthProgram);
		glUniform1i(uniDepthTex, 0);
		glUniform4f(uniRect, x, y, width, height);
		glUniform1i(uniZeroToOne, zeroToOne ? 1 : 0);
		glUniform1f(uniMaxZ, maxZ);
		glActiveTexture(GL_TEXTURE0);
		glBindTexture(GL_TEXTURE_2D, depthTex);
		glBindVertexArray(vao);
		glDrawArrays(GL_TRIANGLES, 0, 3);
		glBindVertexArray(0);
		glBindTexture(GL_TEXTURE_2D, 0);
		glUseProgram(0);
		return true;
	}

	private boolean ensureDepthProgram()
	{
		if (depthProgram != 0)
		{
			return true;
		}
		if (failed)
		{
			return false;
		}
		try
		{
			depthProgram = GlProgram.compile(template, "fullscreen_vert.glsl", "depth_view_frag.glsl");
		}
		catch (RuntimeException ex)
		{
			failed = true;
			log.warn("Depth debug view unavailable: {}", ex.getMessage());
			return false;
		}
		uniDepthTex = glGetUniformLocation(depthProgram, "depthTex");
		uniRect = glGetUniformLocation(depthProgram, "rect");
		uniZeroToOne = glGetUniformLocation(depthProgram, "zeroToOne");
		uniMaxZ = glGetUniformLocation(depthProgram, "maxZ");
		vao = glGenVertexArrays();
		GlObjects.created(GlObjects.Type.VERTEX_ARRAY, 1);
		return true;
	}

	/** A-07, G-11 */
	@Override
	public void close()
	{
		if (depthProgram != 0)
		{
			glDeleteProgram(depthProgram);
			GlObjects.deleted(GlObjects.Type.PROGRAM, 1);
			depthProgram = 0;
		}
		if (vao != 0)
		{
			glDeleteVertexArrays(vao);
			GlObjects.deleted(GlObjects.Type.VERTEX_ARRAY, 1);
			vao = 0;
		}
		failed = false;
	}
}

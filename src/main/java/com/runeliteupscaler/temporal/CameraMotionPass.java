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
package com.runeliteupscaler.temporal;

import com.runeliteupscaler.debug.GlObjects;
import com.runeliteupscaler.renderer.template.Template;
import com.runeliteupscaler.targets.Texture;
import com.runeliteupscaler.upscale.GlProgram;
import lombok.extern.slf4j.Slf4j;
import static org.lwjgl.opengl.GL33C.*;

/**
 * S2-06: full-screen pass writing camera motion vectors (pixels, current to previous) into an RG16F
 * texture at render resolution, from the depth texture and {@link Reprojection#currentToPrevious}.
 * Static scenery only: animated models get their own motion in Step 3. If the shader fails to compile,
 * the pass is skipped and logged (G-13). All methods must be called on the GL thread.
 */
@Slf4j
public final class CameraMotionPass implements AutoCloseable
{
	private final Template template = new Template().addInclude(CameraMotionPass.class);

	private int program;
	private int vao;
	private int uniDepthTex;
	private int uniCurrentToPrevious;
	private int uniRenderSize;
	private int uniZeroToOne;
	private boolean failed;

	/**
	 * @param currentToPrevious column-major, from {@link Reprojection#currentToPrevious}
	 * @return false if the pass couldn't run
	 */
	public boolean execute(int depthTex, Texture motion, float[] currentToPrevious, boolean zeroToOne)
	{
		if (!ensureProgram())
		{
			return false;
		}

		glDisable(GL_BLEND);
		glDisable(GL_DEPTH_TEST);
		glDisable(GL_CULL_FACE);
		glBindFramebuffer(GL_DRAW_FRAMEBUFFER, motion.fbo);
		glViewport(0, 0, motion.width, motion.height);
		glUseProgram(program);
		glUniform1i(uniDepthTex, 0);
		glUniformMatrix4fv(uniCurrentToPrevious, false, currentToPrevious);
		glUniform2f(uniRenderSize, motion.width, motion.height);
		glUniform1i(uniZeroToOne, zeroToOne ? 1 : 0);
		glActiveTexture(GL_TEXTURE0);
		glBindTexture(GL_TEXTURE_2D, depthTex);
		glBindVertexArray(vao);
		glDrawArrays(GL_TRIANGLES, 0, 3);
		glBindVertexArray(0);
		glBindTexture(GL_TEXTURE_2D, 0);
		glUseProgram(0);
		return true;
	}

	private boolean ensureProgram()
	{
		if (program != 0)
		{
			return true;
		}
		if (failed)
		{
			return false;
		}
		try
		{
			program = GlProgram.compile(template, "/com/runeliteupscaler/debug/fullscreen_vert.glsl", "camera_motion_frag.glsl");
		}
		catch (RuntimeException ex)
		{
			failed = true;
			log.warn("Camera motion pass unavailable: {}", ex.getMessage());
			return false;
		}
		uniDepthTex = glGetUniformLocation(program, "depthTex");
		uniCurrentToPrevious = glGetUniformLocation(program, "currentToPrevious");
		uniRenderSize = glGetUniformLocation(program, "renderSize");
		uniZeroToOne = glGetUniformLocation(program, "zeroToOne");
		vao = glGenVertexArrays();
		GlObjects.created(GlObjects.Type.VERTEX_ARRAY, 1);
		return true;
	}

	/** A-07, G-11 */
	@Override
	public void close()
	{
		if (program != 0)
		{
			glDeleteProgram(program);
			GlObjects.deleted(GlObjects.Type.PROGRAM, 1);
			program = 0;
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

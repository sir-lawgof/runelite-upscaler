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

import com.runeliteupscaler.renderer.template.Template;
import com.runeliteupscaler.targets.Size;
import com.runeliteupscaler.targets.Texture;
import lombok.extern.slf4j.Slf4j;
import static org.lwjgl.opengl.GL33C.*;

/**
 * S1-06, S1-07, S1-08: AMD FidelityFX FSR 1 backend. EASU upscales from render to display resolution;
 * RCAS then sharpens at display resolution (skipped when sharpness is 0%). Both are full-screen
 * fragment passes using the official header's GLSL path (AMD MIT notice kept, G-23).
 * Needs OpenGL 4.3 (Step 1's target; EASU uses textureGather).
 */
@Slf4j
public final class Fsr1Upscaler implements Upscaler
{
	public static final String NAME = "FSR 1";

	private final Template template = new Template().addInclude(Fsr1Upscaler.class);

	private int easuProgram;
	private int rcasProgram;
	private int vao;
	private String compileError;

	private int uniEasuInput;
	private final int[] uniEasuCon = new int[4];
	private int uniRcasInput;
	private int uniRcasCon;

	/** Display-res EASU output that RCAS reads (A-07: owned and freed here). */
	private Texture intermediate;
	private int[][] easuCon;

	@Override
	public String name()
	{
		return NAME;
	}

	/**
	 * A-04: needs OpenGL 4.3 and shaders that compile on this driver. Compiling here means a driver that
	 * rejects the shaders makes the chain fall back to bilinear with the compiler's message (G-13).
	 */
	@Override
	public Support isSupported(GpuCaps caps)
	{
		if (!caps.openGL43)
		{
			return Support.unsupported("needs OpenGL 4.3, have " + caps.version);
		}
		if (compileError == null && easuProgram == 0)
		{
			try
			{
				compile();
			}
			catch (RuntimeException ex)
			{
				compileError = ex.getMessage();
				log.warn("FSR 1 shaders failed to compile: {}", compileError);
				deletePrograms();
			}
		}
		return compileError == null ? Support.ok() : Support.unsupported("shader compile failed: " + compileError);
	}

	private void compile()
	{
		easuProgram = GlProgram.compile(template, "fsr1/fullscreen_vert.glsl", "fsr1/easu_frag.glsl");
		uniEasuInput = glGetUniformLocation(easuProgram, "inputTex");
		for (int i = 0; i < 4; i++)
		{
			uniEasuCon[i] = glGetUniformLocation(easuProgram, "con" + i);
		}

		rcasProgram = GlProgram.compile(template, "fsr1/fullscreen_vert.glsl", "fsr1/rcas_frag.glsl");
		uniRcasInput = glGetUniformLocation(rcasProgram, "inputTex");
		uniRcasCon = glGetUniformLocation(rcasProgram, "con");

		// Core profile needs a bound VAO to draw, even with no vertex attributes
		vao = glGenVertexArrays();
	}

	@Override
	public void resize(Size render, Size display)
	{
		deleteIntermediate();

		int tex = glGenTextures();
		glBindTexture(GL_TEXTURE_2D, tex);
		glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, display.width, display.height, 0, GL_RGBA, GL_UNSIGNED_BYTE, 0);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
		glBindTexture(GL_TEXTURE_2D, 0);

		int fbo = glGenFramebuffers();
		glBindFramebuffer(GL_FRAMEBUFFER, fbo);
		glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tex, 0);
		int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
		intermediate = new Texture(tex, fbo, display.width, display.height);
		if (status != GL_FRAMEBUFFER_COMPLETE)
		{
			throw new IllegalStateException("FSR 1 intermediate FBO incomplete: " + status);
		}

		easuCon = Fsr1Constants.easu(render.width, render.height, display.width, display.height);
	}

	@Override
	public void resetHistory()
	{
		// spatial: no history
	}

	@Override
	public void evaluate(FrameInputs in, Texture out)
	{
		if (intermediate == null || intermediate.width != out.width || intermediate.height != out.height
			|| easuCon == null)
		{
			resize(in.color.size(), out.size());
		}

		final int sharpnessPercent = Math.round(in.sharpness * 100f);
		final boolean rcas = Fsr1Constants.rcasEnabled(sharpnessPercent);

		glDisable(GL_BLEND);
		glDisable(GL_DEPTH_TEST);
		glDisable(GL_CULL_FACE);
		glActiveTexture(GL_TEXTURE0);
		glBindVertexArray(vao);
		glViewport(0, 0, out.width, out.height);

		// S1-07: EASU at display resolution, reading the render-res color
		glBindFramebuffer(GL_DRAW_FRAMEBUFFER, rcas ? intermediate.fbo : out.fbo);
		glUseProgram(easuProgram);
		glUniform1i(uniEasuInput, 0);
		for (int i = 0; i < 4; i++)
		{
			int[] c = easuCon[i];
			glUniform4ui(uniEasuCon[i], c[0], c[1], c[2], c[3]);
		}
		glBindTexture(GL_TEXTURE_2D, in.color.id);
		glDrawArrays(GL_TRIANGLES, 0, 3);

		// S1-07, S1-08: RCAS after EASU at display resolution
		if (rcas)
		{
			int[] c = Fsr1Constants.rcas(Fsr1Constants.sharpnessToStops(sharpnessPercent));
			glBindFramebuffer(GL_DRAW_FRAMEBUFFER, out.fbo);
			glUseProgram(rcasProgram);
			glUniform1i(uniRcasInput, 0);
			glUniform4ui(uniRcasCon, c[0], c[1], c[2], c[3]);
			glBindTexture(GL_TEXTURE_2D, intermediate.id);
			glDrawArrays(GL_TRIANGLES, 0, 3);
		}

		glBindTexture(GL_TEXTURE_2D, 0);
		glBindVertexArray(0);
		glUseProgram(0);
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

	/** A-07, G-11 */
	@Override
	public void close()
	{
		deleteIntermediate();
		deletePrograms();
		if (vao != 0)
		{
			glDeleteVertexArrays(vao);
			vao = 0;
		}
		easuCon = null;
		compileError = null;
	}

	private void deletePrograms()
	{
		if (easuProgram != 0)
		{
			glDeleteProgram(easuProgram);
			easuProgram = 0;
		}
		if (rcasProgram != 0)
		{
			glDeleteProgram(rcasProgram);
			rcasProgram = 0;
		}
	}

	private void deleteIntermediate()
	{
		if (intermediate != null)
		{
			glDeleteFramebuffers(intermediate.fbo);
			glDeleteTextures(intermediate.id);
			intermediate = null;
		}
	}
}

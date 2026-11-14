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
import static org.lwjgl.opengl.GL33C.*;

/**
 * G-04: compiles a vertex + fragment program from GLSL resources through the GPU plugin's {@link Template}
 * loader (same #include handling as the plugin's own shaders).
 */
final class GlProgram
{
	private GlProgram()
	{
	}

	/**
	 * @throws IllegalStateException with the driver's log if compiling or linking fails
	 */
	static int compile(Template template, String vertexFile, String fragmentFile)
	{
		int vs = compileShader(template, GL_VERTEX_SHADER, vertexFile);
		int fs;
		try
		{
			fs = compileShader(template, GL_FRAGMENT_SHADER, fragmentFile);
		}
		catch (RuntimeException ex)
		{
			glDeleteShader(vs);
			throw ex;
		}

		int program = glCreateProgram();
		glAttachShader(program, vs);
		glAttachShader(program, fs);
		glLinkProgram(program);
		glDetachShader(program, vs);
		glDetachShader(program, fs);
		glDeleteShader(vs);
		glDeleteShader(fs);

		if (glGetProgrami(program, GL_LINK_STATUS) != GL_TRUE)
		{
			String err = glGetProgramInfoLog(program);
			glDeleteProgram(program);
			throw new IllegalStateException("link failed (" + fragmentFile + "): " + err.trim());
		}
		return program;
	}

	private static int compileShader(Template template, int type, String file)
	{
		String source = template.load(file);
		if (source.isEmpty())
		{
			throw new IllegalStateException("shader not found: " + file);
		}

		int shader = glCreateShader(type);
		glShaderSource(shader, source);
		glCompileShader(shader);
		if (glGetShaderi(shader, GL_COMPILE_STATUS) != GL_TRUE)
		{
			String err = glGetShaderInfoLog(shader);
			glDeleteShader(shader);
			throw new IllegalStateException("compile failed (" + file + "): " + err.trim());
		}
		return shader;
	}
}

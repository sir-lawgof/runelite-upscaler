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

/**
 * S1-15, G-11: counts GL objects created and deleted by this plugin's own code (render targets, upscaler
 * backends, timer queries). After every resize the count should settle back to the same value, and after
 * shutdown it should be 0; a count that keeps climbing is a leak. Objects created by the forked GPU
 * plugin's renderer code are not counted (GPU memory in the overlay covers those).
 */
public final class GlObjects
{
	public enum Type
	{
		TEXTURE("tex"),
		FRAMEBUFFER("fbo"),
		RENDERBUFFER("rb"),
		PROGRAM("prog"),
		VERTEX_ARRAY("vao"),
		QUERY("query");

		final String label;

		Type(String label)
		{
			this.label = label;
		}
	}

	private static final int[] LIVE = new int[Type.values().length];

	private GlObjects()
	{
	}

	public static synchronized void created(Type type, int count)
	{
		LIVE[type.ordinal()] += count;
	}

	public static synchronized void deleted(Type type, int count)
	{
		LIVE[type.ordinal()] -= count;
	}

	public static synchronized int total()
	{
		int sum = 0;
		for (int n : LIVE)
		{
			sum += n;
		}
		return sum;
	}

	/** e.g. "tex 3, fbo 4, rb 2, prog 2, vao 1"; types with 0 live objects are left out */
	public static synchronized String breakdown()
	{
		StringBuilder sb = new StringBuilder();
		for (Type t : Type.values())
		{
			int n = LIVE[t.ordinal()];
			if (n != 0)
			{
				if (sb.length() > 0)
				{
					sb.append(", ");
				}
				sb.append(t.label).append(' ').append(n);
			}
		}
		return sb.length() == 0 ? "none" : sb.toString();
	}
}

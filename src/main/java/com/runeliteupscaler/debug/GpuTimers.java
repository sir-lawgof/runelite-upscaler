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

import java.util.Arrays;

import static org.lwjgl.opengl.GL33C.*;

/**
 * A-06, G-31: GPU time per frame stage, from GL timestamp queries (glQueryCounter, core in GL 3.3).
 * <p>
 * Results are read {@link #FRAMES} frames later from a ring of query objects, only when the driver reports
 * them available, so the CPU never waits on the GPU. Values are smoothed and published to {@link RenderStats}.
 * Disabled (no GL calls at all) unless turned on, so normal rendering is unaffected (G-30).
 * All methods must be called on the thread that owns the GL context.
 */
public final class GpuTimers implements AutoCloseable
{
	public enum Stage
	{
		SCENE("Scene"),
		UPSCALE("Upscale"),
		PRESENT("Present"),
		UI("UI");

		public final String label;

		Stage(String label)
		{
			this.label = label;
		}
	}

	/** Ring depth: results are read this many frames after they were issued. */
	static final int FRAMES = 4;
	/** Exponential smoothing factor for displayed values. */
	private static final double SMOOTHING = 0.1;

	private static final int STAGES = Stage.values().length;

	private final RenderStats stats;
	private final int[][] startQuery = new int[FRAMES][STAGES];
	private final int[][] endQuery = new int[FRAMES][STAGES];
	/** 0 = not issued this frame, 1 = begun, 2 = begun and ended */
	private final int[][] state = new int[FRAMES][STAGES];
	private final double[] smoothedMs = new double[STAGES];
	private boolean created;
	private boolean enabled;
	private int frame;

	public GpuTimers(RenderStats stats)
	{
		this.stats = stats;
	}

	/** Turn timing on or off. Turning it on starts fresh (clears old results). */
	public void setEnabled(boolean enabled)
	{
		if (enabled == this.enabled)
		{
			return;
		}
		this.enabled = enabled;
		if (enabled)
		{
			if (!created)
			{
				for (int f = 0; f < FRAMES; f++)
				{
					glGenQueries(startQuery[f]);
					glGenQueries(endQuery[f]);
				}
				created = true;
			}
			for (int f = 0; f < FRAMES; f++)
			{
				Arrays.fill(state[f], 0);
			}
			Arrays.fill(smoothedMs, -1);
			publish();
		}
		else
		{
			stats.gpuMs = null;
		}
	}

	public void begin(Stage stage)
	{
		if (!enabled)
		{
			return;
		}
		glQueryCounter(startQuery[frame][stage.ordinal()], GL_TIMESTAMP);
		state[frame][stage.ordinal()] = 1;
	}

	public void end(Stage stage)
	{
		if (!enabled || state[frame][stage.ordinal()] != 1)
		{
			return;
		}
		glQueryCounter(endQuery[frame][stage.ordinal()], GL_TIMESTAMP);
		state[frame][stage.ordinal()] = 2;
	}

	/**
	 * Call once per frame after the last stage. Advances the ring and reads the results that were issued
	 * {@link #FRAMES} frames ago, if the GPU has finished them.
	 */
	public void endFrame()
	{
		if (!enabled)
		{
			return;
		}

		frame = (frame + 1) % FRAMES;

		// This slot is about to be reused: collect what it measured FRAMES frames ago
		for (int s = 0; s < STAGES; s++)
		{
			if (state[frame][s] == 2
				&& glGetQueryObjecti(endQuery[frame][s], GL_QUERY_RESULT_AVAILABLE) == GL_TRUE)
			{
				long start = glGetQueryObjecti64(startQuery[frame][s], GL_QUERY_RESULT);
				long end = glGetQueryObjecti64(endQuery[frame][s], GL_QUERY_RESULT);
				double ms = (end - start) / 1_000_000.0;
				smoothedMs[s] = smoothedMs[s] < 0 ? ms : smoothedMs[s] + SMOOTHING * (ms - smoothedMs[s]);
			}
			else if (state[frame][s] == 0)
			{
				// Stage didn't run that frame (e.g. no upscale at 100%): don't keep showing an old value
				smoothedMs[s] = -1;
			}
			state[frame][s] = 0;
		}
		publish();
	}

	private void publish()
	{
		stats.gpuMs = smoothedMs.clone();
	}

	/** A-07, G-11 */
	@Override
	public void close()
	{
		if (created)
		{
			for (int f = 0; f < FRAMES; f++)
			{
				glDeleteQueries(startQuery[f]);
				glDeleteQueries(endQuery[f]);
			}
			created = false;
		}
		enabled = false;
		stats.gpuMs = null;
	}
}

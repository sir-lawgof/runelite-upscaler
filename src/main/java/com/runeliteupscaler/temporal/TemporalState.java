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

/**
 * S2-05, S2-12: per-frame temporal state. Keeps this frame's and the previous frame's unjittered
 * view-projection matrices (column-major, as uploaded to GL), the frame index and the frame time.
 * Jittered matrices are added with jitter (S2-03, S2-04).
 */
public final class TemporalState
{
	private final float[] viewProj = new float[16];
	private final float[] prevViewProj = new float[16];
	private boolean hasPrevious;
	private long frameIndex = -1;
	private long lastFrameNanos;
	private float frameTimeMs;

	/**
	 * Call once per scene frame with the unjittered view-projection used for the scene pass.
	 *
	 * @param nowNanos {@link System#nanoTime()} at the start of the frame
	 */
	public void beginFrame(float[] unjitteredViewProj, long nowNanos)
	{
		if (frameIndex >= 0)
		{
			System.arraycopy(viewProj, 0, prevViewProj, 0, 16);
			hasPrevious = true;
			frameTimeMs = (nowNanos - lastFrameNanos) / 1_000_000f;
		}
		System.arraycopy(unjitteredViewProj, 0, viewProj, 0, 16);
		lastFrameNanos = nowNanos;
		frameIndex++;
	}

	/** Forget history, e.g. when temporal inputs are turned off. The next frame has no previous matrix. */
	public void reset()
	{
		hasPrevious = false;
		frameIndex = -1;
		frameTimeMs = 0;
	}

	public float[] viewProj()
	{
		return viewProj;
	}

	/** This frame's matrix again on the first frame, so motion is zero rather than garbage. */
	public float[] prevViewProj()
	{
		return hasPrevious ? prevViewProj : viewProj;
	}

	public boolean hasPrevious()
	{
		return hasPrevious;
	}

	public long frameIndex()
	{
		return frameIndex;
	}

	public float frameTimeMs()
	{
		return frameTimeMs;
	}

	/** S2-05: matrices as text for the debug dump, one row per line. */
	public static String format(float[] m)
	{
		StringBuilder sb = new StringBuilder();
		for (int row = 0; row < 4; row++)
		{
			sb.append("  ");
			for (int col = 0; col < 4; col++)
			{
				sb.append(String.format("%12.5f", m[col * 4 + row]));
			}
			sb.append('\n');
		}
		return sb.toString();
	}
}

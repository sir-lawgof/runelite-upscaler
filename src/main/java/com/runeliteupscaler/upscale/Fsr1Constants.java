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

/**
 * S1-06, S1-08: CPU-side FSR 1 constant setup, ported from FsrEasuCon and FsrRcasCon in AMD's
 * ffx_fsr1.h (v1.20210629, MIT license, Copyright (c) 2021 Advanced Micro Devices, Inc.; G-23).
 * Constants are uint bit patterns of floats, uploaded with glUniform4ui.
 */
public final class Fsr1Constants
{
	/** S1-08: sharpness 1% maps to this many stops; 100% maps to 0 stops (sharpest). */
	public static final float MAX_STOPS = 2.0f;

	private Fsr1Constants()
	{
	}

	/**
	 * Port of FsrEasuCon. The input image fills its whole texture, so viewport size == texture size.
	 *
	 * @return {con0, con1, con2, con3}, each 4 uint bit patterns
	 */
	public static int[][] easu(float inputWidth, float inputHeight, float outputWidth, float outputHeight)
	{
		final float inViewX = inputWidth;
		final float inViewY = inputHeight;
		final float rcpInX = 1.0f / inputWidth;
		final float rcpInY = 1.0f / inputHeight;

		int[] con0 = {
			bits(inViewX * (1.0f / outputWidth)),
			bits(inViewY * (1.0f / outputHeight)),
			bits(0.5f * inViewX * (1.0f / outputWidth) - 0.5f),
			bits(0.5f * inViewY * (1.0f / outputHeight) - 0.5f),
		};
		int[] con1 = {
			bits(rcpInX),
			bits(rcpInY),
			bits(1.0f * rcpInX),
			bits(-1.0f * rcpInY),
		};
		int[] con2 = {
			bits(-1.0f * rcpInX),
			bits(2.0f * rcpInY),
			bits(1.0f * rcpInX),
			bits(2.0f * rcpInY),
		};
		int[] con3 = {
			bits(0.0f * rcpInX),
			bits(4.0f * rcpInY),
			0,
			0,
		};
		return new int[][]{con0, con1, con2, con3};
	}

	/**
	 * Port of FsrRcasCon (32-bit path; con[1], the packed half used by the FP16 path, is left 0).
	 *
	 * @param stops 0 = maximum sharpness, each +1 halves it
	 */
	public static int[] rcas(float stops)
	{
		float sharpness = (float) Math.pow(2.0, -stops);
		return new int[]{bits(sharpness), 0, 0, 0};
	}

	/**
	 * S1-08: sharpness slider (0-100%) to RCAS stops. 0% disables RCAS (returns NaN; check
	 * {@link #rcasEnabled}). 100% = 0 stops (sharpest), 1% = almost {@link #MAX_STOPS}.
	 */
	public static float sharpnessToStops(int percent)
	{
		if (!rcasEnabled(percent))
		{
			return Float.NaN;
		}
		int p = Math.min(100, percent);
		return MAX_STOPS * (100 - p) / 100f;
	}

	/** S1-08: 0% disables RCAS. */
	public static boolean rcasEnabled(int percent)
	{
		return percent > 0;
	}

	private static int bits(float f)
	{
		return Float.floatToRawIntBits(f);
	}
}

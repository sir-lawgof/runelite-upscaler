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

import com.runeliteupscaler.upscale.Vec2;

/**
 * S2-03, S2-04: sub-pixel jitter, matching AMD FSR 2's ffxFsr2GetJitterPhaseCount / ffxFsr2GetJitterOffset
 * (FidelityFX-FSR2@1680d1e, see 8.4). Offsets are in render-resolution pixels, -0.5 to 0.5, GL y-up.
 */
public final class Jitter
{
	private Jitter()
	{
	}

	/** Port of FSR 2's halton(index, base), same float arithmetic. */
	public static float halton(int index, int base)
	{
		float f = 1.0f;
		float result = 0.0f;
		for (int current = index; current > 0; )
		{
			f /= (float) base;
			result = result + f * (float) (current % base);
			current = (int) Math.floor((float) current / (float) base);
		}
		return result;
	}

	/** S2-03, D-09: int(8 * (display/render)^2), rounded down like ffxFsr2GetJitterPhaseCount; at least 1. */
	public static int phaseCount(int renderWidth, int displayWidth)
	{
		float ratio = (float) displayWidth / renderWidth;
		return Math.max(1, (int) (8.0f * (float) Math.pow(ratio, 2.0f)));
	}

	/** S2-03: jitter offset in pixels for this frame, like ffxFsr2GetJitterOffset. */
	public static Vec2 offset(long frameIndex, int phaseCount)
	{
		int i = (int) (frameIndex % phaseCount);
		return new Vec2(halton(i + 1, 2) - 0.5f, halton(i + 1, 3) - 0.5f);
	}

	/**
	 * S2-04: jittered copy of a column-major view-projection: translate(2*jx/w, 2*jy/h, 0) * m, so every
	 * projected point moves by (jx, jy) render pixels. GL y-up; a backend expecting y-down negates y when it
	 * reports the jitter (S2-07).
	 */
	public static float[] apply(float[] viewProj, Vec2 jitterPixels, int renderWidth, int renderHeight)
	{
		float jx = 2f * jitterPixels.x / renderWidth;
		float jy = 2f * jitterPixels.y / renderHeight;
		float[] m = viewProj.clone();
		for (int col = 0; col < 4; col++)
		{
			m[col * 4] += jx * m[col * 4 + 3];
			m[col * 4 + 1] += jy * m[col * 4 + 3];
		}
		return m;
	}

	/**
	 * S2-09: texture LOD bias for temporal backends, log2(render / display) - 1 (FSR 2 README).
	 * At native size (DLAA) this gives -1, per AMD's formula.
	 */
	public static float temporalMipBias(int renderWidth, int displayWidth)
	{
		return (float) (Math.log((double) renderWidth / displayWidth) / Math.log(2.0) - 1.0);
	}
}

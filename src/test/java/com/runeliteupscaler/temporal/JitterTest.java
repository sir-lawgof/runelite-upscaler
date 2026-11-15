package com.runeliteupscaler.temporal;

import com.runeliteupscaler.upscale.Vec2;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * T-U2: Halton(2,3) values and phase count formula (S2-03, D-09); jitter in the projection (S2-04).
 * T-U3: mip bias formula (S2-09).
 */
public class JitterTest
{
	private static final float EPS = 1e-6f;

	@Test
	public void tU2_haltonBase2And3()
	{
		assertEquals(0.5f, Jitter.halton(1, 2), EPS);
		assertEquals(0.25f, Jitter.halton(2, 2), EPS);
		assertEquals(0.75f, Jitter.halton(3, 2), EPS);
		assertEquals(0.125f, Jitter.halton(4, 2), EPS);
		assertEquals(1f / 3, Jitter.halton(1, 3), EPS);
		assertEquals(2f / 3, Jitter.halton(2, 3), EPS);
		assertEquals(1f / 9, Jitter.halton(3, 3), EPS);
		assertEquals(4f / 9, Jitter.halton(4, 3), EPS);
	}

	@Test
	public void tU2_phaseCountRoundsDownLikeAmd()
	{
		assertEquals(8, Jitter.phaseCount(1920, 1920));    // 100%
		assertEquals(32, Jitter.phaseCount(960, 1920));    // 50%
		assertEquals(17, Jitter.phaseCount(1286, 1920));   // 67%: 17.83 -> 17 (D-09; ceil would give 18)
		assertEquals(73, Jitter.phaseCount(634, 1920));    // 33%: 73.37 -> 73
		assertEquals(1, Jitter.phaseCount(1920, 1));       // never below 1
	}

	@Test
	public void tU2_offsetsInRangeAndCycle()
	{
		int n = Jitter.phaseCount(960, 1920);
		for (int i = 0; i < n; i++)
		{
			Vec2 j = Jitter.offset(i, n);
			assertTrue(j.x >= -0.5f && j.x < 0.5f);
			assertTrue(j.y >= -0.5f && j.y < 0.5f);
		}
		Vec2 first = Jitter.offset(0, n);
		Vec2 again = Jitter.offset(n, n);
		assertEquals(first.x, again.x, 0f);
		assertEquals(first.y, again.y, 0f);
		// frame 0 uses halton index 1: (0.5 - 0.5, 1/3 - 0.5)
		assertEquals(0f, first.x, EPS);
		assertEquals(1f / 3 - 0.5f, first.y, EPS);
	}

	@Test
	public void s2_04_jitterMovesProjectedPointsByThatManyPixels()
	{
		// Any view-projection with w != 0: use a simple perspective-like matrix (column-major)
		float[] vp = {
			1.2f, 0.1f, 0f, 0.3f,
			0.0f, -1.5f, 0f, 0.4f,
			0.2f, 0.3f, 0f, 0.8f,
			10f, -20f, 100f, 50f};
		int w = 1145, h = 685;
		Vec2 j = new Vec2(0.25f, -0.375f);
		float[] jittered = Jitter.apply(vp, j, w, h);

		float x = 30, y = 15, z = 70;
		float[] a = project(vp, x, y, z);
		float[] b = project(jittered, x, y, z);
		float dxPixels = (b[0] - a[0]) * 0.5f * w;
		float dyPixels = (b[1] - a[1]) * 0.5f * h;
		assertEquals(0.25f, dxPixels, 1e-3f);
		assertEquals(-0.375f, dyPixels, 1e-3f);
		// depth (z/w) unchanged
		assertEquals(a[2], b[2], 1e-6f);
	}

	private static float[] project(float[] m, float x, float y, float z)
	{
		float cx = m[0] * x + m[4] * y + m[8] * z + m[12];
		float cy = m[1] * x + m[5] * y + m[9] * z + m[13];
		float cz = m[2] * x + m[6] * y + m[10] * z + m[14];
		float cw = m[3] * x + m[7] * y + m[11] * z + m[15];
		return new float[]{cx / cw, cy / cw, cz / cw};
	}

	@Test
	public void tU3_temporalMipBias()
	{
		assertEquals(-2.0f, Jitter.temporalMipBias(960, 1920), 1e-5f);    // 50%
		assertEquals(-1.585f, Jitter.temporalMipBias(1280, 1920), 1e-3f); // Quality 1.5x (README table: -1.58)
		assertEquals(-1.0f, Jitter.temporalMipBias(1920, 1920), 1e-5f);   // native (DLAA)
	}
}

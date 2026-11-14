package com.runeliteupscaler.upscale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * S1-06, S1-08: FSR 1 constant setup (ports of FsrEasuCon / FsrRcasCon) and the sharpness mapping.
 */
public class Fsr1ConstantsTest
{
	private static final float EPS = 1e-7f;

	private static float f(int bits)
	{
		return Float.intBitsToFloat(bits);
	}

	@Test
	public void s1_06_easuConstantsFor50PercentAt1080p()
	{
		int[][] con = Fsr1Constants.easu(960, 540, 1920, 1080);

		// con0: output pixel -> input pixel scale and offset (pixel centers)
		assertEquals(0.5f, f(con[0][0]), EPS);
		assertEquals(0.5f, f(con[0][1]), EPS);
		assertEquals(-0.25f, f(con[0][2]), EPS);
		assertEquals(-0.25f, f(con[0][3]), EPS);

		// con1: 1/input size, then gather (0) offset (+1, -1) texels
		assertEquals(1f / 960, f(con[1][0]), EPS);
		assertEquals(1f / 540, f(con[1][1]), EPS);
		assertEquals(1f / 960, f(con[1][2]), EPS);
		assertEquals(-1f / 540, f(con[1][3]), EPS);

		// con2: gathers (1) and (2) from (0)
		assertEquals(-1f / 960, f(con[2][0]), EPS);
		assertEquals(2f / 540, f(con[2][1]), EPS);
		assertEquals(1f / 960, f(con[2][2]), EPS);
		assertEquals(2f / 540, f(con[2][3]), EPS);

		// con3: gather (3) from (0); last two unused
		assertEquals(0f, f(con[3][0]), EPS);
		assertEquals(4f / 540, f(con[3][1]), EPS);
		assertEquals(0, con[3][2]);
		assertEquals(0, con[3][3]);
	}

	@Test
	public void s1_06_easuAtNativeSizeIsIdentityMapping()
	{
		int[][] con = Fsr1Constants.easu(1920, 1080, 1920, 1080);
		assertEquals(1f, f(con[0][0]), EPS);
		assertEquals(0f, f(con[0][2]), EPS);
	}

	@Test
	public void s1_08_rcasStopsToLinear()
	{
		assertEquals(1.0f, f(Fsr1Constants.rcas(0f)[0]), EPS);
		assertEquals(0.5f, f(Fsr1Constants.rcas(1f)[0]), EPS);
		assertEquals(0.25f, f(Fsr1Constants.rcas(2f)[0]), EPS);
	}

	@Test
	public void s1_08_sliderMapsToStops()
	{
		assertEquals(0f, Fsr1Constants.sharpnessToStops(100), EPS);
		assertEquals(1f, Fsr1Constants.sharpnessToStops(50), EPS);
		assertEquals(0.4f, Fsr1Constants.sharpnessToStops(80), 1e-6f);
		assertEquals(1.98f, Fsr1Constants.sharpnessToStops(1), 1e-6f);
	}

	@Test
	public void s1_08_zeroPercentDisablesRcas()
	{
		assertFalse(Fsr1Constants.rcasEnabled(0));
		assertTrue(Fsr1Constants.rcasEnabled(1));
		assertTrue(Float.isNaN(Fsr1Constants.sharpnessToStops(0)));
	}
}

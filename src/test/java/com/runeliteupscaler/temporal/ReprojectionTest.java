package com.runeliteupscaler.temporal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import org.junit.Test;

/**
 * S2-06: camera motion reprojection math. Uses the two real view-projection matrices logged by
 * ::upscaler during the Step 2 part 1 test (8.4).
 */
public class ReprojectionTest
{
	private static final int W = 2289;
	private static final int H = 1370;

	/** Build a column-major float[16] from rows as printed by TemporalState.format. */
	private static float[] fromRows(double... rows)
	{
		float[] m = new float[16];
		for (int row = 0; row < 4; row++)
		{
			for (int col = 0; col < 4; col++)
			{
				m[col * 4 + row] = (float) rows[row * 4 + col];
			}
		}
		return m;
	}

	// Logged frame 1745
	private static final float[] VP_A = fromRows(
		1.31939, 0.00000, -0.73532, -4282.09082,
		0.85885, -1.80570, 1.54104, -16283.25586,
		0.00000, 0.00000, 0.00000, 100.00000,
		0.34821, 0.69884, 0.62480, -4557.71582);

	// Logged frame 3834
	private static final float[] VP_B = fromRows(
		0.34544, 0.00000, 0.97403, -8661.70215,
		-0.85200, -1.47183, 0.30216, 3542.80176,
		0.00000, 0.00000, 0.00000, 100.00000,
		-0.80310, 0.52336, 0.28482, 5203.94531);

	private static double[] apply(double[] m, double x, double y, double z, double w)
	{
		return new double[]{
			m[0] * x + m[4] * y + m[8] * z + m[12] * w,
			m[1] * x + m[5] * y + m[9] * z + m[13] * w,
			m[2] * x + m[6] * y + m[10] * z + m[14] * w,
			m[3] * x + m[7] * y + m[11] * z + m[15] * w,
		};
	}

	@Test
	public void s2_06_inverseOfRealViewProjIsIdentity()
	{
		double[] m = Reprojection.toDouble(VP_A);
		double[] inv = Reprojection.invert(m);
		assertNotNull(inv);
		double[] id = Reprojection.multiply(m, inv);
		for (int i = 0; i < 16; i++)
		{
			assertEquals("element " + i, (i % 5 == 0) ? 1.0 : 0.0, id[i], 1e-9);
		}
	}

	@Test
	public void s2_06_stillCameraHasZeroMotion()
	{
		float[] m = Reprojection.currentToPrevious(VP_A, VP_A);
		double[][] pixels = {{0, 0, 0.5}, {-0.9, 0.7, 0.05}, {0.3, -0.4, 1.0}, {0.8, 0.8, 0.0}};
		for (double[] p : pixels)
		{
			double[] mv = Reprojection.motionPixels(m, p[0], p[1], p[2], W, H);
			assertEquals(0, mv[0], 1e-3);
			assertEquals(0, mv[1], 1e-3);
		}
	}

	@Test
	public void s2_06_matchesDirectProjectionOfAWorldPoint()
	{
		// A world point in front of camera A: unproject NDC (0.2, -0.1, depth 0.05) with A
		double[] invA = Reprojection.invert(Reprojection.toDouble(VP_A));
		double[] world = apply(invA, 0.2, -0.1, 0.05, 1);
		double[] wp = {world[0] / world[3], world[1] / world[3], world[2] / world[3]};

		double[] clipA = apply(Reprojection.toDouble(VP_A), wp[0], wp[1], wp[2], 1);
		double[] clipB = apply(Reprojection.toDouble(VP_B), wp[0], wp[1], wp[2], 1);
		double ax = clipA[0] / clipA[3], ay = clipA[1] / clipA[3], az = clipA[2] / clipA[3];
		double expectedDx = (clipB[0] / clipB[3] - ax) * 0.5 * W;
		double expectedDy = (clipB[1] / clipB[3] - ay) * 0.5 * H;

		// Current = A, previous = B
		double[] mv = Reprojection.motionPixels(Reprojection.currentToPrevious(VP_A, VP_B), ax, ay, az, W, H);
		// float matrices and large world coordinates: allow a few hundredths of a pixel
		assertEquals(expectedDx, mv[0], 0.05);
		assertEquals(expectedDy, mv[1], 0.05);
	}

	@Test
	public void s2_06_skyOnlyMovesWithRotationNotTranslation()
	{
		// Same orientation, camera moved: points at infinity (depth 0) don't move on screen
		float[] cur = VP_A.clone();
		float[] prev = VP_A.clone();
		// Moving the camera changes only the translation column (column 3 = indices 12..15)
		prev[12] += 500;
		prev[13] -= 300;
		prev[15] += 200;
		float[] m = Reprojection.currentToPrevious(cur, prev);
		double[] mv = Reprojection.motionPixels(m, 0.3, -0.2, 0.0, W, H);
		assertEquals(0, mv[0], 1e-3);
		assertEquals(0, mv[1], 1e-3);
	}
}

package com.runeliteupscaler.temporal;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * S2-05, S2-12: previous/current matrices, frame index and frame time.
 */
public class TemporalStateTest
{
	private static float[] matrix(float v)
	{
		float[] m = new float[16];
		java.util.Arrays.fill(m, v);
		return m;
	}

	@Test
	public void s2_05_firstFrameUsesCurrentAsPrevious()
	{
		TemporalState s = new TemporalState();
		s.beginFrame(matrix(1), 0);
		assertEquals(0, s.frameIndex());
		assertFalse(s.hasPrevious());
		assertArrayEquals(matrix(1), s.prevViewProj(), 0f);
		assertEquals(0f, s.frameTimeMs(), 0f);
	}

	@Test
	public void s2_05_previousIsLastFramesMatrix()
	{
		TemporalState s = new TemporalState();
		s.beginFrame(matrix(1), 0);
		s.beginFrame(matrix(2), 16_000_000L);
		assertTrue(s.hasPrevious());
		assertArrayEquals(matrix(2), s.viewProj(), 0f);
		assertArrayEquals(matrix(1), s.prevViewProj(), 0f);
	}

	@Test
	public void s2_05_callerArrayIsCopiedNotKept()
	{
		TemporalState s = new TemporalState();
		float[] m = matrix(1);
		s.beginFrame(m, 0);
		m[0] = 99;
		assertEquals(1f, s.viewProj()[0], 0f);
	}

	@Test
	public void s2_12_frameIndexAndTime()
	{
		TemporalState s = new TemporalState();
		s.beginFrame(matrix(1), 1_000_000_000L);
		s.beginFrame(matrix(1), 1_016_500_000L);
		s.beginFrame(matrix(1), 1_050_000_000L);
		assertEquals(2, s.frameIndex());
		assertEquals(33.5f, s.frameTimeMs(), 1e-3f);
	}

	@Test
	public void s2_05_resetDropsHistory()
	{
		TemporalState s = new TemporalState();
		s.beginFrame(matrix(1), 0);
		s.beginFrame(matrix(2), 1_000_000L);
		s.reset();
		assertEquals(-1, s.frameIndex());
		s.beginFrame(matrix(3), 2_000_000L);
		assertEquals(0, s.frameIndex());
		assertFalse(s.hasPrevious());
		assertArrayEquals(matrix(3), s.prevViewProj(), 0f);
	}
}

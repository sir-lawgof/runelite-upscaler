package com.runeliteupscaler.temporal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import org.junit.Test;

/**
 * T-U5: history reset triggers fire on each listed event (S2-08), with simulated client events.
 */
public class HistoryResetTest
{
	@Test
	public void tU5_eachRequestedEventResetsTheNextFrameOnce()
	{
		for (HistoryReset.Reason reason : HistoryReset.Reason.values())
		{
			HistoryReset h = new HistoryReset();
			assertNull(h.consume());
			h.request(reason);
			assertEquals(reason, h.consume());
			assertNull("reset must not repeat on the following frame", h.consume());
			assertEquals(1, h.resetCount());
			assertEquals(reason, h.lastReason());
		}
	}

	@Test
	public void tU5_severalEventsInOneFrameResetOnceKeepingTheFirst()
	{
		HistoryReset h = new HistoryReset();
		h.request(HistoryReset.Reason.SCENE_LOAD);
		h.request(HistoryReset.Reason.RESIZE);
		assertEquals(HistoryReset.Reason.SCENE_LOAD, h.consume());
		assertNull(h.consume());
		assertEquals(1, h.resetCount());
	}

	@Test
	public void tU5_normalCameraMovementDoesNotReset()
	{
		HistoryReset h = new HistoryReset();
		h.cameraPosition(3200, -800, 3200);
		h.consume();
		// a running step, a zoom-sized move: well under 8 tiles
		h.cameraPosition(3200 + 40, -800, 3200 + 40);
		assertNull(h.consume());
		h.cameraPosition(3200 + 40, -800 + 600, 3200 + 40);
		assertNull(h.consume());
	}

	@Test
	public void tU5_cameraJumpResets()
	{
		HistoryReset h = new HistoryReset();
		h.cameraPosition(3200, -800, 3200);
		h.consume();
		h.cameraPosition(3200 + 20 * 128, -800, 3200); // 20 tiles in one frame
		assertEquals(HistoryReset.Reason.TELEPORT, h.consume());
	}

	@Test
	public void tU5_firstCameraPositionAndForgottenCameraDoNotReset()
	{
		HistoryReset h = new HistoryReset();
		h.cameraPosition(100000, 0, 100000);
		assertNull(h.consume());
		h.forgetCamera();
		h.cameraPosition(0, 0, 0);
		assertNull("no comparison after forgetCamera", h.consume());
	}
}

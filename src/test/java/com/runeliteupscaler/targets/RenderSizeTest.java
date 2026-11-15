package com.runeliteupscaler.targets;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

/**
 * T-U1: render size math for every preset and odd window sizes (A-03).
 */
public class RenderSizeTest
{
	@Test
	public void tU1_nativeScaleKeepsDisplaySize()
	{
		assertEquals(1920, RenderSize.scale(1920, 100));
		assertEquals(1017, RenderSize.scale(1017, 100));
		assertEquals(1, RenderSize.scale(1, 100));
	}

	@Test
	public void tU1_everyPresetAt1080p()
	{
		int[][] expected = {
			// percent, width for 1920, height for 1080
			{100, 1920, 1080},
			{77, 1478, 832},
			{67, 1286, 724},
			{58, 1114, 626},
			{50, 960, 540},
			{33, 634, 356},
		};
		for (int[] row : expected)
		{
			assertEquals("width at " + row[0] + "%", row[1], RenderSize.scale(1920, row[0]));
			assertEquals("height at " + row[0] + "%", row[2], RenderSize.scale(1080, row[0]));
		}
	}

	@Test
	public void tU1_oddSizesRoundToNearestPixel()
	{
		assertEquals(501, RenderSize.scale(1001, 50)); // 500.5 rounds up
		assertEquals(513, RenderSize.scale(765, 67)); // 512.55
		assertEquals(169, RenderSize.scale(511, 33)); // 168.63
	}

	@Test
	public void tU1_minimumOnePixel()
	{
		assertEquals(1, RenderSize.scale(1, 33));
		assertEquals(1, RenderSize.scale(0, 50));
		assertEquals(1, RenderSize.scale(2, 33));
	}

	@Test
	public void tU1_presetPercents()
	{
		assertEquals(100, ScalePreset.NATIVE.percent(0));
		assertEquals(77, ScalePreset.SCALE_77.percent(0));
		assertEquals(67, ScalePreset.QUALITY.percent(0));
		assertEquals(58, ScalePreset.BALANCED.percent(0));
		assertEquals(50, ScalePreset.PERFORMANCE.percent(0));
		assertEquals(33, ScalePreset.ULTRA_PERFORMANCE.percent(0));
	}

	@Test
	public void tU1_customScaleClampedTo33To100()
	{
		assertEquals(75, ScalePreset.CUSTOM.percent(75));
		assertEquals(33, ScalePreset.CUSTOM.percent(10));
		assertEquals(100, ScalePreset.CUSTOM.percent(150));
	}
}

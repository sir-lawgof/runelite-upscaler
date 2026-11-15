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

import java.awt.Dimension;
import java.awt.Graphics2D;
import java.util.function.BooleanSupplier;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

/**
 * G-31: debug overlay. Shows backend, render and display resolution (S1-02 acceptance), the fallback
 * reason (A-04, G-13) and GPU time per stage including the upscale pass (A-06).
 * Drawn by RuneLite into the 2D interface, so it stays at native resolution.
 */
public class UpscalerDebugOverlay extends OverlayPanel
{
	private final RenderStats stats;
	private final BooleanSupplier enabled;

	public UpscalerDebugOverlay(RenderStats stats, BooleanSupplier enabled)
	{
		this.stats = stats;
		this.enabled = enabled;
		setPosition(OverlayPosition.TOP_LEFT);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!enabled.getAsBoolean())
		{
			return null;
		}

		panelComponent.getChildren().add(TitleComponent.builder().text("Upscaler GPU").build());
		line("Backend", stats.backend);
		line("Scale", stats.scalePercent + "%");
		line("Render", stats.renderWidth + " x " + stats.renderHeight);
		line("Display", stats.displayWidth + " x " + stats.displayHeight);
		line("MSAA", stats.msaaSamples > 0 ? stats.msaaSamples + "x" : stats.msaaForcedOff ? "off (temporal)" : "off");
		// S2-12
		line("Temporal", stats.frameIndex < 0 ? "off"
			: "frame " + stats.frameIndex + ", " + String.format("%.1f ms", stats.frameTimeMs));
		if (stats.frameIndex >= 0)
		{
			// S2-03: jitter readout cycles through the phase count
			line("Jitter", stats.jitterPhaseCount == 0 ? "off" : String.format("%+.3f, %+.3f px (%d/%d)",
				stats.jitterX, stats.jitterY, stats.jitterPhase + 1, stats.jitterPhaseCount));
			// S2-08
			line("Resets", stats.resetCount + " (last: " + stats.lastReset + ")");
		}
		line("Mip bias", String.format("%.2f", stats.mipBias)); // S2-09
		line("VK interop", stats.interop); // S2-21
		line("VK device", stats.vulkan); // S2-20
		line("VK bridge", stats.bridge); // S2-22 to S2-25
		line("Targets", stats.targetFormat);
		// S1-15: leak checks. GL objects held by this plugin's code; GPU memory for the whole device.
		line("GL objects", GlObjects.total() + " (" + GlObjects.breakdown() + ")");
		line("GPU memory", stats.gpuMemUsedMb < 0 ? "n/a"
			: stats.gpuMemUsedMb + " / " + stats.gpuMemTotalMb + " MB");
		double[] gpu = stats.gpuMs;
		if (gpu != null)
		{
			// A-06, G-31: GPU time per stage
			for (GpuTimers.Stage stage : GpuTimers.Stage.values())
			{
				double ms = gpu[stage.ordinal()];
				line("GPU " + stage.label, ms < 0 ? "-" : String.format("%.2f ms", ms));
			}
		}
		if (!stats.fallbackReason.isEmpty())
		{
			line("Fallback", stats.fallbackReason);
		}
		return super.render(graphics);
	}

	private void line(String left, String right)
	{
		panelComponent.getChildren().add(LineComponent.builder().left(left).right(right).build());
	}
}

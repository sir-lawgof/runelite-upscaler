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

/**
 * G-31: values the renderer publishes each frame for the debug overlay. Written and read on the
 * client thread (scene draw and overlay rendering both run there).
 */
public final class RenderStats
{
	public String backend = "-";
	/** A-04, G-13: why better backends were skipped; empty if none were */
	public String fallbackReason = "";
	public int scalePercent;
	public int renderWidth;
	public int renderHeight;
	public int displayWidth;
	public int displayHeight;
	public int msaaSamples;
	/** S2-01: MSAA is configured but turned off because temporal inputs are on */
	public boolean msaaForcedOff;
	/** S2-12: -1 when temporal inputs are off */
	public long frameIndex = -1;
	public float frameTimeMs;
	/** S2-03: jitter in render pixels and phase; phase count 0 when jitter is off */
	public float jitterX, jitterY;
	public int jitterPhase, jitterPhaseCount;
	/** S2-08 */
	public long resetCount;
	public String lastReset = "-";
	/** S2-09 */
	public float mipBias;
	/** S2-21: GL side of the GL-Vulkan bridge */
	public String interop = "-";
	/** S1-03 */
	public String targetFormat = "-";
	/** A-06: smoothed GPU ms per {@link GpuTimers.Stage}, -1 = no result yet; null when timing is off */
	public double[] gpuMs;
	/** S1-15: GPU memory in use on the whole device (all processes), MB; -1 = not available (non-NVIDIA) */
	public int gpuMemUsedMb = -1;
	public int gpuMemTotalMb = -1;
}

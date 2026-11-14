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
package com.runeliteupscaler.targets;

/**
 * A-03: render scale presets. Render resolution = display viewport size x scale.
 */
public enum ScalePreset
{
	NATIVE(100, "100% (Native)"),
	SCALE_77(77, "77%"),
	QUALITY(67, "67% (Quality)"),
	BALANCED(58, "58% (Balanced)"),
	PERFORMANCE(50, "50% (Performance)"),
	ULTRA_PERFORMANCE(33, "33% (Ultra Performance)"),
	CUSTOM(-1, "Custom");

	/** A-03: the custom slider range. */
	public static final int MIN_PERCENT = 33;
	public static final int MAX_PERCENT = 100;

	private final int percent;
	private final String label;

	ScalePreset(int percent, String label)
	{
		this.percent = percent;
		this.label = label;
	}

	/**
	 * A-03: the scale in percent for this preset, using {@code customPercent} (clamped to 33-100)
	 * when the preset is {@link #CUSTOM}.
	 */
	public int percent(int customPercent)
	{
		if (this == CUSTOM)
		{
			return Math.max(MIN_PERCENT, Math.min(MAX_PERCENT, customPercent));
		}
		return percent;
	}

	@Override
	public String toString()
	{
		return label;
	}
}

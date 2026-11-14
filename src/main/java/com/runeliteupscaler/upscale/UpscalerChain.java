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
package com.runeliteupscaler.upscale;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A-04: picks the backend. Backends are given in fallback order (DLSS, then FSR 2/3, then FSR 1 or NIS,
 * then bilinear). Selection starts at the requested backend and takes the first one that is supported
 * and has not failed at runtime (G-13). Bilinear is always supported, so the chain never ends empty.
 */
public final class UpscalerChain implements AutoCloseable
{
	private final List<Upscaler> backends;
	private final List<String> failed = new ArrayList<>();
	private Upscaler active;
	private String fallbackReason = "";

	/** @param backends in fallback order, best first; the last should always be supported */
	public UpscalerChain(List<Upscaler> backends)
	{
		if (backends.isEmpty())
		{
			throw new IllegalArgumentException("no backends");
		}
		this.backends = Collections.unmodifiableList(new ArrayList<>(backends));
	}

	/**
	 * A-04: select starting at the backend named {@code requested} (or the first backend if there is no
	 * backend with that name).
	 *
	 * @return the selected backend, or null if none is supported
	 */
	public Upscaler select(String requested, GpuCaps caps)
	{
		int start = 0;
		for (int i = 0; i < backends.size(); i++)
		{
			if (backends.get(i).name().equals(requested))
			{
				start = i;
				break;
			}
		}

		StringBuilder reasons = new StringBuilder();
		active = null;
		for (int i = start; i < backends.size(); i++)
		{
			Upscaler u = backends.get(i);
			String reason;
			if (failed.contains(u.name()))
			{
				reason = "failed at runtime";
			}
			else
			{
				Support s = u.isSupported(caps);
				if (s.supported)
				{
					active = u;
					break;
				}
				reason = s.reason;
			}

			if (reasons.length() > 0)
			{
				reasons.append("; ");
			}
			reasons.append(u.name()).append(": ").append(reason);
		}

		fallbackReason = reasons.toString();
		return active;
	}

	/** G-13: record that a backend failed while running, so the next {@link #select} skips it. */
	public void markFailed(Upscaler upscaler)
	{
		if (!failed.contains(upscaler.name()))
		{
			failed.add(upscaler.name());
		}
	}

	public Upscaler active()
	{
		return active;
	}

	/** Why backends before the active one were skipped; empty if the requested backend is active. */
	public String fallbackReason()
	{
		return fallbackReason;
	}

	/** A-07, G-11: close every backend. */
	@Override
	public void close()
	{
		for (Upscaler u : backends)
		{
			u.close();
		}
		active = null;
	}
}

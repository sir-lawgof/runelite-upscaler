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
package com.runeliteupscaler.temporal;

/**
 * S2-08: decides when a temporal upscaler must discard its history, so nothing smears for a frame after
 * a cut. Events are reported as they happen; {@link #consume} is called once per frame and returns why
 * this frame resets (or null). Plain Java, no client or GL access, so it can be unit-tested (T-U5).
 * Thread-safe: scene loads can be reported from the client's map loading thread.
 */
public final class HistoryReset
{
	public enum Reason
	{
		PLUGIN_ENABLE("plugin enable"),
		SCENE_LOAD("scene load"),
		LOGIN("login"),
		LOGOUT("logout"),
		TELEPORT("camera jump"),
		RESIZE("resize"),
		SCALE_CHANGE("scale change"),
		BACKEND_SWITCH("backend switch");

		public final String label;

		Reason(String label)
		{
			this.label = label;
		}
	}

	/**
	 * S2-08: a camera move larger than this between two frames is treated as a cut (teleport, camera snap).
	 * 8 tiles in local units (1 tile = 128); walking or running moves well under a tile per frame.
	 */
	public static final double CAMERA_JUMP_THRESHOLD = 8 * 128;

	private Reason pending;
	private boolean hasCamera;
	private double lastX, lastY, lastZ;
	private long resetCount;
	private Reason lastReason;

	/** Request a reset for the next frame. The first reason in a frame is kept for the readout. */
	public synchronized void request(Reason reason)
	{
		if (pending == null)
		{
			pending = reason;
		}
	}

	/** Report the camera position each frame; a jump above the threshold requests a reset. */
	public synchronized void cameraPosition(double x, double y, double z)
	{
		if (hasCamera)
		{
			double dx = x - lastX, dy = y - lastY, dz = z - lastZ;
			if (dx * dx + dy * dy + dz * dz > CAMERA_JUMP_THRESHOLD * CAMERA_JUMP_THRESHOLD)
			{
				request(Reason.TELEPORT);
			}
		}
		lastX = x;
		lastY = y;
		lastZ = z;
		hasCamera = true;
	}

	/** Forget the camera position, so the next frame is not compared with a stale one. */
	public synchronized void forgetCamera()
	{
		hasCamera = false;
	}

	/** Call once per frame. @return why this frame resets history, or null. */
	public synchronized Reason consume()
	{
		Reason r = pending;
		pending = null;
		if (r != null)
		{
			resetCount++;
			lastReason = r;
		}
		return r;
	}

	public synchronized long resetCount()
	{
		return resetCount;
	}

	/** Reason of the most recent reset; null if none yet. */
	public synchronized Reason lastReason()
	{
		return lastReason;
	}
}

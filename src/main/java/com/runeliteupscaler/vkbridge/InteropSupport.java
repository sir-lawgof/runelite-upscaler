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
package com.runeliteupscaler.vkbridge;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.EXTMemoryObject;
import org.lwjgl.opengl.EXTMemoryObjectWin32;
import org.lwjgl.opengl.GLCapabilities;
import static org.lwjgl.opengl.GL33C.*;

/**
 * S2-20, S2-21, S2-28: OpenGL side of the OpenGL-to-Vulkan bridge. Checks the external memory and semaphore
 * extensions the bridge needs, and reads the device and driver UUIDs (and the LUID on Windows), which the
 * Vulkan side must match (VkPhysicalDeviceIDProperties) to pick the same GPU.
 * The Vulkan side is checked when the bridge is built (needs lwjgl-vulkan, Q-02).
 */
public final class InteropSupport
{
	public enum Platform
	{
		WINDOWS, LINUX, MAC, OTHER;

		public static Platform current()
		{
			String os = System.getProperty("os.name", "").toLowerCase();
			if (os.startsWith("windows"))
			{
				return WINDOWS;
			}
			if (os.startsWith("mac") || os.startsWith("darwin"))
			{
				return MAC;
			}
			if (os.startsWith("linux"))
			{
				return LINUX;
			}
			return OTHER;
		}
	}

	/** True if every required GL extension is present. */
	public final boolean supported;
	/** Why not, or empty if supported. */
	public final String reason;
	/** Required extensions for this platform that are missing. */
	public final List<String> missing;
	/** Device UUIDs reported by GL (one per device in the context); hex, empty if unavailable. */
	public final List<String> deviceUuids;
	public final String driverUuid;
	/** Windows only: device LUID (hex) and node mask; empty / 0 if unavailable. */
	public final String deviceLuid;
	public final int deviceNodeMask;

	private InteropSupport(boolean supported, String reason, List<String> missing, List<String> deviceUuids,
		String driverUuid, String deviceLuid, int deviceNodeMask)
	{
		this.supported = supported;
		this.reason = reason;
		this.missing = missing;
		this.deviceUuids = deviceUuids;
		this.driverUuid = driverUuid;
		this.deviceLuid = deviceLuid;
		this.deviceNodeMask = deviceNodeMask;
	}

	/**
	 * S2-21: required GL extensions for a platform. macOS has no GL-Vulkan interop (S2-28).
	 * Pure function of the capability flags, so it can be unit-tested.
	 */
	public static List<String> missingExtensions(Platform platform, boolean memoryObject, boolean semaphore,
		boolean memoryObjectWin32, boolean semaphoreWin32, boolean memoryObjectFd, boolean semaphoreFd)
	{
		List<String> missing = new ArrayList<>();
		if (!memoryObject)
		{
			missing.add("GL_EXT_memory_object");
		}
		if (!semaphore)
		{
			missing.add("GL_EXT_semaphore");
		}
		if (platform == Platform.WINDOWS)
		{
			if (!memoryObjectWin32)
			{
				missing.add("GL_EXT_memory_object_win32");
			}
			if (!semaphoreWin32)
			{
				missing.add("GL_EXT_semaphore_win32");
			}
		}
		else if (platform == Platform.LINUX)
		{
			if (!memoryObjectFd)
			{
				missing.add("GL_EXT_memory_object_fd");
			}
			if (!semaphoreFd)
			{
				missing.add("GL_EXT_semaphore_fd");
			}
		}
		return missing;
	}

	/** S2-20, S2-21: run the checks on the current GL context. Never throws (G-13). */
	public static InteropSupport check(GLCapabilities caps)
	{
		Platform platform = Platform.current();
		if (platform == Platform.MAC || platform == Platform.OTHER)
		{
			return new InteropSupport(false, "no OpenGL-Vulkan interop on " + platform + " (S2-28)",
				new ArrayList<>(), new ArrayList<>(), "", "", 0);
		}

		List<String> missing = missingExtensions(platform, caps.GL_EXT_memory_object, caps.GL_EXT_semaphore,
			caps.GL_EXT_memory_object_win32, caps.GL_EXT_semaphore_win32, caps.GL_EXT_memory_object_fd,
			caps.GL_EXT_semaphore_fd);

		List<String> deviceUuids = new ArrayList<>();
		String driverUuid = "";
		String luid = "";
		int nodeMask = 0;
		try
		{
			if (caps.GL_EXT_memory_object || caps.GL_EXT_semaphore)
			{
				ByteBuffer uuid = BufferUtils.createByteBuffer(EXTMemoryObject.GL_UUID_SIZE_EXT);
				EXTMemoryObject.glGetUnsignedBytevEXT(EXTMemoryObject.GL_DRIVER_UUID_EXT, uuid);
				driverUuid = hex(uuid);
				int devices = glGetInteger(EXTMemoryObject.GL_NUM_DEVICE_UUIDS_EXT);
				for (int i = 0; i < devices; i++)
				{
					uuid.clear();
					EXTMemoryObject.glGetUnsignedBytei_vEXT(EXTMemoryObject.GL_DEVICE_UUID_EXT, i, uuid);
					deviceUuids.add(hex(uuid));
				}
			}
			if (platform == Platform.WINDOWS && (caps.GL_EXT_memory_object_win32 || caps.GL_EXT_semaphore_win32))
			{
				ByteBuffer l = BufferUtils.createByteBuffer(EXTMemoryObjectWin32.GL_LUID_SIZE_EXT);
				EXTMemoryObject.glGetUnsignedBytevEXT(EXTMemoryObjectWin32.GL_DEVICE_LUID_EXT, l);
				luid = hex(l);
				nodeMask = glGetInteger(EXTMemoryObjectWin32.GL_DEVICE_NODE_MASK_EXT);
			}
		}
		catch (RuntimeException ex)
		{
			// Identifiers are informational at this point; a failure here doesn't change the extension result
			driverUuid = driverUuid.isEmpty() ? "error: " + ex.getMessage() : driverUuid;
		}
		while (glGetError() != GL_NO_ERROR)
		{
			// clear any error from the queries so the renderer's own error checks aren't confused
		}

		boolean ok = missing.isEmpty();
		return new InteropSupport(ok, ok ? "" : "missing " + String.join(", ", missing), missing, deviceUuids,
			driverUuid, luid, nodeMask);
	}

	static String hex(ByteBuffer b)
	{
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < b.capacity(); i++)
		{
			sb.append(String.format("%02x", b.get(i) & 0xff));
		}
		return sb.toString();
	}
}

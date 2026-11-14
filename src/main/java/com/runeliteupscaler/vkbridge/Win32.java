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

import org.lwjgl.system.JNI;
import org.lwjgl.system.windows.Kernel32;

/**
 * S2-22, S2-24: Win32 handle helper. Importing an OPAQUE_WIN32 (NT) handle into OpenGL does not take ownership,
 * so the exporter must close it (VK_KHR_external_memory_win32). LWJGL 3.3.2 has no CloseHandle binding, so it
 * is called through kernel32's export with LWJGL's generic JNI call.
 */
final class Win32
{
	private static long closeHandleAddress;

	private Win32()
	{
	}

	/** @return true if the handle was closed */
	static synchronized boolean closeHandle(long handle)
	{
		if (handle == 0)
		{
			return false;
		}
		if (closeHandleAddress == 0)
		{
			closeHandleAddress = Kernel32.getLibrary().getFunctionAddress("CloseHandle");
			if (closeHandleAddress == 0)
			{
				return false;
			}
		}
		return JNI.callPI(handle, closeHandleAddress) != 0;
	}
}

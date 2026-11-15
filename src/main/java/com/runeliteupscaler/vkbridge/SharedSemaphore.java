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

import com.runeliteupscaler.debug.GlObjects;
import java.nio.LongBuffer;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkExportSemaphoreCreateInfo;
import org.lwjgl.vulkan.VkSemaphoreCreateInfo;
import org.lwjgl.vulkan.VkSemaphoreGetWin32HandleInfoKHR;
import static org.lwjgl.opengl.EXTSemaphore.glDeleteSemaphoresEXT;
import static org.lwjgl.opengl.EXTSemaphore.glGenSemaphoresEXT;
import static org.lwjgl.opengl.EXTSemaphoreWin32.GL_HANDLE_TYPE_OPAQUE_WIN32_EXT;
import static org.lwjgl.opengl.EXTSemaphoreWin32.glImportSemaphoreWin32HandleEXT;
import static org.lwjgl.opengl.GL33C.GL_NO_ERROR;
import static org.lwjgl.opengl.GL33C.glGetError;
import static org.lwjgl.vulkan.KHRExternalSemaphoreWin32.vkGetSemaphoreWin32HandleKHR;
import static org.lwjgl.vulkan.VK10.vkCreateSemaphore;
import static org.lwjgl.vulkan.VK10.vkDestroySemaphore;
import static org.lwjgl.vulkan.VK11.VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_WIN32_BIT;

/**
 * S2-24: a binary semaphore shared by Vulkan and OpenGL (created in Vulkan, exported as a Win32 handle,
 * imported with GL_EXT_semaphore_win32). Create and close on the thread that owns the GL context.
 */
final class SharedSemaphore implements AutoCloseable
{
	private final VkDevice device;
	long vk;
	/** Win32 NT handle; closed in close() after GL deletes its semaphore */
	private long handle;
	int gl;

	private SharedSemaphore(VkDevice device)
	{
		this.device = device;
	}

	static SharedSemaphore create(VkDevice device)
	{
		SharedSemaphore s = new SharedSemaphore(device);
		try (MemoryStack stack = MemoryStack.stackPush())
		{
			VkExportSemaphoreCreateInfo export = VkExportSemaphoreCreateInfo.calloc(stack)
				.sType$Default()
				.handleTypes(VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_WIN32_BIT);
			VkSemaphoreCreateInfo sci = VkSemaphoreCreateInfo.calloc(stack)
				.sType$Default()
				.pNext(export.address());
			LongBuffer lp = stack.mallocLong(1);
			SharedImage.check(vkCreateSemaphore(device, sci, null, lp), "vkCreateSemaphore");
			s.vk = lp.get(0);

			VkSemaphoreGetWin32HandleInfoKHR hi = VkSemaphoreGetWin32HandleInfoKHR.calloc(stack)
				.sType$Default()
				.semaphore(s.vk)
				.handleType(VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_WIN32_BIT);
			PointerBuffer ph = stack.mallocPointer(1);
			SharedImage.check(vkGetSemaphoreWin32HandleKHR(device, hi, ph), "vkGetSemaphoreWin32HandleKHR");
			s.handle = ph.get(0);

			s.gl = glGenSemaphoresEXT();
			GlObjects.created(GlObjects.Type.SEMAPHORE, 1);
			glImportSemaphoreWin32HandleEXT(s.gl, GL_HANDLE_TYPE_OPAQUE_WIN32_EXT, s.handle);
			// Handle kept open until close() (see SharedImage)
			int err = glGetError();
			if (err != GL_NO_ERROR)
			{
				throw new IllegalStateException("GL error importing semaphore: " + err);
			}
			return s;
		}
		catch (RuntimeException ex)
		{
			s.close();
			throw ex;
		}
	}

	/** S2-26: the Vulkan device must be idle. Safe to call twice. */
	@Override
	public void close()
	{
		if (gl != 0)
		{
			glDeleteSemaphoresEXT(gl);
			Win32.closeHandle(handle);
			handle = 0;
			GlObjects.deleted(GlObjects.Type.SEMAPHORE, 1);
			gl = 0;
		}
		if (vk != 0)
		{
			vkDestroySemaphore(device, vk, null);
			vk = 0;
		}
	}
}

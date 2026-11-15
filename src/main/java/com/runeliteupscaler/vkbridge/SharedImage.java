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
import org.lwjgl.vulkan.VkExportMemoryAllocateInfo;
import org.lwjgl.vulkan.VkExternalMemoryImageCreateInfo;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryDedicatedAllocateInfo;
import org.lwjgl.vulkan.VkMemoryGetWin32HandleInfoKHR;
import org.lwjgl.vulkan.VkMemoryRequirements;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;
import static org.lwjgl.opengl.EXTMemoryObject.GL_DEDICATED_MEMORY_OBJECT_EXT;
import static org.lwjgl.opengl.EXTMemoryObject.glCreateMemoryObjectsEXT;
import static org.lwjgl.opengl.EXTMemoryObject.glDeleteMemoryObjectsEXT;
import static org.lwjgl.opengl.EXTMemoryObject.glMemoryObjectParameterivEXT;
import static org.lwjgl.opengl.EXTMemoryObject.glTexStorageMem2DEXT;
import static org.lwjgl.opengl.EXTMemoryObjectWin32.GL_HANDLE_TYPE_OPAQUE_WIN32_EXT;
import static org.lwjgl.opengl.EXTMemoryObjectWin32.glImportMemoryWin32HandleEXT;
import static org.lwjgl.opengl.GL33C.*;
import static org.lwjgl.vulkan.KHRExternalMemoryWin32.vkGetMemoryWin32HandleKHR;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT;

/**
 * S2-22: an RGBA8 image shared by Vulkan and OpenGL. Allocated in Vulkan with exportable dedicated memory,
 * exported as a Win32 handle and imported into OpenGL (memory object + glTexStorageMem2DEXT), with a GL
 * framebuffer for blits. Windows only for now (the FD path is S2-21's Linux variant).
 * Create and close on the thread that owns the GL context.
 */
final class SharedImage implements AutoCloseable
{
	final int width;
	final int height;
	/** Vulkan image usage flags */
	final int usage;

	// Vulkan side
	private final VkDevice device;
	long image;
	private long memory;

	// OpenGL side
	private int glMemory;
	/** Win32 NT handle of the memory; GL import does not take ownership, closed in close() after GL is done */
	private long handle;
	int glTexture;
	int glFbo;

	private SharedImage(VkDevice device, int width, int height, int usage)
	{
		this.device = device;
		this.width = width;
		this.height = height;
		this.usage = usage;
	}

	static SharedImage create(VkDevice device, int width, int height, int usage)
	{
		SharedImage s = new SharedImage(device, width, height, usage);
		try
		{
			s.init();
			return s;
		}
		catch (RuntimeException ex)
		{
			s.close();
			throw ex;
		}
	}

	private void init()
	{
		try (MemoryStack stack = MemoryStack.stackPush())
		{
			// Vulkan image that may be exported as an opaque Win32 handle
			VkExternalMemoryImageCreateInfo external = VkExternalMemoryImageCreateInfo.calloc(stack)
				.sType$Default()
				.handleTypes(VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT);
			VkImageCreateInfo ici = VkImageCreateInfo.calloc(stack)
				.sType$Default()
				.pNext(external.address())
				.imageType(VK_IMAGE_TYPE_2D)
				.format(VK_FORMAT_R8G8B8A8_UNORM)
				.mipLevels(1)
				.arrayLayers(1)
				.samples(VK_SAMPLE_COUNT_1_BIT)
				.tiling(VK_IMAGE_TILING_OPTIMAL)
				.usage(usage)
				.sharingMode(VK_SHARING_MODE_EXCLUSIVE)
				.initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
			ici.extent().width(width).height(height).depth(1);
			LongBuffer lp = stack.mallocLong(1);
			check(vkCreateImage(device, ici, null, lp), "vkCreateImage");
			image = lp.get(0);

			// Dedicated, exportable device-local memory
			VkMemoryRequirements req = VkMemoryRequirements.malloc(stack);
			vkGetImageMemoryRequirements(device, image, req);
			int typeIndex = deviceLocalMemoryType(stack, req.memoryTypeBits());
			VkMemoryDedicatedAllocateInfo dedicated = VkMemoryDedicatedAllocateInfo.calloc(stack)
				.sType$Default()
				.image(image);
			VkExportMemoryAllocateInfo export = VkExportMemoryAllocateInfo.calloc(stack)
				.sType$Default()
				.pNext(dedicated.address())
				.handleTypes(VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT);
			VkMemoryAllocateInfo mai = VkMemoryAllocateInfo.calloc(stack)
				.sType$Default()
				.pNext(export.address())
				.allocationSize(req.size())
				.memoryTypeIndex(typeIndex);
			check(vkAllocateMemory(device, mai, null, lp), "vkAllocateMemory");
			memory = lp.get(0);
			check(vkBindImageMemory(device, image, memory, 0), "vkBindImageMemory");

			// Export as a Win32 handle and import into OpenGL
			VkMemoryGetWin32HandleInfoKHR hi = VkMemoryGetWin32HandleInfoKHR.calloc(stack)
				.sType$Default()
				.memory(memory)
				.handleType(VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT);
			PointerBuffer ph = stack.mallocPointer(1);
			check(vkGetMemoryWin32HandleKHR(device, hi, ph), "vkGetMemoryWin32HandleKHR");
			handle = ph.get(0);

			glMemory = glCreateMemoryObjectsEXT();
			GlObjects.created(GlObjects.Type.MEMORY_OBJECT, 1);
			glMemoryObjectParameterivEXT(glMemory, GL_DEDICATED_MEMORY_OBJECT_EXT, new int[]{GL_TRUE});
			glImportMemoryWin32HandleEXT(glMemory, req.size(), GL_HANDLE_TYPE_OPAQUE_WIN32_EXT, handle);
			// Keep the NT handle open until close(): closing it right after import left GL with its own storage
			// instead of the Vulkan memory on NVIDIA (the import seems to resolve the handle later, 8.4)

			glTexture = glGenTextures();
			GlObjects.created(GlObjects.Type.TEXTURE, 1);
			glBindTexture(GL_TEXTURE_2D, glTexture);
			glTexStorageMem2DEXT(GL_TEXTURE_2D, 1, GL_RGBA8, width, height, glMemory, 0);
			glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
			glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
			glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
			glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
			glBindTexture(GL_TEXTURE_2D, 0);

			glFbo = glGenFramebuffers();
			GlObjects.created(GlObjects.Type.FRAMEBUFFER, 1);
			glBindFramebuffer(GL_FRAMEBUFFER, glFbo);
			glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, glTexture, 0);
			int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
			if (status != GL_FRAMEBUFFER_COMPLETE)
			{
				throw new IllegalStateException("shared image FBO incomplete: " + status);
			}
			int err = glGetError();
			if (err != GL_NO_ERROR)
			{
				throw new IllegalStateException("GL error importing shared image: " + err);
			}
		}
	}

	private int deviceLocalMemoryType(MemoryStack stack, int typeBits)
	{
		VkPhysicalDeviceMemoryProperties props = VkPhysicalDeviceMemoryProperties.malloc(stack);
		vkGetPhysicalDeviceMemoryProperties(device.getPhysicalDevice(), props);
		for (int i = 0; i < props.memoryTypeCount(); i++)
		{
			if ((typeBits & (1 << i)) != 0
				&& (props.memoryTypes(i).propertyFlags() & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT) != 0)
			{
				return i;
			}
		}
		throw new IllegalStateException("no device-local memory type for shared image");
	}

	static void check(int result, String what)
	{
		if (result != VK_SUCCESS)
		{
			throw new IllegalStateException(what + " failed: " + result);
		}
	}

	/** A-07, S2-26: free both sides. The Vulkan device must be idle. Safe to call twice. */
	@Override
	public void close()
	{
		if (glFbo != 0)
		{
			glDeleteFramebuffers(glFbo);
			GlObjects.deleted(GlObjects.Type.FRAMEBUFFER, 1);
			glFbo = 0;
		}
		if (glTexture != 0)
		{
			glDeleteTextures(glTexture);
			GlObjects.deleted(GlObjects.Type.TEXTURE, 1);
			glTexture = 0;
		}
		if (glMemory != 0)
		{
			glDeleteMemoryObjectsEXT(glMemory);
			Win32.closeHandle(handle);
			handle = 0;
			GlObjects.deleted(GlObjects.Type.MEMORY_OBJECT, 1);
			glMemory = 0;
		}
		if (image != 0)
		{
			vkDestroyImage(device, image, null);
			image = 0;
		}
		if (memory != 0)
		{
			vkFreeMemory(device, memory, null);
			memory = 0;
		}
	}
}

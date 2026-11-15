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

import java.nio.LongBuffer;
import lombok.extern.slf4j.Slf4j;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkClearColorValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkImageCopy;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkImageSubresourceRange;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkSubmitInfo;
import static org.lwjgl.opengl.EXTSemaphore.GL_LAYOUT_GENERAL_EXT;
import static org.lwjgl.opengl.EXTSemaphore.glSignalSemaphoreEXT;
import static org.lwjgl.opengl.EXTSemaphore.glWaitSemaphoreEXT;
import static org.lwjgl.opengl.GL33C.*;
import static org.lwjgl.vulkan.VK10.*;

/**
 * S2-22 to S2-25, first test of the GL-Vulkan bridge: a round trip through Vulkan each frame.
 * <ol>
 * <li>GL copies the finished frame into the shared input image and signals {@code glDone}.</li>
 * <li>Vulkan waits for {@code glDone}, copies input to output, and signals {@code vkDone}.</li>
 * <li>GL waits for {@code vkDone} (on the GPU) and the caller presents the shared output image.</li>
 * </ol>
 * The output image is only ever written by Vulkan, so a normal-looking game proves both directions work.
 * No CPU readback (S2-25); the CPU never waits for either API. Images are recreated only on resize (S2-26).
 * Vulkan acquires the images from / releases them to the external queue family around its work.
 * All methods run on the thread that owns the GL context.
 */
@Slf4j
public final class BridgeRoundTrip implements AutoCloseable
{
	private static final int[] NO_BUFFERS = new int[0];
	/**
	 * GL layouts matching the Vulkan layout the images keep between frames. GENERAL on both sides: with
	 * TRANSFER_SRC/DST the round trip read black from GL-written images on NVIDIA (8.4).
	 */
	private static final int[] GL_LAYOUTS = {GL_LAYOUT_GENERAL_EXT, GL_LAYOUT_GENERAL_EXT};

	/**
	 * Images GL writes and Vulkan reads: no STORAGE usage. With STORAGE, NVIDIA Vulkan lays the image out without
	 * colour compression, but GL's glTexStorageMem2DEXT import can't know the usage and may write it compressed,
	 * so Vulkan read black (8.4). Images Vulkan writes and GL reads keep STORAGE (FSR 2/3 writes its output as one).
	 */
	static final int INPUT_USAGE = VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT
		| VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
	static final int OUTPUT_USAGE = INPUT_USAGE | VK_IMAGE_USAGE_STORAGE_BIT;

	private final VulkanDevice vulkan;
	private final VkDevice device;
	private final VkQueue queue;

	private SharedSemaphore glDone;
	private SharedSemaphore vkDone;
	private long commandPool;
	private VkCommandBuffer frameCommands;
	private SharedImage input;
	private SharedImage output;
	private long frames;
	/** frames since the current image set was created (probe schedule) */
	private long framesInSet;

	public BridgeRoundTrip(VulkanDevice vulkan)
	{
		this.vulkan = vulkan;
		this.device = vulkan.device();
		this.queue = vulkan.queue();
	}

	/** Diagnostic modes, to find which half of the round trip fails. */
	public enum Mode
	{
		/** Full round trip: GL writes input, Vulkan copies input to output, GL presents output. */
		COPY("Copy"),
		/** Diagnostic: like Copy, but plain barriers instead of acquiring from / releasing to the external queue family. */
		COPY_NO_OWNERSHIP("Copy (no ownership transfer)"),
		/** Diagnostic: like Copy, but glFinish before the hand-over (rules timing in or out). */
		COPY_FINISH("Copy + glFinish"),
		/** GL writes the shared input image and presents it directly; Vulkan is not used each frame. */
		GL_ONLY("GL only"),
		/** Vulkan clears the output to magenta each frame (input unused); GL presents output. */
		VK_CLEAR("Vulkan clear");

		private final String label;

		Mode(String label)
		{
			this.label = label;
		}

		@Override
		public String toString()
		{
			return label;
		}
	}

	private Mode mode = Mode.COPY;

	/** The GL framebuffer to present after {@link #run}: the output image, or the input image in GL-only mode. */
	public int outputFbo()
	{
		return mode == Mode.GL_ONLY ? input.glFbo : output.glFbo;
	}

	public long frames()
	{
		return frames;
	}

	public int width()
	{
		return output == null ? 0 : output.width;
	}

	public int height()
	{
		return output == null ? 0 : output.height;
	}

	/**
	 * S2-26: make sure the shared images exist at this size (recreated only when it changes).
	 * Leaves GL framebuffer and texture bindings changed.
	 */
	public void ensure(int width, int height, Mode mode)
	{
		if (output != null && output.width == width && output.height == height && mode == this.mode)
		{
			return;
		}

		destroyImages();
		this.mode = mode;
		framesInSet = 0;
		if (glDone == null)
		{
			glDone = SharedSemaphore.create(device);
			vkDone = SharedSemaphore.create(device);
			try (MemoryStack stack = MemoryStack.stackPush())
			{
				VkCommandPoolCreateInfo pci = VkCommandPoolCreateInfo.calloc(stack)
					.sType$Default()
					.flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT)
					.queueFamilyIndex(vulkan.queueFamily());
				LongBuffer lp = stack.mallocLong(1);
				SharedImage.check(vkCreateCommandPool(device, pci, null, lp), "vkCreateCommandPool");
				commandPool = lp.get(0);
			}
		}

		input = SharedImage.create(device, width, height, INPUT_USAGE);
		output = SharedImage.create(device, width, height, OUTPUT_USAGE);
		frameCommands = allocate();
		recordFrame();
		initialTransition();
		log.debug("Bridge round trip images {}x{}, mode {}", width, height, mode);
	}

	/**
	 * S2-24: one round trip. Copies {@code srcFbo} into the input image, hands it to Vulkan, and makes GL wait for
	 * Vulkan's copy into the output image. Leaves read/draw framebuffers changed.
	 */
	public void run(int srcFbo, int srcWidth, int srcHeight, int filter)
	{
		// GL: frame into the shared input image
		glBindFramebuffer(GL_READ_FRAMEBUFFER, srcFbo);
		glBindFramebuffer(GL_DRAW_FRAMEBUFFER, input.glFbo);
		glBlitFramebuffer(0, 0, srcWidth, srcHeight, 0, 0, input.width, input.height, GL_COLOR_BUFFER_BIT, filter);
		if (mode == Mode.GL_ONLY)
		{
			// Diagnostic: present the imported input image directly (tests GL writes/reads of Vulkan memory)
			frames++;
			framesInSet++;
			probeIfDue();
			return;
		}

		// GL -> Vulkan: images handed over in the layouts the Vulkan commands expect
		int[] textures = {input.glTexture, output.glTexture};
		if (mode == Mode.COPY_FINISH)
		{
			glFinish(); // diagnostic: GL has fully finished writing the input before the hand-over
		}
		glSignalSemaphoreEXT(glDone.gl, NO_BUFFERS, textures, GL_LAYOUTS);
		glFlush(); // the signal must reach the GPU before Vulkan's submission waits on it

		// Vulkan: wait, copy input -> output, signal
		try (MemoryStack stack = MemoryStack.stackPush())
		{
			VkSubmitInfo si = VkSubmitInfo.calloc(stack)
				.sType$Default()
				.waitSemaphoreCount(1)
				.pWaitSemaphores(stack.longs(glDone.vk))
				.pWaitDstStageMask(stack.ints(VK_PIPELINE_STAGE_TRANSFER_BIT))
				.pCommandBuffers(stack.pointers(frameCommands))
				.pSignalSemaphores(stack.longs(vkDone.vk));
			SharedImage.check(vkQueueSubmit(queue, si, VK_NULL_HANDLE), "vkQueueSubmit");
		}

		// Vulkan -> GL: GPU-side wait; images come back in the layouts Vulkan left them in
		glWaitSemaphoreEXT(vkDone.gl, NO_BUFFERS, textures, GL_LAYOUTS);
		frames++;
		framesInSet++;
		probeIfDue();
	}

	private VkCommandBuffer allocate()
	{
		try (MemoryStack stack = MemoryStack.stackPush())
		{
			VkCommandBufferAllocateInfo ai = VkCommandBufferAllocateInfo.calloc(stack)
				.sType$Default()
				.commandPool(commandPool)
				.level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
				.commandBufferCount(1);
			PointerBuffer pb = stack.mallocPointer(1);
			SharedImage.check(vkAllocateCommandBuffers(device, ai, pb), "vkAllocateCommandBuffers");
			return new VkCommandBuffer(pb.get(0), device);
		}
	}

	/** Per-frame commands, recorded once per image set and resubmitted every frame. */
	private void recordFrame()
	{
		try (MemoryStack stack = MemoryStack.stackPush())
		{
			VkCommandBufferBeginInfo bi = VkCommandBufferBeginInfo.calloc(stack)
				.sType$Default()
				.flags(VK_COMMAND_BUFFER_USAGE_SIMULTANEOUS_USE_BIT);
			SharedImage.check(vkBeginCommandBuffer(frameCommands, bi), "vkBeginCommandBuffer");

			// Acquire both images from GL (external queue family); layouts unchanged
			VkImageMemoryBarrier.Buffer acquire = VkImageMemoryBarrier.calloc(2, stack);
			barrier(acquire.get(0), input.image, 0, VK_ACCESS_TRANSFER_READ_BIT,
				VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
				externalFamily(), ownFamily());
			barrier(acquire.get(1), output.image, 0, VK_ACCESS_TRANSFER_WRITE_BIT,
				VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
				externalFamily(), ownFamily());
			// srcStage must be the stage the glDone wait blocks (TRANSFER), so the acquire is chained after GL's work.
			// With TOP_OF_PIPE it could run before GL finished, and the copy read black (8.4).
			vkCmdPipelineBarrier(frameCommands, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
				0, null, null, acquire);

			// Solid magenta written only by Vulkan. Vulkan-clear mode stops here (tests GL reads of Vulkan writes);
			// copy mode copies the input over it, so magenta on screen means the copy didn't happen, black means it
			// read black, and a normal image means it worked (old memory contents can't pass for a working copy).
			VkClearColorValue magenta = VkClearColorValue.calloc(stack);
			magenta.float32(0, 1f).float32(1, 0f).float32(2, 1f).float32(3, 1f);
			VkImageSubresourceRange.Buffer range = VkImageSubresourceRange.calloc(1, stack);
			range.get(0).aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
			vkCmdClearColorImage(frameCommands, output.image, VK_IMAGE_LAYOUT_GENERAL, magenta, range);

			if (mode != Mode.VK_CLEAR)
			{
				// Clear before copy: both write the output in the transfer stage
				VkImageMemoryBarrier.Buffer afterClear = VkImageMemoryBarrier.calloc(1, stack);
				barrier(afterClear.get(0), output.image, VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
					VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
					VK_QUEUE_FAMILY_IGNORED, VK_QUEUE_FAMILY_IGNORED);
				vkCmdPipelineBarrier(frameCommands, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
					0, null, null, afterClear);

				VkImageCopy.Buffer region = VkImageCopy.calloc(1, stack);
				region.srcSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
				region.dstSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
				region.extent().width(output.width).height(output.height).depth(1);
				vkCmdCopyImage(frameCommands, input.image, VK_IMAGE_LAYOUT_GENERAL,
					output.image, VK_IMAGE_LAYOUT_GENERAL, region);
			}

			// Release both images back to GL
			VkImageMemoryBarrier.Buffer release = VkImageMemoryBarrier.calloc(2, stack);
			barrier(release.get(0), input.image, VK_ACCESS_TRANSFER_READ_BIT, 0,
				VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
				ownFamily(), externalFamily());
			barrier(release.get(1), output.image, VK_ACCESS_TRANSFER_WRITE_BIT, 0,
				VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
				ownFamily(), externalFamily());
			vkCmdPipelineBarrier(frameCommands, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
				0, null, null, release);

			SharedImage.check(vkEndCommandBuffer(frameCommands), "vkEndCommandBuffer");
		}
	}

	/**
	 * Put new images into the layouts the frame commands expect, hand them to GL, and make GL wait for that, so
	 * the first frame starts from a known state. Waits for the queue once (only at creation and resize).
	 */
	private void initialTransition()
	{
		VkCommandBuffer cb = allocate();
		try (MemoryStack stack = MemoryStack.stackPush())
		{
			VkCommandBufferBeginInfo bi = VkCommandBufferBeginInfo.calloc(stack)
				.sType$Default()
				.flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
			SharedImage.check(vkBeginCommandBuffer(cb, bi), "vkBeginCommandBuffer");

			// Out of UNDEFINED into GENERAL, still owned by Vulkan
			VkImageMemoryBarrier.Buffer toGeneral = VkImageMemoryBarrier.calloc(2, stack);
			barrier(toGeneral.get(0), input.image, 0, VK_ACCESS_TRANSFER_WRITE_BIT, VK_IMAGE_LAYOUT_UNDEFINED,
				VK_IMAGE_LAYOUT_GENERAL, VK_QUEUE_FAMILY_IGNORED, VK_QUEUE_FAMILY_IGNORED);
			barrier(toGeneral.get(1), output.image, 0, VK_ACCESS_TRANSFER_WRITE_BIT, VK_IMAGE_LAYOUT_UNDEFINED,
				VK_IMAGE_LAYOUT_GENERAL, VK_QUEUE_FAMILY_IGNORED, VK_QUEUE_FAMILY_IGNORED);
			vkCmdPipelineBarrier(cb, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
				0, null, null, toGeneral);

			// Clear both once in Vulkan. A fresh image only transitioned out of UNDEFINED left Vulkan's view of it
			// uninitialised on NVIDIA, so Vulkan read GL's writes as black until the memory had been cleared by
			// Vulkan at least once (8.4). Clearing initialises it.
			VkClearColorValue black = VkClearColorValue.calloc(stack);
			black.float32(0, 0f).float32(1, 0f).float32(2, 0f).float32(3, 1f);
			VkImageSubresourceRange.Buffer range = VkImageSubresourceRange.calloc(1, stack);
			range.get(0).aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
			vkCmdClearColorImage(cb, input.image, VK_IMAGE_LAYOUT_GENERAL, black, range);
			vkCmdClearColorImage(cb, output.image, VK_IMAGE_LAYOUT_GENERAL, black, range);

			// Release both to GL
			VkImageMemoryBarrier.Buffer release = VkImageMemoryBarrier.calloc(2, stack);
			barrier(release.get(0), input.image, VK_ACCESS_TRANSFER_WRITE_BIT, 0, VK_IMAGE_LAYOUT_GENERAL,
				VK_IMAGE_LAYOUT_GENERAL, ownFamily(), externalFamily());
			barrier(release.get(1), output.image, VK_ACCESS_TRANSFER_WRITE_BIT, 0, VK_IMAGE_LAYOUT_GENERAL,
				VK_IMAGE_LAYOUT_GENERAL, ownFamily(), externalFamily());
			vkCmdPipelineBarrier(cb, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
				0, null, null, release);
			SharedImage.check(vkEndCommandBuffer(cb), "vkEndCommandBuffer");

			VkSubmitInfo si = VkSubmitInfo.calloc(stack)
				.sType$Default()
				.pCommandBuffers(stack.pointers(cb))
				.pSignalSemaphores(stack.longs(vkDone.vk));
			SharedImage.check(vkQueueSubmit(queue, si, VK_NULL_HANDLE), "vkQueueSubmit");
			vkQueueWaitIdle(queue);
			vkFreeCommandBuffers(device, commandPool, cb);
		}
		glWaitSemaphoreEXT(vkDone.gl, NO_BUFFERS, new int[]{input.glTexture, output.glTexture}, GL_LAYOUTS);
	}

	/** Queue family for the GL side: external, or ignored when ownership transfers are off (diagnostic). */
	private int externalFamily()
	{
		return mode == Mode.COPY_NO_OWNERSHIP ? VK_QUEUE_FAMILY_IGNORED : VK_QUEUE_FAMILY_EXTERNAL;
	}

	/** Queue family for the Vulkan side: ours, or ignored when ownership transfers are off (diagnostic). */
	private int ownFamily()
	{
		return mode == Mode.COPY_NO_OWNERSHIP ? VK_QUEUE_FAMILY_IGNORED : vulkan.queueFamily();
	}

	private static void barrier(VkImageMemoryBarrier b, long image, int srcAccess, int dstAccess,
		int oldLayout, int newLayout, int srcFamily, int dstFamily)
	{
		b.sType$Default()
			.srcAccessMask(srcAccess)
			.dstAccessMask(dstAccess)
			.oldLayout(oldLayout)
			.newLayout(newLayout)
			.srcQueueFamilyIndex(srcFamily)
			.dstQueueFamilyIndex(dstFamily)
			.image(image);
		b.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
	}

	// ---- Diagnostic probe (test only; a CPU readback, which S2-25 forbids outside this test) ----

	private static final int PROBE_EVERY = 120;
	private long probeBuffer;
	private long probeMemory;

	/**
	 * Every {@link #PROBE_EVERY} frames (and on the first frame of an image set), log the centre pixel of the input
	 * and output images as GL sees them and as Vulkan sees them. Waits for both APIs, so only for diagnosis.
	 */
	private void probeIfDue()
	{
		if (framesInSet != 1 && framesInSet % PROBE_EVERY != 0)
		{
			return;
		}
		try
		{
			int cx = output.width / 2;
			int cy = output.height / 2;
			glFinish();
			String glIn = glPixel(input.glFbo, cx, cy);
			String glOut = glPixel(output.glFbo, cx, cy);
			String[] vk = vkPixels(cx, cy);
			log.info("Bridge probe: mode {}, frame {}: GL sees input {} output {}; Vulkan sees input {} output {}",
				mode, frames, glIn, glOut, vk[0], vk[1]);
		}
		catch (RuntimeException ex)
		{
			log.warn("Bridge probe failed", ex);
		}
	}

	private static String glPixel(int fbo, int x, int y)
	{
		try (MemoryStack stack = MemoryStack.stackPush())
		{
			java.nio.ByteBuffer px = stack.calloc(4);
			glBindFramebuffer(GL_READ_FRAMEBUFFER, fbo);
			glReadPixels(x, y, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, px);
			return rgba(px, 0);
		}
	}

	private static String rgba(java.nio.ByteBuffer b, int off)
	{
		return String.format("(%d,%d,%d,%d)", b.get(off) & 0xff, b.get(off + 1) & 0xff, b.get(off + 2) & 0xff,
			b.get(off + 3) & 0xff);
	}

	/** Copy the centre pixel of both images to a host-visible buffer in Vulkan and read it. */
	private String[] vkPixels(int x, int y)
	{
		vkDeviceWaitIdle(device);
		try (MemoryStack stack = MemoryStack.stackPush())
		{
			if (probeBuffer == 0)
			{
				org.lwjgl.vulkan.VkBufferCreateInfo bci = org.lwjgl.vulkan.VkBufferCreateInfo.calloc(stack)
					.sType$Default()
					.size(8)
					.usage(VK_BUFFER_USAGE_TRANSFER_DST_BIT)
					.sharingMode(VK_SHARING_MODE_EXCLUSIVE);
				LongBuffer lp = stack.mallocLong(1);
				SharedImage.check(vkCreateBuffer(device, bci, null, lp), "vkCreateBuffer");
				probeBuffer = lp.get(0);
				org.lwjgl.vulkan.VkMemoryRequirements req = org.lwjgl.vulkan.VkMemoryRequirements.malloc(stack);
				vkGetBufferMemoryRequirements(device, probeBuffer, req);
				org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties props =
					org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties.malloc(stack);
				vkGetPhysicalDeviceMemoryProperties(device.getPhysicalDevice(), props);
				int want = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
				int type = -1;
				for (int i = 0; i < props.memoryTypeCount(); i++)
				{
					if ((req.memoryTypeBits() & (1 << i)) != 0 && (props.memoryTypes(i).propertyFlags() & want) == want)
					{
						type = i;
						break;
					}
				}
				org.lwjgl.vulkan.VkMemoryAllocateInfo mai = org.lwjgl.vulkan.VkMemoryAllocateInfo.calloc(stack)
					.sType$Default()
					.allocationSize(req.size())
					.memoryTypeIndex(type);
				SharedImage.check(vkAllocateMemory(device, mai, null, lp), "vkAllocateMemory");
				probeMemory = lp.get(0);
				SharedImage.check(vkBindBufferMemory(device, probeBuffer, probeMemory, 0), "vkBindBufferMemory");
			}

			VkCommandBuffer cb = allocate();
			VkCommandBufferBeginInfo bi = VkCommandBufferBeginInfo.calloc(stack)
				.sType$Default()
				.flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
			SharedImage.check(vkBeginCommandBuffer(cb, bi), "vkBeginCommandBuffer");
			VkImageMemoryBarrier.Buffer b = VkImageMemoryBarrier.calloc(2, stack);
			barrier(b.get(0), input.image, VK_ACCESS_MEMORY_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT,
				VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL, VK_QUEUE_FAMILY_IGNORED, VK_QUEUE_FAMILY_IGNORED);
			barrier(b.get(1), output.image, VK_ACCESS_MEMORY_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT,
				VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL, VK_QUEUE_FAMILY_IGNORED, VK_QUEUE_FAMILY_IGNORED);
			vkCmdPipelineBarrier(cb, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, null, null, b);
			org.lwjgl.vulkan.VkBufferImageCopy.Buffer region = org.lwjgl.vulkan.VkBufferImageCopy.calloc(1, stack);
			region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
			region.imageOffset().set(x, y, 0);
			region.imageExtent().set(1, 1, 1);
			region.bufferOffset(0);
			vkCmdCopyImageToBuffer(cb, input.image, VK_IMAGE_LAYOUT_GENERAL, probeBuffer, region);
			region.bufferOffset(4);
			vkCmdCopyImageToBuffer(cb, output.image, VK_IMAGE_LAYOUT_GENERAL, probeBuffer, region);
			SharedImage.check(vkEndCommandBuffer(cb), "vkEndCommandBuffer");
			VkSubmitInfo si = VkSubmitInfo.calloc(stack).sType$Default().pCommandBuffers(stack.pointers(cb));
			SharedImage.check(vkQueueSubmit(queue, si, VK_NULL_HANDLE), "vkQueueSubmit");
			vkQueueWaitIdle(queue);
			vkFreeCommandBuffers(device, commandPool, cb);

			org.lwjgl.PointerBuffer pp = stack.mallocPointer(1);
			SharedImage.check(vkMapMemory(device, probeMemory, 0, 8, 0, pp), "vkMapMemory");
			java.nio.ByteBuffer data = org.lwjgl.system.MemoryUtil.memByteBuffer(pp.get(0), 8);
			String[] out = {rgba(data, 0), rgba(data, 4)};
			vkUnmapMemory(device, probeMemory);
			return out;
		}
	}

	private void destroyProbe()
	{
		if (probeBuffer != 0)
		{
			vkDestroyBuffer(device, probeBuffer, null);
			probeBuffer = 0;
		}
		if (probeMemory != 0)
		{
			vkFreeMemory(device, probeMemory, null);
			probeMemory = 0;
		}
	}

	private void destroyImages()
	{
		if (input == null && output == null)
		{
			return;
		}
		glFinish(); // GL is done with the images
		vkDeviceWaitIdle(device); // and so is Vulkan
		if (frameCommands != null)
		{
			vkFreeCommandBuffers(device, commandPool, frameCommands);
			frameCommands = null;
		}
		if (input != null)
		{
			input.close();
			input = null;
		}
		if (output != null)
		{
			output.close();
			output = null;
		}
	}

	/** S2-26, A-07: free everything. Must run before the Vulkan device is destroyed. Safe to call twice. */
	@Override
	public void close()
	{
		destroyImages();
		vkDeviceWaitIdle(device);
		destroyProbe();
		if (glDone != null)
		{
			glDone.close();
			glDone = null;
		}
		if (vkDone != null)
		{
			vkDone.close();
			vkDone = null;
		}
		if (commandPool != 0)
		{
			vkDestroyCommandPool(device, commandPool, null);
			commandPool = 0;
		}
	}
}

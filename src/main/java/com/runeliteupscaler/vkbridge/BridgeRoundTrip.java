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
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkImageCopy;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkSubmitInfo;
import static org.lwjgl.opengl.EXTSemaphore.GL_LAYOUT_TRANSFER_DST_EXT;
import static org.lwjgl.opengl.EXTSemaphore.GL_LAYOUT_TRANSFER_SRC_EXT;
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
	/** GL layouts matching the Vulkan layouts the images keep between frames */
	private static final int[] GL_LAYOUTS = {GL_LAYOUT_TRANSFER_SRC_EXT, GL_LAYOUT_TRANSFER_DST_EXT};

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

	public BridgeRoundTrip(VulkanDevice vulkan)
	{
		this.vulkan = vulkan;
		this.device = vulkan.device();
		this.queue = vulkan.queue();
	}

	/** The output image's GL framebuffer, to present after {@link #run}. */
	public int outputFbo()
	{
		return output.glFbo;
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
	public void ensure(int width, int height)
	{
		if (output != null && output.width == width && output.height == height)
		{
			return;
		}

		destroyImages();
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

		input = SharedImage.create(device, width, height);
		output = SharedImage.create(device, width, height);
		frameCommands = allocate();
		recordFrame();
		initialTransition();
		log.debug("Bridge round trip images {}x{}", width, height);
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

		// GL -> Vulkan: images handed over in the layouts the Vulkan commands expect
		int[] textures = {input.glTexture, output.glTexture};
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
				VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
				VK_QUEUE_FAMILY_EXTERNAL, vulkan.queueFamily());
			barrier(acquire.get(1), output.image, 0, VK_ACCESS_TRANSFER_WRITE_BIT,
				VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
				VK_QUEUE_FAMILY_EXTERNAL, vulkan.queueFamily());
			vkCmdPipelineBarrier(frameCommands, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
				0, null, null, acquire);

			VkImageCopy.Buffer region = VkImageCopy.calloc(1, stack);
			region.srcSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
			region.dstSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
			region.extent().width(output.width).height(output.height).depth(1);
			vkCmdCopyImage(frameCommands, input.image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
				output.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);

			// Release both images back to GL
			VkImageMemoryBarrier.Buffer release = VkImageMemoryBarrier.calloc(2, stack);
			barrier(release.get(0), input.image, VK_ACCESS_TRANSFER_READ_BIT, 0,
				VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
				vulkan.queueFamily(), VK_QUEUE_FAMILY_EXTERNAL);
			barrier(release.get(1), output.image, VK_ACCESS_TRANSFER_WRITE_BIT, 0,
				VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
				vulkan.queueFamily(), VK_QUEUE_FAMILY_EXTERNAL);
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
			VkImageMemoryBarrier.Buffer b = VkImageMemoryBarrier.calloc(2, stack);
			barrier(b.get(0), input.image, 0, 0, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
				vulkan.queueFamily(), VK_QUEUE_FAMILY_EXTERNAL);
			barrier(b.get(1), output.image, 0, 0, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
				vulkan.queueFamily(), VK_QUEUE_FAMILY_EXTERNAL);
			vkCmdPipelineBarrier(cb, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
				0, null, null, b);
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

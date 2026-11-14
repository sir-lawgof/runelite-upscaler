package com.runeliteupscaler;

import com.runeliteupscaler.renderer.GpuPlugin;
import com.runeliteupscaler.vkbridge.VulkanDevice;
import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class UpscalerPluginTest
{
	public static void main(String[] args) throws Exception
	{
		// S2-20: LWJGL's VkInstance constructor lists every device extension on the thread's MemoryStack, which
		// overflows the 64 KB default on drivers with many extensions (8.4, Q-08). LWJGL reads this size once,
		// when it first loads, so it has to be set here before RuneLite starts.
		if (System.getProperty(VulkanDevice.STACK_SIZE_PROPERTY) == null)
		{
			System.setProperty(VulkanDevice.STACK_SIZE_PROPERTY, "1024");
		}

		// S1-01: load the forked GPU renderer ("Upscaler GPU") as the plugin under test
		ExternalPluginManager.loadBuiltin(GpuPlugin.class);
		RuneLite.main(args);
	}
}

package com.runeliteupscaler;

import com.runeliteupscaler.renderer.GpuPlugin;
import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class UpscalerPluginTest
{
	public static void main(String[] args) throws Exception
	{
		// S1-01: load the forked GPU renderer ("Upscaler GPU") as the plugin under test
		ExternalPluginManager.loadBuiltin(GpuPlugin.class);
		RuneLite.main(args);
	}
}

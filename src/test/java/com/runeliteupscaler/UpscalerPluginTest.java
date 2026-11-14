package com.runeliteupscaler;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class UpscalerPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(UpscalerPlugin.class);
		RuneLite.main(args);
	}
}
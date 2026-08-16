package com.osrsadvisor.plugin;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

/**
 * Dev-client entry point. Launching net.runelite.client.RuneLite directly starts the client
 * without this plugin - external plugins are not discovered from the classpath, they have to
 * be registered. That is what loadBuiltin does.
 */
public class AdvisorPluginLauncher
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(AdvisorPlugin.class);
		RuneLite.main(args);
	}
}

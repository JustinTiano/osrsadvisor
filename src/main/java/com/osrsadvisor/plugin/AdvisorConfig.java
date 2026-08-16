package com.osrsadvisor.plugin;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup(AdvisorConfig.GROUP)
public interface AdvisorConfig extends Config
{
	String GROUP = "osrsadvisor";

	@ConfigItem(
		keyName = "url",
		name = "Ingest URL",
		description = "Where your OSRS Advisor companion server is listening. Everything this "
			+ "plugin sends - player name, skill xp, quest and diary completion, bank, "
			+ "inventory, equipment and GE offers - goes to this URL and nowhere else. "
			+ "Keep it pointed at a server you run and trust.",
		position = 1
	)
	default String url()
	{
		return "http://localhost:8777/api/ingest";
	}

	@ConfigItem(
		keyName = "intervalMinutes",
		name = "Heartbeat (minutes)",
		description = "Push even when nothing changed, so the server can tell live from stale. 0 disables.",
		position = 3
	)
	default int intervalMinutes()
	{
		return 15;
	}

	@ConfigItem(
		keyName = "debounceSeconds",
		name = "Debounce (seconds)",
		description = "Minimum gap between pushes. Bank rearranging fires a lot of events.",
		position = 4
	)
	default int debounceSeconds()
	{
		return 20;
	}

	@ConfigItem(
		keyName = "sendBank",
		name = "Send bank",
		description = "Replaces the Bank Memory copy-to-clipboard step.",
		position = 5
	)
	default boolean sendBank()
	{
		return true;
	}

	@ConfigItem(
		keyName = "sendCarried",
		name = "Send inventory and equipment",
		description = "Lets the planner check quest item requirements against what you actually carry.",
		position = 6
	)
	default boolean sendCarried()
	{
		return true;
	}

	@ConfigItem(
		keyName = "sendGeOffers",
		name = "Send GE offers",
		description = "In-flight offers and fill state, for 4-hour buy-limit tracking.",
		position = 7
	)
	default boolean sendGeOffers()
	{
		return true;
	}

	@ConfigItem(
		keyName = "sendSkills",
		name = "Send skill xp",
		description = "Live xp, so plans stop reading levels that are stale until you log out. "
			+ "Held in memory by the server and never written to disk.",
		position = 8
	)
	default boolean sendSkills()
	{
		return true;
	}
}

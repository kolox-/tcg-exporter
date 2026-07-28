package com.tcgexporter;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("tcgexporter")
public interface TcgExporterConfig extends Config
{
	@ConfigItem(
		keyName = "webhook",
		name = "Webhook URL",
		description = "Full URL to POST your osrs-tcg collection to. Empty (the default) means "
			+ "the plugin does nothing -- this is opt-in. Ask whoever runs your group's server for "
			+ "this URL.",
		warning = "This feature submits your IP address to a 3rd-party server not controlled or verified by RuneLite developers",
		position = 0
	)
	default String webhook()
	{
		return "";
	}

	@ConfigItem(
		keyName = "apiKey",
		name = "API key",
		description = "Your personal API key for the webhook, issued by whoever runs the server. "
			+ "Sent as the X-Api-Key header on every export.",
		secret = true,
		position = 1
	)
	default String apiKey()
	{
		return "";
	}

	@ConfigItem(
		keyName = "periodSeconds",
		name = "Export period (seconds)",
		description = "How often to check osrs-tcg's local collection for changes and export it, "
			+ "in seconds. Nothing is sent if nothing changed since the last export.",
		position = 2
	)
	default int periodSeconds()
	{
		return 30;
	}
}

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
		name = "Checkpoint period (seconds)",
		description = "New/sold cards are also exported immediately via osrs-tcg's live update API when "
			+ "available (foil status and pull time not yet known at that point). This setting controls "
			+ "how often, separately, to re-check osrs-tcg's actual saved checkpoint for the real foil/"
			+ "timestamp data, in seconds. Nothing is sent if nothing changed since the last export. Run "
			+ "::tcg-save in chat, or log out, to force that checkpoint sooner instead of waiting.",
		position = 2
	)
	default int periodSeconds()
	{
		return 30;
	}
}

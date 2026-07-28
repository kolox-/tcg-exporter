package com.tcgexporter;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.inject.Provides;
import java.io.IOException;
import java.time.temporal.ChronoUnit;
import java.util.List;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.task.Schedule;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Reads your locally-pulled osrs-tcg card collection (name, foil, when you pulled it) and POSTs it to a
 * webhook URL you configure. Read-only towards osrs-tcg -- see {@link TcgCollectionDecoder} -- and does
 * nothing at all until both a webhook URL and API key are configured (opt-in by default).
 * <p>
 * The export payload's shape is this plugin's own choice, not anything osrs-tcg defines: a JSON body
 * {@code {"instances":[{"cardName","foil","acquiredAtEpochMs"}, ...]}}, with the configured API key sent as
 * the {@code X-Api-Key} header. Point the webhook at whatever server you like that's willing to accept that.
 */
@Slf4j
@PluginDescriptor(
	name = "TCG Exporter",
	description = "Periodically exports your osrs-tcg card collection to a webhook you configure",
	tags = {"tcg", "cards", "collection", "export", "webhook"}
)
public class TcgExporterPlugin extends Plugin
{
	private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

	@Inject
	private Client client;

	@Inject
	private ConfigManager configManager;

	@Inject
	private Gson gson;

	@Inject
	private OkHttpClient okHttpClient;

	@Inject
	private TcgExporterConfig config;

	// Touched from both the @Schedule thread and OkHttp's callback thread.
	private volatile String lastExportedRawState;
	private volatile long lastExportAttemptAtMs;

	@Provides
	TcgExporterConfig getConfig(ConfigManager configManager)
	{
		return configManager.getConfig(TcgExporterConfig.class);
	}

	@Override
	protected void startUp()
	{
		lastExportedRawState = null;
		lastExportAttemptAtMs = 0L;
	}

	@Override
	protected void shutDown()
	{
		lastExportedRawState = null;
	}

	@Schedule(period = 5, unit = ChronoUnit.SECONDS, asynchronous = true)
	public void tick()
	{
		String webhook = config.webhook().trim();
		String apiKey = config.apiKey().trim();
		if (webhook.isEmpty() || apiKey.isEmpty())
		{
			// Opt-in: this plugin does nothing until both are configured.
			return;
		}

		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}

		int periodSeconds = Math.max(5, config.periodSeconds());
		long now = System.currentTimeMillis();
		if (now - lastExportAttemptAtMs < periodSeconds * 1000L)
		{
			return;
		}
		lastExportAttemptAtMs = now;

		String rawState = configManager.getRSProfileConfiguration("osrstcg", "state");
		if (rawState == null || rawState.isEmpty())
		{
			// osrs-tcg isn't installed, or hasn't saved a collection yet.
			return;
		}
		if (rawState.equals(lastExportedRawState))
		{
			return;
		}

		List<OwnedInstance> instances = TcgCollectionDecoder.decode(gson, rawState);
		if (instances.isEmpty())
		{
			return;
		}

		export(webhook, apiKey, instances, rawState);
	}

	private void export(String webhook, String apiKey, List<OwnedInstance> instances, String rawStateAtExportTime)
	{
		HttpUrl url = HttpUrl.parse(webhook);
		if (url == null)
		{
			log.debug("Webhook URL is not valid, skipping export: {}", webhook);
			return;
		}

		JsonArray array = new JsonArray();
		for (OwnedInstance inst : instances)
		{
			JsonObject obj = new JsonObject();
			obj.addProperty("cardName", inst.cardName);
			obj.addProperty("foil", inst.foil);
			obj.addProperty("acquiredAtEpochMs", inst.pulledAtEpochMs);
			array.add(obj);
		}
		JsonObject body = new JsonObject();
		body.add("instances", array);

		Request request = new Request.Builder()
			.url(url)
			.header("X-Api-Key", apiKey)
			.post(RequestBody.create(JSON, gson.toJson(body)))
			.build();

		okHttpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("Export failed, will retry next tick", e);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				if (response.isSuccessful())
				{
					lastExportedRawState = rawStateAtExportTime;
				}
				else
				{
					log.debug("Webhook rejected export: HTTP {}", response.code());
				}
				response.close();
			}
		});
	}
}

package com.tcgexporter;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.inject.Provides;
import java.io.IOException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.PluginMessage;
import net.runelite.client.events.RuneScapeProfileChanged;
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
 * Two complementary export paths feed the same webhook:
 * <ul>
 *     <li><b>Live path</b>: osrs-tcg's own {@code PluginMessage} API pushes the full set of owned card
 *     <i>names</i> the instant your collection changes (in-memory, no save/checkpoint involved) -- but
 *     that API folds away foil status and pull timestamps. New names are exported immediately with
 *     placeholder values ({@code foil=false}, {@code acquiredAtEpochMs=now}).</li>
 *     <li><b>Checkpoint path</b>: periodically decodes osrs-tcg's actual saved state via
 *     {@code ConfigManager}, which alone has the real foil/timestamp data, but is only updated on
 *     osrs-tcg's own save triggers (logout, shutdown, {@code ::tcg-save}, etc).</li>
 * </ul>
 * Whichever ran most recently wins for a given card, so a placeholder from the live path is
 * automatically corrected the next time the checkpoint path runs and finds the same card with accurate
 * data. A backend that treats each sync as that player's complete current collection (diffing and
 * replacing, not appending) self-heals this with no special-casing needed.
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

	// osrs-tcg's PluginMessage API (its OwnedCardNamesApiService). We post a query; it replies with
	// "owned-names" and pushes "owned-names-changed" after every collection change. String constants
	// are copied, not imported -- Hub plugins can't see each other's classes.
	private static final String TCG_API_NAMESPACE = "osrstcg";
	private static final String TCG_API_QUERY = "query-owned-names";
	private static final String TCG_API_REPLY = "owned-names";
	private static final String TCG_API_CHANGED = "owned-names-changed";
	private static final String TCG_API_NAMES_KEY = "ownedNames";

	@Inject
	private Client client;

	@Inject
	private ConfigManager configManager;

	@Inject
	private Gson gson;

	@Inject
	private OkHttpClient okHttpClient;

	@Inject
	private EventBus eventBus;

	@Inject
	private TcgExporterConfig config;

	// All touched from more than one thread (the @Schedule thread, the thread that dispatches
	// PluginMessage -- likely the client thread -- and OkHttp's callback thread), so every mutable
	// field here is either volatile or replaced wholesale with a fresh immutable snapshot.
	private volatile String lastSeenRawState;
	private volatile long lastFullCheckAttemptAtMs;
	private volatile String lastExportedSignature = "";
	private volatile Map<String, List<OwnedInstance>> lastKnownByName = Collections.emptyMap();
	// Null until the live API has answered at least once.
	private volatile Set<String> lastLiveNames;

	@Provides
	TcgExporterConfig getConfig(ConfigManager configManager)
	{
		return configManager.getConfig(TcgExporterConfig.class);
	}

	@Override
	protected void startUp()
	{
		lastSeenRawState = null;
		lastFullCheckAttemptAtMs = 0L;
		lastExportedSignature = "";
		lastKnownByName = Collections.emptyMap();
		lastLiveNames = null;
	}

	@Override
	protected void shutDown()
	{
		lastSeenRawState = null;
		lastExportedSignature = "";
		lastKnownByName = Collections.emptyMap();
		lastLiveNames = null;
	}

	@Subscribe
	public void onRuneScapeProfileChanged(RuneScapeProfileChanged event)
	{
		// A different account's collection is about to load -- forget everything about the old one
		// so its cards can never leak into the new profile's export, then re-baseline.
		lastLiveNames = null;
		lastKnownByName = Collections.emptyMap();
		lastExportedSignature = "";
		lastSeenRawState = null;
		queryLiveApi();
	}

	@Subscribe
	public void onPluginMessage(PluginMessage event)
	{
		if (!TCG_API_NAMESPACE.equals(event.getNamespace())
			|| (!TCG_API_REPLY.equals(event.getName()) && !TCG_API_CHANGED.equals(event.getName())))
		{
			return;
		}

		Map<String, Object> data = event.getData();
		Object namesObj = data == null ? null : data.get(TCG_API_NAMES_KEY);
		if (!(namesObj instanceof List))
		{
			return;
		}

		Set<String> names = new LinkedHashSet<>();
		for (Object n : (List<?>) namesObj)
		{
			if (n instanceof String)
			{
				String trimmed = ((String) n).trim();
				if (!trimmed.isEmpty())
				{
					names.add(trimmed);
				}
			}
		}
		lastLiveNames = names;

		String webhook = config.webhook().trim();
		String apiKey = config.apiKey().trim();
		if (webhook.isEmpty() || apiKey.isEmpty() || client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}

		maybeExport(webhook, apiKey, mergeLiveNames(names));
	}

	@Schedule(period = 5, unit = ChronoUnit.SECONDS, asynchronous = true)
	public void tick()
	{
		if (lastLiveNames == null)
		{
			// Cheap retry until osrs-tcg answers -- harmless no-op if it isn't installed at all.
			queryLiveApi();
		}

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
		if (now - lastFullCheckAttemptAtMs < periodSeconds * 1000L)
		{
			return;
		}
		lastFullCheckAttemptAtMs = now;

		String rawState = configManager.getRSProfileConfiguration("osrstcg", "state");
		if (rawState == null || rawState.isEmpty())
		{
			// osrs-tcg isn't installed, or hasn't saved a checkpoint yet.
			return;
		}
		if (rawState.equals(lastSeenRawState))
		{
			return;
		}
		lastSeenRawState = rawState;

		List<OwnedInstance> instances = TcgCollectionDecoder.decode(gson, rawState);
		// Accurate foil/timestamp data for the live path to merge with, whether or not this
		// particular checkpoint ends up being exported below.
		lastKnownByName = groupByName(instances);

		maybeExport(webhook, apiKey, instances);
	}

	private void queryLiveApi()
	{
		eventBus.post(new PluginMessage(TCG_API_NAMESPACE, TCG_API_QUERY));
	}

	/**
	 * Fills in the current best-known detail for a live name-only snapshot: known cards keep their
	 * real foil/timestamp from the last checkpoint decode, brand new names get a placeholder that a
	 * later checkpoint will correct. A name absent from {@code names} is simply not included --
	 * sold/traded-away cards drop out of the next export this way.
	 */
	private List<OwnedInstance> mergeLiveNames(Set<String> names)
	{
		Map<String, List<OwnedInstance>> known = lastKnownByName;
		List<OwnedInstance> merged = new ArrayList<>(names.size());
		long now = System.currentTimeMillis();
		for (String name : names)
		{
			List<OwnedInstance> variants = known.get(name);
			if (variants != null && !variants.isEmpty())
			{
				merged.addAll(variants);
			}
			else
			{
				merged.add(new OwnedInstance(name, false, now));
			}
		}
		return merged;
	}

	private static Map<String, List<OwnedInstance>> groupByName(List<OwnedInstance> instances)
	{
		Map<String, List<OwnedInstance>> byName = new HashMap<>();
		for (OwnedInstance inst : instances)
		{
			byName.computeIfAbsent(inst.cardName, k -> new ArrayList<>()).add(inst);
		}
		return byName;
	}

	private static String signatureOf(List<OwnedInstance> instances)
	{
		List<String> parts = new ArrayList<>(instances.size());
		for (OwnedInstance inst : instances)
		{
			parts.add(inst.cardName + " " + inst.foil + " " + inst.pulledAtEpochMs);
		}
		Collections.sort(parts);
		return String.join("\n", parts);
	}

	private void maybeExport(String webhook, String apiKey, List<OwnedInstance> instances)
	{
		if (instances.isEmpty())
		{
			return;
		}
		String signature = signatureOf(instances);
		if (signature.equals(lastExportedSignature))
		{
			return;
		}
		export(webhook, apiKey, instances, signature);
	}

	private void export(String webhook, String apiKey, List<OwnedInstance> instances, String signatureAtExportTime)
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
				log.debug("Export failed, will retry next change", e);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				if (response.isSuccessful())
				{
					lastExportedSignature = signatureAtExportTime;
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

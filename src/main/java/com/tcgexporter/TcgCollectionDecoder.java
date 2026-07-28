package com.tcgexporter;

import com.google.gson.Gson;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.zip.GZIPInputStream;
import lombok.extern.slf4j.Slf4j;

/**
 * Reads osrs-tcg's own collection out of {@code ConfigManager.getRSProfileConfiguration("osrstcg", "state")}.
 * <p>
 * Reimplemented here rather than imported: this plugin has no compile-time dependency on osrs-tcg (a separate
 * Hub artifact with its own classloader), and the on-disk format is osrs-tcg's own publicly-documented encoding
 * (gzip -> XOR with a fixed salt -> base64, see its {@code TcgStateStorageEncoding}/{@code TcgStateCodec}) --
 * there is nothing secret being reversed here, just a transform this plugin needs to mirror. Deliberately kept
 * independent from any other project's copy of this same logic (e.g. a private companion tool) -- this plugin
 * is meant to stand alone and be Hub-reviewable on its own.
 */
@Slf4j
final class TcgCollectionDecoder
{
	private static final String STORAGE_PREFIX = "RLTCG_v2:";

	// Must match osrs-tcg's TcgStateStorageEncoding.XOR_SALT exactly.
	private static final byte[] XOR_SALT = {
		(byte) 0x52, (byte) 0x4c, (byte) 0x54, (byte) 0x43, (byte) 0x47,
		(byte) 0x7c, (byte) 0x6f, (byte) 0x73, (byte) 0x72, (byte) 0x73,
		(byte) 0x2d, (byte) 0x74, (byte) 0x63, (byte) 0x67, (byte) 0x21,
	};

	private TcgCollectionDecoder()
	{
	}

	/**
	 * @param rawState the raw string from {@code ConfigManager.getRSProfileConfiguration("osrstcg", "state")}
	 * @return owned instances, or an empty list if osrs-tcg isn't installed / has no state / the format changed
	 */
	static List<OwnedInstance> decode(Gson gson, String rawState)
	{
		String plainJson = decodeToJson(rawState);
		if (plainJson.isEmpty())
		{
			return List.of();
		}

		SerializedState state;
		try
		{
			state = gson.fromJson(plainJson, SerializedState.class);
		}
		catch (Exception ex)
		{
			log.debug("Failed to parse osrs-tcg state JSON (format may have changed upstream)", ex);
			return List.of();
		}
		if (state == null)
		{
			return List.of();
		}

		if (state.cardEntries != null && !state.cardEntries.isEmpty())
		{
			return fromCardEntries(state.cardEntries);
		}
		return fromLegacyInstances(state.cardInstances);
	}

	private static String decodeToJson(String stored)
	{
		String s = Objects.requireNonNullElse(stored, "");
		if (s.isEmpty() || !s.startsWith(STORAGE_PREFIX))
		{
			return "";
		}
		try
		{
			byte[] compressed = Base64.getDecoder().decode(s.substring(STORAGE_PREFIX.length()));
			xorWithSalt(compressed);
			try (GZIPInputStream gzis = new GZIPInputStream(new ByteArrayInputStream(compressed)))
			{
				return new String(gzis.readAllBytes(), StandardCharsets.UTF_8);
			}
		}
		catch (IllegalArgumentException | IOException ex)
		{
			log.debug("Failed to decode osrs-tcg state (format may have changed upstream)", ex);
			return "";
		}
	}

	private static void xorWithSalt(byte[] data)
	{
		for (int i = 0; i < data.length; i++)
		{
			data[i] ^= XOR_SALT[i % XOR_SALT.length];
		}
	}

	private static List<OwnedInstance> fromCardEntries(List<CardEntryDto> entries)
	{
		List<OwnedInstance> out = new ArrayList<>();
		for (CardEntryDto entry : entries)
		{
			if (entry == null || entry.cardName == null || entry.cardName.trim().isEmpty() || entry.variants == null)
			{
				continue;
			}
			String cardName = entry.cardName.trim();
			for (CardVariantDto variant : entry.variants)
			{
				if (variant == null)
				{
					continue;
				}
				// Fresh (schemaVersion 5) saves write exactly one variant per physical copy, so quantity is
				// normally absent. We don't expand a batched legacy quantity into N duplicate rows -- a
				// webhook consumer only needs to know a (card, foil) pair exists, not how many copies.
				int quantity = variant.quantity == null ? 1 : variant.quantity;
				if (quantity <= 0)
				{
					continue;
				}
				boolean foil = Boolean.TRUE.equals(variant.foil);
				long pulledAt = variant.pulledAt == null ? 0L : Math.max(0L, variant.pulledAt);
				out.add(new OwnedInstance(cardName, foil, pulledAt));
			}
		}
		return out;
	}

	private static List<OwnedInstance> fromLegacyInstances(List<LegacyInstanceDto> instances)
	{
		List<OwnedInstance> out = new ArrayList<>();
		if (instances == null)
		{
			return out;
		}
		for (LegacyInstanceDto row : instances)
		{
			if (row == null || row.cardName == null || row.cardName.trim().isEmpty())
			{
				continue;
			}
			long pulledAt = Math.max(0L, row.pulledAt);
			out.add(new OwnedInstance(row.cardName.trim(), row.foil, pulledAt));
		}
		return out;
	}

	private static final class SerializedState
	{
		List<CardEntryDto> cardEntries;
		List<LegacyInstanceDto> cardInstances;
	}

	private static final class CardEntryDto
	{
		String cardName;
		List<CardVariantDto> variants;
	}

	private static final class CardVariantDto
	{
		Boolean foil;
		Long pulledAt;
		Integer quantity;
		String pulledBy; // unused: this plugin exports per-account collections, not in-game pull attribution
	}

	private static final class LegacyInstanceDto
	{
		String cardName;
		boolean foil;
		long pulledAt;
	}
}

package com.tcgexporter;

import com.google.gson.Gson;
import java.util.List;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

/**
 * Regression test for a fresh, independent re-implementation of osrs-tcg's RLTCG_v2 decode.
 * The fixture below is real output of the actual encode algorithm (gzip -> XOR with osrs-tcg's
 * published salt -> base64), generated once via a small Python script mirroring
 * TcgStateStorageEncoding.encode() exactly -- not hand-written -- encoding:
 *   cardEntries: [
 *     {cardName: "Abyssal whip", variants: [{foil: true, pulledBy: "Zezima", pulledAt: 1712345678901}]},
 *     {cardName: "'24-carat' sword", variants: [{pulledAt: 1700000000000}]}
 *   ]
 */
public class TcgCollectionDecoderTest
{
	private static final String FIXTURE = "RLTCG_v2:TcdcQ5MrBxlwjEj5UmijYlzRvA2v6nZh+Q2+5e6K8c/SouMbByF4GqZwapj8gVRZsuEHsM4JHM/aydagQRbzGYQP+fX+"
		+ "JE2Roh+Kgl3tDygM63U7gs9OWWC7X/m+e96TD2ZqjFyqOcJIofoT+TLBuVuFkMab72j0UrOGgVRp9vulOpe91dxpnqlI8SIlf1RZoc13heH3mLOWBh+IdHlZlAp6"
		+ "jrWyjUxUQw==";

	private final Gson gson = new Gson();

	@Test
	public void decodesRealFixtureCorrectly()
	{
		List<OwnedInstance> instances = TcgCollectionDecoder.decode(gson, FIXTURE);
		assertEquals(2, instances.size());

		Map<String, OwnedInstance> byName = new java.util.HashMap<>();
		for (OwnedInstance inst : instances)
		{
			byName.put(inst.cardName, inst);
		}

		OwnedInstance whip = byName.get("Abyssal whip");
		assertTrue("Abyssal whip should be present", whip != null);
		assertTrue("Abyssal whip should be foil", whip.foil);
		assertEquals(1712345678901L, whip.pulledAtEpochMs);

		OwnedInstance sword = byName.get("'24-carat' sword");
		assertTrue("sword should be present", sword != null);
		assertFalse("sword should not be foil", sword.foil);
		assertEquals(1700000000000L, sword.pulledAtEpochMs);
	}

	@Test
	public void emptyOrMissingPrefixReturnsEmptyList()
	{
		assertTrue(TcgCollectionDecoder.decode(gson, "").isEmpty());
		assertTrue(TcgCollectionDecoder.decode(gson, null).isEmpty());
		assertTrue(TcgCollectionDecoder.decode(gson, "not-the-right-format").isEmpty());
	}
}

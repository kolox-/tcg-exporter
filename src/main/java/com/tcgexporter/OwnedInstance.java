package com.tcgexporter;

/** One owned copy of a card, as read from osrs-tcg's local collection. */
final class OwnedInstance
{
	final String cardName;
	final boolean foil;
	final long pulledAtEpochMs;

	OwnedInstance(String cardName, boolean foil, long pulledAtEpochMs)
	{
		this.cardName = cardName;
		this.foil = foil;
		this.pulledAtEpochMs = pulledAtEpochMs;
	}
}

# TCG Exporter

A RuneLite plugin that periodically exports your [osrs-tcg](https://github.com/Azderi/osrs-tcg)
card collection to a webhook URL you configure. Built as a Plugin Hub submission (public,
reviewed by RuneLite's team) rather than a privately-distributed tool, specifically so group
members don't need to run an unsigned standalone executable to keep their collection synced.

## Configuration

Three settings, all in the plugin's config panel:

- **Webhook URL** — the full URL to POST your collection to (e.g.
  `https://your-server.example/api/sync`). Empty by default, which means the plugin does
  nothing at all until you set this — it's opt-in.
- **API key** — sent as the `X-Api-Key` header on every export. Ask whoever runs your
  group's server for this.
- **Export period (seconds)** — how often to check for changes and export (default 30).
  Nothing is sent if your collection hasn't changed since the last successful export.

## What gets sent, and who defines the format

The payload shape is **this plugin's own choice** — it is not dictated by osrs-tcg. osrs-tcg's
own on-disk format (`RLTCG_v2:` + gzip + XOR + base64, see `TcgCollectionDecoder.java`) is just
what this plugin reads *from*; what it sends *to* your webhook is a separate, simple JSON body
this plugin defines itself:

```json
{
  "instances": [
    { "cardName": "Abyssal whip", "foil": true, "acquiredAtEpochMs": 1712345678901 }
  ]
}
```

`acquiredAtEpochMs` is osrs-tcg's own recorded pull timestamp, not the time of the export
request. Point the webhook at whatever backend you like that's willing to accept this shape —
it happens to match the `/api/sync` endpoint of
[osrs-tcg-pool](https://github.com/kolox-/osrs-tcg-pool), a private group collection tracker,
but this plugin has no dependency on that project.

## Data reading: read-only, no compile-time dependency on osrs-tcg

Like other companion plugins in this space (e.g. bronzeman-tcg, tcg-locked), this reads
osrs-tcg's collection via RuneLite's own `ConfigManager` (`getRSProfileConfiguration("osrstcg",
"state")`) and independently re-implements osrs-tcg's own published encoding to decode it. It
never touches osrs-tcg's save file, has no dependency on the osrs-tcg plugin at build or run
time, and works whether or not osrs-tcg happens to be loaded in the same client.

**Known limitation, inherited from how osrs-tcg itself saves data**: osrs-tcg only writes to
`ConfigManager` (which is what this plugin reads) on logout, client shutdown, plugin unload, or
an explicit save — there's no continuous autosave while playing. If a pull doesn't show up
promptly, running `::tcg-save` in your chat (a public osrs-tcg command, not gated behind debug
mode) forces an immediate save that this plugin will pick up on its next check.

## Plugin Hub compliance notes

This plugin submits data to a third-party server, which the Hub's guidelines require to be:

1. **Opt-in / disabled by default** — satisfied by the webhook URL defaulting to empty; the
   plugin is fully inert until both the webhook and API key are set.
2. **Carry a specific warning** on the relevant config item — the webhook field's `warning`
   is set to the exact required text: *"This feature submits your IP address to a 3rd-party
   server not controlled or verified by RuneLite developers."*

The Hub's rejected-features list also has a broader, separately-worded line — "Plugins which
expose player information over HTTP" — that reads ambiguously enough to warrant real
scrutiny before submitting. In practice, opt-in webhook/API export of *your own* collection
data is an established, already-approved pattern (e.g. [Dink](https://github.com/pajlads/DinkPlugin),
a widely-used Hub plugin built entirely around configurable webhook notifications; osrs-tcg's
own "web album" upload feature). This plugin follows the current template's explicit
opt-in-plus-warning requirement precisely, but final acceptance is a human review decision by
the RuneLite team, not something that can be guaranteed in advance.

## Building and testing it yourself

```
./gradlew build
```

To run it in a development client:

```
./gradlew run
```

If you have a Jagex account, follow [Using Jagex Accounts](https://github.com/runelite/runelite/wiki/Using-Jagex-Accounts)
to log into the dev client. This project's author (an AI coding agent, in the session that
wrote this) cannot verify in-game behavior itself — automating input against the real game
risks account bans and isn't something it will do. Compiling and the decoder's unit test
(`TcgCollectionDecoderTest`, checked against real encoded output from osrs-tcg's own algorithm)
were verified; the actual golden path to test in-game is:

1. Set a webhook URL (e.g. `https://webhook.site/...` for a quick manual check) and an API key.
2. Log in with an account that has osrs-tcg installed and some pulled cards.
3. Confirm a POST arrives at the webhook within `periodSeconds` seconds, with a JSON body
   matching the shape above.
4. Pull a new card (or run `::tcg-save` if it doesn't show up promptly) and confirm a second,
   updated export follows.

## Icon

Hub plugins may optionally include an `icon.png` (≤48×72px) at the repo root, shown next to the
plugin's listing. None is included yet — add one before submitting if you'd like.

## Publishing to the Plugin Hub

This repo is **not submitted anywhere yet** — publishing is a public, one-way action for you to
decide on, not something done automatically. When you're ready:

1. Make sure this repository is pushed to a **public** GitHub repo (Hub requirement).
2. Fork [runelite/plugin-hub](https://github.com/runelite/plugin-hub).
3. Add a file `plugins/tcg-exporter` there with:
   ```
   repository=https://github.com/kolox-/tcg-exporter.git
   commit=<40-character hash of your latest commit>
   ```
4. Open a PR against `runelite/plugin-hub` with a short description of what the plugin does.
5. Watch the PR's CI check and any review feedback; push updated commits (bumping `commit=`)
   until it's approved.

Full process: https://github.com/runelite/plugin-hub#submitting-a-plugin

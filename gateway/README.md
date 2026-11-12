# Mono Hi-Res Gateway — a Zenith / BitChord addon

A tiny addon server that makes the Monochrome direct-FLAC catalogue work
in Zenith (a BitChord fork) as a normal addon.

**Why this exists:** Auris's Hi-Res addon link
(`auris.shehriyar.ink/this-hi-res-addon-is-only-compatible-with-auris-…`)
is not an addon at all — it is a static message page with no manifest
behind it, so no app can add it as a source. The actual FLAC files come
from `tracks.monochrome.st`, a public host Auris's own open-source code
queries directly with no key and no account. This gateway translates that
host into the addon protocol Zenith already speaks, so Zenith can add it
like any other addon.

## Protocol (what Zenith expects, from its own source)

| Endpoint | Zenith asks | Gateway answers |
| --- | --- | --- |
| `GET /manifest.json` | addon id, name, resources | `{id: "zenith-mono-hires", name: "Mono Hi-Res Lossless", resources: ["search", "stream"], settings: [{key: "quality", default: "LOSSLESS", options: [LOSSLESS, HIGH, LOW]}]}` |
| `GET /search?q=…&quality=LOSSLESS` | tracks for a query | `{tracks: [{id, title, artist, album, duration (seconds), artworkURL, format: "flac", audioQuality: "LOSSLESS"}]}` mapped from `tracks.monochrome.st/search/tracks` |
| `GET /stream/{id}?quality=…` | playable URL for a track | `{url: "https://tracks.monochrome.st/track/{id}", format/codec/container: "flac", quality: "FLAC · 24-bit · 96 kHz", audioQuality: "LOSSLESS", mimeType: "audio/flac", sampleRate, bitDepth, bitrate}` — measured from a HEAD `Content-Length` + the duration seen at search time, exactly as Auris's `HiResMonochromeBackend` measures it. Below 300 kbps (a transcode in a FLAC container) is refused with HTTP 404, which Zenith reads as "try the next source". Unknown ids also 404. |

No `streamURL` is put on search rows on purpose: Zenith skips `/stream`
when a row carries one, and the measured figures (bitrate, bit depth,
sample rate) only exist on the `/stream` answer.

## Run locally

```sh
node server.mjs          # PORT env or 3000
curl localhost:3000/manifest.json
curl "localhost:3000/search?q=Blinding%20Lights"
curl localhost:3000/stream/154014286732070912
```

No dependencies, Node 18+.

## Deploy (you need a public URL — Zenith on your phone must reach it)

**Vercel — recommended.** Serverless, so it never sleeps (a sleeping host
fails Zenith's 20 s call timeout on first use).

1. This lives in `gateway/` of the techforfriend/Zenith fork.
2. On vercel.com: Add New → Project → import techforfriend/Zenith →
   set **Root Directory** to `gateway` → Deploy.
3. Your URL is `https://<project>.vercel.app` — paste that in Zenith.

**Render — free, but sleeps.** On render.com: New → Web Service →
import techforfriend/Zenith → set **Root Directory** to `gateway`
(it reads `gateway/render.yaml`) → Deploy. Free tier
sleeps after idle, so the first search after a while can time out and
only work on retry.

**Any Node host.** `npm install && node server.mjs` with `PORT` set.

## Add it in Zenith

Profile → Settings → Sources → Add an addon → paste the deployed base
URL (e.g. `https://<project>.vercel.app`) → Save. It appears as
"Mono Hi-Res Lossless". Play a track at Max quality and the player
should show the FLAC / Hi-Res badge; check the audio pipeline / nerd
stats for the measured bitrate.

## Limits, honestly

- The FLAC host is public and unauthenticated, but it is someone else's
  server: it can rate-limit, go down, or change, and then this gateway
  returns HTTP 502 on search (Zenith keeps the source and retries next
  track) — there is no fallback catalogue.
- Search is the host's own fuzzy search: a nonsense query still returns
  *something*. Zenith ranks rows against the requested track, so a wrong
  match falls back to YouTube instead of playing the wrong song.
- Only what the host holds is Hi-Res. A track the host lacks simply has
  no row here; Zenith falls back to YouTube as usual.
- Not affiliated with Auris, BitChord, Zenith or Monochrome.

// Mono Hi-Res Gateway — a BitChord/Zenith addon server backed by tracks.monochrome.st.
//
// Zenith (a BitChord fork) speaks the addon protocol: it fetches
// `/manifest.json`, then `/search?q=…&quality=…`, then `/stream/{id}`.
// This gateway translates those calls to the Monochrome direct-FLAC host
// (the same host Auris's own open-source Hi-Res source queries directly,
// with no key and no account) and answers in the exact JSON shapes Zenith
// parses (see AddonModels.kt: AddonManifest / AddonSearchResponse /
// AddonStream — parser is lenient, unknown keys are ignored).
//
// No dependencies, Node 18+. Run: `node server.mjs` (PORT env or 3000).
// Deploy: Vercel (recommended — serverless, no sleep) or Render (see
// render.yaml — free tier sleeps, so the first search after idle can be
// slow). The handler is exported for serverless adapters (api/index.mjs).

import http from "node:http";
import { fileURLToPath } from "node:url";

const UPSTREAM = "https://tracks.monochrome.st";

// Same neutral browser string Auris's own backend sends. The Monochrome
// host answers it; no app name, no key, no account is ever sent.
const USER_AGENT =
  "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36";

// Bitrate floors, mirrored from Auris's HiResMonochromeBackend so a file
// labelled the same way means the same thing here:
// CD PCM is 1411.2 kbps, hi-res PCM is 2116.8 kbps; below 300 kbps a "FLAC"
// is a transcode in a lossless container and is refused.
const CD_PCM_KBPS = 1411;
const HIRES_PCM_KBPS = 2116;
const MIN_GENUINE_KBPS = 300;

const SEARCH_LIMIT = 25;
const SEARCH_TIMEOUT_MS = 10_000;
const HEAD_TIMEOUT_MS = 6_000;

// Search results are remembered briefly (id -> durationMs) so /stream can
// measure the file's real bitrate from its HEAD Content-Length — the same
// memo Auris keeps in HiResMonochromeBackend. Zenith always searches
// before it streams, so the entry is normally there; when it is not, the
// stream is still returned, just without measured figures.
const origins = new Map(); // id -> { durationMs, at }
const MAX_ORIGINS = 256;
function memoize(id, durationMs) {
  if (!id || !durationMs || durationMs <= 0) return;
  if (origins.size >= MAX_ORIGINS) {
    const oldest = origins.keys().next().value;
    origins.delete(oldest);
  }
  origins.set(String(id), { durationMs, at: Date.now() });
}

function measuredKbps(sizeBytes, durationMs) {
  if (!sizeBytes || sizeBytes <= 0 || !durationMs || durationMs <= 0) return null;
  return Math.round(((sizeBytes * 8 * 1000) / durationMs) / 1000);
}

function qualityLabel(kbps) {
  if (kbps == null) return "FLAC · Lossless";
  const bits = kbps > CD_PCM_KBPS ? 24 : 16;
  const rate = kbps > HIRES_PCM_KBPS ? "96 kHz" : "44.1 kHz";
  return `FLAC · ${bits}-bit · ${rate}`;
}

function sendJson(res, status, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(status, {
    "Content-Type": "application/json; charset=utf-8",
    "Access-Control-Allow-Origin": "*",
    "Cache-Control": "no-store",
  });
  res.end(body);
}

function sendText(res, status, text, type = "text/plain; charset=utf-8") {
  res.writeHead(status, { "Content-Type": type, "Cache-Control": "no-store" });
  res.end(text);
}

const MANIFEST = {
  id: "zenith-mono-hires",
  name: "Mono Hi-Res Lossless",
  version: "1.0.0",
  resources: ["search", "stream"],
  settings: [
    {
      key: "quality",
      label: "Quality",
      default: "LOSSLESS",
      options: [
        { value: "LOSSLESS", label: "Lossless" },
        { value: "HIGH", label: "High" },
        { value: "LOW", label: "Low" },
      ],
    },
  ],
};

async function fetchUpstream(url, { method = "GET", timeoutMs = SEARCH_TIMEOUT_MS } = {}) {
  const res = await fetch(url, {
    method,
    headers: { Accept: "application/json", "User-Agent": USER_AGENT },
    signal: AbortSignal.timeout(timeoutMs),
  });
  return res;
}

function mapTrack(t) {
  const durationMs =
    typeof t.duration === "number" && t.duration > 0 ? t.duration : null;
  return {
    id: String(t.id ?? ""),
    title: t.title ?? "",
    artist: Array.isArray(t.artistNames) ? t.artistNames.join(", ") : "",
    album: t.albumTitle || t.releaseTitle || "",
    duration: durationMs == null ? null : durationMs / 1000,
    artworkURL: t.artwork ?? null,
    format: "flac",
    audioQuality: "LOSSLESS",
  };
}

async function handleSearch(url, res) {
  const q = (url.searchParams.get("q") ?? "").trim();
  // Zenith never sends an empty query (its client returns early), and the
  // upstream answers an empty q with HTTP 400 — shield that either way.
  if (!q) return sendJson(res, 200, { tracks: [] });

  let upstream;
  try {
    upstream = await fetchUpstream(
      `${UPSTREAM}/search/tracks?q=${encodeURIComponent(q)}&limit=${SEARCH_LIMIT}`,
    );
  } catch (e) {
    return sendJson(res, 502, { error: `Upstream search failed: ${e.message}` });
  }
  if (!upstream.ok) {
    return sendJson(res, 502, {
      error: `Upstream search failed (HTTP ${upstream.status})`,
    });
  }
  let data;
  try {
    data = await upstream.json();
  } catch {
    return sendJson(res, 502, { error: "Upstream search returned bad JSON" });
  }
  const tracks = (Array.isArray(data?.tracks) ? data.tracks : [])
    .filter((t) => t && t.playable !== false && String(t.id ?? "").length > 0)
    .map((t) => {
      if (typeof t.duration === "number") memoize(t.id, t.duration);
      return mapTrack(t);
    });
  return sendJson(res, 200, { tracks });
}

async function handleStream(id, res) {
  // Monochrome ids are numeric strings. Anything else can never be a track.
  if (!/^[0-9]+$/.test(id)) {
    return sendJson(res, 404, { error: "No playable audio for this track" });
  }
  const fileUrl = `${UPSTREAM}/track/${id}`;
  let lengthBytes = 0;
  let headOk = false;
  try {
    const head = await fetchUpstream(fileUrl, {
      method: "HEAD",
      timeoutMs: HEAD_TIMEOUT_MS,
    });
    headOk = head.status === 200 || head.status === 206;
    if (!headOk) {
      // A 4xx/5xx here is the host saying it has no such file — a miss,
      // which Zenith reads as "try the next source", not a fault.
      return sendJson(res, 404, { error: "No playable audio for this track" });
    }
    lengthBytes = Number(head.headers.get("content-length") ?? 0) || 0;
  } catch {
    // HEAD failed but the search already proved the id exists: still hand
    // the URL over unmeasured and let the player try, rather than failing
    // a track on a transient HEAD error.
    headOk = false;
  }

  const origin = origins.get(id);
  const kbps =
    headOk && lengthBytes > 0 && origin
      ? measuredKbps(lengthBytes, origin.durationMs)
      : null;

  // A file below the lossless floor is a transcode in a FLAC container —
  // refused, exactly as Auris's backend refuses it.
  if (kbps != null && kbps < MIN_GENUINE_KBPS) {
    return sendJson(res, 404, { error: "No playable audio for this track" });
  }

  const stream = {
    url: fileUrl,
    format: "flac",
    quality: qualityLabel(kbps),
    audioQuality: "LOSSLESS",
    codec: "flac",
    container: "flac",
    manifest: "none",
    mimeType: "audio/flac",
    encrypted: false,
  };
  if (kbps != null) {
    stream.bitrate = kbps;
    stream.bitDepth = kbps > CD_PCM_KBPS ? 24 : 16;
    stream.sampleRate = kbps > HIRES_PCM_KBPS ? 96000 : 44100;
  }
  return sendJson(res, 200, stream);
}

const INFO_PAGE = `<!doctype html>
<html lang="en"><head><meta charset="utf-8"><title>Mono Hi-Res Lossless — Zenith addon</title></head>
<body style="font-family:system-ui;max-width:640px;margin:40px auto;padding:0 16px">
<h1>Mono Hi-Res Lossless</h1>
<p>A BitChord/Zenith addon gateway. It translates the Monochrome direct-FLAC
host (<code>tracks.monochrome.st</code>) into the addon protocol Zenith speaks:
<code>/manifest.json</code>, <code>/search</code>, <code>/stream/{id}</code>.</p>
<p><b>Add it in Zenith:</b> Profile &gt; Settings &gt; Sources &gt; Add an addon,
then paste this server's base URL.</p>
<ul>
<li><a href="/manifest.json">/manifest.json</a></li>
<li><code>/search?q=Blinding%20Lights</code></li>
<li><code>/stream/{id}</code></li>
<li><a href="/health">/health</a></li>
</ul>
<p>Not affiliated with Auris, BitChord, Zenith or Monochrome. The FLAC host is
public and needs no key or account.</p>
</body></html>`;

export async function handleRequest(req, res) {
  const url = new URL(req.url ?? "/", "http://localhost");
  const path = url.pathname.replace(/\/+$/, "") || "/";
  try {
    if (req.method === "GET" && path === "/") {
      return sendText(res, 200, INFO_PAGE, "text/html; charset=utf-8");
    }
    if (req.method === "GET" && path === "/health") {
      return sendJson(res, 200, { ok: true, upstream: UPSTREAM });
    }
    if (req.method === "GET" && path === "/manifest.json") {
      return sendJson(res, 200, MANIFEST);
    }
    if (req.method === "GET" && path === "/search") {
      return await handleSearch(url, res);
    }
    if (req.method === "GET" && path.startsWith("/stream/")) {
      const id = decodeURIComponent(path.slice("/stream/".length)).split("/")[0];
      return await handleStream(id, res);
    }
    return sendJson(res, 404, { error: "not found" });
  } catch (e) {
    return sendJson(res, 500, { error: `Gateway error: ${e.message}` });
  }
}

const isMain =
  process.argv[1] &&
  fileURLToPath(import.meta.url) === process.argv[1];

if (isMain) {
  const port = Number(process.env.PORT ?? 3000);
  http.createServer(handleRequest).listen(port, "0.0.0.0", () => {
    console.log(`Mono Hi-Res gateway listening on :${port} (upstream ${UPSTREAM})`);
  });
}

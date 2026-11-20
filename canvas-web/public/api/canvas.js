const PROJECT_ID = "zenith-canvas-17133";
const API_KEY = "AIzaSyCO8txNS4UPwjrRKplPenuL-RA557gGhco";
const OLD_MANIFEST = "https://vivimusicanvas.mkmdevilmi.workers.dev/canvas.json";
const crypto = require("crypto");
const CACHE_MS = 60 * 1000;
const MAX_SKEW_SECONDS = 12 * 60 * 60;
const cache = { items: null, at: 0 };

function authorized(req) {
  const secret = process.env.CANVAS_SECRET;
  if (!secret) return true;
  const time = String(req.headers["x-zenith-time"] || "");
  const sig = String(req.headers["x-zenith-sig"] || "");
  if (!/^\d+$/.test(time)) return false;
  if (Math.abs(Date.now() / 1000 - Number(time)) > MAX_SKEW_SECONDS) return false;
  const want = crypto.createHmac("sha256", secret).update(time).digest("hex");
  const a = Buffer.from(sig);
  const b = Buffer.from(want);
  return a.length === b.length && crypto.timingSafeEqual(a, b);
}

function norm(text) {
  return String(text || "").toLowerCase().normalize("NFD").replace(/[\u0300-\u036f]/g, "")
    .replace(/[^a-z0-9\s]/g, " ").replace(/\s+/g, " ").trim();
}

function str(field) {
  return field && field.stringValue !== undefined ? field.stringValue : "";
}

function num(field) {
  return field && field.integerValue !== undefined ? Number(field.integerValue) : 0;
}

async function loadOwn() {
  const url = "https://firestore.googleapis.com/v1/projects/" + PROJECT_ID +
    "/databases/(default)/documents:runQuery?key=" + API_KEY;
  const res = await fetch(url, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      structuredQuery: {
        from: [{ collectionId: "canvases" }],
        where: {
          fieldFilter: {
            field: { fieldPath: "status" },
            op: "EQUAL",
            value: { stringValue: "approved" }
          }
        },
        limit: 1000
      }
    })
  });
  if (!res.ok) throw new Error("Firestore " + res.status);
  const rows = await res.json();
  const best = new Map();
  for (const row of rows) {
    if (!row.document) continue;
    const f = row.document.fields || {};
    const item = {
      song: str(f.song),
      artist: str(f.artist),
      album: str(f.album),
      url: str(f.url),
      likes: num(f.likes)
    };
    if (!item.song || !item.artist || !item.url) continue;
    const id = norm(item.song) + "|" + norm(item.artist) + "|" + norm(item.album);
    const current = best.get(id);
    if (!current || item.likes > current.likes) best.set(id, item);
  }
  return [...best.values()]
    .sort((a, b) => b.likes - a.likes)
    .map(({ song, artist, album, url }) => ({ song, artist, album, url }));
}

async function loadOld() {
  try {
    const res = await fetch(OLD_MANIFEST, { signal: AbortSignal.timeout(5000) });
    if (!res.ok) return [];
    const data = await res.json();
    return Array.isArray(data.items) ? data.items : [];
  } catch (error) {
    return [];
  }
}

module.exports = async (req, res) => {
  if (!authorized(req)) {
    res.setHeader("Cache-Control", "no-store");
    res.status(401).json({ error: "unauthorized" });
    return;
  }
  try {
    if (!cache.items || Date.now() - cache.at > CACHE_MS) {
      const [own, old] = await Promise.all([loadOwn(), loadOld()]);
      const seen = new Set(own.map((item) => item.url));
      cache.items = own.concat(old.filter((item) => item && item.url && !seen.has(item.url)));
      cache.at = Date.now();
    }
    const items = cache.items;
    res.setHeader("Content-Type", "application/json; charset=utf-8");
    res.setHeader("Cache-Control", "private, no-store");
    res.setHeader("Access-Control-Allow-Origin", "*");
    res.status(200).send(JSON.stringify({ items }));
  } catch (error) {
    res.setHeader("Cache-Control", "no-store");
    res.status(502).json({ error: String(error.message || error) });
  }
};
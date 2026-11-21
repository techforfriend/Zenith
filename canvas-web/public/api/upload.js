const crypto = require("crypto");
const { guard } = require("./_guard");

const PROJECT_ID = "zenith-canvas-17133";
const SUPABASE_URL = "https://ljckgisqhlypvszlckvf.supabase.co";
const FS = "https://firestore.googleapis.com/v1/projects/" + PROJECT_ID + "/databases/(default)/documents";
const DAILY_LIMIT = 3;
const MAX_BYTES = 3 * 1024 * 1024;
const tokenCache = { value: "", exp: 0 };

function b64u(obj) {
  return Buffer.from(JSON.stringify(obj)).toString("base64url");
}

function norm(text) {
  return String(text || "").toLowerCase().normalize("NFD").replace(/[\u0300-\u036f]/g, "")
    .replace(/[^a-z0-9\s]/g, " ").replace(/\s+/g, " ").trim();
}

async function googleToken() {
  const now = Math.floor(Date.now() / 1000);
  if (tokenCache.value && tokenCache.exp - 60 > now) return tokenCache.value;
  const sa = JSON.parse(process.env.FIREBASE_SERVICE_ACCOUNT);
  const head = b64u({ alg: "RS256", typ: "JWT" });
  const claim = b64u({
    iss: sa.client_email,
    scope: "https://www.googleapis.com/auth/datastore",
    aud: "https://oauth2.googleapis.com/token",
    iat: now,
    exp: now + 3600
  });
  const sig = crypto.createSign("RSA-SHA256").update(head + "." + claim).sign(sa.private_key).toString("base64url");
  const res = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion: head + "." + claim + "." + sig
    }),
    signal: AbortSignal.timeout(8000)
  });
  const json = await res.json();
  if (!res.ok || !json.access_token) throw new Error("google token " + res.status);
  tokenCache.value = json.access_token;
  tokenCache.exp = now + (json.expires_in || 3600);
  return tokenCache.value;
}

function readSession(req) {
  const header = String(req.headers.authorization || "");
  const token = header.startsWith("Bearer ") ? header.slice(7) : "";
  const parts = token.split(".");
  if (parts.length !== 2) return null;
  const want = crypto.createHmac("sha256", process.env.SESSION_SECRET).update(parts[0]).digest("base64url");
  const a = Buffer.from(parts[1]);
  const b = Buffer.from(want);
  if (a.length !== b.length || !crypto.timingSafeEqual(a, b)) return null;
  try {
    const payload = JSON.parse(Buffer.from(parts[0], "base64url").toString());
    return payload.uid && payload.exp > Date.now() / 1000 ? payload : null;
  } catch (error) {
    return null;
  }
}

async function isMember(userId) {
  const url = "https://api.telegram.org/bot" + process.env.TELEGRAM_BOT_TOKEN + "/getChatMember?chat_id=" +
    encodeURIComponent(process.env.TELEGRAM_CHAT_ID) + "&user_id=" + encodeURIComponent(userId);
  const res = await fetch(url, { signal: AbortSignal.timeout(8000) });
  const json = await res.json();
  if (!json.ok) throw new Error("telegram check failed");
  const status = json.result.status;
  if (status === "creator" || status === "administrator") return "admin";
  const member = status === "member" || (status === "restricted" && json.result.is_member === true);
  return member ? "member" : "";
}

async function recentUploads(uid) {
  const token = await googleToken();
  const headers = { Authorization: "Bearer " + token, "Content-Type": "application/json" };
  const res = await fetch(FS + ":runQuery", {
    method: "POST",
    headers,
    body: JSON.stringify({
      structuredQuery: {
        from: [{ collectionId: "uploadLog" }],
        where: { fieldFilter: { field: { fieldPath: "uid" }, op: "EQUAL", value: { stringValue: uid } } },
        limit: 200
      }
    }),
    signal: AbortSignal.timeout(8000)
  });
  if (!res.ok) throw new Error("firestore query " + res.status);
  const rows = await res.json();
  const since = Date.now() - 24 * 60 * 60 * 1000;
  let active = 0;
  let removed = 0;
  for (const row of rows) {
    const doc = row.document;
    if (!doc) continue;
    const field = doc.fields && doc.fields.at;
    const at = field && field.timestampValue ? Date.parse(field.timestampValue) : 0;
    if (at >= since) {
      active += 1;
      continue;
    }
    if (removed < 10) {
      removed += 1;
      try {
        await fetch("https://firestore.googleapis.com/v1/" + doc.name, {
          method: "DELETE",
          headers,
          signal: AbortSignal.timeout(8000)
        });
      } catch (error) {}
    }
  }
  return active;
}

async function hasDuplicate(uid, key) {
  const token = await googleToken();
  const res = await fetch(FS + ":runQuery", {
    method: "POST",
    headers: { Authorization: "Bearer " + token, "Content-Type": "application/json" },
    body: JSON.stringify({
      structuredQuery: {
        from: [{ collectionId: "canvases" }],
        where: {
          compositeFilter: {
            op: "AND",
            filters: [
              { fieldFilter: { field: { fieldPath: "uid" }, op: "EQUAL", value: { stringValue: uid } } },
              { fieldFilter: { field: { fieldPath: "key" }, op: "EQUAL", value: { stringValue: key } } }
            ]
          }
        },
        limit: 20
      }
    }),
    signal: AbortSignal.timeout(8000)
  });
  if (!res.ok) throw new Error("firestore query " + res.status);
  const rows = await res.json();
  return rows.some((row) => {
    const f = row.document && row.document.fields;
    return f && !(f.status && f.status.stringValue === "rejected");
  });
}

const REJECTED_TTL_MS = 3 * 60 * 60 * 1000;
const STALE_TTL_MS = 3 * 24 * 60 * 60 * 1000;
let lastPurge = 0;

async function purgeStale() {
  if (Date.now() - lastPurge < 60 * 60 * 1000) return;
  lastPurge = Date.now();
  try {
    const token = await googleToken();
    const headers = { Authorization: "Bearer " + token, "Content-Type": "application/json" };
    const res = await fetch(FS + ":runQuery", {
      method: "POST",
      headers,
      body: JSON.stringify({
        structuredQuery: {
          from: [{ collectionId: "canvases" }],
          where: { fieldFilter: { field: { fieldPath: "status" }, op: "NOT_EQUAL", value: { stringValue: "approved" } } },
          limit: 100
        }
      }),
      signal: AbortSignal.timeout(8000)
    });
    if (!res.ok) return;
    const rows = await res.json();
    const cutoff = Date.now() - STALE_TTL_MS;
    let removed = 0;
    for (const row of rows) {
      if (removed >= 5) break;
      const doc = row.document;
      if (!doc) continue;
      const f = doc.fields || {};
      if (f.status && f.status.stringValue === "approved") continue;
      const created = f.createdAt && f.createdAt.timestampValue ? Date.parse(f.createdAt.timestampValue) : 0;
      const isRejected = f.status && f.status.stringValue === "rejected";
      const limit = isRejected ? Date.now() - REJECTED_TTL_MS : cutoff;
      if (!created || created > limit) continue;
      const url = f.url && f.url.stringValue ? f.url.stringValue : "";
      const name = url.split("/").pop();
      if (!/^\d+-[0-9a-f-]{36}\.mp4$/.test(name)) continue;
      const gone = await fetch(
        "https://firestore.googleapis.com/v1/" + doc.name + "?currentDocument.updateTime=" + encodeURIComponent(doc.updateTime),
        { method: "DELETE", headers, signal: AbortSignal.timeout(8000) }
      );
      if (!gone.ok) continue;
      await fetch(SUPABASE_URL + "/storage/v1/object/canvas/" + name, {
        method: "DELETE",
        headers: { apikey: process.env.SUPABASE_SECRET_KEY },
        signal: AbortSignal.timeout(8000)
      });
      removed += 1;
    }
    await purgeOrphans(headers, cutoff);
  } catch (error) {}
}

async function purgeOrphans(headers, cutoff) {
  const res = await fetch(FS + ":runQuery", {
    method: "POST",
    headers,
    body: JSON.stringify({
      structuredQuery: {
        from: [{ collectionId: "canvases" }],
        select: { fields: [{ fieldPath: "url" }] },
        limit: 5000
      }
    }),
    signal: AbortSignal.timeout(8000)
  });
  if (!res.ok) return;
  const rows = await res.json();
  if (!Array.isArray(rows) || rows.length >= 5000) return;
  const known = new Set();
  for (const row of rows) {
    const field = row.document && row.document.fields && row.document.fields.url;
    if (field && field.stringValue) known.add(field.stringValue.split("/").pop());
  }
  const storageHeaders = { apikey: process.env.SUPABASE_SECRET_KEY, "Content-Type": "application/json" };
  let removed = 0;
  for (let offset = 0; offset < 500 && removed < 5; offset += 100) {
    const list = await fetch(SUPABASE_URL + "/storage/v1/object/list/canvas", {
      method: "POST",
      headers: storageHeaders,
      body: JSON.stringify({ prefix: "", limit: 100, offset, sortBy: { column: "created_at", order: "asc" } }),
      signal: AbortSignal.timeout(8000)
    });
    if (!list.ok) return;
    const files = await list.json();
    if (!Array.isArray(files) || !files.length) return;
    for (const file of files) {
      if (removed >= 5) break;
      const name = String(file.name || "");
      if (!/^\d+-[0-9a-f-]{36}\.mp4$/.test(name) || known.has(name)) continue;
      const made = Date.parse(file.created_at || "");
      if (!made || made > cutoff) continue;
      const gone = await fetch(SUPABASE_URL + "/storage/v1/object/canvas/" + name, {
        method: "DELETE",
        headers: { apikey: process.env.SUPABASE_SECRET_KEY },
        signal: AbortSignal.timeout(8000)
      });
      if (gone.ok) removed += 1;
    }
    if (files.length < 100) return;
  }
}

function esc(text) {
  return String(text).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
}

async function notifyGroup(docId, item) {
  try {
    const base = "https://api.telegram.org/bot" + process.env.TELEGRAM_BOT_TOKEN;
    const text = "\uD83C\uDFAC <b>New canvas awaiting approval</b>\n\n" +
      "<b>Title:</b> " + esc(item.song) + "\n" +
      "<b>Artist:</b> " + esc(item.artist) + "\n" +
      "<b>Album:</b> " + esc(item.album || "-") + "\n" +
      "<b>Uploader:</b> " + esc(item.uname || "-") + "\n" +
      "<b>Duration:</b> " + item.duration + " seconds\n\n" +
      '<a href="' + esc(item.url) + '">Open video</a>';
    const common = {
      chat_id: process.env.TELEGRAM_CHAT_ID,
      parse_mode: "HTML",
      reply_markup: {
        inline_keyboard: [[
          { text: "\u2705 Approve", callback_data: "ok:" + docId },
          { text: "\u274C Reject", callback_data: "no:" + docId }
        ]]
      }
    };
    if (process.env.TELEGRAM_THREAD_ID) common.message_thread_id = Number(process.env.TELEGRAM_THREAD_ID);
    const send = async (method, extra) => {
      const r = await fetch(base + "/" + method, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(Object.assign({}, common, extra)),
        signal: AbortSignal.timeout(8000)
      });
      return r.json();
    };
    const sent = await send("sendVideo", { video: item.url, caption: text, supports_streaming: true });
    if (!sent.ok) await send("sendMessage", { text, disable_web_page_preview: false });
  } catch (error) {}
}

module.exports = async (req, res) => {
  res.setHeader("Cache-Control", "no-store");
  if (!guard(req, res, "upload", 20, 60 * 1000)) return;
  if (req.method !== "POST") {
    res.status(405).json({ error: "method_not_allowed" });
    return;
  }
  const needed = ["SESSION_SECRET", "TELEGRAM_BOT_TOKEN", "TELEGRAM_CHAT_ID", "FIREBASE_SERVICE_ACCOUNT", "SUPABASE_SECRET_KEY"];
  if (needed.some((key) => !process.env[key])) {
    res.status(500).json({ error: "not_configured" });
    return;
  }
  const session = readSession(req);
  if (!session) {
    res.status(401).json({ error: "login_required" });
    return;
  }
  const body = req.body && typeof req.body === "object" ? req.body : {};
  try {
    const role = await isMember(session.uid);
    if (!role) {
      res.status(403).json({ error: "not_member" });
      return;
    }
    const used = role === "admin" ? 0 : await recentUploads(session.uid);
    if (role !== "admin" && used >= DAILY_LIMIT) {
      res.status(429).json({ error: "limit_reached", limit: DAILY_LIMIT });
      return;
    }

    if (body.action === "ticket") {
      await purgeStale();
      const name = session.uid + "-" + crypto.randomUUID() + ".mp4";
      const r = await fetch(SUPABASE_URL + "/storage/v1/object/upload/sign/canvas/" + name, {
        method: "POST",
        headers: { apikey: process.env.SUPABASE_SECRET_KEY, "Content-Type": "application/json" },
        body: "{}",
        signal: AbortSignal.timeout(8000)
      });
      if (!r.ok) throw new Error("supabase sign " + r.status);
      const data = await r.json();
      res.status(200).json({ name, uploadUrl: SUPABASE_URL + "/storage/v1" + data.url, remaining: DAILY_LIMIT - used });
      return;
    }

    if (body.action === "submit") {
      const name = String(body.name || "");
      if (!new RegExp("^" + session.uid + "-[0-9a-f-]{36}\\.mp4$").test(name)) {
        res.status(400).json({ error: "bad_file" });
        return;
      }
      const song = String(body.song || "").trim();
      const artist = String(body.artist || "").trim();
      const album = String(body.album || "").trim();
      if (!song || song.length >= 120 || !artist || artist.length >= 120 || album.length >= 120) {
        res.status(400).json({ error: "bad_text" });
        return;
      }
      if (await hasDuplicate(session.uid, norm(song) + "|" + norm(artist))) {
        res.status(409).json({ error: "duplicate" });
        return;
      }
      const duration = Number(body.duration);
      const width = Number(body.width);
      const height = Number(body.height);
      if (!(duration >= 3 && duration <= 8) || !Number.isInteger(width) || width !== height || width < 720 || width > 1280) {
        res.status(400).json({ error: "spec_invalid" });
        return;
      }
      const url = SUPABASE_URL + "/storage/v1/object/public/canvas/" + name;
      const head = await fetch(url, { method: "HEAD", signal: AbortSignal.timeout(8000) });
      const size = Number(head.headers.get("content-length") || 0);
      const type = head.headers.get("content-type") || "";
      if (!head.ok || !size || size > MAX_BYTES || !type.startsWith("video/mp4")) {
        res.status(400).json({ error: "file_invalid" });
        return;
      }
      const token = await googleToken();
      const logged = await fetch(FS + "/uploadLog", {
        method: "POST",
        headers: { Authorization: "Bearer " + token, "Content-Type": "application/json" },
        body: JSON.stringify({
          fields: {
            uid: { stringValue: session.uid },
            at: { timestampValue: new Date().toISOString() }
          }
        }),
        signal: AbortSignal.timeout(8000)
      });
      if (!logged.ok) throw new Error("firestore log " + logged.status);
      const created = await fetch(FS + "/canvases", {
        method: "POST",
        headers: { Authorization: "Bearer " + token, "Content-Type": "application/json" },
        body: JSON.stringify({
          fields: {
            song: { stringValue: song },
            artist: { stringValue: artist },
            album: { stringValue: album },
            key: { stringValue: norm(song) + "|" + norm(artist) },
            url: { stringValue: url },
            status: { stringValue: "pending" },
            likes: { integerValue: "0" },
            createdAt: { timestampValue: new Date().toISOString() },
            size: { integerValue: String(size) },
            duration: { doubleValue: duration },
            width: { integerValue: String(width) },
            height: { integerValue: String(height) },
            specVersion: { integerValue: "1" },
            uid: { stringValue: session.uid },
            uname: { stringValue: String(session.name || "") }
          }
        }),
        signal: AbortSignal.timeout(8000)
      });
      if (!created.ok) throw new Error("firestore create " + created.status);
      const createdDoc = await created.json();
      const docId = String(createdDoc.name || "").split("/").pop();
      if (docId) await notifyGroup(docId, { song, artist, album, url, duration, uname: session.name });
      res.status(200).json({ ok: true });
      return;
    }

    res.status(400).json({ error: "bad_action" });
  } catch (error) {
    res.status(502).json({ error: "server_error", detail: String(error.message || error).slice(0, 120) });
  }
};
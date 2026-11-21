const crypto = require("crypto");
const { guard } = require("./_guard");

const PROJECT_ID = "zenith-canvas-17133";
const FS = "https://firestore.googleapis.com/v1/projects/" + PROJECT_ID + "/databases/(default)/documents";
const SUPABASE_URL = "https://ljckgisqhlypvszlckvf.supabase.co";
const tokenCache = { value: "", exp: 0 };

function b64u(obj) {
  return Buffer.from(JSON.stringify(obj)).toString("base64url");
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

async function isAdmin(userId) {
  if (!process.env.TELEGRAM_BOT_TOKEN || !process.env.TELEGRAM_CHAT_ID) return false;
  const url = "https://api.telegram.org/bot" + process.env.TELEGRAM_BOT_TOKEN + "/getChatMember?chat_id=" +
    encodeURIComponent(process.env.TELEGRAM_CHAT_ID) + "&user_id=" + encodeURIComponent(userId);
  const res = await fetch(url, { signal: AbortSignal.timeout(8000) });
  const json = await res.json();
  if (!json.ok) return false;
  const status = json.result.status;
  return status === "creator" || status === "administrator";
}

function str(field) {
  return field && field.stringValue !== undefined ? field.stringValue : "";
}

module.exports = async (req, res) => {
  res.setHeader("Cache-Control", "no-store");
  if (!guard(req, res, "mine", 30, 60 * 1000)) return;
  if (req.method !== "POST") {
    res.status(405).json({ error: "method_not_allowed" });
    return;
  }
  const needed = ["SESSION_SECRET", "FIREBASE_SERVICE_ACCOUNT"];
  if (needed.some((key) => !process.env[key])) {
    res.status(500).json({ error: "not_configured" });
    return;
  }
  const session = readSession(req);
  if (!session) {
    res.status(401).json({ error: "login_required" });
    return;
  }
  try {
    const token = await googleToken();
    const body = req.body && typeof req.body === "object" ? req.body : {};
    if (body.action === "role") {
      res.status(200).json({ admin: await isAdmin(session.uid) });
      return;
    }
    let queryUid = String(session.uid);
    if (body.action === "user") {
      const target = String(body.uid || "");
      if (!/^\d{1,20}$/.test(target)) {
        res.status(400).json({ error: "bad_uid" });
        return;
      }
      if (!(await isAdmin(session.uid))) {
        res.status(403).json({ error: "forbidden" });
        return;
      }
      queryUid = target;
    }
    if (body.action === "delete") {
      const id = String(body.id || "");
      if (!/^[A-Za-z0-9]{10,40}$/.test(id) || !process.env.SUPABASE_SECRET_KEY) {
        res.status(400).json({ error: "bad_id" });
        return;
      }
      const dh = { Authorization: "Bearer " + token, "Content-Type": "application/json" };
      const found = await fetch(FS + "/canvases/" + id, { headers: dh, signal: AbortSignal.timeout(8000) });
      if (found.status === 404) {
        res.status(404).json({ error: "not_found" });
        return;
      }
      if (!found.ok) throw new Error("firestore get " + found.status);
      const doc = await found.json();
      const df = doc.fields || {};
      if (str(df.uid) !== String(session.uid) && !(await isAdmin(session.uid))) {
        res.status(403).json({ error: "forbidden" });
        return;
      }
      const gone = await fetch(
        FS + "/canvases/" + id + "?currentDocument.updateTime=" + encodeURIComponent(doc.updateTime),
        { method: "DELETE", headers: dh, signal: AbortSignal.timeout(8000) }
      );
      if (!gone.ok) throw new Error("firestore delete " + gone.status);
      const fileName = str(df.url).split("/").pop();
      if (/^\d+-[0-9a-f-]{36}\.mp4$/.test(fileName)) {
        await fetch(SUPABASE_URL + "/storage/v1/object/canvas/" + fileName, {
          method: "DELETE",
          headers: { apikey: process.env.SUPABASE_SECRET_KEY },
          signal: AbortSignal.timeout(8000)
        });
      }
      res.status(200).json({ ok: true });
      return;
    }
    const r = await fetch(FS + ":runQuery", {
      method: "POST",
      headers: { Authorization: "Bearer " + token, "Content-Type": "application/json" },
      body: JSON.stringify({
        structuredQuery: {
          from: [{ collectionId: "canvases" }],
          where: { fieldFilter: { field: { fieldPath: "uid" }, op: "EQUAL", value: { stringValue: queryUid } } },
          limit: 100
        }
      }),
      signal: AbortSignal.timeout(8000)
    });
    if (!r.ok) throw new Error("firestore query " + r.status);
    const rows = await r.json();
    const items = [];
    for (const row of rows) {
      if (!row.document) continue;
      const f = row.document.fields || {};
      const likes = f.likes && f.likes.integerValue !== undefined ? Number(f.likes.integerValue) : 0;
      items.push({
        id: row.document.name.split("/").pop(),
        song: str(f.song),
        artist: str(f.artist),
        album: str(f.album),
        url: str(f.url),
        status: str(f.status),
        uid: str(f.uid),
        uname: str(f.uname),
        likes,
        createdAt: f.createdAt && f.createdAt.timestampValue ? f.createdAt.timestampValue : ""
      });
    }
    items.sort((a, b) => String(b.createdAt).localeCompare(String(a.createdAt)));
    res.status(200).json({ items });
  } catch (error) {
    res.status(502).json({ error: "server_error", detail: String(error.message || error).slice(0, 120) });
  }
};
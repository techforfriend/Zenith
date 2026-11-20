const crypto = require("crypto");
const { guard } = require("./_guard");

const PROJECT_ID = "zenith-canvas-17133";
const DOCS = "projects/" + PROJECT_ID + "/databases/(default)/documents";
const FS = "https://firestore.googleapis.com/v1/" + DOCS;
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

async function isMember(userId) {
  const url = "https://api.telegram.org/bot" + process.env.TELEGRAM_BOT_TOKEN + "/getChatMember?chat_id=" +
    encodeURIComponent(process.env.TELEGRAM_CHAT_ID) + "&user_id=" + encodeURIComponent(userId);
  const res = await fetch(url, { signal: AbortSignal.timeout(8000) });
  const json = await res.json();
  if (!json.ok) throw new Error("telegram check failed");
  const status = json.result.status;
  return status === "creator" || status === "administrator" || status === "member" ||
    (status === "restricted" && json.result.is_member === true);
}

module.exports = async (req, res) => {
  res.setHeader("Cache-Control", "no-store");
  if (!guard(req, res, "like", 60, 60 * 1000)) return;
  if (req.method !== "POST") {
    res.status(405).json({ error: "method_not_allowed" });
    return;
  }
  const needed = ["SESSION_SECRET", "TELEGRAM_BOT_TOKEN", "TELEGRAM_CHAT_ID", "FIREBASE_SERVICE_ACCOUNT"];
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
  const id = String(body.id || "");
  if (!/^[A-Za-z0-9]{10,40}$/.test(id)) {
    res.status(400).json({ error: "bad_id" });
    return;
  }
  try {
    if (!(await isMember(session.uid))) {
      res.status(403).json({ error: "not_member" });
      return;
    }
    const token = await googleToken();
    const headers = { Authorization: "Bearer " + token, "Content-Type": "application/json" };
    const found = await fetch(FS + "/canvases/" + id, { headers, signal: AbortSignal.timeout(8000) });
    if (found.status === 404) {
      res.status(404).json({ error: "not_found" });
      return;
    }
    if (!found.ok) throw new Error("firestore get " + found.status);
    const doc = await found.json();
    if (!doc.fields || !doc.fields.status || doc.fields.status.stringValue !== "approved") {
      res.status(404).json({ error: "not_found" });
      return;
    }
    const commit = await fetch(FS + ":commit", {
      method: "POST",
      headers,
      body: JSON.stringify({
        writes: [
          {
            update: {
              name: DOCS + "/votes/" + session.uid + "_" + id,
              fields: {
                canvasId: { stringValue: id },
                uid: { stringValue: session.uid },
                at: { timestampValue: new Date().toISOString() }
              }
            },
            currentDocument: { exists: false }
          },
          {
            transform: {
              document: DOCS + "/canvases/" + id,
              fieldTransforms: [{ fieldPath: "likes", increment: { integerValue: "1" } }]
            },
            currentDocument: { exists: true }
          }
        ]
      }),
      signal: AbortSignal.timeout(8000)
    });
    if (commit.status === 409) {
      res.status(409).json({ error: "already_liked" });
      return;
    }
    if (!commit.ok) throw new Error("firestore commit " + commit.status);
    res.status(200).json({ ok: true });
  } catch (error) {
    res.status(502).json({ error: "server_error", detail: String(error.message || error).slice(0, 120) });
  }
};
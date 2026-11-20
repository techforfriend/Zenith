const crypto = require("crypto");
const { guard } = require("./_guard");

const MAX_AUTH_AGE_SECONDS = 24 * 60 * 60;
const SESSION_SECONDS = 7 * 24 * 60 * 60;
const BOT_USERNAME = "Kakenawu_bot";
const LOGIN_TTL_MS = 5 * 60 * 1000;
const FS = "https://firestore.googleapis.com/v1/projects/zenith-canvas-17133/databases/(default)/documents";
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

function verifyTelegram(data, botToken) {
  const hash = String(data.hash || "");
  const lines = Object.keys(data)
    .filter((key) => key !== "hash")
    .sort()
    .map((key) => key + "=" + data[key]);
  const secret = crypto.createHash("sha256").update(botToken).digest();
  const want = crypto.createHmac("sha256", secret).update(lines.join("\n")).digest("hex");
  const a = Buffer.from(hash);
  const b = Buffer.from(want);
  return a.length === b.length && crypto.timingSafeEqual(a, b);
}

async function isMember(botToken, chatId, userId) {
  const url = "https://api.telegram.org/bot" + botToken + "/getChatMember?chat_id=" +
    encodeURIComponent(chatId) + "&user_id=" + encodeURIComponent(userId);
  const res = await fetch(url, { signal: AbortSignal.timeout(8000) });
  const json = await res.json();
  if (!json.ok) throw new Error("telegram: " + (json.description || res.status));
  const status = json.result.status;
  return status === "creator" || status === "administrator" || status === "member" ||
    (status === "restricted" && json.result.is_member === true);
}

function signSession(payload, secret) {
  const body = Buffer.from(JSON.stringify(payload)).toString("base64url");
  const sig = crypto.createHmac("sha256", secret).update(body).digest("base64url");
  return body + "." + sig;
}

module.exports = async (req, res) => {
  res.setHeader("Cache-Control", "no-store");
  if (!guard(req, res, "auth", 30, 60 * 1000)) return;
  if (req.method !== "POST") {
    res.status(405).json({ error: "method_not_allowed" });
    return;
  }
  const sessionSecret = process.env.SESSION_SECRET;
  if (!sessionSecret || !process.env.FIREBASE_SERVICE_ACCOUNT) {
    res.status(500).json({ error: "not_configured" });
    return;
  }
  const body = req.body && typeof req.body === "object" ? req.body : {};
  try {
    const gToken = await googleToken();
    const headers = { Authorization: "Bearer " + gToken, "Content-Type": "application/json" };

    if (body.action === "start") {
      const code = crypto.randomBytes(9).toString("base64url");
      const created = await fetch(FS + "/logins?documentId=" + code, {
        method: "POST",
        headers,
        body: JSON.stringify({
          fields: {
            status: { stringValue: "pending" },
            createdAt: { timestampValue: new Date().toISOString() }
          }
        }),
        signal: AbortSignal.timeout(8000)
      });
      if (!created.ok) throw new Error("firestore create " + created.status);
      res.status(200).json({ code, link: "https://t.me/" + BOT_USERNAME + "?start=" + code });
      return;
    }

    if (body.action === "poll") {
      const code = String(body.code || "");
      if (!/^[A-Za-z0-9_-]{8,40}$/.test(code)) {
        res.status(400).json({ error: "bad_code" });
        return;
      }
      const found = await fetch(FS + "/logins/" + code, { headers, signal: AbortSignal.timeout(8000) });
      if (found.status === 404) {
        res.status(404).json({ error: "login_expired" });
        return;
      }
      if (!found.ok) throw new Error("firestore get " + found.status);
      const doc = await found.json();
      const f = doc.fields || {};
      const created = f.createdAt && f.createdAt.timestampValue ? Date.parse(f.createdAt.timestampValue) : 0;
      const remove = () => fetch(FS + "/logins/" + code, { method: "DELETE", headers, signal: AbortSignal.timeout(8000) });
      if (!(Date.now() - created < LOGIN_TTL_MS)) {
        await remove();
        res.status(404).json({ error: "login_expired" });
        return;
      }
      if (!f.status || f.status.stringValue !== "done" || !f.uid) {
        res.status(200).json({ pending: true });
        return;
      }
      await remove();
      const uid = String(f.uid.stringValue);
      const name = String(f.name && f.name.stringValue ? f.name.stringValue : "user").slice(0, 60);
      const exp = Math.floor(Date.now() / 1000) + SESSION_SECONDS;
      const token = signSession({ uid, name, exp }, sessionSecret);
      res.status(200).json({ token, exp, user: { id: uid, name } });
      return;
    }

    res.status(400).json({ error: "bad_action" });
  } catch (error) {
    res.status(502).json({ error: "server_error", detail: String(error.message || error).slice(0, 120) });
  }
};

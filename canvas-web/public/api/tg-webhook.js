const crypto = require("crypto");

const PROJECT_ID = "zenith-canvas-17133";
const FS = "https://firestore.googleapis.com/v1/projects/" + PROJECT_ID + "/databases/(default)/documents";
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

async function tg(method, body) {
  const res = await fetch("https://api.telegram.org/bot" + process.env.TELEGRAM_BOT_TOKEN + "/" + method, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
    signal: AbortSignal.timeout(8000)
  });
  return res.json();
}

function secretOk(req) {
  const want = String(process.env.TELEGRAM_WEBHOOK_SECRET || "");
  const got = String(req.headers["x-telegram-bot-api-secret-token"] || "");
  if (!want) return false;
  const a = Buffer.from(got);
  const b = Buffer.from(want);
  return a.length === b.length && crypto.timingSafeEqual(a, b);
}

async function handleStart(msg) {
  const reply = (text) => tg("sendMessage", { chat_id: msg.chat.id, text });
  const m = /^\/start(?:@\w+)?\s+([A-Za-z0-9_-]{8,40})$/.exec(String(msg.text || "").trim());
  if (!m || !msg.from || !msg.chat || msg.chat.type !== "private") return;
  const member = await tg("getChatMember", { chat_id: process.env.TELEGRAM_CHAT_ID, user_id: msg.from.id });
  if (!member.ok) {
    await reply("Failed to verify account.");
    return;
  }
  const status = member.result.status;
  const isMember = status === "creator" || status === "administrator" || status === "member" ||
    (status === "restricted" && member.result.is_member === true);
  if (!isMember) {
    const link = process.env.TELEGRAM_GROUP_LINK;
    const extra = link ? { reply_markup: { inline_keyboard: [[{ text: "Join Zenith Group", url: link }]] } } : {};
    await tg("sendMessage", Object.assign({
      chat_id: msg.chat.id,
      text: "Your account is not a member of the Zenith group. Join the group first, then sign in again from the website."
    }, extra));
    return;
  }
  const token = await googleToken();
  const headers = { Authorization: "Bearer " + token, "Content-Type": "application/json" };
  const code = m[1];
  const found = await fetch(FS + "/logins/" + code, { headers, signal: AbortSignal.timeout(8000) });
  if (!found.ok) {
    await reply("Login code is invalid or expired.");
    return;
  }
  const doc = await found.json();
  const f = doc.fields || {};
  const created = f.createdAt && f.createdAt.timestampValue ? Date.parse(f.createdAt.timestampValue) : 0;
  if (!f.status || f.status.stringValue !== "pending" || !(Date.now() - created < 5 * 60 * 1000)) {
    await reply("Login code is invalid or expired.");
    return;
  }
  const name = String(msg.from.first_name || msg.from.username || "user").slice(0, 60);
  const patch = await fetch(
    FS + "/logins/" + code + "?updateMask.fieldPaths=status&updateMask.fieldPaths=uid&updateMask.fieldPaths=name&currentDocument.exists=true",
    {
      method: "PATCH",
      headers,
      body: JSON.stringify({
        fields: {
          status: { stringValue: "done" },
          uid: { stringValue: String(msg.from.id) },
          name: { stringValue: name }
        }
      }),
      signal: AbortSignal.timeout(8000)
    }
  );
  if (!patch.ok) throw new Error("firestore patch " + patch.status);
  await reply("Login successful. Return to the Zenith Canvas page.");
}

async function handle(cb) {
  const answer = (text) => tg("answerCallbackQuery", { callback_query_id: cb.id, text, show_alert: false });
  const m = /^(ok|no):([A-Za-z0-9]{10,40})$/.exec(String(cb.data || ""));
  const msg = cb.message;
  if (!m || !msg || String(msg.chat.id) !== String(process.env.TELEGRAM_CHAT_ID)) {
    await answer("Invalid action.");
    return;
  }
  const member = await tg("getChatMember", { chat_id: process.env.TELEGRAM_CHAT_ID, user_id: cb.from.id });
  if (!member.ok) {
    await answer("Failed to verify account.");
    return;
  }
  const status = member.result.status;
  const isAdmin = status === "creator" || status === "administrator";
  const isMember = isAdmin || status === "member" || (status === "restricted" && member.result.is_member === true);
  if (!isMember || (process.env.TELEGRAM_APPROVE_ADMIN_ONLY === "1" && !isAdmin)) {
    await answer("You don't have permission.");
    return;
  }
  const approve = m[1] === "ok";
  const id = m[2];
  const token = await googleToken();
  const headers = { Authorization: "Bearer " + token, "Content-Type": "application/json" };
  const found = await fetch(FS + "/canvases/" + id, { headers, signal: AbortSignal.timeout(8000) });
  if (found.status === 404) {
    await answer("Canvas not found.");
    return;
  }
  if (!found.ok) throw new Error("firestore get " + found.status);
  const doc = await found.json();
  const current = doc.fields && doc.fields.status ? doc.fields.status.stringValue : "";
  const by = [cb.from.first_name, cb.from.username ? "@" + cb.from.username : ""].filter(Boolean).join(" ");
  const original = String(msg.caption !== undefined ? msg.caption : msg.text || "");
  const edit = (suffix) => {
    const body = { chat_id: msg.chat.id, message_id: msg.message_id, reply_markup: { inline_keyboard: [] } };
    if (msg.caption !== undefined) return tg("editMessageCaption", Object.assign(body, { caption: original + "\n\n" + suffix }));
    return tg("editMessageText", Object.assign(body, { text: original + "\n\n" + suffix }));
  };
  if (current !== "pending") {
    await answer("Already processed.");
    await edit("Status: " + current);
    return;
  }
  const patch = await fetch(
    FS + "/canvases/" + id + "?updateMask.fieldPaths=status&updateMask.fieldPaths=reviewedBy&currentDocument.exists=true",
    {
      method: "PATCH",
      headers,
      body: JSON.stringify({
        fields: {
          status: { stringValue: approve ? "approved" : "rejected" },
          reviewedBy: { stringValue: by.slice(0, 100) }
        }
      }),
      signal: AbortSignal.timeout(8000)
    }
  );
  if (!patch.ok) throw new Error("firestore patch " + patch.status);
  await answer(approve ? "Canvas approved." : "Canvas rejected.");
  await edit((approve ? "\u2705 Approved by " : "\u274C Rejected by ") + by);
}

module.exports = async (req, res) => {
  res.setHeader("Cache-Control", "no-store");
  if (req.method !== "POST") {
    res.status(405).json({ error: "method_not_allowed" });
    return;
  }
  const needed = ["TELEGRAM_BOT_TOKEN", "TELEGRAM_CHAT_ID", "TELEGRAM_WEBHOOK_SECRET", "FIREBASE_SERVICE_ACCOUNT"];
  if (needed.some((key) => !process.env[key])) {
    res.status(500).json({ error: "not_configured" });
    return;
  }
  if (!secretOk(req)) {
    res.status(401).json({ error: "unauthorized" });
    return;
  }
  const message = req.body && typeof req.body === "object" ? req.body.message : null;
  if (message) {
    try {
      await handleStart(message);
    } catch (error) {}
  }
  const cb = req.body && typeof req.body === "object" ? req.body.callback_query : null;
  if (cb) {
    try {
      await handle(cb);
    } catch (error) {
      try {
        await tg("answerCallbackQuery", { callback_query_id: cb.id, text: "A server error occurred." });
      } catch (e) {}
    }
  }
  res.status(200).json({ ok: true });
};
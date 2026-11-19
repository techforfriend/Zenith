const crypto = require("crypto");

const MAX_AUTH_AGE_SECONDS = 24 * 60 * 60;
const SESSION_SECONDS = 7 * 24 * 60 * 60;

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
  if (req.method !== "POST") {
    res.status(405).json({ error: "method_not_allowed" });
    return;
  }
  const botToken = process.env.TELEGRAM_BOT_TOKEN;
  const chatId = process.env.TELEGRAM_CHAT_ID;
  const sessionSecret = process.env.SESSION_SECRET;
  if (!botToken || !chatId || !sessionSecret) {
    res.status(500).json({ error: "not_configured" });
    return;
  }
  const data = req.body && typeof req.body === "object" ? req.body : {};
  if (!data.id || !data.hash || !data.auth_date || !verifyTelegram(data, botToken)) {
    res.status(401).json({ error: "invalid_login" });
    return;
  }
  const age = Date.now() / 1000 - Number(data.auth_date);
  if (!(age >= -60 && age <= MAX_AUTH_AGE_SECONDS)) {
    res.status(401).json({ error: "login_expired" });
    return;
  }
  try {
    if (!(await isMember(botToken, chatId, data.id))) {
      res.status(403).json({ error: "not_member" });
      return;
    }
  } catch (error) {
    res.status(502).json({ error: "membership_check_failed" });
    return;
  }
  const name = String(data.first_name || data.username || "user").slice(0, 60);
  const exp = Math.floor(Date.now() / 1000) + SESSION_SECONDS;
  const token = signSession({ uid: String(data.id), name, exp }, sessionSecret);
  res.status(200).json({
    token,
    exp,
    user: { id: String(data.id), name, username: data.username || "" }
  });
};
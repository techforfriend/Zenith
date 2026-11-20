const hits = new Map();

function guard(req, res, key, max, windowMs) {
  res.setHeader("X-Content-Type-Options", "nosniff");
  res.setHeader("X-Frame-Options", "DENY");
  res.setHeader("Referrer-Policy", "no-referrer");

  const origin = String(req.headers.origin || "");
  if (req.method !== "GET" && origin) {
    let host = "";
    try { host = new URL(origin).host; } catch (error) { host = ""; }
    if (host !== String(req.headers.host || "")) {
      res.status(403).json({ error: "bad_origin" });
      return false;
    }
  }

  const forwarded = String(req.headers["x-forwarded-for"] || "").split(",")[0].trim();
  const ip = forwarded || (req.socket && req.socket.remoteAddress) || "unknown";
  const now = Date.now();
  const id = key + ":" + ip;
  const entry = hits.get(id);
  if (!entry || now > entry.reset) {
    hits.set(id, { count: 1, reset: now + windowMs });
  } else {
    entry.count += 1;
    if (entry.count > max) {
      res.setHeader("Retry-After", String(Math.ceil((entry.reset - now) / 1000)));
      res.status(429).json({ error: "rate_limited" });
      return false;
    }
  }
  if (hits.size > 5000) {
    for (const [k, v] of hits) if (now > v.reset) hits.delete(k);
  }
  return true;
}

module.exports = { guard };
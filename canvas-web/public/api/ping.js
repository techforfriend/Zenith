const SUPABASE_URL = "https://ljckgisqhlypvszlckvf.supabase.co";
const SUPABASE_KEY = "sb_publishable_FYDrANBKImrFKNoYpQI0MQ_MqMA-Jhr";

module.exports = async (req, res) => {
  res.setHeader("Cache-Control", "no-store");
  try {
    const r = await fetch(SUPABASE_URL + "/rest/v1/ping?select=id&limit=1", {
      headers: { apikey: SUPABASE_KEY },
      signal: AbortSignal.timeout(8000)
    });
    res.status(r.ok ? 200 : 502).json({ ok: r.ok, status: r.status });
  } catch (error) {
    res.status(502).json({ ok: false, error: String(error.message || error) });
  }
};
const MAX_BODY_BYTES = 48 * 1024;
const MAX_MESSAGES = 20;
const MAX_MESSAGE_CHARS = 6000;
const WINDOW_MS = 60 * 60 * 1000;
const MAX_REQUESTS_PER_WINDOW = 20;

// Best-effort per-instance limiter. Use Redis/KV before public launch for
// globally consistent limits across serverless instances.
const buckets = globalThis.__poopRateBuckets || (globalThis.__poopRateBuckets = new Map());

function json(res, status, payload) {
  res.status(status).setHeader("Content-Type", "application/json; charset=utf-8");
  res.setHeader("Cache-Control", "no-store");
  return res.end(JSON.stringify(payload));
}

function getClientKey(req, installId) {
  const forwarded = req.headers["x-forwarded-for"];
  const ip = typeof forwarded === "string" ? forwarded.split(",")[0].trim() : "unknown";
  return ip + ":" + installId;
}

function rateLimited(key) {
  const now = Date.now();
  const current = buckets.get(key);
  if (!current || now - current.startedAt >= WINDOW_MS) {
    buckets.set(key, { startedAt: now, count: 1 });
    return false;
  }
  current.count += 1;
  return current.count > MAX_REQUESTS_PER_WINDOW;
}

module.exports = async function handler(req, res) {
  if (req.method !== "POST") {
    res.setHeader("Allow", "POST");
    return json(res, 405, { error: "Method not allowed" });
  }

  const apiKey = process.env.OPENROUTER_API_KEY;
  if (!apiKey) return json(res, 503, { error: "AI service is not configured yet." });

  let raw = "";
  try {
    for await (const chunk of req) {
      raw += chunk;
      if (Buffer.byteLength(raw, "utf8") > MAX_BODY_BYTES) {
        return json(res, 413, { error: "Request is too large." });
      }
    }
    const body = JSON.parse(raw || "{}");
    const installId = String(body.installId || "");
    const messages = body.messages;

    if (!/^[a-f0-9-]{36}$/i.test(installId)) {
      return json(res, 400, { error: "Invalid app installation ID." });
    }
    if (!Array.isArray(messages) || messages.length < 1 || messages.length > MAX_MESSAGES) {
      return json(res, 400, { error: "Send between 1 and 20 messages." });
    }

    const cleanMessages = [];
    for (const item of messages) {
      if (!item || !["user", "assistant"].includes(item.role) ||
          typeof item.content !== "string" ||
          item.content.trim().length < 1 ||
          item.content.length > MAX_MESSAGE_CHARS) {
        return json(res, 400, { error: "Invalid message format or message too long." });
      }
      cleanMessages.push({ role: item.role, content: item.content.trim() });
    }

    const key = getClientKey(req, installId);
    if (rateLimited(key)) {
      return json(res, 429, { error: "وصلت للحد المؤقت. حاول بعد ساعة." });
    }

    const upstream = await fetch("https://openrouter.ai/api/v1/chat/completions", {
      method: "POST",
      headers: {
        Authorization: "Bearer " + apiKey,
        "Content-Type": "application/json",
        "HTTP-Referer": "https://github.com/abdomousa5959-coder/Poop",
        "X-OpenRouter-Title": "POOP AI"
      },
      body: JSON.stringify({
        model: "openrouter/free",
        messages: [
          {
            role: "system",
            content: "You are POOP AI, a capable and safe AI assistant. Reply in the user's language, using natural Egyptian Arabic when the user writes Arabic. Be clear and useful. You are an assistant, not an unrestricted device controller. Never claim to have performed an action unless a connected, permissioned tool confirms it."
          },
          ...cleanMessages
        ],
        temperature: 0.7,
        max_tokens: 1200
      })
    });

    const data = await upstream.json().catch(() => ({}));
    if (!upstream.ok) {
      const message = data && data.error && data.error.message;
      return json(res, upstream.status === 429 ? 503 : 502, {
        error: message || "The AI provider is temporarily unavailable."
      });
    }

    const answer = data?.choices?.[0]?.message?.content;
    if (typeof answer !== "string" || !answer.trim()) {
      return json(res, 502, { error: "The AI provider returned an empty response." });
    }
    return json(res, 200, { answer: answer.trim(), model: data.model || "openrouter/free" });
  } catch (error) {
    return json(res, 400, { error: "Invalid request or temporary server error." });
  }
};

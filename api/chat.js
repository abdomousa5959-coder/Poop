const MAX_BODY_BYTES = 12 * 1024 * 1024;
const MAX_MESSAGES = 20;
const MAX_MESSAGE_CHARS = 6000;
const MAX_ATTACHMENT_BYTES = 8 * 1024 * 1024;
const WINDOW_MS = 60 * 60 * 1000;
const MAX_REQUESTS_PER_WINDOW = 20;
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
function validateContent(content) {
  if (typeof content === "string") {
    if (!content.trim() || content.length > MAX_MESSAGE_CHARS) return null;
    return content.trim();
  }
  if (!Array.isArray(content) || content.length < 1 || content.length > 6) return null;
  const parts = [];
  for (const part of content) {
    if (part?.type === "text" && typeof part.text === "string" && part.text.length <= MAX_MESSAGE_CHARS) {
      parts.push({ type: "text", text: part.text });
    } else if (part?.type === "image_url" && typeof part.image_url?.url === "string") {
      const url = part.image_url.url;
      const match = /^data:(image\/(?:jpeg|png|webp|gif));base64,([A-Za-z0-9+/=]+)$/.exec(url);
      if (!match || Buffer.byteLength(match[2], "base64") > MAX_ATTACHMENT_BYTES) return null;
      parts.push({ type: "image_url", image_url: { url } });
    } else return null;
  }
  return parts.length ? parts : null;
}

module.exports = async function handler(req, res) {
  if (req.method !== "POST") {
    res.setHeader("Allow", "POST");
    return json(res, 405, { error: "Method not allowed" });
  }
  const apiKey = process.env.OPENROUTER_API_KEY;
  if (!apiKey) return json(res, 503, { error: "خدمة المساعد غير مهيأة حاليًا." });

  try {
    let raw = "";
    for await (const chunk of req) {
      raw += chunk;
      if (Buffer.byteLength(raw, "utf8") > MAX_BODY_BYTES) return json(res, 413, { error: "حجم الملفات أو الرسالة كبير جدًا." });
    }
    const body = JSON.parse(raw || "{}");
    const installId = String(body.installId || "");
    const messages = body.messages;
    if (!/^[a-f0-9-]{36}$/i.test(installId)) return json(res, 400, { error: "تعذر التحقق من نسخة التطبيق." });
    if (!Array.isArray(messages) || messages.length < 1 || messages.length > MAX_MESSAGES) return json(res, 400, { error: "عدد الرسائل غير صالح." });

    const cleanMessages = [];
    for (const item of messages) {
      if (!item || !["user", "assistant"].includes(item.role)) return json(res, 400, { error: "تنسيق الرسالة غير صالح." });
      const content = validateContent(item.content);
      if (!content) return json(res, 400, { error: "محتوى الرسالة أو المرفق غير مدعوم." });
      cleanMessages.push({ role: item.role, content });
    }
    if (rateLimited(getClientKey(req, installId))) return json(res, 429, { error: "وصلت للحد المؤقت. حاول بعد ساعة." });

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
        messages: [{
          role: "system",
          content: "You are POOP AI, a polished, capable assistant. Reply in the user's language and use natural Egyptian Arabic when appropriate. When the user requests code, provide complete runnable code in fenced code blocks with the correct language tag and a sensible filename comment on the first line when useful. Keep explanations outside code blocks concise. For multiple files, provide one fenced block per file and put the filename on the line immediately before each block as **FILE: filename**. Analyze user-provided images and documents when possible. Never claim to have performed actions you did not perform."
        }, ...cleanMessages],
        temperature: 0.7,
        max_tokens: 4000
      })
    });
    const data = await upstream.json().catch(() => ({}));
    if (!upstream.ok) {
      const message = data?.error?.message;
      return json(res, upstream.status === 429 ? 503 : 502, { error: message || "المساعد غير متاح مؤقتًا." });
    }
    const answer = data?.choices?.[0]?.message?.content;
    if (typeof answer !== "string" || !answer.trim()) return json(res, 502, { error: "وصل رد فارغ. جرّب تاني." });
    return json(res, 200, { answer: answer.trim() });
  } catch (_) {
    return json(res, 400, { error: "تعذر معالجة الطلب أو المرفق." });
  }
};

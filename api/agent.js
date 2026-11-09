const MAX_TEXT = 3000;
const ACTIONS = {
  search_youtube: { query: "string" }, search_web: { query: "string" }, read_screen: {},
  open_app: { app_name: "string" }, open_url: { url: "string" }, tap_text: { text: "string" },
  type_text: { text: "string" }, scroll: { direction: "string" }, press_back: {}, press_home: {},
  request_file_access: {}, list_files: { path: "string" }, search_files: { query: "string" },
  read_file: { path: "string" }, create_folder: { path: "string" }
};
function json(res, status, body) { res.status(status).setHeader("Content-Type", "application/json; charset=utf-8"); res.setHeader("Cache-Control", "no-store"); return res.end(JSON.stringify(body)); }
function parsePlan(raw) {
  const cleaned = String(raw || "").replace(/^\s*```(?:json)?\s*/i, "").replace(/\s*```\s*$/, "").trim();
  let p;
  try { p = JSON.parse(cleaned); } catch (_) { const a = cleaned.indexOf("{"), z = cleaned.lastIndexOf("}"); if (a < 0 || z <= a) throw new Error("لم يرجع النموذج خطة JSON صالحة."); p = JSON.parse(cleaned.slice(a, z + 1)); }
  const actionRaw = p.action ?? p.tool_call ?? p.tool ?? null;
  const actionName = typeof actionRaw === "string" ? actionRaw : (actionRaw?.name ?? actionRaw?.tool ?? actionRaw?.action);
  const args = typeof actionRaw === "object" && actionRaw !== null ? (actionRaw.args ?? actionRaw.arguments ?? actionRaw.input ?? {}) : (p.args ?? p.arguments ?? {});
  const done = p.done === true || p.is_done === true || p.complete === true || p.status === "done";
  const message = String(p.message ?? p.summary ?? p.reason ?? p.explanation ?? (done ? "اكتملت المهمة." : "جارٍ تنفيذ الخطوة التالية.")).trim();
  if (done) return { message, done: true, action: null };
  if (typeof actionName !== "string" || !Object.prototype.hasOwnProperty.call(ACTIONS, actionName)) throw new Error("النموذج لم يحدد أداة صالحة.");
  if (!args || typeof args !== "object" || Array.isArray(args)) throw new Error("بيانات الإجراء غير صالحة.");
  for (const [key, type] of Object.entries(ACTIONS[actionName])) if (typeof args[key] !== type) throw new Error("بيانات الإجراء ناقصة: " + key);
  return { message, done: false, action: { name: actionName, args } };
}
async function callModel(key, messages) {
  const upstream = await fetch("https://openrouter.ai/api/v1/chat/completions", {
    method: "POST", headers: { Authorization: "Bearer " + key, "Content-Type": "application/json", "HTTP-Referer": "https://github.com/abdomousa5959-coder/Poop", "X-OpenRouter-Title": "POOP AI" },
    body: JSON.stringify({ model: "openrouter/free", messages, response_format: { type: "json_object" }, temperature: 0.1, max_tokens: 900 })
  });
  const data = await upstream.json().catch(() => ({}));
  if (!upstream.ok) throw new Error(data?.error?.message || "تعذر الاتصال بمحرك التخطيط.");
  const raw = data?.choices?.[0]?.message?.content;
  if (typeof raw !== "string" || !raw.trim()) throw new Error("محرك التخطيط رجّع ردًا فارغًا.");
  return raw;
}
module.exports = async function handler(req, res) {
  if (req.method !== "POST") { res.setHeader("Allow", "POST"); return json(res, 405, { error: "Method not allowed" }); }
  const key = process.env.OPENROUTER_API_KEY;
  if (!key) return json(res, 503, { error: "خدمة الوكيل غير مهيأة على الخادم." });
  try {
    let raw = ""; for await (const chunk of req) { raw += chunk; if (Buffer.byteLength(raw) > 30000) return json(res, 413, { error: "الطلب كبير جدًا." }); }
    const body = JSON.parse(raw || "{}"), task = String(body.task || "").trim(), observations = Array.isArray(body.observations) ? body.observations.slice(-10) : [];
    if (!task || task.length > MAX_TEXT) return json(res, 400, { error: "اكتب مهمة واضحة لا تتجاوز 3000 حرف." });
    const system = "أنت POOP AI، وكيل أندرويد. أخرج JSON فقط بالشكل {message:string,done:boolean,action:{name:string,args:object}|null}. الأدوات المسموحة: " + JSON.stringify(ACTIONS) + ". نفّذ خطوة واحدة فقط في كل دورة واعتمد على نتائج observations. لا تدّع نجاح أي خطوة قبل ظهور نتيجتها. إذا طلب المستخدم البحث عن شيء في TikTok أو YouTube أو Google فابدأ بأداة البحث المناسبة، ولا تفتح التطبيق لمجرد البحث. لا تستخدم open_app إلا إذا طلب المستخدم فتح التطبيق صراحة. لا تخترع نتائج بحث: افتح النتائج ثم اقرأ الشاشة وتحقق. إذا فشلت خطوة فغيّر الطريقة. لا تنفذ حذفًا أو إرسالًا أو شراءً أو نشرًا أو نقلًا أو استبدالًا أو تغيير إعداد حساس؛ اطلب موافقة واضحة. البحث والقراءة وفتح رابط وإنشاء مجلد جديد بطلب المستخدم مسموح. لا تستخدم shell. العربية المصرية. إذا اكتملت المهمة أرسل done=true وaction=null.";
    const messages = [{ role: "system", content: system }, { role: "user", content: JSON.stringify({ task, observations }) }];
    let plan;
    try { plan = parsePlan(await callModel(key, messages)); }
    catch (firstError) {
      const retry = messages.concat([{ role: "assistant", content: "أعد الإجابة ككائن JSON صحيح فقط. الحقول المطلوبة: message, done, action. إذا لم تكتمل المهمة فaction يجب أن يحتوي name وargs." }, { role: "user", content: "أعد الخطة الآن. لا تضف Markdown أو شرحًا خارج JSON." }]);
      try { plan = parsePlan(await callModel(key, retry)); }
      catch (secondError) { return json(res, 502, { error: "تعذر تكوين خطوة صالحة بعد محاولتين.", detail: String(secondError.message || firstError.message).slice(0, 240) }); }
    }
    return json(res, 200, plan);
  } catch (error) { return json(res, 400, { error: error.message || "تعذر تحليل المهمة." }); }
}
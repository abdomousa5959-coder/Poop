package com.poopai.app
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Base64
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

private val Black = Color(0xFF080808)
private val Panel = Color(0xFF141414)
private val White = Color(0xFFF5F5F5)
private val Gray = Color(0xFF929292)
private const val ENDPOINT = "https://poop-ai-43xjw55hb-abdomousa5959-coders-projects.vercel.app/api/chat"
private const val MAX_FILE = 8 * 1024 * 1024

data class ChatFile(val name: String, val code: String, val language: String)
data class ChatMessage(val text: String, val fromUser: Boolean, val image: String? = null, val attachment: String? = null, val files: List<ChatFile> = emptyList())

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { PoopApp() } }
}

private fun installId(context: Context): String {
    val prefs = context.getSharedPreferences("poop_ai", Context.MODE_PRIVATE)
    return prefs.getString("install_id", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("install_id", it).apply() }
}

private fun extractFiles(answer: String): Pair<String, List<ChatFile>> {
    val regex = Regex("(?s)(?:\\*\\*FILE:\\s*([^*\\n]+)\\*\\*\\s*)?\\x60\\x60\\x60([\\w+#.-]*)\\s*\\n(.*?)\\n\\x60\\x60\\x60")
    val files = mutableListOf<ChatFile>()
    val text = regex.replace(answer) { m ->
        val lang = m.groupValues[2].ifBlank { "txt" }.lowercase()
        val ext = when (lang) { "kotlin" -> "kt"; "python" -> "py"; "javascript" -> "js"; "typescript" -> "ts"; "bash" -> "sh"; else -> lang }
        val name = m.groupValues[1].trim().ifBlank { "poop_ai_file_" + (files.size + 1) + "." + ext }.replace(Regex("[^A-Za-z0-9._-]"), "_")
        files.add(ChatFile(name, m.groupValues[3], lang))
        "📎 تم تجهيز الملف: " + name
    }
    return text.trim() to files
}

private suspend fun readFile(context: Context, uri: Uri): ChatMessage = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val mime = resolver.getType(uri) ?: "application/octet-stream"
    val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
    } ?: "attachment"
    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: error("تعذر فتح الملف")
    require(bytes.size <= MAX_FILE) { "الحد الأقصى لحجم الملف 8MB" }
    if (mime.startsWith("image/")) {
        require(mime in listOf("image/jpeg", "image/png", "image/webp", "image/gif")) { "صيغة الصورة غير مدعومة" }
        ChatMessage("حلّل الصورة المرفقة.", true, "data:" + mime + ";base64," + Base64.encodeToString(bytes, Base64.NO_WRAP), name)
    } else {
        val ext = name.substringAfterLast('.', "").lowercase()
        require(mime.startsWith("text/") || ext in listOf("txt","md","json","csv","xml","html","css","js","ts","kt","java","py","sh","sql","yaml","yml","log")) {
            "المدعوم حاليًا الصور وملفات النصوص والأكواد"
        }
        val text = bytes.toString(Charsets.UTF_8)
        require(text.isNotBlank()) { "الملف فارغ" }
        ChatMessage(text, true, attachment = name)
    }
}


// Build retry marker: keep agent API response handling verified in CI.
private suspend fun requestAgent(task: String): Pair<String, List<Pair<String, JSONObject>>> = withContext(Dispatchers.IO) {
    val connection = URL("https://poop-ai-api.vercel.app/api/agent").openConnection() as HttpURLConnection
    try {
        connection.requestMethod = "POST"
        connection.connectTimeout = 20000
        connection.readTimeout = 120000
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connection.outputStream.use {
            it.write(JSONObject().put("task", task).toString().toByteArray(Charsets.UTF_8))
        }

        val status = connection.responseCode
        val raw = (if (status in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        val response = try { JSONObject(raw) } catch (_: Exception) {
            throw IllegalStateException("استجابة الخادم غير مفهومة (HTTP $status)")
        }
        if (status !in 200..299) {
            throw IllegalStateException(response.optString("error").ifBlank { "تعذر تخطيط المهمة (HTTP $status)" })
        }

        // Accept either a JSON array or a JSON-encoded array, and object/string action entries.
        val actionsValue = response.opt("actions")
        val actionsArray = when (actionsValue) {
            is JSONArray -> actionsValue
            is String -> try { JSONArray(actionsValue) } catch (_: Exception) { JSONArray() }
            else -> JSONArray()
        }
        val parsedActions = mutableListOf<Pair<String, JSONObject>>()
        for (index in 0 until actionsArray.length()) {
            val entryValue = actionsArray.opt(index)
            val entry = when (entryValue) {
                is JSONObject -> entryValue
                is String -> try { JSONObject(entryValue) } catch (_: Exception) { null }
                else -> null
            } ?: continue

            val actionName = entry.optString("name").trim()
            if (actionName.isEmpty()) continue

            val argsValue = entry.opt("args")
            val args = when (argsValue) {
                is JSONObject -> argsValue
                is String -> try { JSONObject(argsValue) } catch (_: Exception) { JSONObject() }
                is Map<*, *> -> JSONObject(argsValue)
                else -> JSONObject()
            }
            parsedActions.add(actionName to args)
        }
        response.optString("message").ifBlank { "تم تحليل المهمة." } to parsedActions
    } finally {
        connection.disconnect()
    }
}

private suspend fun ask(id: String, history: List<ChatMessage>): String = withContext(Dispatchers.IO) {
    val c = URL(ENDPOINT).openConnection() as HttpURLConnection
    try {
        c.requestMethod = "POST"; c.connectTimeout = 20000; c.readTimeout = 120000; c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        val arr = JSONArray()
        history.takeLast(20).forEach { m ->
            val body: Any = if (m.image != null) JSONArray()
                .put(JSONObject().put("type","text").put("text",m.text))
                .put(JSONObject().put("type","image_url").put("image_url",JSONObject().put("url",m.image)))
            else if (m.attachment != null) "الملف المرفق " + m.attachment + ":\n" + m.text else m.text
            arr.put(JSONObject().put("role", if (m.fromUser) "user" else "assistant").put("content", body))
        }
        c.outputStream.use { it.write(JSONObject().put("installId",id).put("messages",arr).toString().toByteArray(Charsets.UTF_8)) }
        val status = c.responseCode
        val raw = (if (status in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        val result = JSONObject(raw.ifBlank { "{}" })
        if (status !in 200..299) error(result.optString("error").ifBlank { "خطأ اتصال (" + status + ")" })
        result.optString("answer").ifBlank { error("وصل رد فارغ") }
    } finally { c.disconnect() }
}

@Composable
fun PoopApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val id = remember { installId(context) }
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<ChatMessage?>(null) }
    var saving by remember { mutableStateOf<ChatFile?>(null) }
    var messages by remember { mutableStateOf(listOf(ChatMessage("أهلاً! أنا POOP AI 👋\nابعتلي صورة أو ملف نصّي، واطلب الأكواد كملفات جاهزة للحفظ.", false))) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            try { pending = readFile(context, uri); Toast.makeText(context, "تم إرفاق الملف", Toast.LENGTH_SHORT).show() }
            catch (e: Exception) { Toast.makeText(context, e.message ?: "تعذر قراءة الملف", Toast.LENGTH_LONG).show() }
        }
    }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val file = saving
        if (uri != null && file != null) scope.launch {
            try {
                context.contentResolver.openOutputStream(uri)?.use { it.write(file.code.toByteArray(Charsets.UTF_8)) } ?: error("فشل إنشاء الملف")
                Toast.makeText(context, "تم حفظ الملف", Toast.LENGTH_SHORT).show()
            } catch (_: Exception) { Toast.makeText(context, "فشل حفظ الملف", Toast.LENGTH_SHORT).show() }
            finally { saving = null }
        } else saving = null
    }

    var agentMode by remember { mutableStateOf(false) }

    fun send() {
        val prompt = input.trim()
        val attachment = pending
        if ((prompt.isEmpty() && attachment == null) || busy) return
        if (agentMode && attachment != null) {
            Toast.makeText(context, "وضع الوكيل يستقبل الأوامر النصية حاليًا", Toast.LENGTH_LONG).show()
            return
        }
        val outgoing = if (attachment == null) ChatMessage(prompt, true)
            else attachment.copy(text = if (prompt.isBlank()) attachment.text else prompt + "\n\n" + attachment.text)
        val history = messages + outgoing
        messages = history; input = ""; pending = null; busy = true
        scope.launch {
            try {
                if (agentMode) {
                    val (message, actions) = requestAgent(prompt)
                    if (actions.isNotEmpty() && AgentBridge.service == null) {
                        messages = messages + ChatMessage(message + "\n\nفعّل POOP AI Agent من إعدادات إمكانية الوصول، وبعدها ارجع وجرب الأمر.", false)
                        context.startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    } else {
                        val results = actions.map { (name, args) -> AgentBridge.service?.execute(name, args) ?: "خدمة التحكم غير مفعّلة" }
                        messages = messages + ChatMessage(if (actions.isEmpty()) message else message + "\n\n" + results.joinToString("\n"), false)
                    }
                } else {
                    val (plain, files) = extractFiles(ask(id, history.drop(1)))
                    messages = messages + ChatMessage(plain.ifBlank { "جهزتلك الملفات المطلوبة." }, false, files = files)
                }
            } catch (e: Exception) {
                messages = messages + ChatMessage("تعذر إكمال الطلب: " + (e.message ?: "مشكلة اتصال"), false)
            } finally { busy = false }
        }
    }

    LaunchedEffect(messages.size, busy) {
        val index = messages.size + if (busy) 0 else -1
        if (index >= 0) list.animateScrollToItem(index)
    }

    MaterialTheme(colorScheme = darkColorScheme(background = Black, surface = Panel, primary = White, onSurface = White)) {
        Column(Modifier.fillMaxSize().background(Black).statusBarsPadding().navigationBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(42.dp).background(White, RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) {
                    Text("P", color = Black, fontSize = 25.sp, fontWeight = FontWeight.Black)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("POOP AI", color = White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("YOUR AI COMPANION", color = Gray, fontSize = 9.sp, letterSpacing = 1.5.sp)
                }
                Text("● ONLINE", color = Color(0xFF66D18A), fontSize = 10.sp)
            }
            HorizontalDivider(color = Color(0xFF252525))
            LazyColumn(state = list, modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 14.dp),
                contentPadding = PaddingValues(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (messages.size == 1) item {
                    Column(Modifier.padding(top = 25.dp)) {
                        Text("THINK.", color = White, fontSize = 36.sp, fontWeight = FontWeight.Black)
                        Text("ASK.", color = White, fontSize = 36.sp, fontWeight = FontWeight.Black)
                        Text("CREATE.", color = Gray, fontSize = 36.sp, fontWeight = FontWeight.Black)
                        Text("ابدأ محادثة أو أرفق ملف للتحليل", color = Gray)
                    }
                }
                items(messages) { m ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (m.fromUser) Arrangement.End else Arrangement.Start) {
                        Column(Modifier.widthIn(max = 340.dp).background(if (m.fromUser) Color(0xFF292929) else Panel, RoundedCornerShape(17.dp)).padding(13.dp)) {
                            if (m.attachment != null) Text("📎 " + m.attachment, color = Color(0xFFB8D8FF), fontSize = 12.sp)
                            if (m.text.isNotBlank()) Text(m.text, color = White, fontSize = 14.sp, lineHeight = 21.sp)
                            m.files.forEach { file ->
                                Spacer(Modifier.height(8.dp))
                                Surface(color = Color(0xFF202B25), shape = RoundedCornerShape(12.dp)) {
                                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                        Text("📄 " + file.name, color = Color(0xFF9FE3B1), fontWeight = FontWeight.SemiBold)
                                        Text("ملف جاهز • " + file.language.uppercase(), color = Gray, fontSize = 10.sp)
                                        Button(onClick = { saving = file; save.launch(file.name) },
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF9FE3B1), contentColor = Black)) { Text("حفظ الملف") }
                                    }
                                }
                            }
                        }
                    }
                }
                if (busy) item { Text("POOP AI بيفكر...", color = Gray, fontSize = 12.sp) }
            }
            pending?.let {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("📎 " + it.attachment + " جاهز للإرسال", color = Color(0xFF9FE3B1), fontSize = 12.sp)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { pending = null }) { Text("إزالة", color = Gray) }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { agentMode = !agentMode }) {
                    Text(if (agentMode) "● وضع الوكيل مُفعّل" else "تشغيل وضع الوكيل", color = if (agentMode) Color(0xFF9FE3B1) else Gray, fontSize = 12.sp)
                }
                Spacer(Modifier.weight(1f))
                if (agentMode) Text("تحكم بإذن المستخدم", color = Gray, fontSize = 10.sp)
            }
            Row(Modifier.fillMaxWidth().padding(12.dp).background(Panel, RoundedCornerShape(25.dp)).padding(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Surface(color = Color(0xFF292929), shape = CircleShape, onClick = { picker.launch(arrayOf("*/*")) }) {
                    Text("+", color = White, fontSize = 23.sp, modifier = Modifier.padding(horizontal = 13.dp, vertical = 6.dp))
                }
                Spacer(Modifier.width(8.dp))
                BasicTextField(value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f).padding(vertical = 10.dp),
                    textStyle = TextStyle(color = White, fontSize = 14.sp), cursorBrush = androidx.compose.ui.graphics.SolidColor(White),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { send() }),
                    decorationBox = { inner -> if (input.isEmpty()) Text("اكتب رسالتك...", color = Gray); inner() })
                Spacer(Modifier.width(8.dp))
                Surface(color = if (busy) Color.DarkGray else White, shape = CircleShape, onClick = { send() }) {
                    Text(if (busy) "…" else "↑", color = Black, fontSize = 21.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
                }
            }
            Text("POOP AI • YOUR PRIVATE AI SPACE", color = Color(0xFF626262), fontSize = 9.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 7.dp))
        }
    }
}

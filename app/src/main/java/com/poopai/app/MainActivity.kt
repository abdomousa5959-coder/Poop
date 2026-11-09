package com.poopai.app

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
private const val PREFS = "poop_ai"
private const val INSTALL_ID_KEY = "install_id"
private const val CHAT_ENDPOINT = "https://poop-ai-43xjw55hb-abdomousa5959-coders-projects.vercel.app/api/chat"

data class ChatMessage(val text: String, val fromUser: Boolean)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PoopApp() }
    }
}

private fun getInstallId(context: Context): String {
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val existing = prefs.getString(INSTALL_ID_KEY, null)
    if (!existing.isNullOrBlank()) return existing
    return UUID.randomUUID().toString().also {
        prefs.edit().putString(INSTALL_ID_KEY, it).apply()
    }
}

private suspend fun askPoopAi(installId: String, history: List<ChatMessage>): String =
    withContext(Dispatchers.IO) {
        val connection = (URL(CHAT_ENDPOINT).openConnection() as HttpURLConnection)
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 20000
            connection.readTimeout = 90000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")

            val messages = JSONArray()
            history.filter { it.text.isNotBlank() }.takeLast(20).forEach { item ->
                messages.put(JSONObject()
                    .put("role", if (item.fromUser) "user" else "assistant")
                    .put("content", item.text))
            }
            val body = JSONObject()
                .put("installId", installId)
                .put("messages", messages)

            connection.outputStream.use {
                it.write(body.toString().toByteArray(Charsets.UTF_8))
            }

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val responseText = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val response = try { JSONObject(responseText) } catch (_: Exception) {
                JSONObject()
            }

            if (code !in 200..299) {
                val detail = response.optString("error").takeIf { it.isNotBlank() }
                throw IllegalStateException(detail ?: when (code) {
                    429 -> "وصلت للحد المؤقت. حاول لاحقًا."
                    503 -> "خدمة الذكاء الاصطناعي غير مهيأة حاليًا."
                    else -> "خطأ في السيرفر (HTTP $code)."
                })
            }

            response.optString("answer").takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("السيرفر رجّع رد فاضي.")
        } finally {
            connection.disconnect()
        }
    }

@Composable
fun PoopApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val installId = remember { getInstallId(context) }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var messages by remember {
        mutableStateOf(listOf(ChatMessage("أهلاً! أنا POOP AI 👋\nاسألني أو اطلب مني أساعدك في أي حاجة.", false)))
    }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size, busy) {
        val lastIndex = messages.size + if (busy) 1 else 0
        if (lastIndex > 0) listState.animateScrollToItem(lastIndex)
    }

    fun sendMessage() {
        val prompt = input.trim()
        if (prompt.isEmpty() || busy) return
        val updated = messages + ChatMessage(prompt, true)
        messages = updated
        input = ""
        busy = true
        scope.launch {
            try {
                val answer = askPoopAi(installId, updated.drop(1))
                messages = messages + ChatMessage(answer, false)
            } catch (e: Exception) {
                messages = messages + ChatMessage(
                    "تعذر إكمال الطلب: ${e.message ?: "مشكلة اتصال" }\nحاول تاني بعد شوية.",
                    false
                )
            } finally {
                busy = false
            }
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(
        background = Black,
        surface = Panel,
        primary = White,
        onBackground = White,
        onSurface = White
    )) {
        Column(
            Modifier.fillMaxSize()
                .background(Black)
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(42.dp).background(White, RoundedCornerShape(13.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("P", color = Black, fontWeight = FontWeight.Black, fontSize = 25.sp)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("POOP AI", color = White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Text("YOUR AI COMPANION", color = Gray, fontSize = 9.sp, letterSpacing = 1.5.sp)
                }
                Text("●", color = Color(0xFF66D18A), fontSize = 12.sp)
                Spacer(Modifier.width(6.dp))
                Text("ONLINE", color = Gray, fontSize = 9.sp, letterSpacing = 1.sp)
            }
            HorizontalDivider(color = Color(0xFF252525))

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 15.dp),
                contentPadding = PaddingValues(vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(13.dp)
            ) {
                if (messages.size == 1) {
                    item {
                        Column(Modifier.fillMaxWidth().padding(top = 26.dp, bottom = 12.dp)) {
                            Text("THINK.", color = White, fontSize = 36.sp, fontWeight = FontWeight.Black)
                            Text("ASK.", color = White, fontSize = 36.sp, fontWeight = FontWeight.Black)
                            Text("CREATE.", color = Gray, fontSize = 36.sp, fontWeight = FontWeight.Black)
                            Spacer(Modifier.height(12.dp))
                            Text("ابدأ محادثة جديدة مع POOP AI", color = Gray, fontSize = 14.sp)
                        }
                    }
                }
                items(messages) { msg ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = if (msg.fromUser) Arrangement.End else Arrangement.Start
                    ) {
                        Surface(
                            color = if (msg.fromUser) Color(0xFF292929) else Panel,
                            shape = RoundedCornerShape(
                                topStart = 18.dp, topEnd = 18.dp,
                                bottomStart = if (msg.fromUser) 18.dp else 5.dp,
                                bottomEnd = if (msg.fromUser) 5.dp else 18.dp
                            ),
                            modifier = Modifier.widthIn(max = 330.dp)
                        ) {
                            Text(
                                msg.text,
                                color = White,
                                modifier = Modifier.padding(horizontal = 15.dp, vertical = 12.dp),
                                fontSize = 14.sp,
                                lineHeight = 21.sp
                            )
                        }
                    }
                }
                if (busy) {
                    item {
                        Text("POOP AI بيفكر...", color = Gray, fontSize = 12.sp)
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth()
                    .padding(horizontal = 13.dp, vertical = 9.dp)
                    .background(Panel, RoundedCornerShape(25.dp))
                    .padding(start = 17.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BasicTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f).padding(vertical = 9.dp),
                    textStyle = TextStyle(color = White, fontSize = 14.sp),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(White),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { sendMessage() }),
                    decorationBox = { inner ->
                        if (input.isEmpty()) Text("Message POOP AI...", color = Gray, fontSize = 14.sp)
                        inner()
                    }
                )
                Spacer(Modifier.width(8.dp))
                Surface(
                    color = if (busy) Color(0xFF555555) else White,
                    shape = CircleShape,
                    onClick = { sendMessage() }
                ) {
                    Text(
                        if (busy) "…" else "↑",
                        color = Black,
                        fontWeight = FontWeight.Bold,
                        fontSize = 21.sp,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                }
            }
            Text(
                "POOP AI • FREE MODEL ROUTING",
                color = Color(0xFF626262),
                fontSize = 9.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp),
                letterSpacing = 1.sp
            )
        }
    }
}

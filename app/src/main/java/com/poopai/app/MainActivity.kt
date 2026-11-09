package com.poopai.app

import android.os.Bundle
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Black = Color(0xFF000000)
private val Panel = Color(0xFF101010)
private val White = Color(0xFFF5F5F5)
private val Gray = Color(0xFF999999)
private const val PREFS = "poop_ai_secure"
private const val KEY_ALIAS = "poop_ai_openrouter_key"

data class ChatMessage(val text: String, val fromUser: Boolean)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PoopApp() }
    }
}

private object SecureApiKey {
    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    fun save(activity: ComponentActivity, value: String) {
        if (value.isBlank()) {
            activity.getSharedPreferences(PREFS, 0).edit().remove("api_key").apply()
            return
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val packed = cipher.iv + encrypted
        activity.getSharedPreferences(PREFS, 0).edit()
            .putString("api_key", Base64.encodeToString(packed, Base64.NO_WRAP)).apply()
    }

    fun read(activity: ComponentActivity): String {
        val packedText = activity.getSharedPreferences(PREFS, 0).getString("api_key", null) ?: return ""
        return try {
            val packed = Base64.decode(packedText, Base64.NO_WRAP)
            val iv = packed.copyOfRange(0, 12)
            val encrypted = packed.copyOfRange(12, packed.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        } catch (_: Exception) { "" }
    }
}

private suspend fun askOpenRouter(apiKey: String, model: String, history: List<ChatMessage>): String =
    withContext(Dispatchers.IO) {
        val connection = (URL("https://openrouter.ai/api/v1/chat/completions").openConnection() as HttpURLConnection)
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 20000
            connection.readTimeout = 60000
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("HTTP-Referer", "https://github.com/abdomousa5959-coder/Poop")
            connection.setRequestProperty("X-OpenRouter-Title", "POOP AI")
            val messages = JSONArray()
            messages.put(JSONObject().put("role", "system").put("content", "You are POOP AI, a helpful assistant. Reply in the same language as the user, preferably Egyptian Arabic when they use Arabic."))
            history.takeLast(20).forEach { item ->
                messages.put(JSONObject()
                    .put("role", if (item.fromUser) "user" else "assistant")
                    .put("content", item.text))
            }
            val body = JSONObject()
                .put("model", model)
                .put("messages", messages)
                .put("temperature", 0.7)
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream.bufferedReader().use { it.readText() }
            if (code !in 200..299) {
                val detail = try { JSONObject(response).optJSONObject("error")?.optString("message") } catch (_: Exception) { null }
                throw IllegalStateException(detail?.takeIf { it.isNotBlank() } ?: "OpenRouter HTTP $code")
            }
            val json = JSONObject(response)
            json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                ?.optString("content")?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("الرد وصل لكن محتواه فاضي.")
        } finally {
            connection.disconnect()
        }
    }

@Composable
fun PoopApp() {
    val activity = androidx.compose.ui.platform.LocalContext.current as ComponentActivity
    val prefs = remember { activity.getSharedPreferences(PREFS, 0) }
    var input by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf(SecureApiKey.read(activity)) }
    var model by remember { mutableStateOf(prefs.getString("model", "openrouter/free") ?: "openrouter/free") }
    var showSettings by remember { mutableStateOf(false) }
    var keyDraft by remember { mutableStateOf(apiKey) }
    var modelDraft by remember { mutableStateOf(model) }
    var busy by remember { mutableStateOf(false) }
    var messages by remember { mutableStateOf(listOf(ChatMessage("أهلاً! أنا POOP AI. أضف مفتاح OpenRouter من الإعدادات وابدأ المحادثة.", false))) }
    val scope = rememberCoroutineScope()

    MaterialTheme(colorScheme = darkColorScheme(background = Black, surface = Panel, primary = White)) {
        Column(Modifier.fillMaxSize().background(Black).statusBarsPadding().navigationBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(38.dp).background(White, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                    Text("P", color = Black, fontWeight = FontWeight.Black, fontSize = 23.sp)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("POOP AI", color = White, fontWeight = FontWeight.Bold, fontSize = 19.sp)
                    Text("POWERED BY OPENROUTER", color = Gray, fontSize = 9.sp, letterSpacing = 1.2.sp)
                }
                TextButton(onClick = {
                    keyDraft = apiKey
                    modelDraft = model
                    showSettings = true
                }) { Text("⚙", color = White, fontSize = 22.sp) }
            }
            HorizontalDivider(color = Color(0xFF222222))
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (messages.size == 1) {
                    item {
                        Column(Modifier.fillMaxWidth().padding(vertical = 22.dp)) {
                            Text("THINK.", color = White, fontSize = 34.sp, fontWeight = FontWeight.Black)
                            Text("ASK.", color = White, fontSize = 34.sp, fontWeight = FontWeight.Black)
                            Text("CREATE.", color = Gray, fontSize = 34.sp, fontWeight = FontWeight.Black)
                            Spacer(Modifier.height(12.dp))
                            Text("ابدأ محادثة جديدة مع POOP AI", color = Gray, fontSize = 14.sp)
                        }
                    }
                }
                items(messages) { msg ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (msg.fromUser) Arrangement.End else Arrangement.Start) {
                        Surface(
                            color = if (msg.fromUser) Color(0xFF242424) else Panel,
                            shape = RoundedCornerShape(18.dp),
                            modifier = Modifier.widthIn(max = 320.dp)
                        ) {
                            Text(msg.text, color = White, modifier = Modifier.padding(15.dp), fontSize = 14.sp)
                        }
                    }
                }
                if (busy) item { Text("POOP AI بيفكر...", color = Gray, fontSize = 12.sp) }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)
                    .background(Panel, RoundedCornerShape(24.dp)).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BasicTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                    textStyle = TextStyle(color = White, fontSize = 14.sp),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(White),
                    decorationBox = { inner ->
                        if (input.isEmpty()) Text("Message POOP AI...", color = Gray, fontSize = 14.sp)
                        inner()
                    }
                )
                Spacer(Modifier.width(10.dp))
                Surface(
                    color = if (busy) Gray else White,
                    shape = CircleShape,
                    onClick = {
                        val prompt = input.trim()
                        if (prompt.isNotEmpty() && !busy) {
                            if (apiKey.isBlank()) {
                                showSettings = true
                                keyDraft = ""
                                modelDraft = model
                            } else {
                                val updated = messages + ChatMessage(prompt, true)
                                messages = updated
                                input = ""
                                busy = true
                                scope.launch {
                                    try {
                                        val answer = askOpenRouter(apiKey, model, updated.drop(1))
                                        messages = messages + ChatMessage(answer, false)
                                    } catch (e: Exception) {
                                        messages = messages + ChatMessage("حصل خطأ في الاتصال: ${e.message ?: "حاول مرة أخرى"}", false)
                                    } finally {
                                        busy = false
                                    }
                                }
                            }
                        }
                    }
                ) {
                    Text(if (busy) "…" else "↑", color = Black, fontWeight = FontWeight.Bold, fontSize = 20.sp, modifier = Modifier.padding(horizontal = 13.dp, vertical = 7.dp))
                }
            }
            Text(
                if (apiKey.isBlank()) "ADD YOUR OPENROUTER KEY IN SETTINGS" else "MODEL: $model",
                color = Color(0xFF666666), fontSize = 9.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp),
                letterSpacing = 1.sp
            )
        }

        if (showSettings) {
            AlertDialog(
                onDismissRequest = { showSettings = false },
                containerColor = Panel,
                title = { Text("إعدادات OpenRouter", color = White) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("API Key", color = Gray, fontSize = 12.sp)
                        BasicTextField(
                            value = keyDraft,
                            onValueChange = { keyDraft = it },
                            textStyle = TextStyle(color = White, fontSize = 13.sp),
                            modifier = Modifier.fillMaxWidth().background(Color(0xFF202020), RoundedCornerShape(10.dp)).padding(12.dp),
                            decorationBox = { inner ->
                                if (keyDraft.isEmpty()) Text("الصق مفتاح OpenRouter هنا", color = Gray, fontSize = 12.sp)
                                inner()
                            }
                        )
                        Text("Model ID", color = Gray, fontSize = 12.sp)
                        BasicTextField(
                            value = modelDraft,
                            onValueChange = { modelDraft = it },
                            textStyle = TextStyle(color = White, fontSize = 13.sp),
                            modifier = Modifier.fillMaxWidth().background(Color(0xFF202020), RoundedCornerShape(10.dp)).padding(12.dp),
                            decorationBox = { inner ->
                                if (modelDraft.isEmpty()) Text("openrouter/free", color = Gray, fontSize = 12.sp)
                                inner()
                            }
                        )
                        Text("المفتاح بيتخزن مشفّرًا على جهازك. لا تضعه في GitHub.", color = Gray, fontSize = 11.sp)
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        apiKey = keyDraft.trim()
                        model = modelDraft.trim().ifBlank { "openrouter/free" }
                        SecureApiKey.save(activity, apiKey)
                        prefs.edit().putString("model", model).apply()
                        showSettings = false
                    }) { Text("حفظ", color = White) }
                },
                dismissButton = {
                    TextButton(onClick = { showSettings = false }) { Text("إلغاء", color = Gray) }
                }
            )
        }
    }
}

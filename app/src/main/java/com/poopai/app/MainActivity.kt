package com.poopai.app

import android.os.Bundle
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

private val Black = Color(0xFF000000)
private val Panel = Color(0xFF101010)
private val White = Color(0xFFF5F5F5)
private val Gray = Color(0xFF999999)

data class ChatMessage(val text: String, val fromUser: Boolean)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PoopApp() }
    }
}

@Composable
fun PoopApp() {
    var input by remember { mutableStateOf("") }
    var messages by remember {
        mutableStateOf(listOf(ChatMessage("أهلاً! أنا POOP AI. واجهة الدردشة جاهزة، وسيتم توصيل محرك الذكاء الاصطناعي في الخطوة التالية.", false)))
    }
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
                    Text("YOUR AI SPACE", color = Gray, fontSize = 10.sp, letterSpacing = 2.sp)
                }
                Text("●  LOCAL", color = Color(0xFF9B9B9B), fontSize = 10.sp)
            }
            HorizontalDivider(color = Color(0xFF222222))
            if (messages.size <= 1) {
                Column(Modifier.weight(1f).fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.Center) {
                    Text("THINK.", color = White, fontSize = 34.sp, fontWeight = FontWeight.Black)
                    Text("ASK.", color = White, fontSize = 34.sp, fontWeight = FontWeight.Black)
                    Text("CREATE.", color = Gray, fontSize = 34.sp, fontWeight = FontWeight.Black)
                    Spacer(Modifier.height(16.dp))
                    Text("ابدأ محادثة جديدة مع POOP AI", color = Gray, fontSize = 14.sp)
                    Spacer(Modifier.height(28.dp))
                    listOf("اكتب لي فكرة مشروع", "اشرح موضوعًا معقدًا", "ساعدني في كتابة كود").forEach {
                        Surface(color = Panel, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp), onClick = { input = it }) {
                            Text(it, color = White, modifier = Modifier.padding(16.dp), fontSize = 14.sp)
                        }
                    }
                }
            } else {
                LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(messages) { msg ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = if (msg.fromUser) Arrangement.End else Arrangement.Start) {
                            Surface(color = if (msg.fromUser) Color(0xFF242424) else Panel, shape = RoundedCornerShape(18.dp)) {
                                Text(msg.text, color = White, modifier = Modifier.padding(15.dp), fontSize = 14.sp)
                            }
                        }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(14.dp).background(Panel, RoundedCornerShape(24.dp)).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BasicTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                    textStyle = TextStyle(color = White, fontSize = 14.sp),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(White),
                    decorationBox = { inner -> if (input.isEmpty()) Text("Message POOP AI...", color = Gray, fontSize = 14.sp); inner() }
                )
                Spacer(Modifier.width(10.dp))
                Surface(color = White, shape = CircleShape, onClick = {
                    val text = input.trim()
                    if (text.isNotEmpty()) {
                        messages = messages + ChatMessage(text, true) + ChatMessage("تم استلام رسالتك. محرك الذكاء الاصطناعي غير موصول حاليًا؛ هذه نسخة الواجهة الأولية.", false)
                        input = ""
                    }
                }) {
                    Text("↑", color = Black, fontWeight = FontWeight.Bold, fontSize = 20.sp, modifier = Modifier.padding(horizontal = 13.dp, vertical = 7.dp))
                }
            }
            Text("POOP AI • ANDROID", color = Color(0xFF555555), fontSize = 9.sp, modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp), letterSpacing = 1.5.sp)
        }
    }
}

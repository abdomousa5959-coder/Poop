package com.poopai.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.net.Uri
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

object AgentBridge {
    @Volatile var service: PoopAccessibilityService? = null
}

class PoopAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() { super.onServiceConnected(); AgentBridge.service = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() { AgentBridge.service = null }
    override fun onDestroy() { if (AgentBridge.service === this) AgentBridge.service = null; super.onDestroy() }

    fun execute(action: String, args: org.json.JSONObject): String {
        return try {
            when (action) {
                "open_app" -> openApp(args.optString("app_name"))
                "search_youtube" -> {
                    val query = args.optString("query").trim()
                    require(query.isNotBlank()) { "اكتب كلمات البحث" }
                    val url = "https://www.youtube.com/results?search_query=" + Uri.encode(query)
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    "تم فتح نتائج بحث يوتيوب عن: $query"
                }
                "search_web" -> {
                    val query = args.optString("query").trim()
                    require(query.isNotBlank()) { "اكتب كلمات البحث" }
                    val url = "https://www.google.com/search?q=" + Uri.encode(query)
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    "تم فتح نتائج البحث عن: $query"
                }
                "read_screen" -> {
                    val root = rootInActiveWindow ?: error("الشاشة الحالية غير متاحة")
                    val lines = mutableListOf<String>()
                    collectVisibleText(root, lines, 0)
                    require(lines.isNotEmpty()) { "لم أستطع قراءة نص ظاهر على الشاشة" }
                    lines.distinct().take(80).joinToString("\n")
                }
                "open_url" -> {
                    val url = args.optString("url")
                    require(url.startsWith("https://") || url.startsWith("http://")) { "رابط غير صالح" }
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    "تم فتح الموقع"
                }
                "tap_text" -> {
                    val text = args.optString("text")
                    require(text.isNotBlank())
                    val risky = listOf("send", "submit", "pay", "purchase", "delete", "remove", "confirm", "شراء", "دفع", "حذف", "إرسال", "ارسال", "تأكيد", "موافق")
                    require(risky.none { text.contains(it, true) }) { "الضغط على إجراءات حساسة يحتاج تأكيدًا منفصلًا." }
                    val node = findText(rootInActiveWindow, text) ?: error("مش لاقي العنصر: $text")
                    val clicked = clickNode(node)
                    require(clicked) { "العنصر غير قابل للضغط" }
                    "تم الضغط على: $text"
                }
                "type_text" -> {
                    val value = args.optString("text")
                    val node = findFocused(rootInActiveWindow) ?: error("مفيش حقل كتابة محدد")
                    val bundle = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }
                    require(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)) { "تعذرت الكتابة في الحقل" }
                    "تم إدخال النص"
                }
                "scroll" -> {
                    val down = args.optString("direction") == "down"
                    val node = rootInActiveWindow ?: error("الشاشة غير متاحة")
                    val actionId = if (down) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                    require(node.performAction(actionId)) { "تعذر تمرير الشاشة" }
                    "تم التمرير"
                }
                "press_back" -> { performGlobalAction(GLOBAL_ACTION_BACK); "تم الرجوع" }
                "press_home" -> { performGlobalAction(GLOBAL_ACTION_HOME); "تم الرجوع للشاشة الرئيسية" }
                else -> error("أمر غير مدعوم")
            }
        } catch (e: Exception) { "لم يتم التنفيذ: " + (e.message ?: "خطأ غير معروف") }
    }

    private fun openApp(label: String): String {
        require(label.isNotBlank())
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val matches = pm.queryIntentActivities(intent, 0)
        val match = matches.firstOrNull { it.loadLabel(pm).toString().equals(label, true) }
            ?: matches.firstOrNull { it.loadLabel(pm).toString().contains(label, true) }
            ?: error("مش لاقي تطبيق باسم $label")
        val launch = pm.getLaunchIntentForPackage(match.activityInfo.packageName) ?: error("تعذر فتح التطبيق")
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(launch)
        return "تم فتح " + match.loadLabel(pm)
    }

    private fun collectVisibleText(node: AccessibilityNodeInfo?, out: MutableList<String>, depth: Int) {
        if (node == null || depth > 30 || out.size >= 100) return
        if (node.isVisibleToUser) {
            node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out.add(it) }
            node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out.add(it) }
        }
        for (i in 0 until node.childCount) collectVisibleText(node.getChild(i), out, depth + 1)
    }

    private fun findText(node: AccessibilityNodeInfo?, target: String): AccessibilityNodeInfo? {
        if (node == null) return null
        val own = node.text?.toString().orEmpty() + " " + node.contentDescription?.toString().orEmpty()
        if (own.contains(target, true) && (node.isClickable || node.isFocusable)) return node
        for (i in 0 until node.childCount) findText(node.getChild(i), target)?.let { return it }
        return null
    }
    private fun findFocused(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isFocused && node.isEditable) return node
        for (i in 0 until node.childCount) findFocused(node.getChild(i))?.let { return it }
        return null
    }
    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable) return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        val parent = node.parent ?: return false
        return clickNode(parent)
    }
}
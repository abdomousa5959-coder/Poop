package com.poopai.app
import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File

object AgentBridge { @Volatile var service: PoopAccessibilityService? = null }
class PoopAccessibilityService : AccessibilityService() {
 override fun onServiceConnected(){super.onServiceConnected();AgentBridge.service=this}
 override fun onAccessibilityEvent(event:AccessibilityEvent?){}
 override fun onInterrupt(){AgentBridge.service=null}
 override fun onDestroy(){if(AgentBridge.service===this)AgentBridge.service=null;super.onDestroy()}
 fun execute(action:String,args:org.json.JSONObject):String=try{when(action){
 "search_youtube"->{val q=args.optString("query").trim();require(q.isNotBlank());val u=Uri.parse("https://www.youtube.com/results?search_query="+Uri.encode(q));try{startActivity(Intent(Intent.ACTION_VIEW,u).setPackage("com.google.android.youtube").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}catch(e:Exception){startActivity(Intent(Intent.ACTION_VIEW,u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))};"بحثت يوتيوب عن: $q"}
 "search_web"->{val q=args.optString("query").trim();require(q.isNotBlank());startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://www.google.com/search?q="+Uri.encode(q))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));"فتحت نتائج البحث عن $q"}
 "open_app"->openApp(args.optString("app_name"))
 "open_url"->{val u=args.optString("url");require(u.startsWith("https://")||u.startsWith("http://"));startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));"فتحت الرابط"}
 "request_file_access"->requestFileAccess()
 "list_files"->listFiles(args.optString("path",""))
 "search_files"->searchFiles(args.optString("query"))
 "read_file"->readFile(args.optString("path"))
 "create_folder"->createFolder(args.optString("path"))
 "read_screen"->{val a=mutableListOf<String>();collect(rootInActiveWindow,a,0);a.distinct().take(100).joinToString("\n").ifBlank{"لا يوجد نص ظاهر"}}
 "tap_text"->{val t=args.optString("text");val n=find(rootInActiveWindow,t)?:error("العنصر غير موجود");require(click(n));"تم الضغط على $t"}
 "type_text"->{val n=focused(rootInActiveWindow)?:error("لا يوجد حقل كتابة");val b=Bundle().apply{putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,args.optString("text"))};require(n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,b));"تم إدخال النص"}
 "scroll"->{val n=rootInActiveWindow?:error("الشاشة غير متاحة");require(n.performAction(if(args.optString("direction")=="down")AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD));"تم التمرير"}
 "press_back"->{performGlobalAction(GLOBAL_ACTION_BACK);"رجوع"}
 "press_home"->{performGlobalAction(GLOBAL_ACTION_HOME);"الرئيسية"}
 else->error("أمر غير مدعوم")
 }}catch(e:Exception){"فشل التنفيذ: "+(e.message?:"خطأ")}
 private fun requestFileAccess():String{if(Environment.isExternalStorageManager())return "صلاحية كل الملفات مفعّلة";startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,Uri.parse("package:$packageName")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));return "فعّل السماح بإدارة كل الملفات ثم ارجع للتطبيق"}
 private fun root():File{require(Environment.isExternalStorageManager()){"فعّل All files access أولاً"};return Environment.getExternalStorageDirectory().canonicalFile}
 private fun file(p:String):File{val r=root();val f=File(r,p.trimStart('/')).canonicalFile;require(f.path==r.path||f.path.startsWith(r.path+File.separator)){"المسار خارج مساحة التخزين"};return f}
 private fun listFiles(p:String):String{val d=file(p);require(d.isDirectory){"المجلد غير موجود"};return (d.listFiles()?.sortedBy{it.name}?.take(100)?.joinToString("\n"){(if(it.isDirectory)"📁 " else "📄 ")+it.name}).orEmpty().ifBlank{"المجلد فارغ"}}
 private fun searchFiles(q:String):String{require(q.isNotBlank());val r=root();val hits=mutableListOf<String>();var count=0
 fun walk(d:File,depth:Int){if(depth>8||hits.size>=40||count>=4000)return;d.listFiles()?.forEach{f->count++;if(f.name.contains(q,true))hits.add(f.relativeTo(r).path+(if(f.isDirectory)"/" else ""));if(f.isDirectory)walk(f,depth+1)}}
 walk(r,0);return hits.joinToString("\n").ifBlank{"لم يتم العثور على ملفات مطابقة"}}
 private fun readFile(p:String):String{val f=file(p);require(f.isFile){"الملف غير موجود"};require(f.length()<=1048576){"الحد الأقصى 1MB"};return f.readText(Charsets.UTF_8).take(30000)}
 private fun createFolder(p:String):String{require(p.isNotBlank());val f=file(p);require(!f.exists()){"المسار موجود بالفعل"};require(f.mkdirs()){"تعذر إنشاء المجلد"};return "تم إنشاء المجلد: "+f.relativeTo(root()).path}
 private fun openApp(s:String):String{require(s.isNotBlank());val pm=packageManager;val i=Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);val a=pm.queryIntentActivities(i,0).firstOrNull{it.loadLabel(pm).toString().contains(s,true)}?:error("التطبيق غير موجود");val launch=pm.getLaunchIntentForPackage(a.activityInfo.packageName)?:error("تعذر فتح التطبيق");launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);startActivity(launch);return "تم فتح "+a.loadLabel(pm)}
 private fun collect(n:AccessibilityNodeInfo?,a:MutableList<String>,d:Int){if(n==null||d>30||a.size>120)return;if(n.isVisibleToUser){n.text?.toString()?.takeIf{it.isNotBlank()}?.let{a.add(it)};n.contentDescription?.toString()?.takeIf{it.isNotBlank()}?.let{a.add(it)}};for(i in 0 until n.childCount)collect(n.getChild(i),a,d+1)}
 private fun find(n:AccessibilityNodeInfo?,s:String):AccessibilityNodeInfo?{if(n==null)return null;val t=n.text?.toString().orEmpty()+" "+n.contentDescription?.toString().orEmpty();if(t.contains(s,true)&&(n.isClickable||n.isFocusable))return n;for(i in 0 until n.childCount)find(n.getChild(i),s)?.let{return it};return null}
 private fun focused(n:AccessibilityNodeInfo?):AccessibilityNodeInfo?{if(n==null)return null;if(n.isFocused&&n.isEditable)return n;for(i in 0 until n.childCount)focused(n.getChild(i))?.let{return it};return null}
 private fun click(n:AccessibilityNodeInfo):Boolean{if(n.isClickable)return n.performAction(AccessibilityNodeInfo.ACTION_CLICK);return n.parent?.let{click(it)}?:false}
}
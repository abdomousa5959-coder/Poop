const MAX_TEXT=3000;
const ACTIONS={search_youtube:{query:"string"},search_web:{query:"string"},read_screen:{},open_app:{app_name:"string"},open_url:{url:"string"},tap_text:{text:"string"},type_text:{text:"string"},scroll:{direction:"string"},press_back:{},press_home:{},request_file_access:{},list_files:{path:"string"},search_files:{query:"string"},read_file:{path:"string"},create_folder:{path:"string"}};
function json(res,status,body){res.status(status).setHeader("Content-Type","application/json; charset=utf-8");res.setHeader("Cache-Control","no-store");return res.end(JSON.stringify(body));}
module.exports=async function handler(req,res){
 if(req.method!=="POST"){res.setHeader("Allow","POST");return json(res,405,{error:"Method not allowed"});}
 const key=process.env.OPENROUTER_API_KEY;if(!key)return json(res,503,{error:"خدمة الوكيل غير مهيأة."});
 try{let raw="";for await(const chunk of req){raw+=chunk;if(Buffer.byteLength(raw)>30000)return json(res,413,{error:"الطلب كبير جدًا."});}
 const b=JSON.parse(raw||"{}"),task=String(b.task||"").trim(),observations=Array.isArray(b.observations)?b.observations.slice(-10):[];
 if(!task||task.length>MAX_TEXT)return json(res,400,{error:"اكتب مهمة واضحة."});
 const system="أنت POOP AI، وكيل أندرويد ذكي. حلل الهدف كاملاً ثم نفذ خطوة واحدة فقط في كل دورة. ستصلك نتائج الخطوات السابقة فابن قرارك التالي عليها. أخرج JSON فقط: {message:string,done:boolean,action:{name:string,args:object}|null}. الأدوات: "+JSON.stringify(ACTIONS)+". لا تدع نجاح شيء قبل نتيجة الأداة. ابحث في يوتيوب بأداة search_youtube ثم اقرأ الشاشة للتحقق. افحص الملفات قبل التعامل معها. نفذ خطوة واحدة فقط وغيّر النهج عند الفشل. لا تستخدم أوامر shell ولا تعرض رموز أدوات. لا تنفذ حذفًا أو نقلًا أو استبدالًا أو إرسالًا أو شراءً أو نشرًا أو تغيير إعداد حساس؛ اطلب موافقة واضحة أولاً. لا تطلب موافقة للبحث والقراءة وفتح التطبيقات وإنشاء مجلد جديد بطلب المستخدم. استخدم العربية المصرية.";
 const upstream=await fetch("https://openrouter.ai/api/v1/chat/completions",{method:"POST",headers:{Authorization:"Bearer "+key,"Content-Type":"application/json","HTTP-Referer":"https://github.com/abdomousa5959-coder/Poop","X-OpenRouter-Title":"POOP AI"},body:JSON.stringify({model:"openrouter/free",messages:[{role:"system",content:system},{role:"user",content:JSON.stringify({task,observations})}],response_format:{type:"json_object"},temperature:.2,max_tokens:900})});
 const data=await upstream.json().catch(()=>({}));if(!upstream.ok)return json(res,502,{error:data?.error?.message||"تعذر تخطيط الخطوة."});
 let plan;try{plan=JSON.parse(data?.choices?.[0]?.message?.content||"{}");}catch(_){return json(res,502,{error:"استجابة التخطيط غير مفهومة."});}
 if(typeof plan.message!=="string"||typeof plan.done!=="boolean")return json(res,502,{error:"خطة غير مكتملة."});if(plan.done)return json(res,200,{message:plan.message,done:true,action:null});
 const name=plan.action?.name,args=plan.action?.args;if(!Object.prototype.hasOwnProperty.call(ACTIONS,name)||!args||typeof args!=="object"||Array.isArray(args))return json(res,502,{error:"إجراء غير مسموح."});
 for(const [k,t] of Object.entries(ACTIONS[name]))if(typeof args[k]!=="string")return json(res,502,{error:"بيانات الإجراء ناقصة."});
 return json(res,200,{message:plan.message,done:false,action:{name,args}});
 }catch(_){return json(res,400,{error:"تعذر تحليل المهمة."});}}
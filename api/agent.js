const MAX_TEXT = 3000;
function json(res, status, body) {
  res.status(status).setHeader("Content-Type", "application/json; charset=utf-8");
  res.setHeader("Cache-Control", "no-store");
  return res.end(JSON.stringify(body));
}
const tools = [
  { type:"function", function:{ name:"open_app", description:"Open an installed Android app by its visible app name.", parameters:{type:"object",properties:{app_name:{type:"string"}},required:["app_name"],additionalProperties:false} } },
  { type:"function", function:{ name:"open_url", description:"Open a website URL in the default browser.", parameters:{type:"object",properties:{url:{type:"string"}},required:["url"],additionalProperties:false} } },
  { type:"function", function:{ name:"tap_text", description:"Tap a visible UI element matching the supplied text. Only use when the user explicitly requested this interaction.", parameters:{type:"object",properties:{text:{type:"string"}},required:["text"],additionalProperties:false} } },
  { type:"function", function:{ name:"type_text", description:"Type text into the currently focused field. Only use text dictated by the user.", parameters:{type:"object",properties:{text:{type:"string"}},required:["text"],additionalProperties:false} } },
  { type:"function", function:{ name:"scroll", description:"Scroll the current screen up or down.", parameters:{type:"object",properties:{direction:{type:"string",enum:["up","down"]}},required:["direction"],additionalProperties:false} } },
  { type:"function", function:{ name:"press_back", description:"Navigate back one screen.", parameters:{type:"object",properties:{},additionalProperties:false} } },
  { type:"function", function:{ name:"press_home", description:"Return to the Android home screen.", parameters:{type:"object",properties:{},additionalProperties:false} } }
];
module.exports = async function handler(req,res) {
  if(req.method!=="POST"){res.setHeader("Allow","POST");return json(res,405,{error:"Method not allowed"});}
  const key=process.env.OPENROUTER_API_KEY;
  if(!key)return json(res,503,{error:"خدمة المساعد غير مهيأة حاليًا."});
  try {
    let raw="";
    for await(const chunk of req){raw+=chunk;if(Buffer.byteLength(raw)>16000)return json(res,413,{error:"الطلب كبير جدًا."});}
    const body=JSON.parse(raw||"{}");
    const task=String(body.task||"").trim();
    if(!task||task.length>MAX_TEXT)return json(res,400,{error:"اكتب مهمة واضحة (حتى 3000 حرف)."});
    const upstream=await fetch("https://openrouter.ai/api/v1/chat/completions",{
      method:"POST",headers:{Authorization:"Bearer "+key,"Content-Type":"application/json"},
      body:JSON.stringify({
        model:"openrouter/free",
        messages:[
          {role:"system",content:"You are POOP AI Android task planner. Convert the user's explicit request into the smallest safe sequence of Android actions using only the provided tools. Never infer permission to send messages, post, purchase, pay, delete, change security settings, or submit forms. Those actions are not available as tools. Do not claim actions happened. If a request is ambiguous, asks for a sensitive action, or cannot be safely completed, return a concise Arabic explanation and no tool calls. Use Egyptian Arabic. For opening a website use https URL. For app names use the visible app name. Only use tap/type when explicitly needed by the user's request."},
          {role:"user",content:task}
        ],
        tools,tool_choice:"auto",parallel_tool_calls:false,max_tokens:1200,temperature:0.1
      })
    });
    const data=await upstream.json().catch(()=>({}));
    if(!upstream.ok)return json(res,502,{error:"تعذر تخطيط المهمة حاليًا."});
    const msg=data?.choices?.[0]?.message||{};
    const actions=[];
    for(const call of (msg.tool_calls||[]).slice(0,6)){
      let args={};try{args=JSON.parse(call.function?.arguments||"{}");}catch(_){}
      const name=call.function?.name;
      if(!tools.some(t=>t.function.name===name))continue;
      actions.push({name,args});
    }
    return json(res,200,{message:msg.content|| (actions.length?"جهزت خطوات التنفيذ.":"محتاج توضيح أكتر عشان أنفذ بأمان."),actions});
  } catch(_){return json(res,400,{error:"تعذر فهم المهمة."});}
};
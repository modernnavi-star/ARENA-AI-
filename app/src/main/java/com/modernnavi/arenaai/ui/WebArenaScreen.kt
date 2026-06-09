package com.modernnavi.arenaai.ui

import android.net.Uri
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.modernnavi.arenaai.data.ArenaUiState

private class ArenaAndroidBridge(private val onSignOut: () -> Unit) {
    @JavascriptInterface
    fun signOut() {
        onSignOut()
    }
}

@Composable
fun WebArenaScreen(
    state: ArenaUiState,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier
) {
    var fileCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        fileCallback?.onReceiveValue(uris.toTypedArray())
        fileCallback = null
    }
    val html = remember(state.currentUser?.email, state.currentUser?.displayName) {
        arenaHtml(
            email = state.currentUser?.email.orEmpty(),
            name = state.currentUser?.displayName.orEmpty()
        )
    }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.databaseEnabled = true
                settings.allowFileAccess = true
                settings.allowContentAccess = true
                settings.cacheMode = WebSettings.LOAD_DEFAULT
                webViewClient = WebViewClient()
                webChromeClient = object : WebChromeClient() {
                    override fun onShowFileChooser(
                        webView: WebView?,
                        filePathCallback: ValueCallback<Array<Uri>>?,
                        fileChooserParams: FileChooserParams?
                    ): Boolean {
                        fileCallback?.onReceiveValue(emptyArray())
                        fileCallback = filePathCallback
                        fileLauncher.launch("*/*")
                        return true
                    }
                }
                addJavascriptInterface(ArenaAndroidBridge(onSignOut), "ArenaAndroid")
                loadDataWithBaseURL("https://arena.local/", html, "text/html", "UTF-8", null)
            }
        },
        update = { webView ->
            if (webView.url == null) {
                webView.loadDataWithBaseURL("https://arena.local/", html, "text/html", "UTF-8", null)
            }
        }
    )
}

private fun arenaHtml(email: String, name: String): String {
    val safeEmail = email.escapeForJs()
    val safeName = name.escapeForJs()
    return """
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1">
<title>Arena AI</title>
<style>
:root{--bg:#f8f7f2;--panel:#ffffff;--ink:#272522;--muted:#706c65;--line:#e6e1d8;--accent:#111827;--soft:#f1eee7;--chip:#f7f4ee;--good:#0f766e;--warn:#a16207;--dark:#0b1020;--purple:#7c3aed}
*{box-sizing:border-box} body{margin:0;background:var(--bg);color:var(--ink);font-family:Inter,Arial,system-ui,sans-serif;height:100vh;overflow:hidden} button,input,textarea,select{font:inherit} button{cursor:pointer;border:0;background:none;color:inherit}.app{display:grid;grid-template-columns:320px 1fr;height:100vh}.side{background:var(--panel);border-right:1px solid var(--line);display:flex;flex-direction:column;min-width:0}.brand{height:72px;display:flex;align-items:center;gap:8px;padding:0 18px;font-size:32px;font-weight:800;font-family:Georgia,serif}.brand small{font-family:Arial;font-size:18px}.modeBtn{margin:8px 14px;padding:12px;border-radius:12px;display:flex;gap:12px;align-items:center;text-align:left}.modeBtn:hover,.modeBtn.active{background:var(--soft)}.modeIcon{font-size:24px;width:34px}.modeTitle{font-size:18px;font-weight:700}.modeSub{color:var(--muted);font-size:13px}.newChat{margin:10px 14px;padding:12px;border:1px solid var(--line);border-radius:14px;font-weight:700;text-align:left}.history{overflow:auto;padding:8px 8px 16px}.histTitle{font-size:12px;text-transform:uppercase;color:var(--muted);padding:14px}.histItem{display:flex;gap:10px;align-items:center;padding:12px;border-radius:12px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.histItem:hover,.histItem.active{background:var(--soft)}.account{border-top:1px solid var(--line);padding:14px;display:flex;gap:10px;align-items:center}.avatar{width:34px;height:34px;border-radius:50%;background:linear-gradient(135deg,#7c3aed,#f97316)}.main{display:grid;grid-template-rows:72px 1fr auto;background:#fcfbf7;min-width:0}.top{display:flex;align-items:center;justify-content:space-between;border-bottom:1px solid var(--line);padding:0 18px}.title{font-size:28px;font-weight:800}.tools{display:flex;gap:8px;align-items:center}.tool{padding:10px 12px;border:1px solid var(--line);border-radius:12px;background:var(--panel)}.chat{overflow:auto;padding:20px;scroll-behavior:smooth}.welcome{max-width:920px;margin:0 auto;padding:24px 0}.modeGrid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:12px;margin:18px 0}.card{background:var(--panel);border:1px solid var(--line);border-radius:18px;padding:18px;box-shadow:0 8px 24px rgba(20,20,20,.04)}.card.active{outline:2px solid var(--accent)}.card h3{margin:0 0 6px;font-size:18px}.card p{margin:0;color:var(--muted);font-size:14px}.modelRow{display:flex;gap:8px;overflow:auto;padding:8px 0}.chip{padding:10px 14px;border:1px solid var(--line);border-radius:999px;background:var(--chip);white-space:nowrap}.chip.active{background:var(--accent);color:white}.msg{max-width:920px;margin:0 auto 18px;display:flex;gap:12px}.msg.user{justify-content:flex-end}.bubble{max-width:82%;background:var(--panel);border:1px solid var(--line);border-radius:18px;padding:16px;line-height:1.55;white-space:pre-wrap}.user .bubble{background:#111827;color:white}.assistant .bubble{background:#fff}.modelTag{font-size:12px;color:var(--muted);margin-top:10px}.duel{display:grid;grid-template-columns:1fr 1fr;gap:12px}.vote{margin-top:10px;padding:8px 10px;background:var(--soft);border-radius:10px}.composer{border-top:1px solid var(--line);background:var(--panel);padding:14px}.composeBox{max-width:980px;margin:0 auto;border:1px solid var(--line);border-radius:18px;padding:10px;background:#fff;display:grid;grid-template-columns:auto 1fr auto;gap:8px;align-items:end}.composeBox textarea{border:0;outline:0;resize:none;min-height:52px;max-height:180px;padding:12px;background:transparent}.send{background:var(--accent);color:white;border-radius:14px;padding:13px 18px;font-weight:800}.attach{padding:13px;border-radius:14px;background:var(--soft)}.drawer{position:fixed;right:0;top:0;bottom:0;width:min(560px,92vw);background:var(--panel);border-left:1px solid var(--line);box-shadow:-20px 0 60px rgba(0,0,0,.18);transform:translateX(105%);transition:.25s;z-index:5;display:flex;flex-direction:column}.drawer.open{transform:translateX(0)}.drawerHead{height:64px;display:flex;align-items:center;justify-content:space-between;padding:0 16px;border-bottom:1px solid var(--line)}.drawerBody{padding:14px;overflow:auto}.file{border:1px solid var(--line);border-radius:14px;padding:12px;margin-bottom:10px}.fileName{font-weight:800}.fileMeta{color:var(--muted);font-size:12px}.fileBtns{display:flex;gap:8px;margin-top:10px;flex-wrap:wrap}.mini{padding:8px 10px;border-radius:10px;background:var(--soft);font-size:13px}.toast{position:fixed;left:50%;bottom:90px;transform:translateX(-50%);background:#111827;color:white;padding:12px 16px;border-radius:14px;opacity:0;transition:.2s;z-index:10}.toast.show{opacity:1}.mobileModes{display:none}.empty{color:var(--muted);text-align:center;padding:40px}.workspacePreview{white-space:pre-wrap;background:#0f172a;color:#e5e7eb;border-radius:12px;padding:12px;max-height:260px;overflow:auto;font-family:ui-monospace,monospace;font-size:12px}.lang{font-size:12px;color:var(--muted);margin-top:8px}
@media(max-width:820px){.app{grid-template-columns:1fr}.side{position:fixed;left:0;top:0;bottom:0;width:86vw;z-index:6;transform:translateX(-105%);transition:.25s}.side.open{transform:translateX(0)}.main{height:100vh}.top{height:64px}.title{font-size:24px}.chat{padding:12px}.modeGrid{grid-template-columns:1fr 1fr}.bubble{max-width:92%}.duel{grid-template-columns:1fr}.mobileModes{display:block}.hideMobile{display:none}.composeBox{grid-template-columns:auto 1fr auto}.brand{height:64px}.workspaceLabel{display:none}}
</style>
</head>
<body>
<div class="app">
  <aside id="side" class="side">
    <div class="brand">▥ Arena <small>AI</small></div>
    <button class="newChat" onclick="newChat()">＋ New Chat</button>
    <button class="modeBtn" data-mode="battle" onclick="setMode('battle')"><div class="modeIcon">⚔</div><div><div class="modeTitle">Battle Mode</div><div class="modeSub">Battle 2 anonymous models</div></div></button>
    <button class="modeBtn" data-mode="agent" onclick="setMode('agent')"><div class="modeIcon">🛠</div><div><div class="modeTitle">Agent Mode</div><div class="modeSub">Built for complex tasks</div></div></button>
    <button class="modeBtn" data-mode="side" onclick="setMode('side')"><div class="modeIcon">💬</div><div><div class="modeTitle">Side by Side</div><div class="modeSub">Compare 2 models of your choice</div></div></button>
    <button class="modeBtn" data-mode="direct" onclick="setMode('direct')"><div class="modeIcon">○</div><div><div class="modeTitle">Direct</div><div class="modeSub">Chat with 1 model at a time</div></div></button>
    <div class="history" id="history"></div>
    <div class="account"><div class="avatar"></div><div style="min-width:0"><div id="userName" style="font-weight:800;overflow:hidden;text-overflow:ellipsis"></div><div id="userEmail" style="font-size:12px;color:var(--muted);overflow:hidden;text-overflow:ellipsis"></div><button class="mini" onclick="signOut()">Sign out</button></div></div>
  </aside>
  <main class="main">
    <div class="top"><button class="tool mobileModes" onclick="toggleSide()">☰</button><div class="title" id="modeTitle">Arena</div><div class="tools"><button class="tool" onclick="openWorkspace()">📁 <span class="workspaceLabel">Workspace</span></button><button class="tool" onclick="newChat()">＋</button></div></div>
    <section class="chat" id="chat"></section>
    <section class="composer"><div class="composeBox"><button class="attach" onclick="document.getElementById('fileInput').click()">📎</button><textarea id="prompt" placeholder="Message Arena AI..." oninput="autoGrow(this)"></textarea><button class="send" onclick="sendPrompt()">➜</button><input id="fileInput" type="file" multiple style="display:none" onchange="handleFiles(this.files)"></div><div id="fileNote" class="lang" style="max-width:980px;margin:6px auto 0"></div></section>
  </main>
</div>
<div id="drawer" class="drawer"><div class="drawerHead"><b>Workspace</b><button class="tool" onclick="closeWorkspace()">✕</button></div><div id="workspace" class="drawerBody"></div></div>
<div id="toast" class="toast"></div>
<script>
var USER_EMAIL='__USER_EMAIL__';
var USER_NAME='__USER_NAME__';
var stateKey='arena-web-state-v4';
var state=loadState();
var attachedFiles=[];
function defaultState(){return{mode:'agent',modelA:'Random',modelB:'Claude',chats:[],current:null,workspace:[]};}
function loadState(){try{return JSON.parse(localStorage.getItem(stateKey))||defaultState();}catch(e){return defaultState();}}
function save(){localStorage.setItem(stateKey,JSON.stringify(state));}
function uid(){return 'id-'+Date.now()+'-'+Math.random().toString(16).slice(2);}
function now(){return new Date().toLocaleString();}
function escapeHtml(s){return String(s).replace(/[&<>\"]/g,function(c){return {'&':'&amp;','<':'&lt;','>':'&gt;','\"':'&quot;'}[c];});}
function toast(s){var t=document.getElementById('toast');t.textContent=s;t.classList.add('show');setTimeout(function(){t.classList.remove('show');},2200);}
function currentChat(){if(!state.current)newChat(false);return state.chats.find(function(c){return c.id===state.current;});}
function newChat(renderNow){var c={id:uid(),title:'New chat',mode:state.mode,messages:[],created:now()};state.chats.unshift(c);state.current=c.id;save();if(renderNow!==false)render();}
function setMode(m){state.mode=m;save();render();toggleSide(false);}
function setModel(which,name){if(which==='a')state.modelA=name;else state.modelB=name;save();render();}
function toggleSide(force){var s=document.getElementById('side');if(force===false)s.classList.remove('open');else s.classList.toggle('open');}
function openWorkspace(){document.getElementById('drawer').classList.add('open');renderWorkspace();}
function closeWorkspace(){document.getElementById('drawer').classList.remove('open');}
function signOut(){try{ArenaAndroid.signOut();}catch(e){toast('Sign out unavailable');}}
function autoGrow(t){t.style.height='52px';t.style.height=Math.min(t.scrollHeight,180)+'px';}
function handleFiles(files){attachedFiles=[];var pending=files.length;if(!pending){return;}Array.from(files).forEach(function(file){var item={name:file.name,type:file.type||'file',size:file.size,text:''};if(file.type.indexOf('text')>=0 || /\.(txt|md|csv|json|xml|html|yaml|yml|kt|java|js|py|css)$/i.test(file.name)){var r=new FileReader();r.onload=function(){item.text=String(r.result).slice(0,12000);attachedFiles.push(item);pending--;if(!pending)fileDone();};r.readAsText(file);}else{attachedFiles.push(item);pending--;if(!pending)fileDone();}});}
function fileDone(){document.getElementById('fileNote').textContent='Attached: '+attachedFiles.map(function(f){return f.name;}).join(', ')+' — text files are read, PDFs/images are included as file metadata.';}
function modeInfo(){var map={battle:['Battle Mode','Battle 2 anonymous models and vote'],agent:['Agent Mode','Built for complex tasks with plan, files, and steps'],side:['Side by Side','Compare 2 selected models'],direct:['Direct','Chat with 1 model at a time']};return map[state.mode]||map.agent;}
function render(){document.getElementById('userName').textContent=USER_NAME||'Arena user';document.getElementById('userEmail').textContent=USER_EMAIL||'';var mi=modeInfo();document.getElementById('modeTitle').textContent=mi[0];document.querySelectorAll('.modeBtn').forEach(function(b){b.classList.toggle('active',b.dataset.mode===state.mode);});renderHistory();renderChat();}
function renderHistory(){var h=document.getElementById('history');var html='<div class="histTitle">Chats</div>';if(!state.chats.length)html+='<div class="empty">No chats yet</div>';state.chats.forEach(function(c){html+='<button class="histItem '+(c.id===state.current?'active':'')+'" onclick="state.current=\''+c.id+'\';save();render();toggleSide(false)">✣ '+escapeHtml(c.title)+'</button>';});h.innerHTML=html;}
function renderWelcome(){var models=['Random','Gemini','OpenAI','Claude','Mistral','Llama','Qwen','Kimi','Grok'];var mi=modeInfo();var html='<div class="welcome"><h1>What are we building today?</h1><p>'+escapeHtml(mi[1])+'</p><div class="modeGrid">';[['battle','⚔','Battle Mode','Anonymous model battle'],['agent','🛠','Agent Mode','Complex tasks and workspace'],['side','💬','Side by Side','Compare two models'],['direct','○','Direct','One model chat']].forEach(function(m){html+='<button class="card '+(state.mode===m[0]?'active':'')+'" onclick="setMode(\''+m[0]+'\')"><h3>'+m[1]+' '+m[2]+'</h3><p>'+m[3]+'</p></button>';});html+='</div><h3>Models</h3><div class="modelRow">';models.forEach(function(m){html+='<button class="chip '+(state.modelA===m?'active':'')+'" onclick="setModel(\'a\',\''+m+'\')">'+m+'</button>';});html+='</div><div class="card"><b>Workspace support</b><p>Every answer can generate Markdown, TXT, HTML/PDF-ready, JSON, YAML, and CSV files. Upload/paste text files as context.</p></div></div>';return html;}
function renderChat(){var c=currentChat();var chat=document.getElementById('chat');if(!c.messages.length){chat.innerHTML=renderWelcome();return;}var html='';c.messages.forEach(function(m){if(m.type==='duel'){html+='<div class="msg assistant"><div class="bubble" style="max-width:96%"><div class="duel"><div>'+format(m.a)+'<button class="vote" onclick="toast(\'Voted A\')">Vote A</button></div><div>'+format(m.b)+'<button class="vote" onclick="toast(\'Voted B\')">Vote B</button></div></div><div class="modelTag">Arena comparison</div></div></div>'; } else {html+='<div class="msg '+m.role+'"><div class="bubble">'+format(m.content)+'<div class="modelTag">'+escapeHtml(m.model||'')+'</div></div></div>';}});chat.innerHTML=html;chat.scrollTop=chat.scrollHeight;}
function format(s){return escapeHtml(s).replace(/\n/g,'<br>');}
function sendPrompt(){var p=document.getElementById('prompt');var prompt=p.value.trim();if(!prompt)return;var c=currentChat();if(c.title==='New chat')c.title=prompt.slice(0,54);c.messages.push({role:'user',content:prompt,model:'You',time:now()});p.value='';autoGrow(p);renderChat();setTimeout(function(){answer(prompt,c);},350);save();}
function answer(prompt,c){var context=attachedFiles.map(function(f){return 'File: '+f.name+' ('+f.type+', '+f.size+' bytes)\n'+(f.text||'[binary/PDF/image metadata only]');}).join('\n\n');var full=context?prompt+'\n\nAttached context:\n'+context:prompt;if(state.mode==='side'||state.mode==='battle'){var a=makeAnswer(full,state.modelA||'Random','A');var b=makeAnswer(full,state.modelB||'Claude','B');c.messages.push({type:'duel',a:a,b:b,model:'Arena Duel'});makeFiles(prompt,a+'\n\n--- Model B ---\n\n'+b,c.id,'Arena Duel');}else{var ans=makeAnswer(full,state.modelA||'Random','');c.messages.push({role:'assistant',content:ans,model:'Arena '+(state.modelA||'Random'),time:now()});makeFiles(prompt,ans,c.id,'Arena '+(state.modelA||'Random'));}attachedFiles=[];document.getElementById('fileNote').textContent='';save();render();}
function makeAnswer(prompt,model,slot){var lower=prompt.toLowerCase();var head=(slot?('Model '+slot+' — '):'')+model+' response\n\n';if(state.mode==='agent')head+='Agent plan\n1. Understand the goal\n2. Break it into tasks\n3. Produce deliverables\n4. Save workspace files\n5. Suggest next action\n\n';if(/article|essay|blog|write/.test(lower))return head+'# Article: '+cleanTitle(prompt)+'\n\n## Introduction\nArtificial intelligence is one of the most important technologies of the modern world. It helps software understand language, generate content, analyze data, automate work, and support human decision-making.\n\n## Main Benefits\n1. Productivity: AI completes repetitive tasks faster.\n2. Creativity: AI helps writers, designers, coders, and creators draft ideas.\n3. Learning: AI explains difficult topics in simple language and can support many languages.\n4. Business: AI improves support, analysis, planning, and automation.\n\n## Challenges\nAI can make mistakes, reflect bias, or miss context. Important outputs should be reviewed, especially in finance, health, law, and education.\n\n## Future\nAI assistants will become more useful for complex work, file generation, research, coding, and personal productivity.\n\n## Conclusion\nAI is best used as a partner that helps people think, create, and work faster while humans remain responsible for judgment and ethics.';if(/app|code|android|firebase|website|server/.test(lower))return head+'## Build Plan\nGoal: '+prompt+'\n\n### Architecture\n- Android/Web UI\n- Authentication\n- AI router/server\n- Database history\n- Workspace file generation\n- Export formats\n\n### Steps\n1. Design screens and navigation.\n2. Implement chat and model modes.\n3. Add server routing and fallback.\n4. Store chats and files.\n5. Test login, responses, history, and downloads.';if(/compare| vs |difference|battle/.test(lower))return head+'## Comparison\nRequest: '+prompt+'\n\n| Factor | Option A | Option B |\n|---|---|---|\n| Accuracy | Check source quality | Check source quality |\n| Speed | Measure response time | Measure response time |\n| Cost | Estimate usage | Estimate usage |\n| UX | Simple and clear | Flexible and powerful |\n\nRecommendation: choose the option that gives the best balance of accuracy, speed, cost, and user experience.';return head+'I understand your task:\n'+prompt+'\n\n## Best next steps\n1. Clarify the final output format.\n2. Break the task into smaller parts.\n3. Create a strong first draft.\n4. Review for accuracy and missing details.\n5. Export the final result from Workspace if needed.\n\nPractical answer: start with the most important requirement, keep the result simple, and improve it step by step.';}
function cleanTitle(s){return s.replace(/\s+/g,' ').slice(0,70);}
function makeFiles(prompt,answer,chatId,model){var title=cleanTitle(prompt)||'Arena response';var base=title.toLowerCase().replace(/[^a-z0-9]+/g,'-').replace(/^-|-$/g,'').slice(0,40)||'arena-response';var md='# '+title+'\n\nModel: '+model+'\n\n## Prompt\n'+prompt+'\n\n## Response\n'+answer;var txt=title+'\n\nModel: '+model+'\n\nPrompt:\n'+prompt+'\n\nResponse:\n'+answer;var html='<!doctype html><html><head><meta charset="utf-8"><title>'+escapeHtml(title)+'</title><style>body{font-family:Arial;padding:32px;line-height:1.55}pre{white-space:pre-wrap}</style></head><body><h1>'+escapeHtml(title)+'</h1><p><b>Model:</b> '+escapeHtml(model)+'</p><h2>Prompt</h2><p>'+escapeHtml(prompt)+'</p><h2>Response</h2><pre>'+escapeHtml(answer)+'</pre></body></html>';var obj={title:title,model:model,prompt:prompt,response:answer};var yaml='title: '+title+'\nmodel: '+model+'\nprompt: |\n  '+prompt.replace(/\n/g,'\n  ')+'\nresponse: |\n  '+answer.replace(/\n/g,'\n  ');var csv='field,value\ntitle,"'+title.replace(/"/g,'""')+'"\nmodel,"'+model+'"';var pdf=makePdf(title+'\n\n'+answer);addFile(base+'.md','Markdown',md,chatId);addFile(base+'.txt','Plain text',txt,chatId);addFile(base+'.html','HTML / PDF-ready',html,chatId);addFile(base+'.json','JSON',JSON.stringify(obj,null,2),chatId);addFile(base+'.yaml','YAML',yaml,chatId);addFile(base+'.csv','CSV',csv,chatId);addFile(base+'.pdf','PDF',pdf,chatId,'application/pdf');toast('Workspace files generated');}
function addFile(name,type,content,chatId,mime){state.workspace.unshift({id:uid(),name:name,type:type,content:content,chatId:chatId,mime:mime||'text/plain',created:now()});}
function renderWorkspace(){var w=document.getElementById('workspace');if(!state.workspace.length){w.innerHTML='<div class="empty">No files yet. Ask Arena to create an article, app plan, report, or comparison.</div>';return;}var html='';state.workspace.forEach(function(f){html+='<div class="file"><div class="fileName">'+escapeHtml(f.name)+'</div><div class="fileMeta">'+escapeHtml(f.type)+' • '+escapeHtml(f.created)+'</div><div class="fileBtns"><button class="mini" onclick="copyFile(\''+f.id+'\')">Copy</button><button class="mini" onclick="downloadFile(\''+f.id+'\')">Download</button></div><div class="workspacePreview">'+escapeHtml(String(f.content).slice(0,1200))+'</div></div>';});w.innerHTML=html;}
function findFile(id){return state.workspace.find(function(f){return f.id===id;});}
function copyFile(id){var f=findFile(id);if(!f)return;navigator.clipboard.writeText(f.content).then(function(){toast('Copied '+f.name);});}
function downloadFile(id){var f=findFile(id);if(!f)return;var blob;if(f.mime==='application/pdf')blob=new Blob([f.content],{type:'application/pdf'});else blob=new Blob([f.content],{type:f.mime||'text/plain'});var a=document.createElement('a');a.href=URL.createObjectURL(blob);a.download=f.name;document.body.appendChild(a);a.click();setTimeout(function(){URL.revokeObjectURL(a.href);a.remove();},1000);}
function makePdf(text){var safe=String(text).replace(/[()\\]/g,function(c){return '\\'+c;}).split('\n').slice(0,42);var lines=safe.map(function(line,i){return 'BT /F1 12 Tf 50 '+(780-i*17)+' Td ('+line.slice(0,92)+') Tj ET';}).join('\n');var body='1 0 obj << /Type /Catalog /Pages 2 0 R >> endobj\n2 0 obj << /Type /Pages /Kids [3 0 R] /Count 1 >> endobj\n3 0 obj << /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >> endobj\n4 0 obj << /Type /Font /Subtype /Type1 /BaseFont /Helvetica >> endobj\n5 0 obj << /Length '+lines.length+' >> stream\n'+lines+'\nendstream endobj\n';return '%PDF-1.4\n'+body+'trailer << /Root 1 0 R >>\n%%EOF';}
if(!state.chats.length)newChat(false);render();
</script>
</body>
</html>
""".trimIndent()
        .replace("__USER_EMAIL__", safeEmail)
        .replace("__USER_NAME__", safeName)
}

private fun String.escapeForJs(): String = this
    .replace("\\", "\\\\")
    .replace("'", "\\'")
    .replace("\n", " ")
    .replace("\r", " ")

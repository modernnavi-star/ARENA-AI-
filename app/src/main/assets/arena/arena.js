const params = new URLSearchParams(location.search);
const USER_EMAIL = params.get('email') || '';
const USER_NAME = params.get('name') || 'Arena user';
const stateKey = 'arena-ai-complete-web-state-v1';
const models = ['Random', 'Gemini', 'OpenAI', 'Claude', 'Mistral', 'Llama', 'Qwen', 'Kimi', 'Grok'];
let attachedFiles = [];
let state = loadState();

function defaultState() {
  return { mode: 'agent', modelA: 'Random', modelB: 'Claude', current: null, chats: [], workspace: [] };
}
function loadState() { try { return JSON.parse(localStorage.getItem(stateKey)) || defaultState(); } catch (_) { return defaultState(); } }
function save() { localStorage.setItem(stateKey, JSON.stringify(state)); }
function id() { return 'id-' + Date.now() + '-' + Math.random().toString(16).slice(2); }
function now() { return new Date().toLocaleString(); }
function esc(v) { return String(v).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c])); }
function toast(msg) { const t = byId('toast'); t.textContent = msg; t.classList.add('show'); setTimeout(() => t.classList.remove('show'), 2000); }
function byId(x) { return document.getElementById(x); }
function currentChat() { if (!state.current) newChat(false); return state.chats.find(c => c.id === state.current); }
function newChat(shouldRender = true) { const chat = { id: id(), title: 'New chat', mode: state.mode, messages: [], created: now() }; state.chats.unshift(chat); state.current = chat.id; save(); if (shouldRender) render(); }
function setMode(mode) { state.mode = mode; save(); render(); closeSide(); }
function setModel(which, model) { if (which === 'a') state.modelA = model; else state.modelB = model; save(); render(); }
function modeInfo() { return { battle: ['Battle Mode', 'Battle 2 anonymous models'], agent: ['Agent Mode', 'Built for complex tasks'], side: ['Side by Side', 'Compare 2 models of your choice'], direct: ['Direct', 'Chat with 1 model at a time'] }[state.mode] || ['Agent Mode', 'Built for complex tasks']; }
function openSide() { byId('side').classList.add('open'); byId('overlay').classList.add('show'); }
function closeSide() { byId('side').classList.remove('open'); byId('overlay').classList.remove('show'); }
function openWorkspace() { byId('workspace').classList.add('open'); byId('overlay').classList.add('show'); renderWorkspace(); }
function closeWorkspace() { byId('workspace').classList.remove('open'); byId('overlay').classList.remove('show'); }
function signOut() { try { ArenaAndroid.signOut(); } catch (_) { toast('Sign out unavailable'); } }

function render() {
  const info = modeInfo();
  byId('userName').textContent = USER_NAME;
  byId('userEmail').textContent = USER_EMAIL;
  byId('topTitle').textContent = info[0];
  byId('topSubtitle').textContent = info[1];
  document.querySelectorAll('.mode-item').forEach(b => b.classList.toggle('active', b.dataset.mode === state.mode));
  renderHistory();
  renderChat();
}
function renderHistory() {
  const h = byId('history');
  if (!state.chats.length) { h.innerHTML = '<div class="empty">No chats yet</div>'; return; }
  h.innerHTML = state.chats.map(c => `<button class="hist ${c.id === state.current ? 'active' : ''}" data-chat="${c.id}">✣ ${esc(c.title)}</button>`).join('');
  h.querySelectorAll('[data-chat]').forEach(b => b.onclick = () => { state.current = b.dataset.chat; save(); render(); closeSide(); });
}
function renderWelcome() {
  const modeCards = [ ['battle','⚔','Battle Mode','Battle 2 anonymous models'], ['agent','🛠','Agent Mode','Built for complex tasks'], ['side','💬','Side by Side','Compare two models'], ['direct','○','Direct','One model chat'] ];
  return `<div class="welcome"><h2>What are we building today?</h2><p>Choose a mode, pick models, attach files, and generate workspace outputs.</p><div class="mode-grid">${modeCards.map(m => `<button class="card ${state.mode === m[0] ? 'active' : ''}" data-set-mode="${m[0]}"><b>${m[1]} ${m[2]}</b><small>${m[3]}</small></button>`).join('')}</div><h3>Models</h3><div class="model-row">${models.map(m => `<button class="chip ${state.modelA === m ? 'active' : ''}" data-model="${m}">${m}</button>`).join('')}</div><div class="sample-grid">${['Create an article regarding AI','Build an Android app architecture','Compare Gemini and Claude','Generate a PDF-ready report'].map(s => `<button class="sample" data-sample="${esc(s)}">${esc(s)}</button>`).join('')}</div></div>`;
}
function renderChat() {
  const chat = currentChat();
  const el = byId('chat');
  if (!chat.messages.length) {
    el.innerHTML = renderWelcome();
    el.querySelectorAll('[data-set-mode]').forEach(b => b.onclick = () => setMode(b.dataset.setMode));
    el.querySelectorAll('[data-model]').forEach(b => b.onclick = () => setModel('a', b.dataset.model));
    el.querySelectorAll('[data-sample]').forEach(b => { b.onclick = () => { byId('prompt').value = b.dataset.sample; autoGrow(byId('prompt')); }; });
    return;
  }
  el.innerHTML = chat.messages.map(m => {
    if (m.type === 'duel') return `<div class="duel"><div class="duel-card">${format(m.a)}<button class="vote">Vote A</button></div><div class="duel-card">${format(m.b)}<button class="vote">Vote B</button></div></div>`;
    return `<div class="msg ${m.role}"><div class="bubble">${format(m.content)}<div class="tag">${esc(m.model || '')}</div></div></div>`;
  }).join('');
  el.querySelectorAll('.vote').forEach(b => b.onclick = () => toast('Vote saved'));
  el.scrollTop = el.scrollHeight;
}
function format(s) { return esc(s).replace(/\n/g, '<br>'); }
function autoGrow(t) { t.style.height = '50px'; t.style.height = Math.min(t.scrollHeight, 170) + 'px'; }

function sendPrompt() {
  const input = byId('prompt');
  let prompt = input.value.trim();
  if (!prompt && attachedFiles.length) {
    prompt = 'Analyze the attached file(s): ' + attachedFiles.map(f => f.name).join(', ') + '. Summarize what they are, extract useful details if possible, and generate next steps.';
  }
  if (!prompt) { toast('Type a message or attach a file first'); return; }
  const chat = currentChat();
  if (chat.title === 'New chat') chat.title = prompt.slice(0, 58);
  chat.messages.push({ role: 'user', content: prompt, model: 'You', time: now() });
  input.value = '';
  autoGrow(input);
  renderChat();
  setTimeout(() => answerPrompt(prompt, chat), 120);
  save();
}
function answerPrompt(prompt, chat) {
  const context = attachedFiles.map(f => `File: ${f.name} (${f.type}, ${f.size} bytes)\n${f.text || '[binary/PDF/image metadata only]'}`).join('\n\n');
  const fullPrompt = context ? `${prompt}\n\nAttached context:\n${context}` : prompt;
  if (state.mode === 'side' || state.mode === 'battle') {
    const a = makeAnswer(fullPrompt, state.modelA || 'Random', 'A');
    const b = makeAnswer(fullPrompt, state.modelB || 'Claude', 'B');
    chat.messages.push({ type: 'duel', a, b, model: 'Arena comparison', time: now() });
    makeFiles(prompt, `${a}\n\n--- Model B ---\n\n${b}`, chat.id, 'Arena Duel');
  } else {
    const ans = makeAnswer(fullPrompt, state.modelA || 'Random', '');
    chat.messages.push({ role: 'assistant', content: ans, model: `Arena ${state.modelA || 'Random'}`, time: now() });
    makeFiles(prompt, ans, chat.id, `Arena ${state.modelA || 'Random'}`);
  }
  attachedFiles = [];
  byId('attachments').textContent = '';
  save();
  render();
}
function makeAnswer(prompt, model, slot) {
  const lower = prompt.toLowerCase();
  let head = `${slot ? `Model ${slot} — ` : ''}${model} response\n\n`;
  if (state.mode === 'agent') head += 'Agent plan\n1. Understand the goal\n2. Break it into tasks\n3. Produce deliverables\n4. Save workspace files\n5. Suggest next action\n\n';
  if (isKannadaRequest(prompt)) return head + buildKannadaAnswer(prompt);
  if (/attached context|attached file|file:|image|jpg|jpeg|png|pdf/.test(lower)) return head + buildFileAnalysis(prompt);
  if (/article|essay|blog|write/.test(lower)) return head + `# Article: ${cleanTitle(prompt)}\n\n## Introduction\nArtificial intelligence is one of the most important technologies of the modern world. It helps software understand language, generate content, analyze data, automate work, and support human decision-making.\n\n## Main Benefits\n1. Productivity: AI completes repetitive tasks faster.\n2. Creativity: AI helps writers, designers, coders, and creators draft ideas.\n3. Learning: AI explains difficult topics in simple language and supports many languages.\n4. Business: AI improves customer support, analysis, planning, and automation.\n\n## Challenges\nAI can make mistakes, reflect bias, or miss context. Important outputs should be reviewed, especially in finance, health, law, and education.\n\n## Future\nAI assistants will become more useful for complex work, file generation, research, coding, and personal productivity.\n\n## Conclusion\nAI is best used as a partner that helps people think, create, and work faster while humans remain responsible for judgment and ethics.`;
  if (/app|code|android|firebase|website|server/.test(lower)) return head + `## Build Plan\nGoal: ${prompt}\n\n### Architecture\n- Android/Web UI\n- Authentication\n- AI router/server\n- Database history\n- Workspace file generation\n- Export formats\n\n### Steps\n1. Design screens and navigation.\n2. Implement chat and model modes.\n3. Add server routing and fallback.\n4. Store chats and files.\n5. Test login, responses, history, and downloads.`;
  if (/compare| vs |difference|battle/.test(lower)) return head + `## Comparison\nRequest: ${prompt}\n\n| Factor | Option A | Option B |\n|---|---|---|\n| Accuracy | Check source quality | Check source quality |\n| Speed | Measure response time | Measure response time |\n| Cost | Estimate usage | Estimate usage |\n| UX | Simple and clear | Flexible and powerful |\n\nRecommendation: choose the option that gives the best balance of accuracy, speed, cost, and user experience.`;
  return head + `I understand your task:\n${prompt}\n\n## Best next steps\n1. Clarify the final output format.\n2. Break the task into smaller parts.\n3. Create a strong first draft.\n4. Review for accuracy and missing details.\n5. Export the final result from Workspace if needed.\n\nPractical answer: start with the most important requirement, keep the result simple, and improve it step by step.`;
}


function isKannadaRequest(text) {
  return /[ಀ-೿]/.test(text) || /kannada|ಕನ್ನಡ|letter in kannada|kannada letter/i.test(text);
}
function buildKannadaAnswer(prompt) {
  const wantsLetter = /letter|ಪತ್ರ|application|request/i.test(prompt);
  if (wantsLetter) {
    return `# ಕನ್ನಡ ಪತ್ರ

## ವಿಷಯ: ಕೃತಕ ಬುದ್ಧಿಮತ್ತೆ (AI) ಕುರಿತು ಮಾಹಿತಿ

ಕಳುಹಿಸುವವರು,
ನಿಮ್ಮ ಹೆಸರು,
ನಿಮ್ಮ ವಿಳಾಸ,
ದಿನಾಂಕ: ${new Date().toLocaleDateString()}

ಸ್ವೀಕರಿಸುವವರು,
ಗೌರವಾನ್ವಿತ ಅಧಿಕಾರಿಗಳಿಗೆ / ಶಿಕ್ಷಕರಿಗೆ,

ಮಾನ್ಯರೇ,

ವಿಷಯಕ್ಕೆ ಸಂಬಂಧಿಸಿದಂತೆ, ಕೃತಕ ಬುದ್ಧಿಮತ್ತೆ ಅಂದರೆ Artificial Intelligence ಇಂದಿನ ತಂತ್ರಜ್ಞಾನ ಜಗತ್ತಿನಲ್ಲಿ ಅತ್ಯಂತ ಪ್ರಮುಖ ಪಾತ್ರ ವಹಿಸುತ್ತಿದೆ. AI ಬಳಸಿ ಮನುಷ್ಯರು ಬರವಣಿಗೆ, ಅಧ್ಯಯನ, ಸಂಶೋಧನೆ, ಅನುವಾದ, ಕೋಡಿಂಗ್, ಡೇಟಾ ವಿಶ್ಲೇಷಣೆ ಮತ್ತು ದಿನನಿತ್ಯದ ಕೆಲಸಗಳನ್ನು ಹೆಚ್ಚು ವೇಗವಾಗಿ ಮತ್ತು ಸುಲಭವಾಗಿ ಮಾಡಬಹುದು.

AI ಯ ಪ್ರಮುಖ ಪ್ರಯೋಜನಗಳು ಹೀಗಿವೆ:
1. ಕೆಲಸದ ವೇಗ ಮತ್ತು ಉತ್ಪಾದಕತೆ ಹೆಚ್ಚುತ್ತದೆ.
2. ವಿದ್ಯಾರ್ಥಿಗಳಿಗೆ ಕಠಿಣ ವಿಷಯಗಳನ್ನು ಸರಳವಾಗಿ ಅರ್ಥಮಾಡಿಕೊಳ್ಳಲು ಸಹಾಯವಾಗುತ್ತದೆ.
3. ವ್ಯವಹಾರಗಳಲ್ಲಿ ಗ್ರಾಹಕ ಸೇವೆ, ಯೋಜನೆ ಮತ್ತು ಮಾಹಿತಿ ವಿಶ್ಲೇಷಣೆಗೆ ಸಹಾಯ ಮಾಡುತ್ತದೆ.
4. ಸೃಜನಾತ್ಮಕ ಬರವಣಿಗೆ, ವಿನ್ಯಾಸ ಮತ್ತು ಅಪ್ಲಿಕೇಶನ್ ಅಭಿವೃದ್ಧಿಯಲ್ಲಿ ಸಹಕಾರ ನೀಡುತ್ತದೆ.

ಆದರೆ AI ಬಳಸುವಾಗ ಜಾಗರೂಕತೆಯೂ ಅಗತ್ಯ. AI ನೀಡುವ ಮಾಹಿತಿ ಯಾವಾಗಲೂ ನಿಖರವಾಗಿರುತ್ತದೆ ಎಂದು ಊಹಿಸಬಾರದು. ಮುಖ್ಯ ನಿರ್ಧಾರಗಳಿಗಾಗಿ ಮಾಹಿತಿಯನ್ನು ಪರಿಶೀಲಿಸಿ, ನೈತಿಕವಾಗಿ ಮತ್ತು ಜವಾಬ್ದಾರಿಯಿಂದ ಬಳಸಬೇಕು.

ಆದ್ದರಿಂದ, AI ಒಂದು ಶಕ್ತಿಶಾಲಿ ಸಹಾಯಕ ತಂತ್ರಜ್ಞಾನವಾಗಿದ್ದು, ಸರಿಯಾಗಿ ಬಳಸಿದರೆ ಶಿಕ್ಷಣ, ಉದ್ಯಮ, ತಂತ್ರಜ್ಞಾನ ಮತ್ತು ಸಮಾಜದ ಅಭಿವೃದ್ಧಿಗೆ ಬಹಳ ಉಪಯುಕ್ತವಾಗುತ್ತದೆ.

ಧನ್ಯವಾದಗಳು.

ನಿಮ್ಮ ವಿಶ್ವಾಸಿ,
ನಿಮ್ಮ ಹೆಸರು

## Workspace ಸೂಚನೆ
ಈ ಉತ್ತರಕ್ಕಾಗಿ Markdown, TXT, HTML/PDF-ready, JSON, YAML, CSV ಮತ್ತು PDF ಫೈಲ್‌ಗಳನ್ನು Workspace ನಲ್ಲಿ ರಚಿಸಲಾಗಿದೆ.`;
  }
  return `# ಕನ್ನಡ ಪ್ರತಿಕ್ರಿಯೆ

ನಿಮ್ಮ ಸಂದೇಶವನ್ನು ನಾನು ಅರ್ಥಮಾಡಿಕೊಂಡಿದ್ದೇನೆ:
${prompt}

## ಮುಖ್ಯ ಉತ್ತರ
ಕನ್ನಡದಲ್ಲಿ ಕೆಲಸ ಮಾಡಲು ಈ ಅಪ್ಲಿಕೇಶನ್ ಸಿದ್ಧವಾಗಿದೆ. ನೀವು ಕನ್ನಡ ಅಕ್ಷರ, ಪದ, ವಾಕ್ಯ, ಲೇಖನ, ವರದಿ ಅಥವಾ ಯೋಜನೆ ಕೇಳಿದರೆ, Arena AI ಕನ್ನಡದಲ್ಲೇ ಉತ್ತರವನ್ನು ರಚಿಸುತ್ತದೆ.

## ಮುಂದಿನ ಹಂತಗಳು
1. ನೀವು ಬೇಕಾದ ವಿಷಯವನ್ನು ಕನ್ನಡದಲ್ಲಿ ಬರೆಯಿರಿ.
2. ಬೇಕಾದರೆ ಫೈಲ್ ಅಥವಾ ಚಿತ್ರವನ್ನು ಜೋಡಿಸಿ.
3. ಉತ್ತರ ಬಂದ ನಂತರ Workspace ತೆರೆಯಿರಿ.
4. Markdown, TXT, HTML, JSON, YAML, CSV ಮತ್ತು PDF ರೂಪದಲ್ಲಿ ಫೈಲ್ ಡೌನ್‌ಲೋಡ್ ಮಾಡಿ.

## ಉದಾಹರಣೆ
“ಕನ್ನಡದಲ್ಲಿ AI ಬಗ್ಗೆ ಲೇಖನ ಬರೆಯಿರಿ” ಎಂದು ಕೇಳಿದರೆ, ಅಪ್ಲಿಕೇಶನ್ ಕನ್ನಡ ಲೇಖನ ಮತ್ತು PDF-ready ಫೈಲ್ ರಚಿಸುತ್ತದೆ.

## ಸಾರಾಂಶ
ಈ ಅಪ್ಲಿಕೇಶನ್ ಬಹುಭಾಷಾ ಬೆಂಬಲದೊಂದಿಗೆ ಕೆಲಸ ಮಾಡುತ್ತದೆ ಮತ್ತು ಕನ್ನಡ ಪಠ್ಯವನ್ನು Workspace ನಲ್ಲಿ ಉಳಿಸುತ್ತದೆ.`;
}
function buildFileAnalysis(prompt) {
  const files = attachedFiles.length ? attachedFiles : extractFilesFromPrompt(prompt);
  const list = files.map((f, i) => `${i + 1}. ${f.name || f} ${f.type ? '(' + f.type + ', ' + f.size + ' bytes)' : ''}`).join('\n');
  const hasImage = files.some(f => /image|jpg|jpeg|png|webp/i.test((f.type || '') + ' ' + (f.name || f)));
  const hasPdf = files.some(f => /pdf/i.test((f.type || '') + ' ' + (f.name || f)));
  let out = `# Attached file analysis\n\n## Files received\n${list || '- Attached file'}\n\n`;
  if (hasImage) out += '## Image handling\nThe image was received by the app. In embedded offline mode I can use the file name, type, size, and any user instructions. For true pixel-level vision/OCR, deploy the included Arena server with a vision-capable provider such as Gemini or OpenAI.\n\n';
  if (hasPdf) out += '## PDF handling\nThe PDF was received as a file. Text extraction works for text files in this embedded mode; full PDF text extraction is prepared through Workspace export/server architecture.\n\n';
  out += '## Useful next steps\n1. Tell me what you want done with the file: summarize, extract text, compare, convert, or make a report.\n2. I generated workspace exports for this task.\n3. Open Workspace to download Markdown, TXT, HTML/PDF-ready, JSON, YAML, CSV, and PDF files.\n\n## Draft response\nBased on the attachment metadata, this looks like a user-provided file for analysis. I can create a report, checklist, summary, document, or conversion-ready output from your instructions.';
  return out;
}
function extractFilesFromPrompt(prompt) {
  const matches = String(prompt).match(/File: ([^\n]+)/g) || [];
  return matches.map(m => m.replace(/^File: /, '').split(' (')[0]);
}

function cleanTitle(s) { return String(s).replace(/\s+/g, ' ').slice(0, 72); }

function handleFiles(files) {
  attachedFiles = [];
  const list = Array.from(files || []);
  if (!list.length) return;
  let pending = list.length;
  list.forEach(file => {
    const item = { name: file.name, type: file.type || 'file', size: file.size, text: '' };
    if (file.type.startsWith('text/') || /\.(txt|md|csv|json|xml|html|yaml|yml|kt|java|js|py|css|gradle)$/i.test(file.name)) {
      const reader = new FileReader();
      reader.onload = () => { item.text = String(reader.result).slice(0, 15000); attachedFiles.push(item); if (--pending === 0) showAttached(); };
      reader.readAsText(file);
    } else if (file.type.startsWith('image/')) {
      const reader = new FileReader();
      reader.onload = () => { item.dataUrl = String(reader.result); attachedFiles.push(item); if (--pending === 0) showAttached(); };
      reader.readAsDataURL(file);
    } else { attachedFiles.push(item); if (--pending === 0) showAttached(); }
  });
}
function showAttached() { byId('attachments').textContent = 'Attached: ' + attachedFiles.map(f => f.name).join(', ') + ' — text files are read; PDFs/images are added as metadata.'; }

function makeFiles(prompt, answer, chatId, model) {
  const title = cleanTitle(prompt) || 'Arena response';
  const base = title.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '').slice(0, 42) || 'arena-response';
  const md = `# ${title}\n\nModel: ${model}\n\n## Prompt\n${prompt}\n\n## Response\n${answer}`;
  const txt = `${title}\n\nModel: ${model}\n\nPrompt:\n${prompt}\n\nResponse:\n${answer}`;
  const imageHtml = attachedFiles.filter(f => f.dataUrl).map(f => `<figure><img src="${f.dataUrl}" style="max-width:100%;border:1px solid #ddd;border-radius:12px"><figcaption>${esc(f.name)}</figcaption></figure>`).join('');
  const html = `<!doctype html><html><head><meta charset="utf-8"><title>${esc(title)}</title><style>body{font-family:Arial;padding:32px;line-height:1.55}pre{white-space:pre-wrap}</style></head><body><h1>${esc(title)}</h1><p><b>Model:</b> ${esc(model)}</p>${imageHtml}<h2>Prompt</h2><p>${esc(prompt)}</p><h2>Response</h2><pre>${esc(answer)}</pre></body></html>`;
  const json = JSON.stringify({ title, model, prompt, response: answer }, null, 2);
  const yaml = `title: ${title}\nmodel: ${model}\nprompt: |\n  ${prompt.replace(/\n/g, '\n  ')}\nresponse: |\n  ${answer.replace(/\n/g, '\n  ')}`;
  const csv = `field,value\ntitle,"${title.replace(/"/g, '""')}"\nmodel,"${model.replace(/"/g, '""')}"`;
  addFile(`${base}.md`, 'Markdown', md, chatId);
  addFile(`${base}.txt`, 'Plain text', txt, chatId);
  addFile(`${base}.html`, 'HTML / PDF-ready', html, chatId, 'text/html');
  addFile(`${base}.json`, 'JSON', json, chatId, 'application/json');
  addFile(`${base}.yaml`, 'YAML', yaml, chatId);
  addFile(`${base}.csv`, 'CSV', csv, chatId, 'text/csv');
  addFile(`${base}.pdf`, 'PDF', `${title}\n\n${answer}`, chatId, 'application/pdf');
  toast('Workspace files generated');
}
function addFile(name, type, content, chatId, mime = 'text/plain') { state.workspace.unshift({ id: id(), name, type, content, chatId, mime, created: now() }); }
function renderWorkspace() {
  const el = byId('workspaceList');
  if (!state.workspace.length) { el.innerHTML = '<div class="empty">No files yet. Ask Arena to create an article, app plan, report, or comparison.</div>'; return; }
  el.innerHTML = state.workspace.map(f => `<div class="file"><div class="file-title">${esc(f.name)}</div><div class="file-meta">${esc(f.type)} • ${esc(f.created)}</div><div class="file-actions"><button class="mini" data-copy="${f.id}">Copy</button><button class="mini" data-download="${f.id}">Download</button></div><div class="preview">${esc(String(f.content).slice(0, 1400))}</div></div>`).join('');
  el.querySelectorAll('[data-copy]').forEach(b => b.onclick = () => copyFile(b.dataset.copy));
  el.querySelectorAll('[data-download]').forEach(b => b.onclick = () => downloadFile(b.dataset.download));
}
function findFile(fid) { return state.workspace.find(f => f.id === fid); }
function copyFile(fid) { const f = findFile(fid); if (!f) return; navigator.clipboard.writeText(f.content).then(() => toast('Copied ' + f.name)); }
function toBase64Unicode(str) {
  const bytes = new TextEncoder().encode(String(str));
  let bin = '';
  bytes.forEach(b => bin += String.fromCharCode(b));
  return btoa(bin);
}
function downloadFile(fid) {
  const f = findFile(fid); if (!f) return;
  try {
    if (window.ArenaAndroid) {
      if (f.type === 'PDF') {
        ArenaAndroid.savePdf(f.name, f.name.replace(/\.pdf$/i, ''), String(f.content));
      } else {
        ArenaAndroid.saveFileBase64(f.name, f.mime || 'text/plain', toBase64Unicode(f.content));
      }
      toast('Saving ' + f.name + ' to Downloads');
      return;
    }
  } catch (e) { console.log(e); }
  const blob = new Blob([f.content], { type: f.mime || 'text/plain' });
  const a = document.createElement('a'); a.href = URL.createObjectURL(blob); a.download = f.name; document.body.appendChild(a); a.click(); setTimeout(() => { URL.revokeObjectURL(a.href); a.remove(); }, 800);
}
function makePdf(text) {
  const lines = String(text).replace(/[()\\]/g, c => '\\' + c).split('\n').slice(0, 44).map((line, i) => `BT /F1 12 Tf 50 ${780 - i * 17} Td (${line.slice(0, 92)}) Tj ET`).join('\n');
  const body = `1 0 obj << /Type /Catalog /Pages 2 0 R >> endobj\n2 0 obj << /Type /Pages /Kids [3 0 R] /Count 1 >> endobj\n3 0 obj << /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >> endobj\n4 0 obj << /Type /Font /Subtype /Type1 /BaseFont /Helvetica >> endobj\n5 0 obj << /Length ${lines.length} >> stream\n${lines}\nendstream endobj\n`;
  return `%PDF-1.4\n${body}trailer << /Root 1 0 R >>\n%%EOF`;
}
function exportAll() { addFile('arena-workspace-export.json', 'Workspace export', JSON.stringify(state, null, 2), state.current || 'all', 'application/json'); renderWorkspace(); downloadFile(state.workspace[0].id); }

byId('newChatSide').onclick = () => newChat(true);
byId('newChatTop').onclick = () => newChat(true);
byId('openSide').onclick = openSide;
byId('overlay').onclick = () => { closeSide(); closeWorkspace(); };
byId('openWorkspace').onclick = openWorkspace;
byId('closeWorkspace').onclick = closeWorkspace;
byId('signOut').onclick = signOut;
byId('send').onclick = sendPrompt;
byId('prompt').addEventListener('input', e => autoGrow(e.target));
byId('prompt').addEventListener('keydown', e => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); sendPrompt(); } });
byId('attach').onclick = () => byId('fileInput').click();
byId('fileInput').onchange = e => handleFiles(e.target.files);
byId('exportAll').onclick = exportAll;
byId('clearWorkspace').onclick = () => { state.workspace = []; save(); renderWorkspace(); toast('Workspace cleared'); };
document.querySelectorAll('.mode-item').forEach(b => b.onclick = () => setMode(b.dataset.mode));

if (!state.chats.length) newChat(false);
render();

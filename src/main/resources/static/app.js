const api = {
    list: () => fetch('/api/servers').then(r => r.json()),
    add: (s) => fetch('/api/servers', {method:'POST', headers:{'Content-Type':'application/json'}, body: JSON.stringify(s)}).then(r=>r.json()),
    update: (id,s) => fetch('/api/servers/'+id, {method:'PUT', headers:{'Content-Type':'application/json'}, body: JSON.stringify(s)}).then(r=>r.json()),
    del: (id) => fetch('/api/servers/'+id, {method:'DELETE'}).then(r=>r.ok),
    attach: (id, pid) => fetch('/api/servers/'+id+'/attach-command' + (pid ? '?pid='+encodeURIComponent(pid) : '')).then(r=>r.json()),
};

let servers = [];
let current = null;
let editingServerId = null;
let chatWs = null;
let chatConnected = false;

let modelList = [];
let currentModel = null;

const icons = {
    sun: '<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="5"/><line x1="12" y1="1" x2="12" y2="3"/><line x1="12" y1="21" x2="12" y2="23"/><line x1="4.22" y1="4.22" x2="5.64" y2="5.64"/><line x1="18.36" y1="18.36" x2="19.78" y2="19.78"/><line x1="1" y1="12" x2="3" y2="12"/><line x1="21" y1="12" x2="23" y2="12"/><line x1="4.22" y1="19.78" x2="5.64" y2="18.36"/><line x1="18.36" y1="5.64" x2="19.78" y2="4.22"/></svg>',
    moon: '<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z"/></svg>',
    clipboard: '<svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M16 4h2a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h2"/><rect x="8" y="2" width="8" height="4" rx="1" ry="1"/></svg>',
    check: '<svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="20 6 9 17 4 12"/></svg>',
    loader: '<svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="12" y1="2" x2="12" y2="6"/><line x1="12" y1="18" x2="12" y2="22"/><line x1="4.93" y1="4.93" x2="7.76" y2="7.76"/><line x1="16.24" y1="16.24" x2="19.07" y2="19.07"/><line x1="2" y1="12" x2="6" y2="12"/><line x1="18" y1="12" x2="22" y2="12"/><line x1="4.93" y1="19.07" x2="7.76" y2="16.24"/><line x1="16.24" y1="7.76" x2="19.07" y2="4.93"/></svg>',
    flame: '<svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M8.5 14.5A2.5 2.5 0 0 0 11 12c0-1.38-.5-2-1-3-1.072-2.143-.224-4.054 2-6 .5 2.5 2 4.9 4 6.5 2 1.6 3 3.5 3 5.5a7 7 0 1 1-14 0c0-1.153.433-2.294 1-3a2.5 2.5 0 0 0 2.5 2.5z"/></svg>',
};

async function loadModels() {
    try {
        const r = await fetch('/api/models');
        modelList = await r.json();
        const sel = el('modelSelect');
        sel.innerHTML = '';
        modelList.forEach((m, i) => {
            const opt = document.createElement('option');
            opt.value = m.value;
            opt.textContent = m.name;
            sel.appendChild(opt);
            if (i === 0) currentModel = m;
        });
        updateThinkingState();
    } catch(e) {
        console.error('load models failed', e);
    }
}

function updateThinkingState() {
    const sel = el('modelSelect');
    const val = sel.value;
    const model = modelList.find(m => m.value === val);
    el('thinkingToggle').checked = model ? model.thinking : false;
    currentModel = model;
}

el('modelSelect').onchange = updateThinkingState;

function el(id){ return document.getElementById(id); }

async function refresh() {
    servers = await api.list();
    const selectedId = current ? current.id : null;
    if (selectedId) current = servers.find(s => s.id === selectedId) || null;
    renderList();
}

function renderList() {
    const search = el('serverSearch').value.toLowerCase();
    const ul = el('serverList');
    ul.innerHTML = '';
    servers.filter(s => {
        if (!search) return true;
        return (s.name||'').toLowerCase().includes(search) ||
               (s.ip||'').toLowerCase().includes(search) ||
               (s.agentId||'').toLowerCase().includes(search);
    }).forEach(s => {
        const li = document.createElement('li');
        li.className = (current && current.id === s.id) ? 'active' : '';
        li.innerHTML = `<div class="server-name"><span class="dot ${s.online?'online':''}"></span>${esc(s.name||s.ip)}</div>
                        <div class="server-ip">${esc(s.ip||'')} · ${esc(s.agentId)}</div>`;
        li.onclick = () => selectServer(s.id);
        ul.appendChild(li);
    });
}

function esc(t){ return (t||'').replace(/[&<>"]/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c])); }

function selectServer(id) {
    current = servers.find(s => s.id === id);
    if (!current) return;
    el('empty').classList.add('hidden');
    el('panel').classList.remove('hidden');
    el('panelName').textContent = current.name || current.ip;
    el('panelIp').textContent = ' ' + (current.ip||'');
    el('panelStatus').className = 'dot ' + (current.online ? 'online' : '');
    el('agentIdText').textContent = current.agentId;
    loadAttach();
    switchTab('chat');
    renderList();
}

async function loadAttach() {
    try {
        const pid = el('pidInput').value.trim();
        const r = await api.attach(current.id, pid);
        el('attachCmd').textContent = r.command || '';
    } catch(e) { el('attachCmd').textContent = '加载失败'; }
}

function switchTab(name) {
    document.querySelectorAll('.tab').forEach(t => t.classList.toggle('active', t.dataset.tab === name));
    document.querySelectorAll('.tabpane').forEach(p => p.classList.remove('active'));
    el(name + 'Tab').classList.add('active');
    if (name === 'chat') openChat();
}

/* ---------- Theme Toggle ---------- */
function toggleTheme() {
    const html = document.documentElement;
    const isDark = html.getAttribute('data-theme') === 'dark';
    html.setAttribute('data-theme', isDark ? 'light' : 'dark');
    el('themeToggle').innerHTML = isDark ? icons.moon : icons.sun;
    localStorage.setItem('theme', isDark ? 'light' : 'dark');
}
(function initTheme() {
    const saved = localStorage.getItem('theme') || 'light';
    document.documentElement.setAttribute('data-theme', saved);
    el('themeToggle').innerHTML = saved === 'dark' ? icons.sun : icons.moon;
})();

/* ---------- Copy Text (works on http too) ---------- */
function copyText(text) {
    if (navigator.clipboard && window.isSecureContext) {
        navigator.clipboard.writeText(text).catch(() => fallbackCopy(text));
    } else {
        fallbackCopy(text);
    }
}
function fallbackCopy(text) {
    const ta = document.createElement('textarea');
    ta.value = text; ta.style.position = 'fixed'; ta.style.left = '-9999px';
    document.body.appendChild(ta); ta.select();
    try { document.execCommand('copy'); } catch(e) {}
    document.body.removeChild(ta);
}

/* ---------- Chat ---------- */
function openChat() {
    if (chatWs) { try { chatWs.close(); } catch(e){} chatWs = null; }
    hideThinkingIndicator();
    if (!current) return;
    const proto = location.protocol === 'https:' ? 'wss://' : 'ws://';
    const url = proto + location.host + '/ws/chat/' + encodeURIComponent(current.id);
    chatWs = new WebSocket(url);
    chatWs.onopen = () => { chatConnected = true; };
    chatWs.onclose = () => { chatConnected = false; };
    chatWs.onmessage = (ev) => {
        try {
            const m = JSON.parse(ev.data);
            addChat(m.type, m.text);
        } catch(e) { addChat('system', ev.data); }
    };
}

function showThinkingIndicator(text) {
    let div = document.getElementById('thinkingIndicator');
    if (!div) {
        div = document.createElement('div');
        div.id = 'thinkingIndicator';
        div.className = 'msg status';
        div.innerHTML = '<div class="bubble">' + text + '</div>';
        el('chatLog').appendChild(div);
    } else {
        div.querySelector('.bubble').textContent = text;
    }
    el('chatLog').scrollTop = el('chatLog').scrollHeight;
}

function hideThinkingIndicator() {
    const div = document.getElementById('thinkingIndicator');
    if (div) div.remove();
}

function addChat(type, text, skipTime) {
    if (type === 'assistant' || type === 'error') {
        hideThinkingIndicator();
    }
    if (!el('thinkingToggle').checked) {
        if (type === 'status' || type === 'command' || type === 'result') {
            showThinkingIndicator('执行中...');
            return;
        }
        if (type !== 'user' && type !== 'assistant' && type !== 'system' && type !== 'error') return;
    }
    const log = el('chatLog');
    const div = document.createElement('div');
    div.className = 'msg ' + type;
    const b = document.createElement('div');
    b.className = 'bubble';
    if (type === 'assistant') {
        try {
            b.innerHTML = marked.parse(text, {breaks: true, gfm: true});
        } catch(e) {
            b.textContent = text;
        }
        const copyBtn = document.createElement('button');
        copyBtn.className = 'copy-btn';
        copyBtn.innerHTML = icons.clipboard;
        copyBtn.title = '复制';
        copyBtn.onclick = (e) => {
            e.stopPropagation();
            copyText(text);
            copyBtn.innerHTML = icons.check;
            copyBtn.classList.add('copied');
            setTimeout(() => { copyBtn.innerHTML = icons.clipboard; copyBtn.classList.remove('copied'); }, 1500);
        };
        b.appendChild(copyBtn);
    } else {
        b.textContent = text;
    }
    div.appendChild(b);
    if (!skipTime) {
        const t = document.createElement('div');
        t.className = 'time';
        t.textContent = new Date().toLocaleTimeString('zh-CN', {hour:'2-digit',minute:'2-digit'});
        div.appendChild(t);
    }
    log.appendChild(div);
    log.scrollTop = log.scrollHeight;
}

function sendChat() {
    const ta = el('chatText');
    const text = ta.value.trim();
    if (!text || !chatWs || chatWs.readyState !== 1) return;
    addChat('user', text);
    const maxRounds = parseInt(el('maxRoundsInput').value) || 5;
    const model = el('modelSelect').value;
    const thinking = el('thinkingToggle').checked;
    chatWs.send(JSON.stringify({text: text, maxRounds: maxRounds, model: model, thinking: thinking}));
    ta.value = '';
}

/* ---------- Export Chat ---------- */
function exportChat() {
    const bubbles = el('chatLog').querySelectorAll('.msg');
    if (bubbles.length === 0) return;
    let md = '# Arthas 诊断对话\n\n';
    md += '**服务器**: ' + (current ? (current.name || current.ip) : '') + '\n';
    md += '**agentId**: ' + (current ? current.agentId : '') + '\n';
    md += '**时间**: ' + new Date().toLocaleString() + '\n\n---\n\n';
    bubbles.forEach(b => {
        const type = [...b.classList].find(c => c !== 'msg');
        const text = b.querySelector('.bubble')?.textContent || '';
        if (type === 'user') md += '### 🧑 你\n\n' + text + '\n\n';
        else if (type === 'assistant') md += '### 🤖 AI\n\n' + text + '\n\n';
        else md += '> ' + text.replace(/\n/g, '\n> ') + '\n\n';
    });
    const blob = new Blob([md], {type:'text/markdown'});
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = 'arthas-chat-' + new Date().toISOString().slice(0,10) + '.md';
    a.click();
}

/* ---------- Modal ---------- */
function openModal(server) {
    if (server) {
        el('modalTitle').textContent = '编辑服务器';
        el('f_name').value = server.name || '';
        el('f_ip').value = server.ip || '';
        el('f_agent').value = server.agentId || '';
        el('f_note').value = server.note || '';
        editingServerId = server.id;
    } else {
        el('modalTitle').textContent = '添加服务器';
        el('f_name').value = '';
        el('f_ip').value = '';
        el('f_agent').value = '';
        el('f_note').value = '';
        editingServerId = null;
    }
    el('modal').classList.remove('hidden');
}
function closeModal() { el('modal').classList.add('hidden'); }

async function saveModal() {
    const s = { name: el('f_name').value.trim(), ip: el('f_ip').value.trim(),
                agentId: el('f_agent').value.trim(), note: el('f_note').value.trim() };
    if (!s.name && !s.ip) { alert('请填写名称或 IP'); return; }
    if (editingServerId) {
        await api.update(editingServerId, s);
    } else {
        await api.add(s);
    }
    closeModal();
    await refresh();
}

/* ---------- Quick Tools & Flame ---------- */
async function execQuickTool(cmd) {
    if (!current || !current.online) {
        addChat('system', '⚠ agent 未连接，无法执行工具。');
        return;
    }
    addChat('system', '▶ ' + cmd);
    try {
        const body = {
            jsonrpc: '2.0', id: Date.now(), method: 'tools/call',
            params: {
                name: 'execute_arthas_command',
                arguments: { target: current.agentId, command: cmd }
            }
        };
        const r = await fetch('/mcp', {method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)});
        const j = await r.json();
        const text = j.result?.content?.text || j.result?.content?.[0]?.text || JSON.stringify(j);
        addChatDirect('result', text);
    } catch(e) {
        addChat('error', '工具执行失败: ' + e.message);
    }
}

function addChatDirect(type, text) {
    hideThinkingIndicator();
    const log = el('chatLog');
    const div = document.createElement('div');
    div.className = 'msg ' + type;
    const b = document.createElement('div');
    b.className = 'bubble';
    b.textContent = text;
    div.appendChild(b);
    const t = document.createElement('div');
    t.className = 'time';
    t.textContent = new Date().toLocaleTimeString('zh-CN', {hour:'2-digit',minute:'2-digit'});
    div.appendChild(t);
    log.appendChild(div);
    log.scrollTop = log.scrollHeight;
}

let flameRunning = false;
async function toggleFlameGraph() {
    if (!current || !current.online) {
        addChat('system', '⚠ agent 未连接。');
        return;
    }
    if (flameRunning) return;
    flameRunning = true;
    el('flameGraphBtn').innerHTML = icons.loader;
    el('flameGraphBtn').disabled = true;
    try {
        const exec = (cmd) => fetch('/mcp', {method:'POST',headers:{'Content-Type':'application/json'},
            body: JSON.stringify({jsonrpc:'2.0',id:Date.now(),method:'tools/call',
                params:{name:'execute_arthas_command',arguments:{target:current.agentId,command:cmd}}})
        }).then(r => r.json());
        const startResp = await exec('profiler start');
        const startText = startResp.result?.content?.text || '';
        if (startText.includes('Current OS do not support') || startText.includes('Only support Linux')) {
            addChat('error', '❌ AsyncProfiler 不支持 Windows，火焰图仅支持 Linux/Mac 环境。');
            return;
        }
        if (startText.includes('already started')) {
            await exec('profiler stop');
            await new Promise(r => setTimeout(r, 1000));
            await exec('profiler start');
        }
        addChat('system', '⏱ 采集 30 秒中...');
        await new Promise(r => setTimeout(r, 30000));

        const j = await exec('profiler stop --format html');
        let text = j.result?.content?.text || j.result?.content?.[0]?.text || '';

        let html = '';
        let filePath = '';
        const htmlIdx = text.indexOf('<!DOCTYPE html>');
        if (htmlIdx >= 0) {
            html = text.substring(htmlIdx);
        }
        const pathMatch = text.match(/文件已生成:\s*(.+)/);
        if (pathMatch) {
            filePath = pathMatch[1].trim();
        }
        if (!html && filePath) {
            try {
                const r = await fetch('/api/servers/' + current.id + '/download-file?path=' + encodeURIComponent(filePath));
                if (r.ok) {
                    const buf = await r.arrayBuffer();
                    const dec = new TextDecoder('utf-8');
                    const catText = dec.decode(buf);
                    const catIdx = catText.indexOf('<!DOCTYPE html>');
                    if (catIdx >= 0) html = catText.substring(catIdx);
                }
            } catch(e) {}
        }
        if (html && html.length > 100) {
            const downloadUrl = filePath
                ? '/api/servers/' + current.id + '/download-file?path=' + encodeURIComponent(filePath)
                : URL.createObjectURL(new Blob([html], {type:'text/html'}));
            const log = el('chatLog');
            const div = document.createElement('div');
            div.className = 'msg system';
            const b = document.createElement('div');
            b.className = 'bubble';
            b.innerHTML = '📄 火焰图已生成' + (filePath ? ' (' + filePath + ')' : '') + '<br><a href="'+downloadUrl+'" download="flamegraph.html" style="color:var(--primary-color);font-weight:600;">📥 点击下载火焰图</a>';
            div.appendChild(b);
            const t = document.createElement('div');
            t.className = 'time';
            t.textContent = new Date().toLocaleTimeString('zh-CN', {hour:'2-digit',minute:'2-digit'});
            div.appendChild(t);
            log.appendChild(div);
            log.scrollTop = log.scrollHeight;
        } else if (filePath) {
            addChat('system', '📄 火焰图已生成: ' + filePath);
        } else {
            addChat('system', text || '火焰图完成');
        }
    } catch(e) {
        addChat('error', '火焰图失败: ' + e.message);
    } finally {
        flameRunning = false;
        el('flameGraphBtn').innerHTML = icons.flame + ' 火焰图';
        el('flameGraphBtn').disabled = false;
    }
}

/* ---------- events ---------- */
el('addBtn').onclick = () => openModal(null);
el('editBtn').onclick = () => { if (current) openModal(current); };
el('modalCancel').onclick = closeModal;
el('modalCancel2').onclick = closeModal;
el('modalSave').onclick = saveModal;
el('themeToggle').onclick = toggleTheme;
el('exportChatBtn').onclick = exportChat;
el('serverSearch').addEventListener('input', () => renderList());
el('deleteBtn').onclick = async () => {
    if (!current) return;
    if (!confirm('确认删除服务器 ' + (current.name||current.ip) + ' ?')) return;
    await api.del(current.id);
    current = null;
    el('panel').classList.add('hidden');
    el('empty').classList.remove('hidden');
    await refresh();
};
el('chatSend').onclick = sendChat;
el('clearChatBtn').onclick = () => { if (chatWs && chatWs.readyState === 1) { chatWs.send('/clear'); el('chatLog').innerHTML = ''; } };
el('chatText').addEventListener('keydown', e => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); sendChat(); } });
el('copyCmd').onclick = () => { copyText(el('attachCmd').textContent); el('copyCmd').innerHTML = icons.check + ' 已复制'; setTimeout(()=>el('copyCmd').innerHTML=icons.clipboard + ' 复制命令',1500); };
el('pidInput').addEventListener('input', loadAttach);
el('flameGraphBtn').onclick = toggleFlameGraph;
document.querySelectorAll('.tab').forEach(t => t.onclick = () => switchTab(t.dataset.tab));
document.querySelectorAll('.tool-badge:not(.flame-btn)').forEach(b => {
    b.onclick = () => execQuickTool(b.dataset.tool);
});

refresh();
setInterval(refresh, 5000);
loadModels();

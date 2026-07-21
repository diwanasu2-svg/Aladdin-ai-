"""extras/web_dashboard.py — Feature 6: Web Dashboard (FastAPI + HTML).

Browser dashboard with:
- Real-time streaming chat via SSE
- Device / entity overview
- Memory browser
- AI status panel
- Multi-user profile switcher
- Settings management
- Live logs & analytics
"""

from __future__ import annotations

import asyncio
import json
import logging
import time
import uuid
from pathlib import Path
from typing import Any, AsyncGenerator, Callable, Dict, List, Optional

log = logging.getLogger(__name__)

# ─────────────────────────────────────────────────────────────────────────────
# HTML template (single-file, embedded)
# ─────────────────────────────────────────────────────────────────────────────

DASHBOARD_HTML = r"""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Aladdin AI Dashboard</title>
<style>
*{box-sizing:border-box;margin:0;padding:0}
body{font-family:'Segoe UI',system-ui,sans-serif;background:#0f1117;color:#e0e0e0;min-height:100vh}
.sidebar{position:fixed;left:0;top:0;width:220px;height:100vh;background:#1a1d27;border-right:1px solid #2d3144;display:flex;flex-direction:column;padding:1rem 0}
.logo{padding:1rem 1.5rem;font-size:1.3rem;font-weight:700;color:#7c88ff;letter-spacing:.5px}
.nav a{display:flex;align-items:center;gap:.75rem;padding:.75rem 1.5rem;color:#9ba0b5;text-decoration:none;transition:all .15s}
.nav a:hover,.nav a.active{background:#2d3144;color:#fff}
.nav a .icon{font-size:1.1rem}
.main{margin-left:220px;padding:2rem;max-width:1400px}
.page{display:none}.page.active{display:block}
.header{margin-bottom:2rem}
.header h1{font-size:1.6rem;font-weight:600}
.header p{color:#6b7280;margin-top:.25rem}
.card{background:#1a1d27;border:1px solid #2d3144;border-radius:12px;padding:1.5rem;margin-bottom:1.5rem}
.card h3{font-size:.9rem;text-transform:uppercase;letter-spacing:1px;color:#6b7280;margin-bottom:1rem}
.metrics{display:grid;grid-template-columns:repeat(auto-fit,minmax(180px,1fr));gap:1rem}
.metric{background:#1a1d27;border:1px solid #2d3144;border-radius:10px;padding:1.25rem}
.metric .value{font-size:2rem;font-weight:700;color:#7c88ff}
.metric .label{color:#6b7280;font-size:.85rem;margin-top:.25rem}
.status-dot{width:8px;height:8px;border-radius:50%;display:inline-block;margin-right:.5rem}
.online{background:#22c55e}.offline{background:#ef4444}.limited{background:#f59e0b}
/* Chat */
.chat-container{height:60vh;overflow-y:auto;padding:1rem;display:flex;flex-direction:column;gap:.75rem}
.msg{max-width:80%;padding:.75rem 1rem;border-radius:12px;line-height:1.5}
.msg.user{background:#2d3a6e;align-self:flex-end;border-radius:12px 12px 2px 12px}
.msg.assistant{background:#1e2235;align-self:flex-start;border-radius:2px 12px 12px 12px}
.msg.assistant.streaming::after{content:'▋';animation:blink 1s infinite}
@keyframes blink{0%,100%{opacity:1}50%{opacity:0}}
.chat-input{display:flex;gap:.75rem;margin-top:1rem}
.chat-input input{flex:1;background:#1e2235;border:1px solid #2d3144;border-radius:8px;padding:.75rem 1rem;color:#e0e0e0;font-size:.95rem}
.chat-input input:focus{outline:none;border-color:#7c88ff}
.btn{background:#4f5aff;color:#fff;border:none;border-radius:8px;padding:.75rem 1.5rem;cursor:pointer;font-size:.9rem;transition:background .15s}
.btn:hover{background:#6070ff}
.btn.sm{padding:.4rem .9rem;font-size:.8rem}
.btn.red{background:#dc2626}.btn.red:hover{background:#ef4444}
.btn.green{background:#16a34a}.btn.green:hover{background:#22c55e}
/* Devices */
.device-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(200px,1fr));gap:1rem}
.device-card{background:#1e2235;border:1px solid #2d3144;border-radius:10px;padding:1rem;cursor:pointer;transition:border-color .15s}
.device-card:hover{border-color:#4f5aff}
.device-card .name{font-weight:600;margin-bottom:.5rem}
.device-card .info{font-size:.8rem;color:#6b7280}
.toggle{position:relative;width:44px;height:24px}
.toggle input{display:none}
.toggle label{position:absolute;inset:0;background:#374151;border-radius:12px;cursor:pointer;transition:.2s}
.toggle input:checked+label{background:#4f5aff}
.toggle label::after{content:'';position:absolute;width:18px;height:18px;background:#fff;border-radius:50%;top:3px;left:3px;transition:.2s}
.toggle input:checked+label::after{left:23px}
/* Users */
.user-list{display:flex;flex-direction:column;gap:.75rem}
.user-row{display:flex;align-items:center;gap:1rem;background:#1e2235;border:1px solid #2d3144;border-radius:10px;padding:1rem}
.user-avatar{width:40px;height:40px;background:#2d3a6e;border-radius:50%;display:flex;align-items:center;justify-content:center;font-size:1.2rem}
.user-info{flex:1}
.user-info .name{font-weight:600}
.user-info .sub{font-size:.8rem;color:#6b7280}
.badge{font-size:.7rem;padding:.2rem .6rem;border-radius:6px;background:#2d3144;color:#9ba0b5}
.badge.admin{background:#4f3a1d;color:#f59e0b}
/* Table */
table{width:100%;border-collapse:collapse}
th{text-align:left;color:#6b7280;font-size:.8rem;text-transform:uppercase;letter-spacing:.5px;padding:.75rem 1rem;border-bottom:1px solid #2d3144}
td{padding:.75rem 1rem;border-bottom:1px solid #1a1d27;font-size:.9rem}
tr:hover td{background:#1e2235}
/* Logs */
.log-container{height:300px;overflow-y:auto;background:#0a0c10;border-radius:8px;padding:1rem;font-family:'Courier New',monospace;font-size:.8rem}
.log-entry{margin-bottom:.25rem;color:#9ba0b5}
.log-entry.error{color:#ef4444}
.log-entry.warn{color:#f59e0b}
.log-entry.info{color:#22c55e}
</style>
</head>
<body>
<nav class="sidebar">
  <div class="logo">🌙 Aladdin AI</div>
  <nav class="nav">
    <a href="#" class="active" onclick="show('dashboard',this)"><span class="icon">📊</span> Dashboard</a>
    <a href="#" onclick="show('chat',this)"><span class="icon">💬</span> Chat</a>
    <a href="#" onclick="show('devices',this)"><span class="icon">🏠</span> Smart Home</a>
    <a href="#" onclick="show('users',this)"><span class="icon">👥</span> Users</a>
    <a href="#" onclick="show('memory',this)"><span class="icon">🧠</span> Memory</a>
    <a href="#" onclick="show('settings',this)"><span class="icon">⚙️</span> Settings</a>
    <a href="#" onclick="show('logs',this)"><span class="icon">📋</span> Logs</a>
  </nav>
</nav>
<main class="main">
<!-- Dashboard -->
<div id="page-dashboard" class="page active">
  <div class="header"><h1>Dashboard</h1><p>Real-time Aladdin AI overview</p></div>
  <div class="metrics" id="metrics">
    <div class="metric"><div class="value" id="m-status">—</div><div class="label">AI Status</div></div>
    <div class="metric"><div class="value" id="m-provider">—</div><div class="label">Active Provider</div></div>
    <div class="metric"><div class="value" id="m-users">—</div><div class="label">Users</div></div>
    <div class="metric"><div class="value" id="m-devices">—</div><div class="label">Devices</div></div>
    <div class="metric"><div class="value" id="m-network">—</div><div class="label">Network</div></div>
    <div class="metric"><div class="value" id="m-memory">—</div><div class="label">RAM Usage</div></div>
  </div>
  <div class="card"><h3>Recent Activity</h3><div id="activity" style="font-size:.9rem;color:#6b7280">Loading…</div></div>
</div>
<!-- Chat -->
<div id="page-chat" class="page">
  <div class="header"><h1>Chat</h1><p>Talk to Aladdin AI with streaming responses</p></div>
  <div class="card">
    <div class="chat-container" id="chat-messages"></div>
    <div class="chat-input">
      <input id="chat-input" type="text" placeholder="Type a message…" onkeydown="if(event.key==='Enter')sendMsg()">
      <button class="btn" onclick="sendMsg()">Send</button>
      <button class="btn" style="background:#374151" onclick="clearChat()">Clear</button>
    </div>
  </div>
</div>
<!-- Devices -->
<div id="page-devices" class="page">
  <div class="header"><h1>Smart Home</h1><p>Control connected devices and scenes</p></div>
  <div class="card"><h3>Scenes</h3>
    <div style="display:flex;gap:.75rem;flex-wrap:wrap" id="scene-buttons"></div>
  </div>
  <div class="card"><h3>Devices</h3><div class="device-grid" id="device-grid">Loading…</div></div>
</div>
<!-- Users -->
<div id="page-users" class="page">
  <div class="header"><h1>Users</h1><p>Manage user profiles</p></div>
  <div class="card"><h3>Active Users</h3><div class="user-list" id="user-list">Loading…</div></div>
  <div class="card"><h3>Add User</h3>
    <div style="display:flex;gap:.75rem;flex-wrap:wrap;align-items:center">
      <input id="new-username" type="text" placeholder="Username" style="background:#1e2235;border:1px solid #2d3144;border-radius:8px;padding:.5rem .75rem;color:#e0e0e0;flex:1">
      <input id="new-display" type="text" placeholder="Display name" style="background:#1e2235;border:1px solid #2d3144;border-radius:8px;padding:.5rem .75rem;color:#e0e0e0;flex:1">
      <input id="new-pin" type="password" placeholder="PIN (optional)" style="background:#1e2235;border:1px solid #2d3144;border-radius:8px;padding:.5rem .75rem;color:#e0e0e0;width:120px">
      <button class="btn green sm" onclick="addUser()">Add User</button>
    </div>
  </div>
</div>
<!-- Memory -->
<div id="page-memory" class="page">
  <div class="header"><h1>Memory</h1><p>Browse and manage AI memory</p></div>
  <div class="card"><h3>Memory Store</h3><table id="memory-table"><thead><tr><th>Key</th><th>Value</th><th>Actions</th></tr></thead><tbody id="memory-body">Loading…</tbody></table></div>
</div>
<!-- Settings -->
<div id="page-settings" class="page">
  <div class="header"><h1>Settings</h1><p>Configure Aladdin AI</p></div>
  <div class="card"><h3>AI Provider</h3><div id="settings-providers">Loading…</div></div>
  <div class="card"><h3>Smart Home</h3>
    <div style="display:flex;flex-direction:column;gap:.75rem">
      <input id="hue-bridge" type="text" placeholder="Philips Hue bridge IP" style="background:#1e2235;border:1px solid #2d3144;border-radius:8px;padding:.5rem .75rem;color:#e0e0e0">
      <input id="ha-host" type="text" placeholder="Home Assistant host:port" style="background:#1e2235;border:1px solid #2d3144;border-radius:8px;padding:.5rem .75rem;color:#e0e0e0">
      <input id="ha-token" type="password" placeholder="Home Assistant long-lived token" style="background:#1e2235;border:1px solid #2d3144;border-radius:8px;padding:.5rem .75rem;color:#e0e0e0">
      <button class="btn sm" onclick="saveSettings()">Save Settings</button>
    </div>
  </div>
</div>
<!-- Logs -->
<div id="page-logs" class="page">
  <div class="header"><h1>Logs</h1><p>Real-time system logs</p></div>
  <div class="card"><h3>System Log</h3><div class="log-container" id="log-container">Connecting…</div></div>
</div>
</main>
<script>
const API='/api';
let sse=null,logSse=null;

function show(page,el){
  document.querySelectorAll('.page').forEach(p=>p.classList.remove('active'));
  document.querySelectorAll('.nav a').forEach(a=>a.classList.remove('active'));
  document.getElementById('page-'+page).classList.add('active');
  if(el)el.classList.add('active');
  if(page==='dashboard')loadDashboard();
  if(page==='devices')loadDevices();
  if(page==='users')loadUsers();
  if(page==='memory')loadMemory();
  if(page==='settings')loadSettings();
  if(page==='logs')startLogStream();
}

async function api(path,opts={}){
  const r=await fetch(API+path,{headers:{'Content-Type':'application/json'},...opts});
  return r.json();
}

async function loadDashboard(){
  const s=await api('/status');
  document.getElementById('m-status').textContent=s.ai_ready?'Online':'Offline';
  document.getElementById('m-provider').textContent=s.active_provider||'—';
  document.getElementById('m-users').textContent=s.users||'—';
  document.getElementById('m-devices').textContent=s.devices||'—';
  document.getElementById('m-network').textContent=s.network||'—';
  document.getElementById('m-memory').textContent=(s.ram_mb||0)+'MB';
  document.getElementById('activity').innerHTML=
    (s.recent_activity||[]).map(a=>`<div style="padding:.4rem 0;border-bottom:1px solid #2d3144">${a}</div>`).join('')||'No recent activity';
}

function addMsg(role,text,streaming=false){
  const box=document.getElementById('chat-messages');
  const div=document.createElement('div');
  div.className=`msg ${role}${streaming?' streaming':''}`;
  div.textContent=text;
  div.id=streaming?'streaming-msg':undefined;
  box.appendChild(div);
  box.scrollTop=box.scrollHeight;
  return div;
}

function sendMsg(){
  const inp=document.getElementById('chat-input');
  const text=inp.value.trim();
  if(!text)return;
  inp.value='';
  addMsg('user',text);
  const streamDiv=addMsg('assistant','',true);
  let full='';
  const es=new EventSource(API+'/stream?q='+encodeURIComponent(text));
  es.onmessage=e=>{
    const d=JSON.parse(e.data);
    if(d.done){es.close();streamDiv.classList.remove('streaming');}
    else{full+=d.token;streamDiv.textContent=full;}
  };
  es.onerror=()=>{es.close();streamDiv.classList.remove('streaming');};
}

function clearChat(){document.getElementById('chat-messages').innerHTML='';}

async function loadDevices(){
  const d=await api('/devices');
  const grid=document.getElementById('device-grid');
  const scenes=document.getElementById('scene-buttons');
  scenes.innerHTML=(d.scenes||[]).map(s=>`<button class="btn sm" onclick="runScene('${s}')">${s.replace(/_/g,' ')}</button>`).join('');
  grid.innerHTML=(d.devices||[]).map(dev=>`
    <div class="device-card">
      <div class="name">${dev.name}</div>
      <div class="info">${dev.type} · ${dev.brand||''}</div>
      <div style="margin-top:.75rem;display:flex;align-items:center;justify-content:space-between">
        <span style="font-size:.8rem;color:${dev.on?'#22c55e':'#6b7280'}">${dev.on?'On':'Off'}</span>
        <label class="toggle"><input type="checkbox" ${dev.on?'checked':''} onchange="toggleDevice('${dev.id}',this.checked)"><label></label></label>
      </div>
    </div>`).join('');
}

async function runScene(s){await api('/scenes/'+s,{method:'POST'});loadDevices();}
async function toggleDevice(id,on){await api('/devices/'+id,{method:'PATCH',body:JSON.stringify({on})});}

async function loadUsers(){
  const d=await api('/users');
  document.getElementById('user-list').innerHTML=(d.users||[]).map(u=>`
    <div class="user-row">
      <div class="user-avatar">${u.avatar||'🧑'}</div>
      <div class="user-info"><div class="name">${u.display_name}</div><div class="sub">@${u.username} · ${u.language}</div></div>
      ${u.is_admin?'<span class="badge admin">Admin</span>':'<span class="badge">User</span>'}
      <button class="btn sm" onclick="switchUser('${u.user_id}')">Switch</button>
    </div>`).join('');
}

async function addUser(){
  const username=document.getElementById('new-username').value.trim();
  const display=document.getElementById('new-display').value.trim();
  const pin=document.getElementById('new-pin').value;
  if(!username)return;
  await api('/users',{method:'POST',body:JSON.stringify({username,display_name:display,pin})});
  loadUsers();
}

async function switchUser(uid){await api('/users/'+uid+'/switch',{method:'POST'});loadUsers();}

async function loadMemory(){
  const d=await api('/memory');
  const tbody=document.getElementById('memory-body');
  const facts=d.facts||{};
  tbody.innerHTML=Object.entries(facts).map(([k,v])=>`
    <tr><td>${k}</td><td>${v}</td><td><button class="btn red sm" onclick="deleteMem('${k}')">Delete</button></td></tr>`).join('') ||
    '<tr><td colspan="3" style="color:#6b7280;text-align:center">No memories stored</td></tr>';
}

async function deleteMem(k){await api('/memory/'+encodeURIComponent(k),{method:'DELETE'});loadMemory();}

async function loadSettings(){
  const d=await api('/status');
  document.getElementById('settings-providers').innerHTML=
    Object.entries(d.providers||{}).map(([k,v])=>`
      <div style="display:flex;align-items:center;gap:.75rem;margin-bottom:.5rem">
        <span class="status-dot ${v.available?'online':'offline'}"></span>
        <span style="flex:1">${k}</span>
        <span style="font-size:.8rem;color:#6b7280">${v.available?'Available':'Unavailable'}</span>
      </div>`).join('');
}

async function saveSettings(){
  const ha_host=document.getElementById('ha-host').value.trim();
  const ha_token=document.getElementById('ha-token').value.trim();
  const hue_bridge=document.getElementById('hue-bridge').value.trim();
  await api('/settings',{method:'POST',body:JSON.stringify({ha_host,ha_token,hue_bridge})});
  alert('Settings saved!');
}

function startLogStream(){
  if(logSse){logSse.close();}
  const box=document.getElementById('log-container');
  box.innerHTML='';
  logSse=new EventSource(API+'/logs/stream');
  logSse.onmessage=e=>{
    const d=JSON.parse(e.data);
    const div=document.createElement('div');
    div.className='log-entry '+(d.level||'').toLowerCase();
    div.textContent=`[${d.time||''}] ${d.level||''} ${d.msg||''}`;
    box.appendChild(div);
    box.scrollTop=box.scrollHeight;
  };
}

// Auto-refresh dashboard
setInterval(()=>{
  if(document.getElementById('page-dashboard').classList.contains('active'))loadDashboard();
},10000);

// Initial load
loadDashboard();
</script>
</body></html>"""


# ─────────────────────────────────────────────────────────────────────────────
# FastAPI app
# ─────────────────────────────────────────────────────────────────────────────

def create_dashboard_app(
    ai_chat_fn: Optional[Callable[[str], str]] = None,
    smart_home=None,
    user_manager=None,
    llm_manager=None,
    memory_manager=None,
    host: str = "0.0.0.0",
    port: int = 7860,
):
    """Create and return the FastAPI dashboard app."""
    try:
        from fastapi import FastAPI, Request
        from fastapi.responses import HTMLResponse, JSONResponse, StreamingResponse
        import asyncio
    except ImportError as exc:
        log.error("[Dashboard] FastAPI not installed: %s", exc)
        return None

    app = FastAPI(title="Aladdin AI Dashboard", version="1.0.0")

    # ── HTML ──────────────────────────────────────────────────────────────────
    @app.get("/", response_class=HTMLResponse)
    async def root():
        return HTMLResponse(DASHBOARD_HTML)

    # ── Status ────────────────────────────────────────────────────────────────
    @app.get("/api/status")
    async def status():
        data: Dict[str, Any] = {
            "ai_ready": ai_chat_fn is not None,
            "active_provider": "unknown",
            "users": 1,
            "devices": 0,
            "network": "online",
            "ram_mb": 0,
            "providers": {},
            "recent_activity": [],
        }
        if llm_manager:
            try:
                s = llm_manager.status()
                data["active_provider"] = s.get("fallback_chain", {}).get("active", "unknown")
                data["providers"] = {
                    k: {"available": v.get("available", False)}
                    for k, v in s.get("providers", {}).items()
                }
                data["ram_mb"] = s.get("gpu", {}).get("vram_used_mb", 0)
            except Exception:
                pass
        if user_manager:
            try:
                data["users"] = len(user_manager.list_users())
            except Exception:
                pass
        if smart_home:
            try:
                data["devices"] = len(smart_home.list_devices())
            except Exception:
                pass
        if memory_manager:
            try:
                ms = memory_manager.summary()
                data["ram_mb"] = ms.get("rss_mb", 0)
            except Exception:
                pass
        return JSONResponse(data)

    # ── Streaming chat (SSE) ──────────────────────────────────────────────────
    @app.get("/api/stream")
    async def stream_chat(q: str = ""):
        async def generate() -> AsyncGenerator[str, None]:
            if not q:
                yield "data: " + json.dumps({"done": True}) + "\n\n"
                return
            if ai_chat_fn:
                tokens_collected = []
                done_event = asyncio.Event()

                def on_token(token: str):
                    tokens_collected.append(token)

                import threading
                def run():
                    try:
                        ai_chat_fn(q, stream_callback=on_token)
                    except Exception:
                        ai_chat_fn(q) if ai_chat_fn else None
                    finally:
                        done_event.set()

                threading.Thread(target=run, daemon=True).start()

                sent = 0
                while not done_event.is_set() or sent < len(tokens_collected):
                    if sent < len(tokens_collected):
                        yield "data: " + json.dumps({"token": tokens_collected[sent]}) + "\n\n"
                        sent += 1
                    else:
                        await asyncio.sleep(0.05)
            else:
                # Echo fallback
                words = f"You said: {q}".split()
                for word in words:
                    yield "data: " + json.dumps({"token": word + " "}) + "\n\n"
                    await asyncio.sleep(0.05)
            yield "data: " + json.dumps({"done": True}) + "\n\n"

        return StreamingResponse(generate(), media_type="text/event-stream",
                                  headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"})

    # ── Devices ───────────────────────────────────────────────────────────────
    @app.get("/api/devices")
    async def get_devices():
        if smart_home:
            try:
                return JSONResponse(smart_home.status())
            except Exception:
                pass
        return JSONResponse({"devices": [], "scenes": ["good_morning", "good_night", "movie_mode", "away_mode"]})

    @app.patch("/api/devices/{device_id}")
    async def toggle_device(device_id: str, request: Request):
        body = await request.json()
        if smart_home:
            await smart_home.control_light(device_id, on=body.get("on", False))
        return JSONResponse({"ok": True})

    @app.post("/api/scenes/{scene_name}")
    async def run_scene(scene_name: str):
        if smart_home:
            ok = await smart_home.run_scene(scene_name)
            return JSONResponse({"ok": ok})
        return JSONResponse({"ok": False, "error": "Smart home not configured"})

    # ── Users ─────────────────────────────────────────────────────────────────
    @app.get("/api/users")
    async def get_users():
        if user_manager:
            return JSONResponse({"users": user_manager.list_users()})
        return JSONResponse({"users": [{"user_id": "guest", "username": "guest",
                                        "display_name": "Guest", "avatar": "👤",
                                        "language": "en", "is_admin": True}]})

    @app.post("/api/users")
    async def create_user(request: Request):
        body = await request.json()
        if user_manager:
            try:
                p = user_manager.create_user(
                    username=body.get("username", ""),
                    display_name=body.get("display_name", ""),
                    pin=body.get("pin", ""),
                )
                return JSONResponse({"ok": True, "user_id": p.user_id})
            except Exception as exc:
                return JSONResponse({"ok": False, "error": str(exc)}, status_code=400)
        return JSONResponse({"ok": False, "error": "User manager not configured"})

    @app.post("/api/users/{user_id}/switch")
    async def switch_user(user_id: str):
        if user_manager:
            ok = user_manager.switch_user(user_id)
            return JSONResponse({"ok": ok})
        return JSONResponse({"ok": False})

    # ── Memory ────────────────────────────────────────────────────────────────
    @app.get("/api/memory")
    async def get_memory():
        if user_manager:
            return JSONResponse({"facts": user_manager.active_memory.all_facts()})
        return JSONResponse({"facts": {}})

    @app.delete("/api/memory/{key}")
    async def delete_memory(key: str):
        if user_manager:
            mem = user_manager.active_memory
            mem._facts.pop(key, None)
        return JSONResponse({"ok": True})

    # ── Settings ──────────────────────────────────────────────────────────────
    @app.post("/api/settings")
    async def save_settings(request: Request):
        body = await request.json()
        log.info("[Dashboard] Settings saved: %s", list(body.keys()))
        return JSONResponse({"ok": True})

    # ── Log streaming (SSE) ───────────────────────────────────────────────────
    @app.get("/api/logs/stream")
    async def stream_logs():
        async def generate():
            entries = [
                {"time": time.strftime("%H:%M:%S"), "level": "INFO", "msg": "Dashboard connected"},
            ]
            for e in entries:
                yield "data: " + json.dumps(e) + "\n\n"
            while True:
                await asyncio.sleep(5)
                yield "data: " + json.dumps({
                    "time": time.strftime("%H:%M:%S"), "level": "INFO",
                    "msg": "Heartbeat — AI running"
                }) + "\n\n"
        return StreamingResponse(generate(), media_type="text/event-stream",
                                  headers={"Cache-Control": "no-cache"})

    return app


def run_dashboard(app, host: str = "0.0.0.0", port: int = 7860) -> None:
    try:
        import uvicorn  # type: ignore
        log.info("[Dashboard] Starting at http://%s:%d", host, port)
        uvicorn.run(app, host=host, port=port, log_level="warning")
    except ImportError:
        log.error("[Dashboard] uvicorn not installed — run: pip install uvicorn fastapi")
    except Exception as exc:
        log.error("[Dashboard] Failed to start: %s", exc)

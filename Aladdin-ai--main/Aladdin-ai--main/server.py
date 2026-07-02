"""
Aladdin REST API Server
========================
Exposes Aladdin's core pipeline over HTTP (FastAPI).

Run standalone:
    uvicorn api.server:app --host 0.0.0.0 --port 8000 --reload

Or from main.py with --api flag (see main.py).

Endpoints:
    POST /chat            — text chat
    POST /tts             — synthesize text to WAV
    GET  /memory/facts    — list long-term facts
    POST /memory/remember — store a fact
    GET  /calendar        — upcoming events
    POST /calendar/event  — add event
    GET  /reminders       — pending reminders
    POST /reminder        — set reminder
    GET  /status          — health/status
    GET  /models          — available Ollama models
    WebSocket /ws/chat    — streaming chat
"""

from __future__ import annotations

import base64
import logging
import os
import sys
from datetime import datetime
from pathlib import Path
from typing import Any, Dict, List, Optional

# Make aladdin_core importable
ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

try:
    from fastapi import FastAPI, HTTPException, WebSocket, WebSocketDisconnect
    from fastapi.middleware.cors import CORSMiddleware
    from fastapi.responses import JSONResponse
    from pydantic import BaseModel
except ImportError:
    raise ImportError("FastAPI not installed. Run: pip install fastapi uvicorn")

from aladdin_core.config import AladdinConfig
from aladdin_core.logger import setup_logging
from aladdin_core.llm import OllamaClient
from aladdin_core.memory import ConversationMemory
from aladdin_core.search import InternetSearch, needs_search
from aladdin_core.calendar_manager import CalendarManager, ReminderManager
from aladdin_core.tools import register_all_tools

log = logging.getLogger("aladdin.api")

# ---------------------------------------------------------------------------
# App factory
# ---------------------------------------------------------------------------


def create_app(cfg: Optional[AladdinConfig] = None) -> FastAPI:
    if cfg is None:
        cfg_path = ROOT / "config.yaml"
        cfg = AladdinConfig.load(cfg_path)

    setup_logging(cfg.logging)

    app = FastAPI(
        title="Aladdin AI Assistant API",
        description="Local AI personal assistant — REST interface",
        version="2.0.0",
        docs_url="/docs",
        redoc_url="/redoc",
    )

    app.add_middleware(
        CORSMiddleware,
        allow_origins=["*"],
        allow_methods=["*"],
        allow_headers=["*"],
    )

    # ── Bootstrap core modules ──────────────────────────────────────────
    Path("data").mkdir(exist_ok=True)
    memory = ConversationMemory(cfg.memory)
    search = InternetSearch(cfg.search)
    calendar = CalendarManager(cfg.calendar) if cfg.calendar.enabled else None
    reminder = ReminderManager(cfg.reminder) if cfg.reminder.enabled else None

    context = memory.build_context_prompt()
    system_prompt = cfg.system_prompt.format(name="Aladdin")
    if context:
        system_prompt = f"{system_prompt}\n\n{context}"

    llm = OllamaClient(cfg.ollama, system_prompt=system_prompt)
    register_all_tools(llm, memory, search, calendar, reminder)

    if reminder:
        reminder.start()

    # Store in app state for access in routes
    app.state.cfg = cfg
    app.state.llm = llm
    app.state.memory = memory
    app.state.search = search
    app.state.calendar = calendar
    app.state.reminder = reminder

    # ── Pydantic models ─────────────────────────────────────────────────

    class ChatRequest(BaseModel):
        message: str
        history_turns: int = 12

    class ChatResponse(BaseModel):
        reply: str
        model: str
        search_used: bool = False

    class RememberRequest(BaseModel):
        key: str
        value: str
        category: str = "general"
        importance: int = 1

    class EventRequest(BaseModel):
        title: str
        start: str  # ISO format: 2024-06-18T14:00:00
        end: Optional[str] = None
        description: str = ""
        location: str = ""

    class ReminderRequest(BaseModel):
        message: str
        minutes: int

    class TTSRequest(BaseModel):
        text: str

    # ── Routes ──────────────────────────────────────────────────────────

    @app.get("/status")
    async def status() -> Dict[str, Any]:
        ollama_ok = llm.is_available()
        mem_stats = memory.stats()
        return {
            "status": "ok",
            "ollama_available": ollama_ok,
            "model": cfg.ollama.model,
            "memory": mem_stats,
            "calendar_enabled": calendar is not None,
            "reminders_enabled": reminder is not None,
            "search_enabled": cfg.search.enabled,
            "version": "2.0.0",
        }

    @app.post("/chat", response_model=ChatResponse)
    async def chat(req: ChatRequest) -> ChatResponse:
        history = memory.recent(req.history_turns)
        search_used = False

        augmented = req.message
        if needs_search(req.message) and cfg.search.enabled:
            result = search.answer(req.message)
            if result:
                augmented = f"{req.message}\n\n[Web search result: {result}]"
                search_used = True

        reply = llm.chat(augmented, history=history)
        memory.append(req.message, reply)
        memory.summarize_old()

        return ChatResponse(
            reply=reply,
            model=cfg.ollama.model,
            search_used=search_used,
        )

    @app.get("/memory/facts")
    async def get_facts() -> List[Dict]:
        return memory.all_facts()

    @app.post("/memory/remember")
    async def remember(req: RememberRequest) -> Dict:
        memory.remember(req.key, req.value, req.category, req.importance)
        return {"status": "ok", "key": req.key, "value": req.value}

    @app.delete("/memory/facts/{key}")
    async def forget(key: str) -> Dict:
        memory.forget(key)
        return {"status": "ok", "deleted": key}

    @app.get("/memory/history")
    async def history(n: int = 20) -> List[Dict]:
        turns = memory.recent(n)
        return [{"user": u, "assistant": a} for u, a in turns]

    @app.get("/calendar")
    async def get_calendar(days: int = 7) -> List[Dict]:
        if not calendar:
            raise HTTPException(status_code=503, detail="Calendar not enabled")
        return calendar.upcoming(days)

    @app.post("/calendar/event")
    async def add_event(req: EventRequest) -> Dict:
        if not calendar:
            raise HTTPException(status_code=503, detail="Calendar not enabled")
        start = datetime.fromisoformat(req.start)
        end = datetime.fromisoformat(req.end) if req.end else None
        eid = calendar.add_event(req.title, start, end, req.description, req.location)
        return {"status": "ok", "id": eid}

    @app.get("/reminders")
    async def get_reminders() -> List[Dict]:
        if not reminder:
            raise HTTPException(status_code=503, detail="Reminders not enabled")
        return reminder.pending()

    @app.post("/reminder")
    async def set_reminder(req: ReminderRequest) -> Dict:
        if not reminder:
            raise HTTPException(status_code=503, detail="Reminders not enabled")
        rid = reminder.add_in(req.message, req.minutes)
        return {
            "status": "ok",
            "id": rid,
            "message": req.message,
            "minutes": req.minutes,
        }

    @app.get("/models")
    async def list_models() -> Dict:
        models = llm.list_models()
        return {"models": models, "current": cfg.ollama.model}

    @app.post("/tts")
    async def synthesize_tts(req: TTSRequest) -> Dict:
        """Synthesize text to WAV, returns base64-encoded audio."""
        try:
            from aladdin_core.tts import PiperSynthesizer

            tts = PiperSynthesizer(cfg.piper)
            wav_path = tts.synthesize(req.text)
            with open(wav_path, "rb") as f:
                audio_b64 = base64.b64encode(f.read()).decode()
            try:
                os.unlink(wav_path)
            except OSError:
                pass
            return {"audio_base64": audio_b64, "format": "wav"}
        except Exception as exc:
            raise HTTPException(status_code=500, detail=str(exc))

    @app.websocket("/ws/chat")
    async def ws_chat(websocket: WebSocket) -> None:
        """Streaming chat over WebSocket."""
        await websocket.accept()
        try:
            while True:
                data = await websocket.receive_json()
                user_text = data.get("message", "")
                if not user_text:
                    continue
                history = memory.recent(12)
                tokens: list[str] = []

                def on_token(t: str) -> None:
                    tokens.append(t)
                    import asyncio

                    # Task 17: get_running_loop() — we are inside an async context here
                    try:
                        loop = asyncio.get_running_loop()
                        loop.call_soon_threadsafe(
                            lambda tok=t: websocket.send_text(tok)
                        )
                    except RuntimeError:
                        pass

                reply = llm.chat(user_text, history=history, stream_callback=on_token)
                memory.append(user_text, reply)
                await websocket.send_json({"done": True, "full_reply": reply})
        except WebSocketDisconnect:
            log.debug("WebSocket client disconnected")
        except Exception as exc:
            log.error("WebSocket error: %s", exc)

    return app


# ---------------------------------------------------------------------------
# Standalone entry
# ---------------------------------------------------------------------------

app = create_app()

if __name__ == "__main__":
    import uvicorn

    uvicorn.run("api.server:app", host="0.0.0.0", port=8000, reload=True)

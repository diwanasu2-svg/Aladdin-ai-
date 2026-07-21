"""Chat routes — single, streaming, sessions."""

from __future__ import annotations

import json
import logging
from typing import List

from fastapi import APIRouter, HTTPException
from fastapi.responses import StreamingResponse

from ..models import ChatRequest, ChatResponse, SessionInfo
from ..llm.session_manager import session_manager
from ..llm.context_manager import ContextManager

log = logging.getLogger(__name__)


def _sanitize_user_input(text: str) -> str:
    """Block common prompt injection patterns."""
    import re
    INJECTION_PATTERNS = [
        r"ignore previous instructions",
        r"ignore all instructions",
        r"disregard.{0,20}instructions",
        r"you are now",
        r"new system prompt",
        r"act as.{0,30}(AI|assistant|GPT|Claude|model)",
        r"pretend.{0,30}(you are|to be)",
        r"jailbreak",
        r"DAN mode",
    ]
    lower = text.lower()
    for pattern in INJECTION_PATTERNS:
        if re.search(pattern, lower, re.IGNORECASE):
            log.warning("Potential prompt injection detected: %s", pattern)
            raise HTTPException(400, "Input contains disallowed content")
    if len(text) > 10000:
        raise HTTPException(400, "Input too long (max 10000 chars)")
    return text.strip()

router = APIRouter(prefix="/chat", tags=["Chat"])
_ctx = ContextManager()


def _get_client(provider: str, state: dict):
    client = state.get(provider)
    if client is None:
        raise HTTPException(status_code=503, detail=f"Provider '{provider}' not configured")
    return client


def _resolve_provider(request: ChatRequest, state: dict) -> tuple:
    """Pick provider + model, falling back through openai→gemini→anthropic→ollama."""
    preferred = request.provider or state.get("default_provider", "ollama")
    order = [preferred, "openai", "gemini", "anthropic", "ollama"]
    seen = set()
    for p in order:
        if p in seen:
            continue
        seen.add(p)
        if state.get(p) is not None:
            model = request.model or state.get("default_model", "")
            return p, model, state[p]
    raise HTTPException(status_code=503, detail="No LLM provider available")


@router.post("", response_model=ChatResponse)
async def chat(request: ChatRequest):
    from ..main import app_state

    session = session_manager.get_or_create(request.session_id)
    provider, model, client = _resolve_provider(request, app_state)

    # Inject system prompt + short-term memory summary
    system = request.system_prompt or app_state.get("default_system_prompt", "You are Aladdin, a helpful AI assistant.")
    st_mem = app_state.get("short_term")
    if st_mem:
        system = st_mem.inject_into_prompt(session.session_id, system)
        # Auto-save user message
        st_mem.save(session.session_id, "user", request.message)

    # Build messages
    history = session.messages.copy()
    request.message = _sanitize_user_input(request.message)
    history.append({"role": "user", "content": request.message})
    messages = _ctx.trim(history, model=model, system_prompt=system)

    try:
        result = await client.chat(
            messages=messages,
            model=model or None,
            temperature=request.temperature or 0.7,
            max_tokens=request.max_tokens or 512,
            tools=request.tools,
        )
    except Exception as exc:
        log.error("LLM error: %s", exc)
        raise HTTPException(status_code=502, detail=str(exc))

    # Save to session + short-term memory
    session_manager.add_message(session.session_id, "user", request.message)
    session_manager.add_message(session.session_id, "assistant", result.content)
    if st_mem:
        st_mem.save(session.session_id, "assistant", result.content)

    # Save to semantic memory
    sem = app_state.get("semantic")
    if sem and request.message:
        sem.add(request.message, metadata={"session_id": session.session_id, "role": "user"})

    return ChatResponse(
        response=result.content,
        session_id=session.session_id,
        model=result.model,
        provider=result.provider,
        tokens_used=result.tokens_used,
        finish_reason=result.finish_reason,
    )


@router.post("/stream")
async def stream_chat(request: ChatRequest):
    from ..main import app_state

    session = session_manager.get_or_create(request.session_id)
    provider, model, client = _resolve_provider(request, app_state)

    system = request.system_prompt or app_state.get("default_system_prompt", "You are Aladdin, a helpful AI assistant.")
    st_mem = app_state.get("short_term")
    if st_mem:
        system = st_mem.inject_into_prompt(session.session_id, system)
        st_mem.save(session.session_id, "user", request.message)

    history = session.messages.copy()
    request.message = _sanitize_user_input(request.message)
    history.append({"role": "user", "content": request.message})
    messages = _ctx.trim(history, model=model, system_prompt=system)

    session_manager.add_message(session.session_id, "user", request.message)

    async def event_stream():
        collected = []
        try:
            async for token in client.stream_chat(
                messages=messages,
                model=model or None,
                temperature=request.temperature or 0.7,
                max_tokens=request.max_tokens or 512,
            ):
                collected.append(token)
                data = json.dumps({"text": token, "session_id": session.session_id, "done": False})
                yield f"data: {data}\n\n"
        except Exception as exc:
            log.error("Stream error: %s", exc)
            yield f"data: {json.dumps({'error': str(exc)})}\n\n"
            return

        full = "".join(collected)
        session_manager.add_message(session.session_id, "assistant", full)
        if st_mem:
            st_mem.save(session.session_id, "assistant", full)
        yield f"data: {json.dumps({'text': '', 'session_id': session.session_id, 'done': True})}\n\n"

    return StreamingResponse(event_stream(), media_type="text/event-stream")


@router.get("/sessions", response_model=List[SessionInfo])
async def list_sessions():
    return session_manager.list_sessions()


@router.delete("/session/{session_id}")
async def end_session(session_id: str):
    deleted = session_manager.delete_session(session_id)
    if not deleted:
        raise HTTPException(status_code=404, detail="Session not found")
    return {"message": f"Session {session_id} ended"}

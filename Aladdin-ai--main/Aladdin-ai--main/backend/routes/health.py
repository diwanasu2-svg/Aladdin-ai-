"""Health check route."""

from __future__ import annotations

from fastapi import APIRouter
from ..models import HealthStatus

router = APIRouter(tags=["Health"])


@router.get("/health", response_model=HealthStatus)
async def health_check():
    from ..main import app_state
    providers = {
        "openai": app_state.get("openai") is not None,
        "gemini": app_state.get("gemini") is not None,
        "anthropic": app_state.get("anthropic") is not None,
        "ollama": app_state.get("ollama") is not None,
    }
    memory = {
        "short_term": app_state.get("short_term") is not None,
        "long_term": app_state.get("long_term") is not None,
        "semantic": app_state.get("semantic") is not None,
    }
    voice = {
        "stt": app_state.get("stt") is not None and app_state["stt"].available,
        "vad": app_state.get("vad") is not None,
    }
    return HealthStatus(status="ok", providers=providers, memory=memory, voice=voice)

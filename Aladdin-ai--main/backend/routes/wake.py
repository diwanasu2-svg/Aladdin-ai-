"""Wake word detection routes."""
from __future__ import annotations
import logging
from typing import List, Optional
from fastapi import APIRouter, HTTPException
from pydantic import BaseModel

log = logging.getLogger(__name__)
router = APIRouter(prefix="/wake", tags=["Wake Word"])


class WakeConfigRequest(BaseModel):
    engine: Optional[str] = None
    wake_words: Optional[List[str]] = None
    sensitivity: float = 0.5
    porcupine_access_key: Optional[str] = None
    openwakeword_models: Optional[List[str]] = None


@router.get("/status")
async def wake_status():
    from ..main import app_state
    wake = app_state.get("wake")
    if not wake:
        return {"active": False, "engine": None, "error": "Wake manager not initialized"}
    return wake.status


@router.post("/start")
async def wake_start(engine: Optional[str] = None):
    from ..main import app_state
    wake = app_state.get("wake")
    if not wake:
        raise HTTPException(503, "Wake word manager not initialized")
    active_engine = wake.start(engine)
    return {"message": f"Wake detection started", "engine": active_engine}


@router.post("/stop")
async def wake_stop():
    from ..main import app_state
    wake = app_state.get("wake")
    if not wake:
        raise HTTPException(503, "Wake word manager not initialized")
    wake.stop()
    return {"message": "Wake detection stopped"}


@router.post("/configure")
async def wake_configure(cfg: WakeConfigRequest):
    from ..main import app_state
    wake = app_state.get("wake")
    if not wake:
        raise HTTPException(503, "Wake word manager not initialized")
    results = {}
    if cfg.engine == "openwakeword" or cfg.openwakeword_models:
        ok = wake.setup_openwakeword(
            model_paths=cfg.openwakeword_models,
            threshold=cfg.sensitivity,
        )
        results["openwakeword"] = "ready" if ok else "failed"
    if cfg.engine == "porcupine" and cfg.porcupine_access_key:
        ok = wake.setup_porcupine(
            access_key=cfg.porcupine_access_key,
            keywords=cfg.wake_words,
        )
        results["porcupine"] = "ready" if ok else "failed"
    if cfg.wake_words:
        wake.setup_browser(wake_words=cfg.wake_words, sensitivity=cfg.sensitivity)
        results["browser"] = "ready"
    return {"configured": results, "status": wake.status,
            "browser_config": wake.get_browser_config()}

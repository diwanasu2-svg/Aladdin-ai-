"""TTS routes — generate, stream, voices, play."""
from __future__ import annotations
import logging
from typing import Optional
from fastapi import APIRouter, HTTPException
from fastapi.responses import Response, StreamingResponse
from pydantic import BaseModel

log = logging.getLogger(__name__)
router = APIRouter(prefix="/tts", tags=["TTS"])


class TTSRequest(BaseModel):
    text: str
    provider: Optional[str] = None
    voice_id: Optional[str] = None
    format: str = "mp3"


@router.post("")
async def generate_tts(req: TTSRequest):
    from ..main import app_state
    tts = app_state.get("tts")
    if not tts or not tts.available_providers:
        raise HTTPException(503, "No TTS provider available. Configure OPENAI_API_KEY, ELEVENLABS_API_KEY, or Piper.")
    try:
        audio, content_type = await tts.synthesize(
            req.text, provider=req.provider, voice_id=req.voice_id, format=req.format
        )
    except Exception as exc:
        log.error("TTS error: %s", exc)
        raise HTTPException(502, str(exc))
    return Response(content=audio, media_type=content_type,
                    headers={"Content-Disposition": f"inline; filename=speech.{req.format}",
                             "X-TTS-Provider": req.provider or "auto"})


@router.post("/stream")
async def stream_tts(req: TTSRequest):
    from ..main import app_state
    tts = app_state.get("tts")
    if not tts or not tts.available_providers:
        raise HTTPException(503, "No TTS provider available.")
    content_type = "audio/mpeg" if req.format == "mp3" else f"audio/{req.format}"

    async def audio_gen():
        try:
            async for chunk in tts.stream_synthesize(
                req.text, provider=req.provider, voice_id=req.voice_id, format=req.format
            ):
                yield chunk
        except Exception as exc:
            log.error("TTS stream error: %s", exc)

    return StreamingResponse(audio_gen(), media_type=content_type,
                             headers={"X-TTS-Provider": req.provider or "auto"})


@router.get("/voices")
async def list_voices():
    from ..main import app_state
    tts = app_state.get("tts")
    if not tts:
        return {"voices": [], "providers": []}
    voices = await tts.list_all_voices()
    return {"voices": voices, "providers": tts.available_providers}


@router.post("/play")
async def play_tts(req: TTSRequest):
    """Same as /tts but with explicit browser-play headers."""
    from ..main import app_state
    tts = app_state.get("tts")
    if not tts or not tts.available_providers:
        raise HTTPException(503, "No TTS provider available.")
    try:
        audio, content_type = await tts.synthesize(
            req.text, provider=req.provider, voice_id=req.voice_id, format=req.format
        )
    except Exception as exc:
        raise HTTPException(502, str(exc))
    return Response(content=audio, media_type=content_type,
                    headers={"Cache-Control": "no-cache",
                             "Content-Disposition": f"inline; filename=speech.{req.format}",
                             "Access-Control-Allow-Origin": "*"})

"""Voice / STT routes."""

from __future__ import annotations

import json
import logging

from fastapi import APIRouter, File, Form, HTTPException, UploadFile
from fastapi.responses import StreamingResponse

from ..models import TranscriptionResponse

log = logging.getLogger(__name__)
router = APIRouter(prefix="/transcribe", tags=["Voice"])


@router.post("", response_model=TranscriptionResponse)
async def transcribe(
    file: UploadFile = File(...),
    language: str = Form(default=None),
):
    from ..main import app_state
    stt = app_state.get("stt")
    if not stt or not stt.available:
        raise HTTPException(503, "STT not available — install whisper or faster-whisper")

    audio_bytes = await file.read()
    try:
        text, detected_lang, duration = await stt.transcribe(audio_bytes, language or None)
    except Exception as exc:
        log.error("Transcription error: %s", exc)
        raise HTTPException(500, detail=str(exc))

    return TranscriptionResponse(text=text, language=detected_lang, duration=duration)


@router.post("/stream")
async def stream_transcribe(
    file: UploadFile = File(...),
    language: str = Form(default=None),
    chunk_size: int = Form(default=48000),
):
    """Streaming transcription via SSE — splits uploaded audio into chunks."""
    from ..main import app_state
    stt = app_state.get("stt")
    if not stt or not stt.available:
        raise HTTPException(503, "STT not available")

    audio_bytes = await file.read()

    async def event_gen():
        # Split into chunks and transcribe progressively
        offset = 0
        while offset < len(audio_bytes):
            chunk = audio_bytes[offset:offset + chunk_size]
            offset += chunk_size
            try:
                text, lang, _ = await stt.transcribe(chunk, language or None)
                if text:
                    data = json.dumps({"text": text, "language": lang, "is_final": False})
                    yield f"data: {data}\n\n"
            except Exception as exc:
                log.warning("Chunk transcription error: %s", exc)
        yield f"data: {json.dumps({'text': '', 'is_final': True})}\n\n"

    return StreamingResponse(event_gen(), media_type="text/event-stream")


@router.post("/live")
async def live_transcribe(
    file: UploadFile = File(...),
    language: str = Form(default=None),
):
    """Live transcription with VAD-based silence detection."""
    from ..main import app_state
    stt = app_state.get("stt")
    vad = app_state.get("vad")
    if not stt or not stt.available:
        raise HTTPException(503, "STT not available")

    audio_bytes = await file.read()

    async def event_gen():
        # Apply VAD to find speech segments
        speech_buffer = b""
        frame_size = 960  # 30ms at 16kHz 16-bit mono
        has_speech = False

        for i in range(0, len(audio_bytes), frame_size):
            frame = audio_bytes[i:i + frame_size]
            if len(frame) < frame_size:
                frame = frame.ljust(frame_size, b'\x00')

            is_speech = vad.is_speech(frame) if vad else True
            if is_speech:
                speech_buffer += frame
                has_speech = True
            elif has_speech and len(speech_buffer) > 0:
                # End of speech segment — transcribe
                try:
                    text, lang, _ = await stt.transcribe(speech_buffer, language or None)
                    if text:
                        data = json.dumps({"text": text, "language": lang, "is_final": True})
                        yield f"data: {data}\n\n"
                except Exception as exc:
                    log.warning("Live transcription error: %s", exc)
                speech_buffer = b""
                has_speech = False

        # Transcribe any remaining buffer
        if speech_buffer:
            try:
                text, lang, _ = await stt.transcribe(speech_buffer, language or None)
                if text:
                    yield f"data: {json.dumps({'text': text, 'language': lang, 'is_final': True})}\n\n"
            except Exception as exc:
                log.warning("Final buffer transcription error: %s", exc)

        yield f"data: {json.dumps({'text': '', 'is_final': True, 'done': True})}\n\n"

    return StreamingResponse(event_gen(), media_type="text/event-stream")

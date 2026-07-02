"""WebSocket /ws/listen — continuous audio streaming with wake word + STT."""
from __future__ import annotations
import asyncio
import json
import logging
import time
from typing import Optional

from fastapi import APIRouter, WebSocket, WebSocketDisconnect

log = logging.getLogger(__name__)
router = APIRouter(tags=["WebSocket"])


@router.websocket("/ws/listen")
async def ws_listen(websocket: WebSocket):
    """
    Bi-directional WebSocket for continuous listening.

    Client → Server:
      Binary frames: raw PCM audio (16kHz, 16-bit mono)
      Text frames (JSON): {"type": "wake_detected", "word": "aladdin"}
                          {"type": "stop"}
                          {"type": "config", "language": "en"}

    Server → Client:
      {"type": "ready"}
      {"type": "transcript", "text": "...", "is_final": true/false}
      {"type": "wake", "word": "aladdin"}
      {"type": "error", "message": "..."}
      {"type": "status", "state": "listening"|"processing"|"idle"}
    """
    await websocket.accept()
    log.info("WebSocket /ws/listen connected: %s", websocket.client)

    from ..main import app_state
    stt = app_state.get("stt")
    wake = app_state.get("wake")
    vad = app_state.get("vad")

    await websocket.send_text(json.dumps({"type": "ready", "stt": stt is not None and stt.available,
                                           "wake": wake is not None}))

    audio_buffer = b""
    language: Optional[str] = None
    state = "listening"
    last_speech_time = time.time()
    SILENCE_TIMEOUT = 2.0  # seconds of silence to trigger transcription
    MIN_AUDIO_BYTES = 16000 * 2  # ~0.5s at 16kHz 16-bit

    try:
        while True:
            try:
                msg = await asyncio.wait_for(websocket.receive(), timeout=30.0)
            except asyncio.TimeoutError:
                await websocket.send_text(json.dumps({"type": "ping"}))
                continue

            if msg["type"] == "websocket.disconnect":
                break

            # Text control messages
            if msg.get("text"):
                try:
                    data = json.loads(msg["text"])
                    msg_type = data.get("type", "")
                    if msg_type == "stop":
                        break
                    elif msg_type == "config":
                        language = data.get("language")
                        await websocket.send_text(json.dumps({"type": "ack", "config": {"language": language}}))
                    elif msg_type == "wake_detected":
                        word = data.get("word", "wake_word")
                        log.info("Client-side wake word detected: %s", word)
                        await websocket.send_text(json.dumps({"type": "wake", "word": word}))
                        await websocket.send_text(json.dumps({"type": "status", "state": "listening"}))
                except json.JSONDecodeError:
                    pass
                continue

            # Binary audio frames
            if msg.get("bytes"):
                chunk = msg["bytes"]
                audio_buffer += chunk

                # Server-side VAD check
                has_speech = True
                if vad:
                    frame_size = 960  # 30ms at 16kHz 16-bit
                    for i in range(0, len(chunk), frame_size):
                        frame = chunk[i:i + frame_size]
                        if len(frame) == frame_size:
                            has_speech = vad.is_speech(frame)
                            break

                # Server-side wake word detection
                if wake and wake.status.get("active") and wake._oww:
                    word = wake._oww.detect(chunk)
                    if word:
                        await websocket.send_text(json.dumps({"type": "wake", "word": word}))

                if has_speech:
                    last_speech_time = time.time()

                # Transcribe when enough silence or buffer size exceeded
                buffer_full = len(audio_buffer) > 16000 * 2 * 15  # 15 seconds max
                silence_gap = time.time() - last_speech_time > SILENCE_TIMEOUT
                if (silence_gap or buffer_full) and len(audio_buffer) >= MIN_AUDIO_BYTES and stt and stt.available:
                    await websocket.send_text(json.dumps({"type": "status", "state": "processing"}))
                    try:
                        text, lang, _ = await stt.transcribe(audio_buffer, language)
                        if text:
                            await websocket.send_text(json.dumps({
                                "type": "transcript",
                                "text": text,
                                "language": lang,
                                "is_final": True,
                            }))
                    except Exception as exc:
                        log.warning("WS transcription error: %s", exc)
                        await websocket.send_text(json.dumps({"type": "error", "message": str(exc)}))
                    audio_buffer = b""
                    await websocket.send_text(json.dumps({"type": "status", "state": "listening"}))

    except WebSocketDisconnect:
        log.info("WebSocket /ws/listen disconnected")
    except Exception as exc:
        log.error("WebSocket error: %s", exc)
        try:
            await websocket.send_text(json.dumps({"type": "error", "message": str(exc)}))
        except Exception:
            pass
    finally:
        log.info("WebSocket /ws/listen closed")

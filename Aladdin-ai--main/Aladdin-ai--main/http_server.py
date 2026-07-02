#!/usr/bin/env python3
"""
http_server.py — Piper TTS HTTP server (FastAPI edition).

Converted from Flask to FastAPI for consistency with the main backend.
Serves POST/GET requests to synthesize text → WAV audio.

Usage:
    python -m piper.http_server -m en_US-lessac-medium.onnx --port 5000
"""

import argparse
import io
import logging
import wave
from pathlib import Path
from typing import Any, Dict

import uvicorn
from fastapi import FastAPI, Query, Request, Response
from fastapi.responses import Response as FastAPIResponse

from . import PiperVoice
from .download import ensure_voice_exists, find_voice, get_voices

_LOGGER = logging.getLogger()


def create_app(voice: PiperVoice, synthesize_args: Dict[str, Any]) -> FastAPI:
    """Create and return the FastAPI application."""
    app = FastAPI(
        title="Piper TTS Server",
        description="Text-to-speech synthesis server powered by Piper.",
        version="1.0.0",
    )

    @app.get("/", response_class=FastAPIResponse)
    async def synthesize_get(text: str = Query(default="", description="Text to synthesize")) -> FastAPIResponse:
        """Synthesize text to WAV via GET request."""
        text = text.strip()
        if not text:
            return FastAPIResponse(content=b"No text provided", status_code=400)
        _LOGGER.debug("Synthesizing text (GET): %s", text)
        return _synthesize(text, voice, synthesize_args)

    @app.post("/", response_class=FastAPIResponse)
    async def synthesize_post(request: Request) -> FastAPIResponse:
        """Synthesize text to WAV via POST request (body = plain text)."""
        body = await request.body()
        text = body.decode("utf-8", errors="replace").strip()
        if not text:
            return FastAPIResponse(content=b"No text provided", status_code=400)
        _LOGGER.debug("Synthesizing text (POST): %s", text)
        return _synthesize(text, voice, synthesize_args)

    @app.get("/health")
    async def health() -> dict:
        return {"status": "ok", "service": "piper-tts"}

    return app


def _synthesize(text: str, voice: PiperVoice, synthesize_args: Dict[str, Any]) -> FastAPIResponse:
    """Run Piper synthesis and return WAV bytes."""
    with io.BytesIO() as wav_io:
        with wave.open(wav_io, "wb") as wav_file:
            voice.synthesize(text, wav_file, **synthesize_args)
        wav_bytes = wav_io.getvalue()
    return FastAPIResponse(content=wav_bytes, media_type="audio/wav")


def main() -> None:
    parser = argparse.ArgumentParser(description="Piper TTS HTTP server")
    parser.add_argument("--host", default="0.0.0.0", help="HTTP server host")
    parser.add_argument("--port", type=int, default=5000, help="HTTP server port")
    #
    parser.add_argument("-m", "--model", required=True, help="Path to Onnx model file")
    parser.add_argument("-c", "--config", help="Path to model config file")
    #
    parser.add_argument("-s", "--speaker", type=int, help="Id of speaker (default: 0)")
    parser.add_argument("--length-scale", "--length_scale", type=float, help="Phoneme length")
    parser.add_argument("--noise-scale", "--noise_scale", type=float, help="Generator noise")
    parser.add_argument("--noise-w", "--noise_w", type=float, help="Phoneme width noise")
    #
    parser.add_argument("--cuda", action="store_true", help="Use GPU")
    #
    parser.add_argument(
        "--sentence-silence", "--sentence_silence",
        type=float, default=0.0,
        help="Seconds of silence after each sentence",
    )
    #
    parser.add_argument(
        "--data-dir", "--data_dir",
        action="append", default=[str(Path.cwd())],
        help="Data directory to check for downloaded models (default: current directory)",
    )
    parser.add_argument(
        "--download-dir", "--download_dir",
        help="Directory to download voices into (default: first data dir)",
    )
    #
    parser.add_argument("--update-voices", action="store_true",
                        help="Download latest voices.json during startup")
    parser.add_argument("--debug", action="store_true", help="Print DEBUG messages to console")
    args = parser.parse_args()
    logging.basicConfig(level=logging.DEBUG if args.debug else logging.INFO)
    _LOGGER.debug(args)

    if not args.download_dir:
        args.download_dir = args.data_dir[0]

    # Download voice if file doesn't exist
    model_path = Path(args.model)
    if not model_path.exists():
        voices_info = get_voices(args.download_dir, update_voices=args.update_voices)
        aliases_info: Dict[str, Any] = {}
        for voice_info in voices_info.values():
            for voice_alias in voice_info.get("aliases", []):
                aliases_info[voice_alias] = {"_is_alias": True, **voice_info}
        voices_info.update(aliases_info)
        ensure_voice_exists(args.model, args.data_dir, args.download_dir, voices_info)
        args.model, args.config = find_voice(args.model, args.data_dir)

    # Load voice
    voice = PiperVoice.load(args.model, config_path=args.config, use_cuda=args.cuda)
    synthesize_args = {
        "speaker_id": args.speaker,
        "length_scale": args.length_scale,
        "noise_scale": args.noise_scale,
        "noise_w": args.noise_w,
        "sentence_silence": args.sentence_silence,
    }

    app = create_app(voice, synthesize_args)
    uvicorn.run(app, host=args.host, port=args.port)


if __name__ == "__main__":
    main()

"""Speech-to-Text using Whisper / Faster-Whisper."""

from __future__ import annotations

import io
import logging
import tempfile
import time
from pathlib import Path
from typing import AsyncIterator, Optional, Tuple

log = logging.getLogger(__name__)

_WHISPER_AVAILABLE = False
_FASTER_AVAILABLE = False

try:
    import whisper as _whisper_lib
    _WHISPER_AVAILABLE = True
    log.info("STT: openai-whisper available")
except ImportError:
    pass

try:
    from faster_whisper import WhisperModel as _FasterWhisper
    _FASTER_AVAILABLE = True
    log.info("STT: faster-whisper available")
except ImportError:
    pass


class SpeechToText:
    """Transcribes audio using Whisper (faster-whisper preferred, openai-whisper fallback)."""

    def __init__(self, model_name: str = "base", device: str = "cpu") -> None:
        self._model_name = model_name
        self._device = device
        self._model = None
        self._backend: Optional[str] = None
        self._load_model()

    def _load_model(self) -> None:
        if _FASTER_AVAILABLE:
            try:
                compute_type = "int8" if self._device == "cpu" else "float16"
                self._model = _FasterWhisper(self._model_name, device=self._device, compute_type=compute_type)
                self._backend = "faster-whisper"
                log.info("STT: loaded faster-whisper model '%s'", self._model_name)
                return
            except Exception as exc:
                log.warning("faster-whisper load failed: %s", exc)

        if _WHISPER_AVAILABLE:
            try:
                self._model = _whisper_lib.load_model(self._model_name)
                self._backend = "whisper"
                log.info("STT: loaded openai-whisper model '%s'", self._model_name)
                return
            except Exception as exc:
                log.warning("openai-whisper load failed: %s", exc)

        log.error("No Whisper backend available — transcription will fail")

    @property
    def available(self) -> bool:
        return self._model is not None

    async def transcribe(self, audio_bytes: bytes, language: Optional[str] = None) -> Tuple[str, Optional[str], Optional[float]]:
        """
        Transcribe audio bytes.
        Returns (text, detected_language, duration_seconds).
        """
        if self._model is None:
            raise RuntimeError("No STT model loaded")

        start = time.time()

        # Write audio to temp file
        with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as tmp:
            tmp.write(audio_bytes)
            tmp_path = tmp.name

        try:
            text, lang = await self._do_transcribe(tmp_path, language)
        finally:
            Path(tmp_path).unlink(missing_ok=True)

        duration = time.time() - start
        return text, lang, duration

    async def _do_transcribe(self, path: str, language: Optional[str]) -> Tuple[str, Optional[str]]:
        import asyncio
        loop = asyncio.get_running_loop()

        if self._backend == "faster-whisper":
            def _run():
                segments, info = self._model.transcribe(path, language=language, beam_size=5)
                text = " ".join(seg.text for seg in segments).strip()
                return text, info.language
            return await loop.run_in_executor(None, _run)
        else:
            def _run():
                result = self._model.transcribe(path, language=language)
                return result["text"].strip(), result.get("language")
            return await loop.run_in_executor(None, _run)

    async def transcribe_stream(self, audio_chunks: AsyncIterator[bytes]) -> AsyncIterator[str]:
        """Live streaming transcription — buffers audio and transcribes chunks."""
        buffer = b""
        CHUNK_SIZE = 16000 * 2 * 3  # ~3 seconds at 16kHz mono 16-bit
        async for chunk in audio_chunks:
            buffer += chunk
            if len(buffer) >= CHUNK_SIZE:
                text, _, _ = await self.transcribe(buffer)
                if text:
                    yield text
                buffer = b""
        # Transcribe remaining
        if buffer:
            text, _, _ = await self.transcribe(buffer)
            if text:
                yield text

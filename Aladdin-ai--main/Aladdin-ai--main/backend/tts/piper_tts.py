"""Piper TTS integration (subprocess-based)."""
from __future__ import annotations
import asyncio
import io
import json
import logging
import subprocess
import tempfile
from pathlib import Path
from typing import AsyncIterator, List, Optional
from .base import BaseTTSClient, TTSVoice

log = logging.getLogger(__name__)


class PiperTTSClient(BaseTTSClient):
    provider_name = "piper"

    def __init__(self, piper_binary: str = "piper", model_path: str = "",
                 config_path: Optional[str] = None, speaker_id: Optional[int] = None) -> None:
        self._binary = piper_binary
        self._model = model_path
        self._config = config_path
        self._speaker = speaker_id
        self._voices: List[TTSVoice] = []
        if model_path:
            name = Path(model_path).stem
            self._voices = [TTSVoice(id=name, name=name, language="en", provider=self.provider_name)]

    @property
    def available(self) -> bool:
        return bool(self._model) and Path(self._model).exists()

    def _build_cmd(self, output_file: str) -> list:
        cmd = [self._binary, "--model", self._model, "--output_file", output_file]
        if self._config:
            cmd += ["--config", self._config]
        if self._speaker is not None:
            cmd += ["--speaker", str(self._speaker)]
        return cmd

    async def synthesize(self, text: str, voice_id: Optional[str] = None,
                         format: str = "wav", **kwargs) -> bytes:
        if not self.available:
            raise RuntimeError("Piper model not configured or not found")
        with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as tmp:
            tmp_path = tmp.name
        try:
            cmd = self._build_cmd(tmp_path)
            proc = await asyncio.create_subprocess_exec(
                *cmd,
                stdin=asyncio.subprocess.PIPE,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE,
            )
            _, stderr = await proc.communicate(input=text.encode())
            if proc.returncode != 0:
                raise RuntimeError(f"Piper failed: {stderr.decode()}")
            return Path(tmp_path).read_bytes()
        finally:
            Path(tmp_path).unlink(missing_ok=True)

    async def stream_synthesize(self, text: str, voice_id: Optional[str] = None,
                                 format: str = "wav", **kwargs) -> AsyncIterator[bytes]:
        audio = await self.synthesize(text, voice_id, format, **kwargs)
        chunk_size = 8192
        for i in range(0, len(audio), chunk_size):
            yield audio[i:i + chunk_size]

    async def list_voices(self) -> List[TTSVoice]:
        return self._voices

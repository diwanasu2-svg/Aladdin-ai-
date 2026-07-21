"""TTS manager — picks the best available provider."""
from __future__ import annotations
import logging
from typing import Any, AsyncIterator, Dict, List, Optional
from .base import BaseTTSClient, TTSVoice

log = logging.getLogger(__name__)


class TTSManager:
    """Manages multiple TTS backends with ordered fallback."""

    def __init__(self) -> None:
        self._providers: Dict[str, BaseTTSClient] = {}
        self._order: List[str] = ["openai", "elevenlabs", "piper", "browser"]

    def register(self, name: str, client: BaseTTSClient) -> None:
        self._providers[name] = client
        log.info("TTS provider registered: %s", name)

    def _pick(self, preferred: Optional[str] = None) -> Optional[BaseTTSClient]:
        if preferred and preferred in self._providers:
            return self._providers[preferred]
        for name in self._order:
            if name in self._providers and self._providers[name].available:
                return self._providers[name]
        return None

    async def synthesize(self, text: str, provider: Optional[str] = None,
                         voice_id: Optional[str] = None, format: str = "mp3") -> tuple[bytes, str]:
        """Returns (audio_bytes, content_type)."""
        client = self._pick(provider)
        if client is None:
            raise RuntimeError("No TTS provider available")
        audio = await client.synthesize(text, voice_id=voice_id, format=format)
        content_type = _content_type(format, client.provider_name)
        return audio, content_type

    async def stream_synthesize(self, text: str, provider: Optional[str] = None,
                                voice_id: Optional[str] = None, format: str = "mp3") -> AsyncIterator[bytes]:
        client = self._pick(provider)
        if client is None:
            raise RuntimeError("No TTS provider available")
        async for chunk in client.stream_synthesize(text, voice_id=voice_id, format=format):
            yield chunk

    async def list_all_voices(self) -> List[Dict[str, Any]]:
        voices = []
        for name, client in self._providers.items():
            try:
                pv = await client.list_voices()
                for v in pv:
                    voices.append({"id": v.id, "name": v.name, "language": v.language, "provider": v.provider})
            except Exception as exc:
                log.warning("list_voices failed for %s: %s", name, exc)
        return voices

    @property
    def available_providers(self) -> List[str]:
        return [n for n, c in self._providers.items() if c.available]


def _content_type(fmt: str, provider: str) -> str:
    if provider == "browser":
        return "application/json"
    mapping = {"mp3": "audio/mpeg", "wav": "audio/wav", "ogg": "audio/ogg",
               "opus": "audio/ogg; codecs=opus", "pcm": "audio/pcm", "aac": "audio/aac"}
    return mapping.get(fmt, "audio/mpeg")

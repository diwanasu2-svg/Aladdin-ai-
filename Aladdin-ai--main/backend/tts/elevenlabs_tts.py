"""ElevenLabs TTS integration."""
from __future__ import annotations
import logging
from typing import AsyncIterator, List, Optional
from .base import BaseTTSClient, TTSVoice

log = logging.getLogger(__name__)

DEFAULT_VOICE_ID = "21m00Tcm4TlvDq8ikWAM"  # Rachel


class ElevenLabsTTSClient(BaseTTSClient):
    provider_name = "elevenlabs"

    def __init__(self, api_key: str, default_voice_id: str = DEFAULT_VOICE_ID,
                 stability: float = 0.5, similarity_boost: float = 0.75) -> None:
        self._api_key = api_key
        self._default_voice_id = default_voice_id
        self._stability = stability
        self._similarity_boost = similarity_boost
        try:
            import httpx
            self._httpx = httpx
        except ImportError as e:
            raise ImportError("httpx required: pip install httpx") from e

    def _headers(self):
        return {"xi-api-key": self._api_key, "Content-Type": "application/json"}

    def _base_url(self):
        return "https://api.elevenlabs.io/v1"

    async def synthesize(self, text: str, voice_id: Optional[str] = None,
                         format: str = "mp3", **kwargs) -> bytes:
        vid = voice_id or self._default_voice_id
        payload = {
            "text": text,
            "model_id": "eleven_monolingual_v1",
            "voice_settings": {"stability": self._stability, "similarity_boost": self._similarity_boost},
        }
        async with self._httpx.AsyncClient(timeout=60) as client:
            resp = await client.post(
                f"{self._base_url()}/text-to-speech/{vid}",
                json=payload, headers=self._headers()
            )
            resp.raise_for_status()
            return resp.content

    async def stream_synthesize(self, text: str, voice_id: Optional[str] = None,
                                 format: str = "mp3", **kwargs) -> AsyncIterator[bytes]:
        vid = voice_id or self._default_voice_id
        payload = {
            "text": text,
            "model_id": "eleven_monolingual_v1",
            "voice_settings": {"stability": self._stability, "similarity_boost": self._similarity_boost},
        }
        async with self._httpx.AsyncClient(timeout=60) as client:
            async with client.stream(
                "POST",
                f"{self._base_url()}/text-to-speech/{vid}/stream",
                json=payload, headers=self._headers()
            ) as resp:
                resp.raise_for_status()
                async for chunk in resp.aiter_bytes(4096):
                    yield chunk

    async def list_voices(self) -> List[TTSVoice]:
        try:
            async with self._httpx.AsyncClient(timeout=15) as client:
                resp = await client.get(f"{self._base_url()}/voices", headers=self._headers())
                resp.raise_for_status()
                data = resp.json()
                return [
                    TTSVoice(id=v["voice_id"], name=v["name"],
                             language=v.get("labels", {}).get("language", "en"),
                             provider=self.provider_name)
                    for v in data.get("voices", [])
                ]
        except Exception as exc:
            log.warning("ElevenLabs list_voices failed: %s", exc)
            return [TTSVoice(id=DEFAULT_VOICE_ID, name="Rachel", provider=self.provider_name)]

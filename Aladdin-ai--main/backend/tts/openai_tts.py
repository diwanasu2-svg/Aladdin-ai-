"""OpenAI TTS (alloy, echo, fable, onyx, nova, shimmer)."""
from __future__ import annotations
import logging
from typing import AsyncIterator, List, Optional
from .base import BaseTTSClient, TTSVoice

log = logging.getLogger(__name__)

OPENAI_VOICES = ["alloy", "echo", "fable", "onyx", "nova", "shimmer"]


class OpenAITTSClient(BaseTTSClient):
    provider_name = "openai"

    def __init__(self, api_key: str, default_voice: str = "alloy", default_model: str = "tts-1") -> None:
        try:
            from openai import AsyncOpenAI
            self._client = AsyncOpenAI(api_key=api_key)
        except ImportError as e:
            raise ImportError("openai package required: pip install openai") from e
        self._default_voice = default_voice
        self._model = default_model

    async def synthesize(self, text: str, voice_id: Optional[str] = None,
                         format: str = "mp3", **kwargs) -> bytes:
        voice = voice_id or self._default_voice
        if voice not in OPENAI_VOICES:
            voice = self._default_voice
        resp = await self._client.audio.speech.create(
            model=self._model,
            voice=voice,
            input=text,
            response_format=format if format in ("mp3","opus","aac","flac","wav","pcm") else "mp3",
        )
        return resp.content

    async def stream_synthesize(self, text: str, voice_id: Optional[str] = None,
                                 format: str = "mp3", **kwargs) -> AsyncIterator[bytes]:
        voice = voice_id or self._default_voice
        async with self._client.audio.speech.with_streaming_response.create(
            model=self._model, voice=voice, input=text,
            response_format=format if format in ("mp3","opus","aac","flac","wav","pcm") else "mp3",
        ) as resp:
            async for chunk in resp.iter_bytes(chunk_size=4096):
                yield chunk

    async def list_voices(self) -> List[TTSVoice]:
        return [TTSVoice(id=v, name=v.capitalize(), language="en", provider=self.provider_name)
                for v in OPENAI_VOICES]

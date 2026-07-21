"""Browser SpeechSynthesis fallback descriptor (server-side config only)."""
from __future__ import annotations
from typing import AsyncIterator, List, Optional
from .base import BaseTTSClient, TTSVoice


class BrowserTTSClient(BaseTTSClient):
    """
    Not a real TTS engine — signals the frontend to use the
    Web Speech API (SpeechSynthesis) instead of streaming audio.
    """
    provider_name = "browser"

    async def synthesize(self, text: str, voice_id: Optional[str] = None,
                         format: str = "mp3", **kwargs) -> bytes:
        # Return JSON instruction for browser
        import json
        payload = json.dumps({"provider": "browser", "text": text, "voice": voice_id})
        return payload.encode()

    async def stream_synthesize(self, text: str, voice_id: Optional[str] = None,
                                 format: str = "mp3", **kwargs) -> AsyncIterator[bytes]:
        import json
        yield json.dumps({"provider": "browser", "text": text, "voice": voice_id}).encode()

    async def list_voices(self) -> List[TTSVoice]:
        return [
            TTSVoice(id="browser-default", name="Browser Default",
                     language="en", provider=self.provider_name),
        ]

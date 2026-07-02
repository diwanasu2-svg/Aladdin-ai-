"""Abstract base for all TTS providers."""
from __future__ import annotations
from abc import ABC, abstractmethod
from dataclasses import dataclass
from typing import AsyncIterator, List, Optional


@dataclass
class TTSVoice:
    id: str
    name: str
    language: str = "en"
    provider: str = "unknown"


class BaseTTSClient(ABC):
    provider_name: str = "base"

    @abstractmethod
    async def synthesize(self, text: str, voice_id: Optional[str] = None,
                         format: str = "mp3", **kwargs) -> bytes:
        """Return raw audio bytes."""
        ...

    @abstractmethod
    async def stream_synthesize(self, text: str, voice_id: Optional[str] = None,
                                format: str = "mp3", **kwargs) -> AsyncIterator[bytes]:
        """Yield audio chunks as they are generated."""
        ...

    @abstractmethod
    async def list_voices(self) -> List[TTSVoice]:
        """Return available voices for this provider."""
        ...

    @property
    def available(self) -> bool:
        return True

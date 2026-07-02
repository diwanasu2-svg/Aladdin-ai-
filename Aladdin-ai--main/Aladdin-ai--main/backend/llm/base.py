"""Abstract base class for all LLM providers."""

from __future__ import annotations

import asyncio
import logging
from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from typing import Any, AsyncIterator, Dict, List, Optional

log = logging.getLogger(__name__)


@dataclass
class LLMResponse:
    content: str
    model: str
    provider: str
    tokens_used: Optional[int] = None
    finish_reason: Optional[str] = None
    tool_calls: Optional[List[Dict[str, Any]]] = None


class BaseLLMClient(ABC):
    """Common interface every LLM client must implement."""

    provider_name: str = "base"

    @abstractmethod
    async def chat(
        self,
        messages: List[Dict[str, str]],
        model: Optional[str] = None,
        temperature: float = 0.7,
        max_tokens: int = 512,
        tools: Optional[List[Dict[str, Any]]] = None,
        **kwargs: Any,
    ) -> LLMResponse:
        ...

    @abstractmethod
    async def stream_chat(
        self,
        messages: List[Dict[str, str]],
        model: Optional[str] = None,
        temperature: float = 0.7,
        max_tokens: int = 512,
        **kwargs: Any,
    ) -> AsyncIterator[str]:
        ...

    async def _retry(self, coro_fn, *args, max_retries: int = 3, **kwargs):
        """Exponential back-off retry wrapper."""
        delay = 1.0
        last_exc: Optional[Exception] = None
        for attempt in range(max_retries):
            try:
                return await coro_fn(*args, **kwargs)
            except Exception as exc:
                last_exc = exc
                if attempt < max_retries - 1:
                    log.warning("[%s] attempt %d failed: %s — retrying in %.1fs",
                                self.provider_name, attempt + 1, exc, delay)
                    await asyncio.sleep(delay)
                    delay *= 2
        raise RuntimeError(f"{self.provider_name} failed after {max_retries} retries") from last_exc

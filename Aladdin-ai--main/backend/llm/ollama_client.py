"""Ollama LLM client (local fallback)."""

from __future__ import annotations

import json
import logging
from typing import Any, AsyncIterator, Dict, List, Optional

import aiohttp

from .base import BaseLLMClient, LLMResponse

log = logging.getLogger(__name__)

DEFAULT_MODEL = "llama3.1"


class OllamaClient(BaseLLMClient):
    provider_name = "ollama"

    def __init__(self, host: str = "http://localhost:11434", default_model: str = DEFAULT_MODEL) -> None:
        self._host = host.rstrip("/")
        self._default_model = default_model

    async def chat(
        self,
        messages: List[Dict[str, str]],
        model: Optional[str] = None,
        temperature: float = 0.7,
        max_tokens: int = 512,
        tools: Optional[List[Dict[str, Any]]] = None,
        **kwargs: Any,
    ) -> LLMResponse:
        async def _call():
            payload = {
                "model": model or self._default_model,
                "messages": messages,
                "options": {"temperature": temperature, "num_predict": max_tokens},
                "stream": False,
            }
            async with aiohttp.ClientSession() as session:
                async with session.post(f"{self._host}/api/chat", json=payload, timeout=aiohttp.ClientTimeout(total=300)) as resp:
                    resp.raise_for_status()
                    data = await resp.json()
            return LLMResponse(
                content=data.get("message", {}).get("content", ""),
                model=data.get("model", model or self._default_model),
                provider=self.provider_name,
                tokens_used=data.get("eval_count"),
                finish_reason="stop" if data.get("done") else None,
            )
        return await self._retry(_call, max_retries=3)

    async def stream_chat(
        self,
        messages: List[Dict[str, str]],
        model: Optional[str] = None,
        temperature: float = 0.7,
        max_tokens: int = 512,
        **kwargs: Any,
    ) -> AsyncIterator[str]:
        payload = {
            "model": model or self._default_model,
            "messages": messages,
            "options": {"temperature": temperature, "num_predict": max_tokens},
            "stream": True,
        }
        async with aiohttp.ClientSession() as session:
            async with session.post(f"{self._host}/api/chat", json=payload, timeout=aiohttp.ClientTimeout(total=300)) as resp:
                resp.raise_for_status()
                async for raw_line in resp.content:
                    line = raw_line.strip()
                    if not line:
                        continue
                    try:
                        data = json.loads(line)
                        token = data.get("message", {}).get("content", "")
                        if token:
                            yield token
                        if data.get("done"):
                            break
                    except json.JSONDecodeError:
                        continue

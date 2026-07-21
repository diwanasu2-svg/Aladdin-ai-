"""OpenAI LLM client (GPT-4, GPT-3.5, etc.)."""

from __future__ import annotations

import logging
from typing import Any, AsyncIterator, Dict, List, Optional

from .base import BaseLLMClient, LLMResponse

log = logging.getLogger(__name__)

DEFAULT_MODEL = "gpt-4o-mini"


class OpenAIClient(BaseLLMClient):
    provider_name = "openai"

    def __init__(self, api_key: str, default_model: str = DEFAULT_MODEL) -> None:
        try:
            from openai import AsyncOpenAI
            self._client = AsyncOpenAI(api_key=api_key)
        except ImportError as e:
            raise ImportError("openai package not installed. Run: pip install openai") from e
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
            params: Dict[str, Any] = dict(
                model=model or self._default_model,
                messages=messages,
                temperature=temperature,
                max_tokens=max_tokens,
            )
            if tools:
                params["tools"] = tools
                params["tool_choice"] = "auto"
            resp = await self._client.chat.completions.create(**params)
            choice = resp.choices[0]
            tool_calls = None
            if choice.message.tool_calls:
                tool_calls = [
                    {"id": tc.id, "function": {"name": tc.function.name, "arguments": tc.function.arguments}}
                    for tc in choice.message.tool_calls
                ]
            return LLMResponse(
                content=choice.message.content or "",
                model=resp.model,
                provider=self.provider_name,
                tokens_used=resp.usage.total_tokens if resp.usage else None,
                finish_reason=choice.finish_reason,
                tool_calls=tool_calls,
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
        stream = await self._client.chat.completions.create(
            model=model or self._default_model,
            messages=messages,
            temperature=temperature,
            max_tokens=max_tokens,
            stream=True,
        )
        async for chunk in stream:
            delta = chunk.choices[0].delta.content
            if delta:
                yield delta

"""Anthropic Claude LLM client."""

from __future__ import annotations

import logging
from typing import Any, AsyncIterator, Dict, List, Optional

from .base import BaseLLMClient, LLMResponse

log = logging.getLogger(__name__)

DEFAULT_MODEL = "claude-3-haiku-20240307"


class AnthropicClient(BaseLLMClient):
    provider_name = "anthropic"

    def __init__(self, api_key: str, default_model: str = DEFAULT_MODEL) -> None:
        try:
            import anthropic
            self._client = anthropic.AsyncAnthropic(api_key=api_key)
        except ImportError as e:
            raise ImportError("anthropic package not installed. Run: pip install anthropic") from e
        self._default_model = default_model

    def _split_messages(self, messages: List[Dict[str, str]]):
        system = ""
        conv = []
        for msg in messages:
            if msg["role"] == "system":
                system = msg["content"]
            else:
                conv.append({"role": msg["role"], "content": msg["content"]})
        return system, conv

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
            system, conv = self._split_messages(messages)
            params: Dict[str, Any] = dict(
                model=model or self._default_model,
                max_tokens=max_tokens,
                temperature=temperature,
                messages=conv,
            )
            if system:
                params["system"] = system
            if tools:
                params["tools"] = tools
            resp = await self._client.messages.create(**params)
            content_text = ""
            tool_calls = None
            for block in resp.content:
                if block.type == "text":
                    content_text += block.text
                elif block.type == "tool_use":
                    if tool_calls is None:
                        tool_calls = []
                    tool_calls.append({"id": block.id, "function": {"name": block.name, "arguments": block.input}})
            return LLMResponse(
                content=content_text,
                model=resp.model,
                provider=self.provider_name,
                tokens_used=(resp.usage.input_tokens + resp.usage.output_tokens) if resp.usage else None,
                finish_reason=resp.stop_reason,
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
        system, conv = self._split_messages(messages)
        params: Dict[str, Any] = dict(
            model=model or self._default_model,
            max_tokens=max_tokens,
            temperature=temperature,
            messages=conv,
        )
        if system:
            params["system"] = system
        async with self._client.messages.stream(**params) as stream:
            async for text in stream.text_stream:
                yield text

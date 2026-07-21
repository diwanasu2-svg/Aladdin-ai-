"""Google Gemini LLM client."""

from __future__ import annotations

import json
import logging
from typing import Any, AsyncIterator, Dict, List, Optional

from .base import BaseLLMClient, LLMResponse

log = logging.getLogger(__name__)

DEFAULT_MODEL = "gemini-1.5-flash"


class GeminiClient(BaseLLMClient):
    provider_name = "gemini"

    def __init__(self, api_key: str, default_model: str = DEFAULT_MODEL) -> None:
        try:
            import google.generativeai as genai
            genai.configure(api_key=api_key)
            self._genai = genai
        except ImportError as e:
            raise ImportError("google-generativeai not installed. Run: pip install google-generativeai") from e
        self._api_key = api_key
        self._default_model = default_model

    def _to_gemini_messages(self, messages: List[Dict[str, str]]):
        """Convert OpenAI-style messages to Gemini format."""
        history = []
        system_parts = []
        for msg in messages:
            role = msg["role"]
            content = msg["content"]
            if role == "system":
                system_parts.append(content)
            elif role == "user":
                history.append({"role": "user", "parts": [content]})
            elif role == "assistant":
                history.append({"role": "model", "parts": [content]})
        return system_parts, history

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
            system_parts, history = self._to_gemini_messages(messages)
            gen_model = self._genai.GenerativeModel(
                model_name=model or self._default_model,
                system_instruction="\n".join(system_parts) if system_parts else None,
                generation_config={"temperature": temperature, "max_output_tokens": max_tokens},
            )
            chat_session = gen_model.start_chat(history=history[:-1] if len(history) > 1 else [])
            last_user = history[-1]["parts"][0] if history else ""
            response = await chat_session.send_message_async(last_user)
            return LLMResponse(
                content=response.text,
                model=model or self._default_model,
                provider=self.provider_name,
                finish_reason="stop",
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
        system_parts, history = self._to_gemini_messages(messages)
        gen_model = self._genai.GenerativeModel(
            model_name=model or self._default_model,
            system_instruction="\n".join(system_parts) if system_parts else None,
            generation_config={"temperature": temperature, "max_output_tokens": max_tokens},
        )
        chat_session = gen_model.start_chat(history=history[:-1] if len(history) > 1 else [])
        last_user = history[-1]["parts"][0] if history else ""
        async for chunk in await chat_session.send_message_async(last_user, stream=True):
            if chunk.text:
                yield chunk.text

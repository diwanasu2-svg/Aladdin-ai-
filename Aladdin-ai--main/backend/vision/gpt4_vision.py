"""GPT-4o Vision integration."""
from __future__ import annotations
import base64, logging
from pathlib import Path
from typing import Any, AsyncIterator, Dict, List, Optional

log = logging.getLogger(__name__)


class GPT4VisionClient:
    provider = "gpt4_vision"

    def __init__(self, api_key: str, model: str = "gpt-4o") -> None:
        try:
            from openai import AsyncOpenAI
            self._client = AsyncOpenAI(api_key=api_key)
        except ImportError as e:
            raise ImportError("openai package required") from e
        self._model = model

    def _encode_image(self, image_bytes: bytes) -> str:
        return base64.b64encode(image_bytes).decode()

    async def analyze(self, image_bytes: bytes, prompt: str = "Describe this image in detail.",
                      mime_type: str = "image/jpeg", max_tokens: int = 1024) -> Dict[str, Any]:
        b64 = self._encode_image(image_bytes)
        messages = [{"role": "user", "content": [
            {"type": "image_url", "image_url": {"url": f"data:{mime_type};base64,{b64}"}},
            {"type": "text", "text": prompt},
        ]}]
        resp = await self._client.chat.completions.create(
            model=self._model, messages=messages, max_tokens=max_tokens
        )
        return {
            "description": resp.choices[0].message.content,
            "model": resp.model,
            "provider": self.provider,
            "tokens_used": resp.usage.total_tokens if resp.usage else None,
        }

    async def analyze_url(self, url: str, prompt: str = "Describe this image.",
                          max_tokens: int = 1024) -> Dict[str, Any]:
        messages = [{"role": "user", "content": [
            {"type": "image_url", "image_url": {"url": url}},
            {"type": "text", "text": prompt},
        ]}]
        resp = await self._client.chat.completions.create(
            model=self._model, messages=messages, max_tokens=max_tokens
        )
        return {"description": resp.choices[0].message.content,
                "model": resp.model, "provider": self.provider}

    async def stream_analyze(self, image_bytes: bytes, prompt: str = "Describe this image.",
                              mime_type: str = "image/jpeg") -> AsyncIterator[str]:
        b64 = self._encode_image(image_bytes)
        messages = [{"role": "user", "content": [
            {"type": "image_url", "image_url": {"url": f"data:{mime_type};base64,{b64}"}},
            {"type": "text", "text": prompt},
        ]}]
        stream = await self._client.chat.completions.create(
            model=self._model, messages=messages, max_tokens=1024, stream=True
        )
        async for chunk in stream:
            delta = chunk.choices[0].delta.content
            if delta:
                yield delta

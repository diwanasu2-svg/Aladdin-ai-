"""Gemini Vision integration."""
from __future__ import annotations
import base64, logging
from typing import Any, AsyncIterator, Dict, Optional

log = logging.getLogger(__name__)


class GeminiVisionClient:
    provider = "gemini_vision"

    def __init__(self, api_key: str, model: str = "gemini-1.5-flash") -> None:
        try:
            import google.generativeai as genai
            genai.configure(api_key=api_key)
            self._genai = genai
        except ImportError as e:
            raise ImportError("google-generativeai required") from e
        self._model_name = model

    def _make_model(self):
        return self._genai.GenerativeModel(self._model_name)

    async def analyze(self, image_bytes: bytes, prompt: str = "Describe this image.",
                      mime_type: str = "image/jpeg") -> Dict[str, Any]:
        import asyncio
        model = self._make_model()
        image_part = {"mime_type": mime_type, "data": image_bytes}
        def _run():
            resp = model.generate_content([prompt, image_part])
            return resp.text
        text = await asyncio.get_running_loop().run_in_executor(None, _run)
        return {"description": text, "model": self._model_name, "provider": self.provider}

    async def analyze_url(self, url: str, prompt: str = "Describe this image.") -> Dict[str, Any]:
        import httpx, asyncio
        async with httpx.AsyncClient(timeout=30) as client:
            resp = await client.get(url)
            resp.raise_for_status()
            image_bytes = resp.content
            mime = resp.headers.get("content-type", "image/jpeg").split(";")[0]
        return await self.analyze(image_bytes, prompt, mime)

    async def stream_analyze(self, image_bytes: bytes, prompt: str = "Describe this image.",
                              mime_type: str = "image/jpeg") -> AsyncIterator[str]:
        import asyncio
        model = self._make_model()
        image_part = {"mime_type": mime_type, "data": image_bytes}
        def _run():
            resp = model.generate_content([prompt, image_part], stream=True)
            return [chunk.text for chunk in resp if chunk.text]
        chunks = await asyncio.get_running_loop().run_in_executor(None, _run)
        for c in chunks:
            yield c

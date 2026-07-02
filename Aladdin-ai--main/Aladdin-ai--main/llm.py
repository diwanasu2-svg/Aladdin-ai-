"""LLM client — Ollama backend with streaming support and tool calling."""

from __future__ import annotations

import json
import logging
from typing import Callable, Iterable, List, Optional, Tuple

import requests

from .config import OllamaCfg

log = logging.getLogger(__name__)


class OllamaClient:
    """Talks to a local Ollama server."""

    def __init__(self, cfg: OllamaCfg, system_prompt: str):
        self.cfg = cfg
        self.system_prompt = system_prompt
        self._tools: List[dict] = []
        self._tool_handlers: dict[str, Callable] = {}

    # ------------------------------------------------------------------
    # Tool / function registration
    # ------------------------------------------------------------------

    def register_tool(
        self, name: str, description: str, parameters: dict, handler: Callable
    ) -> None:
        """Register a callable tool for function calling."""
        self._tools.append(
            {
                "type": "function",
                "function": {
                    "name": name,
                    "description": description,
                    "parameters": parameters,
                },
            }
        )
        self._tool_handlers[name] = handler
        log.debug("Registered tool: %s", name)

    # ------------------------------------------------------------------
    # Chat
    # ------------------------------------------------------------------

    def chat(
        self,
        user_text: str,
        history: Iterable[Tuple[str, str]] = (),
        stream_callback: Optional[Callable[[str], None]] = None,
    ) -> str:
        """Send a message and return the assistant reply."""
        messages: List[dict] = [{"role": "system", "content": self.system_prompt}]
        for u, a in history:
            messages.append({"role": "user", "content": u})
            messages.append({"role": "assistant", "content": a})
        messages.append({"role": "user", "content": user_text})

        url = f"{self.cfg.host.rstrip('/')}/api/chat"
        payload: dict = {
            "model": self.cfg.model,
            "messages": messages,
            "stream": stream_callback is not None,
            "options": {
                "temperature": self.cfg.temperature,
                "num_predict": self.cfg.max_tokens,
            },
        }

        log.debug("POST %s model=%s", url, self.cfg.model)

        try:
            if stream_callback:
                return self._stream_chat(url, payload, stream_callback)
            else:
                r = requests.post(url, json=payload, timeout=self.cfg.timeout)
                r.raise_for_status()
                data = r.json()
                reply = (data.get("message") or {}).get("content", "").strip()
                # Handle tool calls if present
                reply = self._handle_tool_calls(data, messages, url, payload) or reply
                return reply
        except requests.ConnectionError:
            log.error(
                "Cannot reach Ollama at %s — is 'ollama serve' running?", self.cfg.host
            )
            return "I'm having trouble connecting to my brain right now. Please make sure Ollama is running."
        except requests.Timeout:
            log.error("Ollama request timed out after %ds", self.cfg.timeout)
            return "That took too long. Could you try a simpler question?"
        except requests.HTTPError as exc:
            log.error("Ollama HTTP error: %s", exc)
            return "Something went wrong with the language model."
        except Exception as exc:
            log.error("Unexpected LLM error: %s", exc)
            return "I encountered an unexpected error."

    def _stream_chat(
        self, url: str, payload: dict, callback: Callable[[str], None]
    ) -> str:
        payload["stream"] = True
        full_reply = []
        with requests.post(
            url, json=payload, timeout=self.cfg.timeout, stream=True
        ) as r:
            r.raise_for_status()
            for line in r.iter_lines():
                if not line:
                    continue
                try:
                    chunk = json.loads(line)
                    token = (chunk.get("message") or {}).get("content", "")
                    if token:
                        callback(token)
                        full_reply.append(token)
                    if chunk.get("done"):
                        break
                except json.JSONDecodeError:
                    pass
        return "".join(full_reply).strip()

    def _handle_tool_calls(
        self, data: dict, messages: list, url: str, payload: dict
    ) -> Optional[str]:
        """Process tool calls returned by the model."""
        msg = data.get("message") or {}
        tool_calls = msg.get("tool_calls") or []
        if not tool_calls or not self._tool_handlers:
            return None

        messages.append({"role": "assistant", "content": "", "tool_calls": tool_calls})
        for tc in tool_calls:
            fn = tc.get("function", {})
            name = fn.get("name", "")
            args = fn.get("arguments", {})
            if isinstance(args, str):
                try:
                    args = json.loads(args)
                except Exception:
                    args = {}
            handler = self._tool_handlers.get(name)
            if handler:
                try:
                    result = handler(**args)
                except Exception as exc:
                    result = f"Error: {exc}"
            else:
                result = f"Unknown tool: {name}"
            messages.append({"role": "tool", "content": str(result), "name": name})

        # Second pass with tool results
        payload2 = {**payload, "messages": messages, "stream": False}
        try:
            r2 = requests.post(url, json=payload2, timeout=self.cfg.timeout)
            r2.raise_for_status()
            return (r2.json().get("message") or {}).get("content", "").strip()
        except Exception:
            return None

    def is_available(self) -> bool:
        """Check if Ollama server is reachable."""
        try:
            r = requests.get(f"{self.cfg.host.rstrip('/')}/api/tags", timeout=5)
            return r.status_code == 200
        except Exception:
            return False

    def list_models(self) -> List[str]:
        """Return list of available models."""
        try:
            r = requests.get(f"{self.cfg.host.rstrip('/')}/api/tags", timeout=5)
            r.raise_for_status()
            return [m["name"] for m in r.json().get("models", [])]
        except Exception:
            return []

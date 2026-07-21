"""Tool-call adapters for OpenAI, Gemini, Anthropic, and Ollama."""

from __future__ import annotations

import json
import logging
from typing import Any, Callable, Dict, List, Optional

log = logging.getLogger(__name__)


class ToolAdapter:
    """Parse tool calls from LLM responses and dispatch to registered callables."""

    def __init__(self) -> None:
        self._tools: Dict[str, Callable] = {}

    def register(self, name: str, fn: Callable) -> None:
        self._tools[name] = fn
        log.info("Registered tool: %s", name)

    def openai_schema(self, name: str, description: str, parameters: Dict) -> Dict:
        return {
            "type": "function",
            "function": {"name": name, "description": description, "parameters": parameters},
        }

    async def execute(self, tool_calls: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
        """Execute tool calls and return results."""
        results = []
        for tc in tool_calls:
            func_info = tc.get("function", {})
            name = func_info.get("name", "")
            raw_args = func_info.get("arguments", "{}")
            try:
                args = json.loads(raw_args) if isinstance(raw_args, str) else raw_args
            except json.JSONDecodeError:
                args = {}

            fn = self._tools.get(name)
            if fn is None:
                results.append({"tool_call_id": tc.get("id", ""), "role": "tool", "content": f"Tool '{name}' not found"})
                continue
            try:
                import asyncio
                if asyncio.iscoroutinefunction(fn):
                    result = await fn(**args)
                else:
                    result = fn(**args)
                results.append({
                    "tool_call_id": tc.get("id", ""),
                    "role": "tool",
                    "content": json.dumps(result) if not isinstance(result, str) else result,
                })
            except Exception as exc:
                log.error("Tool %s error: %s", name, exc)
                results.append({"tool_call_id": tc.get("id", ""), "role": "tool", "content": f"Error: {exc}"})
        return results

    def parse_ollama_tool_calls(self, content: str) -> Optional[List[Dict]]:
        """Try to extract JSON tool calls embedded in Ollama's text response."""
        import re
        match = re.search(r"\{.*\}", content, re.DOTALL)
        if not match:
            return None
        try:
            data = json.loads(match.group())
            if "tool" in data or "function" in data:
                return [data]
        except Exception:
            pass
        return None

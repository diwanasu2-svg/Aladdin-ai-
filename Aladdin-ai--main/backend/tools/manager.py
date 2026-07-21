"""Tool Manager — registers, executes, and tracks all tools."""
from __future__ import annotations
import asyncio, logging, time
from typing import Any, Dict, List, Optional
from .base import BaseTool, ToolResult

log = logging.getLogger(__name__)
_execution_log: List[Dict] = []


class ToolManager:
    def __init__(self) -> None:
        self._tools: Dict[str, BaseTool] = {}

    def register(self, tool: BaseTool) -> None:
        self._tools[tool.name] = tool
        log.debug("Tool registered: %s", tool.name)

    def register_all(self, tools: List[BaseTool]) -> None:
        for t in tools:
            self.register(t)

    def list_tools(self) -> List[Dict]:
        return [{"name": t.name, "description": t.description, "parameters": t.parameters}
                for t in self._tools.values()]

    def schemas(self) -> List[Dict]:
        return [t.schema() for t in self._tools.values()]

    async def execute(self, tool_name: str, **kwargs) -> ToolResult:
        tool = self._tools.get(tool_name)
        if tool is None:
            return ToolResult(False, tool_name, error=f"Tool '{tool_name}' not found")
        t0 = time.time()
        log.info("Executing tool: %s args=%s", tool_name, list(kwargs.keys()))
        try:
            result = await tool.execute(**kwargs)
        except Exception as exc:
            log.error("Tool %s error: %s", tool_name, exc)
            result = ToolResult(False, tool_name, error=str(exc),
                                duration_ms=(time.time()-t0)*1000)
        # Keep execution log (last 100 entries)
        _execution_log.append({"tool": tool_name, "success": result.success,
                                "duration_ms": result.duration_ms,
                                "ts": time.time(), "error": result.error})
        if len(_execution_log) > 100:
            _execution_log.pop(0)
        return result

    async def execute_from_llm(self, tool_calls: List[Dict[str, Any]]) -> List[Dict]:
        """Execute tool calls from LLM response (OpenAI format) and return results."""
        results = []
        for tc in tool_calls:
            fn = tc.get("function", {})
            name = fn.get("name", "")
            import json
            raw_args = fn.get("arguments", "{}")
            try:
                args = json.loads(raw_args) if isinstance(raw_args, str) else raw_args
            except Exception:
                args = {}
            result = await self.execute(name, **args)
            results.append({"tool_call_id": tc.get("id", ""), "role": "tool",
                             "name": name, "content": str(result.data) if result.success else result.error})
        return results

    def get_log(self, limit: int = 50) -> List[Dict]:
        return _execution_log[-limit:]

    def get_tool(self, name: str) -> Optional[BaseTool]:
        return self._tools.get(name)

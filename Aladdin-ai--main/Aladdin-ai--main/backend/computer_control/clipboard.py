"""Clipboard history — save, retrieve, search, pin, and auto-clear sensitive clipboard data."""
from __future__ import annotations
import asyncio, hashlib, logging, re, time
from collections import deque
from typing import Any, Dict, List, Optional
from ..tools.base import BaseTool, ToolResult

log = logging.getLogger(__name__)

_HISTORY_MAX = 100
_history: deque = deque(maxlen=_HISTORY_MAX)
_pinned: List[Dict] = []

_SENSITIVE_PATTERNS = [
    re.compile(r"\b(?:\d{4}[-\s]?){3}\d{4}\b"),          # credit card
    re.compile(r"\b\d{3}-\d{2}-\d{4}\b"),                 # SSN (US)
    re.compile(r"(?i)(password|passwd|pwd|secret|token|api[_\-]?key)\s*[:=]\s*\S+"),  # passwords/keys
    re.compile(r"(?i)bearer\s+[a-z0-9_\-\.]{20,}"),       # bearer tokens
]


def _is_sensitive(text: str) -> bool:
    return any(p.search(text) for p in _SENSITIVE_PATTERNS)


def _hash(text: str) -> str:
    return hashlib.sha256(text.encode()).hexdigest()[:12]


def _add_to_history(text: str, source: str = "clipboard") -> Dict:
    entry = {
        "id": _hash(text),
        "text": text,
        "preview": text[:80] + ("..." if len(text) > 80 else ""),
        "length": len(text),
        "source": source,
        "timestamp": time.time(),
        "is_sensitive": _is_sensitive(text),
        "pinned": False
    }
    # Avoid duplicates of the most recent item
    if _history and _history[-1]["id"] == entry["id"]:
        return _history[-1]
    _history.append(entry)
    return entry


class CopyToClipboardTool(BaseTool):
    name = "copy_to_clipboard"
    description = "Copy text to the system clipboard and add to clipboard history."
    parameters = {"type": "object", "properties": {
        "text": {"type": "string"}}, "required": ["text"]}

    async def execute(self, text: str) -> ToolResult:
        t0 = time.time()
        try:
            import pyperclip
            pyperclip.copy(text)
            entry = _add_to_history(text, source="manual")
            if entry["is_sensitive"]:
                log.warning("Sensitive content copied to clipboard (id=%s)", entry["id"])
            return ToolResult(True, self.name, {
                "copied": True, "id": entry["id"],
                "length": len(text), "is_sensitive": entry["is_sensitive"]
            }, duration_ms=(time.time() - t0) * 1000)
        except Exception as exc:
            return ToolResult(False, self.name, error=str(exc), duration_ms=(time.time() - t0) * 1000)


class GetClipboardTool(BaseTool):
    name = "get_clipboard"
    description = "Get the current clipboard contents."
    parameters = {"type": "object", "properties": {}}

    async def execute(self) -> ToolResult:
        t0 = time.time()
        try:
            import pyperclip
            text = pyperclip.paste()
            if text:
                _add_to_history(text, source="read")
            return ToolResult(True, self.name, {
                "text": text, "length": len(text),
                "is_sensitive": _is_sensitive(text)
            }, duration_ms=(time.time() - t0) * 1000)
        except Exception as exc:
            return ToolResult(False, self.name, error=str(exc), duration_ms=(time.time() - t0) * 1000)


class GetClipboardHistoryTool(BaseTool):
    name = "get_clipboard_history"
    description = "Get clipboard history (recent copies)."
    parameters = {"type": "object", "properties": {
        "limit": {"type": "integer", "default": 20},
        "include_sensitive": {"type": "boolean", "default": False}}}

    async def execute(self, limit: int = 20, include_sensitive: bool = False) -> ToolResult:
        items = list(_history)[-limit:]
        if not include_sensitive:
            items = [
                {**i, "text": "[REDACTED — sensitive]", "preview": "[REDACTED]"} if i["is_sensitive"] else i
                for i in items
            ]
        return ToolResult(True, self.name, {"history": list(reversed(items)), "count": len(items)})


class PinClipboardItemTool(BaseTool):
    name = "pin_clipboard_item"
    description = "Pin a clipboard item so it persists across sessions."
    parameters = {"type": "object", "properties": {
        "item_id": {"type": "string"},
        "label": {"type": "string", "description": "Optional friendly name for the pinned item"}},
        "required": ["item_id"]}

    async def execute(self, item_id: str, label: str = "") -> ToolResult:
        for item in _history:
            if item["id"] == item_id:
                pinned_entry = {**item, "pinned": True, "label": label or item["preview"]}
                if not any(p["id"] == item_id for p in _pinned):
                    _pinned.append(pinned_entry)
                return ToolResult(True, self.name, {"pinned": item_id, "label": label})
        return ToolResult(False, self.name, error=f"Item {item_id} not found in history")


class SearchClipboardTool(BaseTool):
    name = "search_clipboard"
    description = "Search clipboard history for matching text."
    parameters = {"type": "object", "properties": {
        "query": {"type": "string"}, "limit": {"type": "integer", "default": 10}},
        "required": ["query"]}

    async def execute(self, query: str, limit: int = 10) -> ToolResult:
        q = query.lower()
        results = [
            i for i in reversed(list(_history))
            if q in i["text"].lower() and not i["is_sensitive"]
        ][:limit]
        return ToolResult(True, self.name, {"results": results, "count": len(results)})


class ClearClipboardTool(BaseTool):
    name = "clear_clipboard"
    description = "Clear the clipboard and optionally clear sensitive items from history."
    parameters = {"type": "object", "properties": {
        "clear_history": {"type": "boolean", "default": False},
        "sensitive_only": {"type": "boolean", "default": True}}}

    async def execute(self, clear_history: bool = False, sensitive_only: bool = True) -> ToolResult:
        try:
            import pyperclip
            pyperclip.copy("")
            cleared = 0
            if clear_history:
                if sensitive_only:
                    to_remove = [i for i in _history if i["is_sensitive"]]
                    for item in to_remove:
                        _history.remove(item)
                    cleared = len(to_remove)
                else:
                    cleared = len(_history)
                    _history.clear()
            return ToolResult(True, self.name, {"cleared_clipboard": True, "history_items_cleared": cleared})
        except Exception as exc:
            return ToolResult(False, self.name, error=str(exc))

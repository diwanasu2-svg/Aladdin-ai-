"""Keyboard simulation — type text, shortcuts, function keys, hold keys via pynput."""
from __future__ import annotations
import asyncio, logging, time
from typing import List
from ..tools.base import BaseTool, ToolResult

log = logging.getLogger(__name__)


def _keyboard():
    try:
        from pynput.keyboard import Controller, Key
        return Controller(), Key
    except ImportError:
        # Task 29: Log warning so issue is visible in server logs
        log.warning(
            "pynput not installed — keyboard control unavailable. "
            "Install it with: pip install pynput"
        )
        raise RuntimeError("pynput not installed — run: pip install pynput")


class TypeTextTool(BaseTool):
    name = "type_text"
    description = "Type text as if entered via keyboard. Supports multi-line text."
    parameters = {"type": "object", "properties": {
        "text": {"type": "string"},
        "delay_ms": {"type": "integer", "default": 0, "description": "Delay between keystrokes in milliseconds"}},
        "required": ["text"]}

    async def execute(self, text: str, delay_ms: int = 0) -> ToolResult:
        t0 = time.time()
        try:
            kb, _ = _keyboard()
            if delay_ms > 0:
                for char in text:
                    kb.type(char)
                    await asyncio.sleep(delay_ms / 1000)
            else:
                kb.type(text)
            return ToolResult(True, self.name, {"typed": len(text), "text_preview": text[:50]},
                              duration_ms=(time.time() - t0) * 1000)
        except Exception as exc:
            return ToolResult(False, self.name, error=str(exc), duration_ms=(time.time() - t0) * 1000)


class PressShortcutTool(BaseTool):
    name = "press_shortcut"
    description = "Press a keyboard shortcut combination (Ctrl+C, Alt+Tab, Win+D, etc.)."
    parameters = {"type": "object", "properties": {
        "shortcut": {"type": "string",
                     "description": "Shortcut string, e.g. 'ctrl+c', 'ctrl+shift+n', 'alt+tab', 'win+d'"}},
        "required": ["shortcut"]}

    _KEY_MAP = {
        "ctrl": "ctrl_l", "control": "ctrl_l",
        "alt": "alt_l", "shift": "shift_l",
        "win": "cmd", "super": "cmd", "windows": "cmd",
        "tab": "tab", "enter": "enter", "esc": "esc", "escape": "esc",
        "space": "space", "backspace": "backspace", "delete": "delete",
        "home": "home", "end": "end", "up": "up", "down": "down",
        "left": "left", "right": "right", "page_up": "page_up", "page_down": "page_down",
    }

    async def execute(self, shortcut: str) -> ToolResult:
        t0 = time.time()
        try:
            from pynput.keyboard import Controller, Key
            kb = Controller()
            parts = [p.strip().lower() for p in shortcut.split("+")]
            keys = []
            for p in parts:
                mapped = self._KEY_MAP.get(p)
                if mapped:
                    keys.append(getattr(Key, mapped))
                elif p.startswith("f") and p[1:].isdigit():
                    keys.append(getattr(Key, p))
                else:
                    keys.append(p)
            # Press all modifier keys, then the last key
            for key in keys[:-1]:
                kb.press(key)
            kb.press(keys[-1])
            kb.release(keys[-1])
            for key in reversed(keys[:-1]):
                kb.release(key)
            return ToolResult(True, self.name, {"shortcut": shortcut},
                              duration_ms=(time.time() - t0) * 1000)
        except Exception as exc:
            return ToolResult(False, self.name, error=str(exc), duration_ms=(time.time() - t0) * 1000)


class PressKeyTool(BaseTool):
    name = "press_key"
    description = "Press a single key (Enter, Escape, Tab, arrow keys, F1-F12, etc.)."
    parameters = {"type": "object", "properties": {
        "key": {"type": "string", "description": "Key name: enter, esc, tab, up, down, left, right, f1-f12, space, backspace, delete, etc."}},
        "required": ["key"]}

    async def execute(self, key: str) -> ToolResult:
        t0 = time.time()
        try:
            from pynput.keyboard import Controller, Key
            kb = Controller()
            k = key.lower()
            special = {
                "enter": Key.enter, "esc": Key.esc, "escape": Key.esc,
                "tab": Key.tab, "space": Key.space, "backspace": Key.backspace,
                "delete": Key.delete, "home": Key.home, "end": Key.end,
                "up": Key.up, "down": Key.down, "left": Key.left, "right": Key.right,
                "page_up": Key.page_up, "page_down": Key.page_down,
                "caps_lock": Key.caps_lock, "print_screen": Key.print_screen,
            }
            if k in special:
                kb.press(special[k])
                kb.release(special[k])
            elif k.startswith("f") and k[1:].isdigit():
                fkey = getattr(Key, k)
                kb.press(fkey)
                kb.release(fkey)
            else:
                kb.press(k)
                kb.release(k)
            return ToolResult(True, self.name, {"key": key}, duration_ms=(time.time() - t0) * 1000)
        except Exception as exc:
            return ToolResult(False, self.name, error=str(exc), duration_ms=(time.time() - t0) * 1000)


class HoldKeyTool(BaseTool):
    name = "hold_key"
    description = "Hold a key down for a specified duration."
    parameters = {"type": "object", "properties": {
        "key": {"type": "string"}, "duration_s": {"type": "number", "default": 1.0}},
        "required": ["key"]}

    async def execute(self, key: str, duration_s: float = 1.0) -> ToolResult:
        t0 = time.time()
        try:
            from pynput.keyboard import Controller, Key
            kb = Controller()
            special_map = {
                "enter": Key.enter, "esc": Key.esc, "tab": Key.tab,
                "space": Key.space, "backspace": Key.backspace,
            }
            k = special_map.get(key.lower(), key)
            kb.press(k)
            await asyncio.sleep(duration_s)
            kb.release(k)
            return ToolResult(True, self.name, {"key": key, "duration_s": duration_s},
                              duration_ms=(time.time() - t0) * 1000)
        except Exception as exc:
            return ToolResult(False, self.name, error=str(exc), duration_ms=(time.time() - t0) * 1000)

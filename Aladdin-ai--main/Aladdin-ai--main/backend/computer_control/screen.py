"""Screen interaction — tap by coordinates, long press, swipe, pinch via pyautogui / ADB."""
from __future__ import annotations
import asyncio, logging, os, time
from typing import List
from ..tools.base import BaseTool, ToolResult

log = logging.getLogger(__name__)

_ADB = os.getenv("ADB_PATH", "adb")


def _adb(*args) -> str:
    """Run adb command and return output. Used when controlling an Android device."""
    import subprocess
    result = subprocess.run([_ADB] + list(args), capture_output=True, text=True, timeout=10)
    if result.returncode != 0 and result.stderr:
        raise RuntimeError(result.stderr.strip())
    return result.stdout.strip()


def _desktop_or_adb(mode: str = "auto") -> str:
    """Detect whether we're running on desktop or controlling Android via ADB."""
    if mode == "adb":
        return "adb"
    if mode == "desktop":
        return "desktop"
    try:
        _adb("devices")
        return "adb"
    except Exception:
        return "desktop"


class TapCoordinatesTool(BaseTool):
    name = "tap_coordinates"
    description = "Tap/click a specific point on the screen by X,Y coordinates."
    parameters = {"type": "object", "properties": {
        "x": {"type": "integer"}, "y": {"type": "integer"},
        "mode": {"type": "string", "enum": ["auto", "desktop", "adb"], "default": "auto"}},
        "required": ["x", "y"]}

    async def execute(self, x: int, y: int, mode: str = "auto") -> ToolResult:
        t0 = time.time()
        try:
            env = _desktop_or_adb(mode)
            if env == "adb":
                await asyncio.get_running_loop().run_in_executor(None, lambda: _adb("shell", "input", "tap", str(x), str(y)))
            else:
                import pyautogui
                pyautogui.click(x, y)
            return ToolResult(True, self.name, {"x": x, "y": y, "env": env},
                              duration_ms=(time.time() - t0) * 1000)
        except Exception as exc:
            return ToolResult(False, self.name, error=str(exc), duration_ms=(time.time() - t0) * 1000)


class LongPressTool(BaseTool):
    name = "long_press"
    description = "Perform a long press at screen coordinates."
    parameters = {"type": "object", "properties": {
        "x": {"type": "integer"}, "y": {"type": "integer"},
        "duration_ms": {"type": "integer", "default": 1000},
        "mode": {"type": "string", "default": "auto"}},
        "required": ["x", "y"]}

    async def execute(self, x: int, y: int, duration_ms: int = 1000, mode: str = "auto") -> ToolResult:
        t0 = time.time()
        try:
            env = _desktop_or_adb(mode)
            if env == "adb":
                await asyncio.get_running_loop().run_in_executor(
                    None, lambda: _adb("shell", "input", "swipe", str(x), str(y), str(x), str(y), str(duration_ms))
                )
            else:
                from pynput.mouse import Button, Controller
                mouse = Controller()
                mouse.position = (x, y)
                mouse.press(Button.left)
                await asyncio.sleep(duration_ms / 1000)
                mouse.release(Button.left)
            return ToolResult(True, self.name, {"x": x, "y": y, "duration_ms": duration_ms},
                              duration_ms=(time.time() - t0) * 1000)
        except Exception as exc:
            return ToolResult(False, self.name, error=str(exc), duration_ms=(time.time() - t0) * 1000)


class DoubleTapTool(BaseTool):
    name = "double_tap"
    description = "Double-tap on a screen element by coordinates."
    parameters = {"type": "object", "properties": {
        "x": {"type": "integer"}, "y": {"type": "integer"},
        "mode": {"type": "string", "default": "auto"}},
        "required": ["x", "y"]}

    async def execute(self, x: int, y: int, mode: str = "auto") -> ToolResult:
        t0 = time.time()
        try:
            env = _desktop_or_adb(mode)
            if env == "adb":
                await asyncio.get_running_loop().run_in_executor(None, lambda: _adb("shell", "input", "tap", str(x), str(y)))
                await asyncio.sleep(0.1)
                await asyncio.get_running_loop().run_in_executor(None, lambda: _adb("shell", "input", "tap", str(x), str(y)))
            else:
                import pyautogui
                pyautogui.doubleClick(x, y)
            return ToolResult(True, self.name, {"x": x, "y": y, "double_tap": True},
                              duration_ms=(time.time() - t0) * 1000)
        except Exception as exc:
            return ToolResult(False, self.name, error=str(exc), duration_ms=(time.time() - t0) * 1000)


class SwipeTool(BaseTool):
    name = "swipe"
    description = "Swipe gesture in a direction or between two points."
    parameters = {"type": "object", "properties": {
        "direction": {"type": "string", "enum": ["up", "down", "left", "right"]},
        "from_x": {"type": "integer"}, "from_y": {"type": "integer"},
        "to_x": {"type": "integer"}, "to_y": {"type": "integer"},
        "duration_ms": {"type": "integer", "default": 300},
        "mode": {"type": "string", "default": "auto"}}}

    async def execute(self, direction: str = None, from_x: int = None, from_y: int = None,
                      to_x: int = None, to_y: int = None, duration_ms: int = 300, mode: str = "auto") -> ToolResult:
        t0 = time.time()
        try:
            env = _desktop_or_adb(mode)
            # Compute from/to if direction given
            if direction and from_x is not None and from_y is not None:
                dist = 400
                if direction == "up":
                    to_x, to_y = from_x, from_y - dist
                elif direction == "down":
                    to_x, to_y = from_x, from_y + dist
                elif direction == "left":
                    to_x, to_y = from_x - dist, from_y
                elif direction == "right":
                    to_x, to_y = from_x + dist, from_y
            if env == "adb":
                await asyncio.get_running_loop().run_in_executor(
                    None, lambda: _adb("shell", "input", "swipe",
                                       str(from_x), str(from_y), str(to_x), str(to_y), str(duration_ms))
                )
            else:
                import pyautogui
                pyautogui.moveTo(from_x, from_y)
                pyautogui.dragTo(to_x, to_y, duration=duration_ms / 1000)
            return ToolResult(True, self.name, {
                "from": [from_x, from_y], "to": [to_x, to_y], "direction": direction
            }, duration_ms=(time.time() - t0) * 1000)
        except Exception as exc:
            return ToolResult(False, self.name, error=str(exc), duration_ms=(time.time() - t0) * 1000)


class PinchTool(BaseTool):
    name = "pinch"
    description = "Pinch in or out gesture (zoom) on a screen area."
    parameters = {"type": "object", "properties": {
        "center_x": {"type": "integer"}, "center_y": {"type": "integer"},
        "direction": {"type": "string", "enum": ["in", "out"]},
        "scale": {"type": "number", "default": 100, "description": "Distance in pixels to move fingers"},
        "mode": {"type": "string", "default": "auto"}},
        "required": ["center_x", "center_y", "direction"]}

    async def execute(self, center_x: int, center_y: int, direction: str,
                      scale: float = 100, mode: str = "auto") -> ToolResult:
        t0 = time.time()
        try:
            env = _desktop_or_adb(mode)
            half = int(scale / 2)
            if direction == "in":
                # Start from outer, move to center
                x1a, y1a = center_x - half, center_y
                x1b, y1b = center_x + half, center_y
                x2a, y2a = center_x - 5, center_y
                x2b, y2b = center_x + 5, center_y
            else:
                x1a, y1a = center_x - 5, center_y
                x1b, y1b = center_x + 5, center_y
                x2a, y2a = center_x - half, center_y
                x2b, y2b = center_x + half, center_y
            if env == "adb":
                # ADB multi-touch via sendevent is complex; use a simpler approach
                cmd = (f"input swipe {x1a} {y1a} {x2a} {y2a} 300 & "
                       f"input swipe {x1b} {y1b} {x2b} {y2b} 300")
                await asyncio.get_running_loop().run_in_executor(
                    None, lambda: _adb("shell", cmd)
                )
            else:
                # Desktop: simulate with scroll
                import pyautogui
                pyautogui.moveTo(center_x, center_y)
                pyautogui.scroll(5 if direction == "out" else -5)
            return ToolResult(True, self.name, {"direction": direction, "center": [center_x, center_y]},
                              duration_ms=(time.time() - t0) * 1000)
        except Exception as exc:
            return ToolResult(False, self.name, error=str(exc), duration_ms=(time.time() - t0) * 1000)


class TypeOnScreenTool(BaseTool):
    name = "type_on_screen"
    description = "Click on a text field at given coordinates and type text."
    parameters = {"type": "object", "properties": {
        "x": {"type": "integer"}, "y": {"type": "integer"},
        "text": {"type": "string"}, "clear_first": {"type": "boolean", "default": True},
        "mode": {"type": "string", "default": "auto"}},
        "required": ["x", "y", "text"]}

    async def execute(self, x: int, y: int, text: str, clear_first: bool = True, mode: str = "auto") -> ToolResult:
        t0 = time.time()
        try:
            env = _desktop_or_adb(mode)
            if env == "adb":
                await asyncio.get_running_loop().run_in_executor(None, lambda: _adb("shell", "input", "tap", str(x), str(y)))
                await asyncio.sleep(0.3)
                if clear_first:
                    await asyncio.get_running_loop().run_in_executor(None, lambda: _adb("shell", "input", "keyevent", "KEYCODE_CTRL_A"))
                    await asyncio.sleep(0.1)
                escaped = text.replace(" ", "%s").replace("'", "\'")
                await asyncio.get_running_loop().run_in_executor(None, lambda: _adb("shell", "input", "text", escaped))
            else:
                import pyautogui, pyperclip
                pyautogui.click(x, y)
                await asyncio.sleep(0.2)
                if clear_first:
                    pyautogui.hotkey("ctrl", "a")
                pyperclip.copy(text)
                pyautogui.hotkey("ctrl", "v")
            return ToolResult(True, self.name, {"x": x, "y": y, "typed": len(text)},
                              duration_ms=(time.time() - t0) * 1000)
        except Exception as exc:
            return ToolResult(False, self.name, error=str(exc), duration_ms=(time.time() - t0) * 1000)

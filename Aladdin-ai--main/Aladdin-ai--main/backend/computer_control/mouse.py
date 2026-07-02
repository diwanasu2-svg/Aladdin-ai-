"""Mouse control — move, click, drag, scroll via pynput / pyautogui."""
import logging
log = logging.getLogger(__name__)
from __future__ import annotations
import asyncio, logging, math, time
from typing import Tuple
from ..tools.base import BaseTool, ToolResult

log = logging.getLogger(__name__)


def _mouse():
    try:
        from pynput.mouse import Button, Controller
        return Controller(), Button
    except ImportError:
        # Task 29: Log warning so issue is visible in server logs
        log.warning(
            "pynput not installed — mouse control unavailable. "
            "Install it with: pip install pynput"
        )
        raise RuntimeError("pynput not installed — run: pip install pynput")


class MoveMouseTool(BaseTool):
    name = "move_mouse"
    description = "Move the mouse pointer to specific screen coordinates."
    parameters = {"type": "object", "properties": {
        "x": {"type": "integer"}, "y": {"type": "integer"},
        "smooth": {"type": "boolean", "default": False, "description": "Move with smooth animation"}},
        "required": ["x", "y"]}

    async def execute(self, x: int, y: int, smooth: bool = False) -> ToolResult:
        t0 = time.time()
        try:
            mouse, _ = _mouse()
            if smooth:
                cx, cy = mouse.position
                steps = 30
                for i in range(1, steps + 1):
                    nx = cx + (x - cx) * i / steps
                    ny = cy + (y - cy) * i / steps
                    mouse.position = (nx, ny)
                    await asyncio.sleep(0.01)
            else:
                mouse.position = (x, y)
            return ToolResult(True, self.name, {"x": x, "y": y},
                              duration_ms=(time.time() - t0) * 1000)
        except Exception as exc:
            return ToolResult(False, self.name, error=str(exc), duration_ms=(time.time() - t0) * 1000)


class LeftClickTool(BaseTool):
    name = "left_click"
    description = "Perform a left mouse click, optionally at given coordinates."
    parameters = {"type": "object", "properties": {
        "x": {"type": "integer"}, "y": {"type": "integer"}}}

    async def execute(self, x: int = None, y: int = None) -> ToolResult:
        t0 = time.time()
        try:
            mouse, Button = _mouse()
            if x is not None and y is not None:
                mouse.position = (x, y)
            mouse.click(Button.left)
            pos = mouse.position
            return ToolResult(True, self.name, {"x": pos[0], "y": pos[1], "button": "left"},
                              duration_ms=(time.time() - t0) * 1000)
        except Exception as exc:
            return ToolResult(False, self.name, error=str(exc), duration_ms=(time.time() - t0) * 1000)


class RightClickTool(BaseTool):
    name = "right_click"
    description = "Perform a right mouse click at given coordinates."
    parameters = {"type": "object", "properties": {
        "x": {"type": "integer"}, "y": {"type": "integer"}}}

    async def execute(self, x: int = None, y: int = None) -> ToolResult:
        t0 = time.time()
        try:
            mouse, Button = _mouse()
            if x is not None and y is not None:
                mouse.position = (x, y)
            mouse.click(Button.right)
            return ToolResult(True, self.name, {"button": "right"},
                              duration_ms=(time.time() - t0) * 1000)
        except Exception as exc:
            return ToolResult(False, self.name, error=str(exc), duration_ms=(time.time() - t0) * 1000)


class DoubleClickTool(BaseTool):
    name = "double_click"
    description = "Perform a double left-click at given coordinates."
    parameters = {"type": "object", "properties": {
        "x": {"type": "integer"}, "y": {"type": "integer"}}}

    async def execute(self, x: int = None, y: int = None) -> ToolResult:
        t0 = time.time()
        try:
            mouse, Button = _mouse()
            if x is not None and y is not None:
                mouse.position = (x, y)
            mouse.click(Button.left, 2)
            return ToolResult(True, self.name, {"double_click": True},
                              duration_ms=(time.time() - t0) * 1000)
        except Exception as exc:
            return ToolResult(False, self.name, error=str(exc), duration_ms=(time.time() - t0) * 1000)


class DragDropTool(BaseTool):
    name = "drag_drop"
    description = "Drag from one position and drop to another."
    parameters = {"type": "object", "properties": {
        "from_x": {"type": "integer"}, "from_y": {"type": "integer"},
        "to_x": {"type": "integer"}, "to_y": {"type": "integer"},
        "duration_s": {"type": "number", "default": 0.5}},
        "required": ["from_x", "from_y", "to_x", "to_y"]}

    async def execute(self, from_x: int, from_y: int, to_x: int, to_y: int, duration_s: float = 0.5) -> ToolResult:
        t0 = time.time()
        try:
            mouse, Button = _mouse()
            mouse.position = (from_x, from_y)
            mouse.press(Button.left)
            steps = max(int(duration_s * 60), 10)
            for i in range(1, steps + 1):
                nx = from_x + (to_x - from_x) * i / steps
                ny = from_y + (to_y - from_y) * i / steps
                mouse.position = (nx, ny)
                await asyncio.sleep(duration_s / steps)
            mouse.release(Button.left)
            return ToolResult(True, self.name, {
                "from": [from_x, from_y], "to": [to_x, to_y]
            }, duration_ms=(time.time() - t0) * 1000)
        except Exception as exc:
            return ToolResult(False, self.name, error=str(exc), duration_ms=(time.time() - t0) * 1000)


class ScrollTool(BaseTool):
    name = "scroll"
    description = "Scroll the mouse wheel up, down, or horizontally."
    parameters = {"type": "object", "properties": {
        "direction": {"type": "string", "enum": ["up", "down", "left", "right"]},
        "amount": {"type": "integer", "default": 3},
        "x": {"type": "integer"}, "y": {"type": "integer"}},
        "required": ["direction"]}

    async def execute(self, direction: str, amount: int = 3, x: int = None, y: int = None) -> ToolResult:
        t0 = time.time()
        try:
            mouse, _ = _mouse()
            if x is not None and y is not None:
                mouse.position = (x, y)
            if direction == "up":
                mouse.scroll(0, amount)
            elif direction == "down":
                mouse.scroll(0, -amount)
            elif direction == "left":
                mouse.scroll(-amount, 0)
            elif direction == "right":
                mouse.scroll(amount, 0)
            return ToolResult(True, self.name, {"direction": direction, "amount": amount},
                              duration_ms=(time.time() - t0) * 1000)
        except Exception as exc:
            return ToolResult(False, self.name, error=str(exc), duration_ms=(time.time() - t0) * 1000)


class SmoothMoveTool(BaseTool):
    name = "smooth_move"
    description = "Move mouse to target coordinates with smooth curved animation."
    parameters = {"type": "object", "properties": {
        "x": {"type": "integer"}, "y": {"type": "integer"},
        "steps": {"type": "integer", "default": 50}},
        "required": ["x", "y"]}

    async def execute(self, x: int, y: int, steps: int = 50) -> ToolResult:
        t0 = time.time()
        try:
            mouse, _ = _mouse()
            cx, cy = mouse.position
            for i in range(1, steps + 1):
                t = i / steps
                # Ease in-out using cubic Bezier
                ease = t * t * (3 - 2 * t)
                nx = cx + (x - cx) * ease
                ny = cy + (y - cy) * ease
                mouse.position = (nx, ny)
                await asyncio.sleep(1 / (steps * 30))
            return ToolResult(True, self.name, {"x": x, "y": y, "steps": steps},
                              duration_ms=(time.time() - t0) * 1000)
        except Exception as exc:
            return ToolResult(False, self.name, error=str(exc), duration_ms=(time.time() - t0) * 1000)

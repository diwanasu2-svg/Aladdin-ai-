"""Accessibility automation — inspect UI elements, tap by description, form-fill via pyautogui / AT-SPI."""
import logging
from __future__ import annotations
import asyncio, logging, platform, time
from typing import Any, Dict, List, Optional
from ..tools.base import BaseTool, ToolResult

log = logging.getLogger(__name__)
_SYSTEM = platform.system()


def _get_screenshot_b64():
    """Capture screen and return base64 JPEG for vision-based accessibility."""
    try:
        import pyautogui, base64, io
        from PIL import Image
        img = pyautogui.screenshot()
        buf = io.BytesIO()
        img.save(buf, format="JPEG", quality=70)
        return base64.b64encode(buf.getvalue()).decode()
    except Exception:
        return None


class GetUiElementsTool(BaseTool):
    name = "get_ui_elements"
    description = "Get interactive UI elements on the current screen (buttons, text fields, labels)."
    parameters = {"type": "object", "properties": {
        "element_type": {"type": "string",
                         "enum": ["all", "button", "text_field", "label", "checkbox", "combo"],
                         "default": "all"}}}

    async def execute(self, element_type: str = "all") -> ToolResult:
        t0 = time.time()
        try:
            if _SYSTEM == "Linux":
                # Use AT-SPI (assistive technology) on Linux
                import subprocess
                result = subprocess.run(
                    ["python3", "-c",
                     "import pyatspi; desk=pyatspi.Registry.getDesktop(0); "
                     "log.info([str(c) for c in desk])"],
                    capture_output=True, text=True, timeout=5
                )
                elements = [{"name": e} for e in result.stdout.strip().split("\n") if e]
            elif _SYSTEM == "Windows":
                import pywinauto
                from pywinauto import Desktop
                windows = Desktop(backend="uia").windows()
                elements = [{"title": w.window_text(), "control_type": w.control_type()} for w in windows]
            else:
                # MacOS — use AppleScript or accessibility framework
                elements = [{"note": "macOS accessibility requires explicit permissions"}]
            return ToolResult(True, self.name, {"elements": elements, "count": len(elements)},
                              duration_ms=(time.time() - t0) * 1000)
        except Exception as exc:
            return ToolResult(False, self.name, error=str(exc), duration_ms=(time.time() - t0) * 1000)


class TapByDescriptionTool(BaseTool):
    name = "tap_by_description"
    description = "Find a UI element by its text content or accessibility label and click it."
    parameters = {"type": "object", "properties": {
        "description": {"type": "string", "description": "Text label or accessibility description to find"},
        "confidence": {"type": "number", "default": 0.8}},
        "required": ["description"]}

    async def execute(self, description: str, confidence: float = 0.8) -> ToolResult:
        t0 = time.time()
        try:
            import pyautogui
            # Use image recognition to find text on screen
            location = pyautogui.locateCenterOnScreen(description, confidence=confidence)
            if location:
                pyautogui.click(location.x, location.y)
                return ToolResult(True, self.name, {"found": True, "x": location.x, "y": location.y},
                                  duration_ms=(time.time() - t0) * 1000)
            return ToolResult(True, self.name, {"found": False, "description": description},
                              duration_ms=(time.time() - t0) * 1000)
        except Exception as exc:
            return ToolResult(False, self.name, error=str(exc), duration_ms=(time.time() - t0) * 1000)


class FillFormTool(BaseTool):
    name = "fill_form"
    description = "Automatically fill form fields identified by labels."
    parameters = {"type": "object", "properties": {
        "fields": {"type": "object",
                   "description": "Dict of {field_label: value_to_type}"}},
        "required": ["fields"]}

    async def execute(self, fields: Dict[str, str]) -> ToolResult:
        t0 = time.time()
        filled = []
        errors = []
        try:
            import pyautogui, pyperclip
            for label, value in fields.items():
                try:
                    loc = pyautogui.locateCenterOnScreen(label, confidence=0.7)
                    if loc:
                        # Click slightly to the right of the label (usually the field)
                        pyautogui.click(loc.x + 150, loc.y)
                        await asyncio.sleep(0.2)
                        pyautogui.hotkey("ctrl", "a")
                        pyperclip.copy(value)
                        pyautogui.hotkey("ctrl", "v")
                        filled.append(label)
                    else:
                        errors.append(f"Label not found: {label}")
                except Exception as e:
                    errors.append(f"{label}: {e}")
            return ToolResult(True, self.name, {"filled": filled, "errors": errors},
                              duration_ms=(time.time() - t0) * 1000)
        except Exception as exc:
            return ToolResult(False, self.name, error=str(exc), duration_ms=(time.time() - t0) * 1000)


class GetScreenHierarchyTool(BaseTool):
    name = "get_screen_hierarchy"
    description = "Capture the current screen and describe the visible UI hierarchy."
    parameters = {"type": "object", "properties": {}}

    async def execute(self) -> ToolResult:
        t0 = time.time()
        try:
            b64 = _get_screenshot_b64()
            return ToolResult(True, self.name, {
                "screenshot_base64": b64[:500] + "..." if b64 else None,
                "captured": b64 is not None
            }, duration_ms=(time.time() - t0) * 1000)
        except Exception as exc:
            return ToolResult(False, self.name, error=str(exc), duration_ms=(time.time() - t0) * 1000)


class WaitForElementTool(BaseTool):
    name = "wait_for_element"
    description = "Wait until a UI element appears on screen."
    parameters = {"type": "object", "properties": {
        "image_path": {"type": "string", "description": "Path to reference image of the element"},
        "timeout_s": {"type": "number", "default": 10.0},
        "confidence": {"type": "number", "default": 0.8}},
        "required": ["image_path"]}

    async def execute(self, image_path: str, timeout_s: float = 10.0, confidence: float = 0.8) -> ToolResult:
        t0 = time.time()
        try:
            import pyautogui
            deadline = t0 + timeout_s
            while time.time() < deadline:
                loc = pyautogui.locateCenterOnScreen(image_path, confidence=confidence)
                if loc:
                    return ToolResult(True, self.name, {
                        "found": True, "x": loc.x, "y": loc.y,
                        "waited_s": time.time() - t0
                    }, duration_ms=(time.time() - t0) * 1000)
                await asyncio.sleep(0.5)
            return ToolResult(True, self.name, {"found": False, "timeout_s": timeout_s},
                              duration_ms=(time.time() - t0) * 1000)
        except Exception as exc:
            return ToolResult(False, self.name, error=str(exc), duration_ms=(time.time() - t0) * 1000)

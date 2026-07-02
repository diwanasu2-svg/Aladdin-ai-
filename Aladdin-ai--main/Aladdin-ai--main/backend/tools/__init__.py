"""
backend/tools/__init__.py — Phase 9 + Phase 10 tool registrations.

All tools are registered here as a central registry.
Import and instantiate via get_tool_registry().
"""

from __future__ import annotations

import logging
from typing import Any, Dict, Optional

log = logging.getLogger(__name__)

# ── Phase 9: Communication Tools ──────────────────────────────────────────────

def _load_tool(module_name: str, class_name: str) -> Optional[Any]:
    try:
        import importlib
        module = importlib.import_module(f"backend.tools.{module_name}")
        return getattr(module, class_name)()
    except ImportError as e:
        log.warning("Tool %s.%s unavailable: %s", module_name, class_name, e)
        return None
    except Exception as e:
        log.error("Failed to load tool %s.%s: %s", module_name, class_name, e)
        return None


class ToolRegistry:
    """Central registry for all backend tools."""

    def __init__(self) -> None:
        self._tools: Dict[str, Any] = {}
        self._registered = False

    def register_all(self) -> None:
        if self._registered:
            return
        self._registered = True

        # ── Phase 9: Communication Tools ─────────────────────────────────────
        self._try_register("maps",       "backend.tools.maps",       "MapsToolBackend")
        self._try_register("phone",      "backend.tools.phone",      "PhoneTool")
        self._try_register("sms",        "backend.tools.sms",        "SmsTool")
        self._try_register("whatsapp",   "backend.tools.whatsapp",   "WhatsAppTool")
        self._try_register("camera",     "backend.tools.camera",     "CameraTool")
        self._try_register("smart_home", "backend.tools.smart_home", "SmartHomeTool")
        self._try_register("telegram",   "backend.tools.telegram",   "TelegramTool")
        self._try_register("discord",    "backend.tools.discord",    "DiscordTool")
        self._try_register("contacts",   "backend.tools.contacts",   "ContactsTool")
        self._try_register("email",      "backend.tools.email",      "EmailTool")
        self._try_register("browser",    "backend.tools.browser",    "WebSearchTool")
        self._try_register("calendar",   "backend.tools.calendar",   "CalendarTool")
        self._try_register("files",      "backend.tools.files",      "FilesTool")
        self._try_register("notes",      "backend.tools.notes",      "NotesTool")
        self._try_register("reminder",   "backend.tools.reminder",   "ReminderTool")
        self._try_register("weather",    "backend.tools.weather",    "WeatherTool")

        # ── Phase 10: Computer Control Tools ─────────────────────────────────
        self._try_register("mouse",          "backend.computer_control.mouse",          "MouseController")
        self._try_register("keyboard",       "backend.computer_control.keyboard",       "KeyboardController")
        self._try_register("screen",         "backend.computer_control.screen",         "ScreenCapture")
        self._try_register("app_automation", "backend.computer_control.app_automation", "AppAutomation")
        self._try_register("file_manager",   "backend.computer_control.file_manager",   "FileManager")
        self._try_register("clipboard",      "backend.computer_control.clipboard",      "ClipboardManager")
        self._try_register("notifications",  "backend.computer_control.notifications",  "NotificationManager")
        self._try_register("device_settings","backend.computer_control.device_settings","DeviceSettings")
        self._try_register("accessibility",  "backend.computer_control.accessibility",  "AccessibilityController")

        log.info("Tool registry: %d tools registered", len(self._tools))

    def _try_register(self, name: str, module: str, class_name: str) -> None:
        try:
            import importlib
            mod = importlib.import_module(module)
            cls = getattr(mod, class_name, None)
            if cls is None:
                # Try getting first class from module
                log.debug("Class %s not found in %s — skipping", class_name, module)
                return
            self._tools[name] = cls()
            log.debug("Registered tool: %s (%s.%s)", name, module, class_name)
        except ImportError as e:
            log.debug("Tool %s unavailable (missing dependency): %s", name, e)
        except Exception as e:
            log.warning("Tool %s registration failed: %s", name, e)

    def get(self, name: str) -> Optional[Any]:
        return self._tools.get(name)

    def list_tools(self) -> list:
        return list(self._tools.keys())

    def execute(self, tool_name: str, params: Dict[str, Any]) -> Dict[str, Any]:
        tool = self.get(tool_name)
        if tool is None:
            return {"success": False, "error": f"Tool '{tool_name}' not registered or unavailable"}
        try:
            if hasattr(tool, "execute"):
                return tool.execute(params)
            elif hasattr(tool, "run"):
                return tool.run(params)
            else:
                return {"success": False, "error": f"Tool '{tool_name}' has no execute() method"}
        except Exception as exc:
            log.error("Tool %s execution error: %s", tool_name, exc)
            return {"success": False, "error": str(exc)}


# Module-level singleton
_registry: Optional[ToolRegistry] = None


def get_tool_registry() -> ToolRegistry:
    global _registry
    if _registry is None:
        _registry = ToolRegistry()
        _registry.register_all()
    return _registry

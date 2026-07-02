"""Phase 10 — Computer Control module for Aladdin AI.

Sub-modules:
  mouse          — Move, click, drag, scroll the mouse pointer
  keyboard       — Type text, press shortcuts, function keys
  accessibility  — Inspect and interact with UI elements via accessibility APIs
  screen         — Screen clicks, swipes, pinch, multi-touch
  app_automation — Launch apps, automate workflows, manage app state
  file_manager   — Full file-system CRUD, search, archive ops
  clipboard      — Clipboard history, pin, search, auto-clear sensitive data
  notifications  — Read, reply, dismiss, filter notifications
  device_settings— Wi-Fi, Bluetooth, volume, brightness, Do Not Disturb, etc.
"""
from .mouse import (
    MoveMouseTool, LeftClickTool, RightClickTool, DoubleClickTool,
    DragDropTool, ScrollTool, SmoothMoveTool
)
from .keyboard import (
    TypeTextTool, PressShortcutTool, PressKeyTool, HoldKeyTool
)
from .accessibility import (
    GetUiElementsTool, TapByDescriptionTool, FillFormTool,
    GetScreenHierarchyTool, WaitForElementTool
)
from .screen import (
    TapCoordinatesTool, LongPressTool, DoubleTapTool,
    SwipeTool, PinchTool
)
from .app_automation import (
    LaunchAppTool, CloseAppTool, GetForegroundAppTool,
    AutomateWorkflowTool, ListRunningAppsTool
)
from .file_manager import (
    CreateFileTool, RenameFileTool, MoveFileTool, DeleteFsTool,
    SearchFilesTool, OpenFileTool, GetStorageInfoTool,
    CompressFilesTool, ExtractArchiveTool
)
from .clipboard import (
    CopyToClipboardTool, GetClipboardTool, GetClipboardHistoryTool,
    PinClipboardItemTool, ClearClipboardTool, SearchClipboardTool
)
from .notifications import (
    GetNotificationsTool, DismissNotificationTool, ReplyToNotificationTool,
    FilterNotificationsTool, GetPendingNotificationsTool
)
from .device_settings import (
    SetWifiTool, SetBluetoothTool, SetVolumeTool, SetBrightnessTool,
    ToggleFlashlightTool, SetDoNotDisturbTool, SetBatterySaverTool,
    SetRotationLockTool, SetAirplaneModeT as SetAirplaneModeTool,
    GetDeviceInfoTool
)

__all__ = [
    "MoveMouseTool", "LeftClickTool", "RightClickTool", "DoubleClickTool",
    "DragDropTool", "ScrollTool", "SmoothMoveTool",
    "TypeTextTool", "PressShortcutTool", "PressKeyTool", "HoldKeyTool",
    "GetUiElementsTool", "TapByDescriptionTool", "FillFormTool",
    "GetScreenHierarchyTool", "WaitForElementTool",
    "TapCoordinatesTool", "LongPressTool", "DoubleTapTool",
    "SwipeTool", "PinchTool",
    "LaunchAppTool", "CloseAppTool", "GetForegroundAppTool",
    "AutomateWorkflowTool", "ListRunningAppsTool",
    "CreateFileTool", "RenameFileTool", "MoveFileTool", "DeleteFsTool",
    "SearchFilesTool", "OpenFileTool", "GetStorageInfoTool",
    "CompressFilesTool", "ExtractArchiveTool",
    "CopyToClipboardTool", "GetClipboardTool", "GetClipboardHistoryTool",
    "PinClipboardItemTool", "ClearClipboardTool", "SearchClipboardTool",
    "GetNotificationsTool", "DismissNotificationTool", "ReplyToNotificationTool",
    "FilterNotificationsTool", "GetPendingNotificationsTool",
    "SetWifiTool", "SetBluetoothTool", "SetVolumeTool", "SetBrightnessTool",
    "ToggleFlashlightTool", "SetDoNotDisturbTool", "SetBatterySaverTool",
    "SetRotationLockTool", "SetAirplaneModeTool", "GetDeviceInfoTool",
]


# Phase 10 fix — convenience function for tool registry integration
def get_registry():
    """Return all Phase 10 computer control tools as a flat dict."""
    tools = {}
    try:
        from .mouse import MoveMouseTool, LeftClickTool, RightClickTool, DoubleClickTool, DragDropTool, ScrollTool
        tools["mouse_move"] = MoveMouseTool()
        tools["mouse_click"] = LeftClickTool()
        tools["mouse_right_click"] = RightClickTool()
        tools["mouse_double_click"] = DoubleClickTool()
        tools["mouse_drag"] = DragDropTool()
        tools["mouse_scroll"] = ScrollTool()
    except ImportError as e:
        import logging; logging.getLogger(__name__).debug("Mouse tools unavailable: %s", e)

    try:
        from .keyboard import TypeTextTool, PressShortcutTool, PressKeyTool
        tools["keyboard_type"] = TypeTextTool()
        tools["keyboard_shortcut"] = PressShortcutTool()
        tools["keyboard_press"] = PressKeyTool()
    except ImportError as e:
        import logging; logging.getLogger(__name__).debug("Keyboard tools unavailable: %s", e)

    try:
        from .clipboard import CopyToClipboardTool, GetClipboardTool, GetClipboardHistoryTool
        tools["clipboard_copy"] = CopyToClipboardTool()
        tools["clipboard_get"] = GetClipboardTool()
        tools["clipboard_history"] = GetClipboardHistoryTool()
    except ImportError as e:
        import logging; logging.getLogger(__name__).debug("Clipboard tools unavailable: %s", e)

    try:
        from .notifications import GetNotificationsTool, DismissNotificationTool
        tools["notifications_get"] = GetNotificationsTool()
        tools["notifications_dismiss"] = DismissNotificationTool()
    except ImportError as e:
        import logging; logging.getLogger(__name__).debug("Notification tools unavailable: %s", e)

    try:
        from .device_settings import SetWifiTool, SetBluetoothTool, SetVolumeTool, SetBrightnessTool
        tools["settings_wifi"] = SetWifiTool()
        tools["settings_bluetooth"] = SetBluetoothTool()
        tools["settings_volume"] = SetVolumeTool()
        tools["settings_brightness"] = SetBrightnessTool()
    except ImportError as e:
        import logging; logging.getLogger(__name__).debug("Device settings tools unavailable: %s", e)

    return tools

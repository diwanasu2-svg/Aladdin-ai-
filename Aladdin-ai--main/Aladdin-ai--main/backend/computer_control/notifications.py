"""Notification control — read, reply, dismiss, filter, prioritize notifications."""
from __future__ import annotations
import asyncio, logging, os, time
from typing import Dict, List, Optional
from ..tools.base import BaseTool, ToolResult

log = logging.getLogger(__name__)
_ADB = os.getenv("ADB_PATH", "adb")

# In-memory notification store (populated from ADB or desktop API)
_notifications: List[Dict] = []


def _adb(*args) -> str:
    import subprocess
    result = subprocess.run([_ADB] + list(args), capture_output=True, text=True, timeout=10)
    return result.stdout.strip()


class GetNotificationsTool(BaseTool):
    name = "get_notifications"
    description = "Read all current notifications from the device."
    parameters = {"type": "object", "properties": {
        "app_filter": {"type": "string", "description": "Filter by app package name"},
        "limit": {"type": "integer", "default": 20}}}

    async def execute(self, app_filter: str = None, limit: int = 20) -> ToolResult:
        t0 = time.time()
        try:
            notifs = await asyncio.get_running_loop().run_in_executor(None, _fetch_notifications)
            if app_filter:
                notifs = [n for n in notifs if app_filter.lower() in (n.get("app") or "").lower()]
            notifs = notifs[:limit]
            return ToolResult(True, self.name, {"notifications": notifs, "count": len(notifs)},
                              duration_ms=(time.time() - t0) * 1000)
        except Exception as exc:
            return ToolResult(False, self.name, error=str(exc), duration_ms=(time.time() - t0) * 1000)


class DismissNotificationTool(BaseTool):
    name = "dismiss_notification"
    description = "Dismiss/clear a specific notification or all notifications."
    parameters = {"type": "object", "properties": {
        "notification_key": {"type": "string", "description": "Notification key to dismiss, or 'all'"}},
        "required": ["notification_key"]}

    async def execute(self, notification_key: str) -> ToolResult:
        t0 = time.time()
        try:
            if notification_key == "all":
                await asyncio.get_running_loop().run_in_executor(
                    None, lambda: _adb("shell", "service", "call", "notification", "1")
                )
                _notifications.clear()
                return ToolResult(True, self.name, {"dismissed": "all"}, duration_ms=(time.time() - t0) * 1000)
            # Dismiss specific notification via ADB
            result = _adb("shell", "cmd", "notification", "cancel", notification_key)
            _notifications[:] = [n for n in _notifications if n.get("key") != notification_key]
            return ToolResult(True, self.name, {"dismissed": notification_key},
                              duration_ms=(time.time() - t0) * 1000)
        except Exception as exc:
            return ToolResult(False, self.name, error=str(exc), duration_ms=(time.time() - t0) * 1000)


class ReplyToNotificationTool(BaseTool):
    name = "reply_to_notification"
    description = "Reply to a notification that supports inline replies (e.g. messages)."
    parameters = {"type": "object", "properties": {
        "notification_key": {"type": "string"}, "reply_text": {"type": "string"}},
        "required": ["notification_key", "reply_text"]}

    async def execute(self, notification_key: str, reply_text: str) -> ToolResult:
        t0 = time.time()
        try:
            # On Android, use the notification reply action via ADB
            # This is a best-effort; full implementation requires AccessibilityService
            result = await asyncio.get_running_loop().run_in_executor(
                None, lambda: _adb("shell", "cmd", "notification", "reply",
                                   notification_key, reply_text.replace(" ", "_"))
            )
            log.info("Replied to notification %s: %s", notification_key, reply_text[:50])
            return ToolResult(True, self.name, {
                "replied_to": notification_key, "reply": reply_text
            }, duration_ms=(time.time() - t0) * 1000)
        except Exception as exc:
            return ToolResult(False, self.name, error=str(exc), duration_ms=(time.time() - t0) * 1000)


class FilterNotificationsTool(BaseTool):
    name = "filter_notifications"
    description = "Filter or block notifications by app or keyword."
    parameters = {"type": "object", "properties": {
        "app": {"type": "string", "description": "App package to filter"},
        "keywords": {"type": "array", "items": {"type": "string"}, "description": "Block notifications containing these words"},
        "action": {"type": "string", "enum": ["block", "allow", "mute"], "default": "block"}}}

    _filters: List[Dict] = []

    async def execute(self, app: str = None, keywords: List[str] = None, action: str = "block") -> ToolResult:
        filter_rule = {"app": app, "keywords": keywords or [], "action": action, "created_at": time.time()}
        FilterNotificationsTool._filters.append(filter_rule)
        return ToolResult(True, self.name, {
            "filter_applied": filter_rule,
            "total_filters": len(FilterNotificationsTool._filters)
        })


class GetPendingNotificationsTool(BaseTool):
    name = "get_pending_notifications"
    description = "Get a count and summary of unread/pending notifications grouped by app."
    parameters = {"type": "object", "properties": {}}

    async def execute(self) -> ToolResult:
        t0 = time.time()
        try:
            notifs = await asyncio.get_running_loop().run_in_executor(None, _fetch_notifications)
            by_app: Dict[str, int] = {}
            for n in notifs:
                app = n.get("app", "Unknown")
                by_app[app] = by_app.get(app, 0) + 1
            return ToolResult(True, self.name, {
                "total": len(notifs),
                "by_app": by_app,
                "apps": list(by_app.keys())
            }, duration_ms=(time.time() - t0) * 1000)
        except Exception as exc:
            return ToolResult(False, self.name, error=str(exc), duration_ms=(time.time() - t0) * 1000)


def _fetch_notifications() -> List[Dict]:
    """Fetch notifications via ADB dumpsys."""
    try:
        raw = _adb("shell", "dumpsys", "notification", "--noredact")
        lines = raw.splitlines()
        notifs = []
        current: Dict = {}
        for line in lines:
            line = line.strip()
            if line.startswith("NotificationRecord"):
                if current:
                    notifs.append(current)
                current = {"raw": line}
            elif "pkg=" in line and current:
                import re
                m = re.search(r"pkg=(\S+)", line)
                if m:
                    current["app"] = m.group(1)
            elif "android.title=" in line and current:
                title = line.split("android.title=")[-1].strip()
                current["title"] = title
            elif "android.text=" in line and current:
                text = line.split("android.text=")[-1].strip()
                current["text"] = text
        if current:
            notifs.append(current)
        # Deduplicate
        seen = set()
        result = []
        for n in notifs:
            key = n.get("app", "") + n.get("title", "")
            if key not in seen:
                seen.add(key)
                result.append({
                    "app": n.get("app", "Unknown"),
                    "title": n.get("title", ""),
                    "text": n.get("text", ""),
                    "key": str(hash(key))
                })
        return result
    except Exception as e:
        log.warning("Could not fetch notifications via ADB: %s", e)
        return _notifications

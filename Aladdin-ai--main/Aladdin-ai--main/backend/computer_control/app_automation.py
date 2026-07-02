"""App automation — launch, close, navigate apps, automate workflows, inter-app communication."""
from __future__ import annotations
import asyncio, logging, os, platform, subprocess, time
from typing import Any, Dict, List, Optional
from ..tools.base import BaseTool, ToolResult

log = logging.getLogger(__name__)
_SYSTEM = platform.system()
_ADB = os.getenv("ADB_PATH", "adb")


def _adb(*args) -> str:
    result = subprocess.run([_ADB] + list(args), capture_output=True, text=True, timeout=15)
    return result.stdout.strip()



DESKTOP_APP_ALLOWLIST = {
    # Windows
    "chrome", "google chrome", "firefox", "code", "visual studio code", 
    "notepad", "notepad++", "explorer", "word", "excel", "outlook",
    "terminal", "cmd", "powershell", "calculator",
    # macOS
    "safari", "terminal", "finder", "textedit",
    # Linux
    "gedit", "nautilus", "xterm", "gnome-terminal",
}

class LaunchAppTool(BaseTool):
    name = "launch_app"
    description = "Launch an application by name or package name."
    parameters = {"type": "object", "properties": {
        "app": {"type": "string", "description": "App name (e.g. 'Chrome') or Android package (e.g. 'com.android.chrome')"},
        "mode": {"type": "string", "enum": ["auto", "desktop", "android"], "default": "auto"}},
        "required": ["app"]}

    async def execute(self, app: str, mode: str = "auto") -> ToolResult:
        t0 = time.time()
        try:
            env = mode if mode != "auto" else ("android" if _is_android_available() else "desktop")
            if env == "android":
                # Launch via ADB intent
                pkg = app if "." in app else _resolve_package(app)
                out = await asyncio.get_running_loop().run_in_executor(
                    None, lambda: _adb("shell", "monkey", "-p", pkg, "-c",
                                       "android.intent.category.LAUNCHER", "1")
                )
                return ToolResult(True, self.name, {"app": app, "package": pkg, "env": "android"},
                                  duration_ms=(time.time() - t0) * 1000)
            else:
                # Desktop
                app_lower = app.lower()
                if app_lower not in DESKTOP_APP_ALLOWLIST:
                    return ToolResult(False, self.name, 
                                     error=f"App '{app}' is not in the allowed list. Allowed: {sorted(DESKTOP_APP_ALLOWLIST)}",
                                     duration_ms=(time.time() - t0) * 1000)
                if _SYSTEM == "Windows":
                    await asyncio.get_running_loop().run_in_executor(None, lambda: subprocess.Popen(["cmd.exe", "/c", "start", "", app], shell=False))
                elif _SYSTEM == "Darwin":
                    await asyncio.get_running_loop().run_in_executor(None, lambda: subprocess.Popen(["open", "-a", app]))
                else:
                    await asyncio.get_running_loop().run_in_executor(None, lambda: subprocess.Popen([app]))
                return ToolResult(True, self.name, {"app": app, "env": "desktop"},
                                  duration_ms=(time.time() - t0) * 1000)
        except Exception as exc:
            return ToolResult(False, self.name, error=str(exc), duration_ms=(time.time() - t0) * 1000)


class CloseAppTool(BaseTool):
    name = "close_app"
    description = "Close/terminate an application."
    parameters = {"type": "object", "properties": {
        "app": {"type": "string"}, "mode": {"type": "string", "default": "auto"}},
        "required": ["app"]}

    async def execute(self, app: str, mode: str = "auto") -> ToolResult:
        t0 = time.time()
        try:
            env = mode if mode != "auto" else ("android" if _is_android_available() else "desktop")
            if env == "android":
                pkg = app if "." in app else _resolve_package(app)
                await asyncio.get_running_loop().run_in_executor(
                    None, lambda: _adb("shell", "am", "force-stop", pkg)
                )
                return ToolResult(True, self.name, {"app": app, "closed": True},
                                  duration_ms=(time.time() - t0) * 1000)
            else:
                if _SYSTEM == "Windows":
                    subprocess.run(["taskkill", "/IM", f"{app}.exe", "/F"], capture_output=True)
                elif _SYSTEM == "Darwin":
                    subprocess.run(["pkill", "-x", app], capture_output=True)
                else:
                    subprocess.run(["pkill", "-f", app], capture_output=True)
                return ToolResult(True, self.name, {"app": app, "closed": True},
                                  duration_ms=(time.time() - t0) * 1000)
        except Exception as exc:
            return ToolResult(False, self.name, error=str(exc), duration_ms=(time.time() - t0) * 1000)


class GetForegroundAppTool(BaseTool):
    name = "get_foreground_app"
    description = "Get the currently active foreground application."
    parameters = {"type": "object", "properties": {}}

    async def execute(self) -> ToolResult:
        t0 = time.time()
        try:
            if _is_android_available():
                out = await asyncio.get_running_loop().run_in_executor(
                    None, lambda: _adb("shell", "dumpsys", "activity", "activities", "|", "grep", "mResumedActivity")
                )
                return ToolResult(True, self.name, {"foreground": out, "env": "android"},
                                  duration_ms=(time.time() - t0) * 1000)
            else:
                if _SYSTEM == "Windows":
                    import ctypes
                    hwnd = ctypes.windll.user32.GetForegroundWindow()
                    length = ctypes.windll.user32.GetWindowTextLengthW(hwnd)
                    buf = ctypes.create_unicode_buffer(length + 1)
                    ctypes.windll.user32.GetWindowTextW(hwnd, buf, length + 1)
                    return ToolResult(True, self.name, {"foreground": buf.value, "env": "windows"},
                                      duration_ms=(time.time() - t0) * 1000)
                elif _SYSTEM == "Darwin":
                    out = subprocess.check_output(
                        ["osascript", "-e", "tell application \"System Events\" to get name of first process whose frontmost is true"],
                        text=True
                    ).strip()
                    return ToolResult(True, self.name, {"foreground": out, "env": "macos"},
                                      duration_ms=(time.time() - t0) * 1000)
                else:
                    out = subprocess.check_output(["xdotool", "getactivewindow", "getwindowname"], text=True).strip()
                    return ToolResult(True, self.name, {"foreground": out, "env": "linux"},
                                      duration_ms=(time.time() - t0) * 1000)
        except Exception as exc:
            return ToolResult(False, self.name, error=str(exc), duration_ms=(time.time() - t0) * 1000)


class ListRunningAppsTool(BaseTool):
    name = "list_running_apps"
    description = "List all currently running applications."
    parameters = {"type": "object", "properties": {}}

    async def execute(self) -> ToolResult:
        t0 = time.time()
        try:
            if _is_android_available():
                out = await asyncio.get_running_loop().run_in_executor(
                    None, lambda: _adb("shell", "ps", "-A")
                )
                procs = [line.split()[-1] for line in out.splitlines()[1:] if line.strip()]
                return ToolResult(True, self.name, {"apps": procs[:50], "count": len(procs)},
                                  duration_ms=(time.time() - t0) * 1000)
            else:
                import psutil
                procs = [{"name": p.name(), "pid": p.pid} for p in psutil.process_iter(["name", "pid"])
                         if p.info["name"]]
                return ToolResult(True, self.name, {"apps": procs[:50], "count": len(procs)},
                                  duration_ms=(time.time() - t0) * 1000)
        except Exception as exc:
            return ToolResult(False, self.name, error=str(exc), duration_ms=(time.time() - t0) * 1000)


class AutomateWorkflowTool(BaseTool):
    name = "automate_workflow"
    description = "Execute a sequence of automation steps as a named workflow."
    parameters = {"type": "object", "properties": {
        "name": {"type": "string"},
        "steps": {"type": "array", "items": {"type": "object"},
                  "description": "List of {action, args} steps. Actions: tap, type, swipe, wait, launch, close"}},
        "required": ["name", "steps"]}

    async def execute(self, name: str, steps: List[Dict[str, Any]]) -> ToolResult:
        t0 = time.time()
        log.info("Starting workflow: %s (%d steps)", name, len(steps))
        results = []
        for i, step in enumerate(steps):
            action = step.get("action", "")
            args = step.get("args", {})
            try:
                if action == "wait":
                    await asyncio.sleep(args.get("seconds", 1))
                    results.append({"step": i, "action": "wait", "ok": True})
                elif action == "tap":
                    from .screen import TapCoordinatesTool
                    r = await TapCoordinatesTool().execute(**args)
                    results.append({"step": i, "action": action, "ok": r.success})
                elif action == "type":
                    from .keyboard import TypeTextTool
                    r = await TypeTextTool().execute(**args)
                    results.append({"step": i, "action": action, "ok": r.success})
                elif action == "swipe":
                    from .screen import SwipeTool
                    r = await SwipeTool().execute(**args)
                    results.append({"step": i, "action": action, "ok": r.success})
                elif action == "launch":
                    r = await LaunchAppTool().execute(**args)
                    results.append({"step": i, "action": action, "ok": r.success})
                elif action == "close":
                    r = await CloseAppTool().execute(**args)
                    results.append({"step": i, "action": action, "ok": r.success})
                else:
                    results.append({"step": i, "action": action, "ok": False, "error": "Unknown action"})
            except Exception as e:
                results.append({"step": i, "action": action, "ok": False, "error": str(e)})
        success_count = sum(1 for r in results if r.get("ok"))
        return ToolResult(True, self.name, {
            "workflow": name, "steps_total": len(steps),
            "steps_ok": success_count, "results": results
        }, duration_ms=(time.time() - t0) * 1000)


def _is_android_available() -> bool:
    try:
        out = subprocess.run([_ADB, "devices"], capture_output=True, text=True, timeout=3)
        lines = out.stdout.strip().splitlines()
        return len(lines) > 1 and any("device" in l for l in lines[1:])
    except Exception:
        return False


def _resolve_package(app_name: str) -> str:
    """Map common app names to Android package names."""
    packages = {
        "chrome": "com.android.chrome", "firefox": "org.mozilla.firefox",
        "gmail": "com.google.android.gm", "maps": "com.google.android.apps.maps",
        "youtube": "com.google.android.youtube", "whatsapp": "com.whatsapp",
        "telegram": "org.telegram.messenger", "discord": "com.discord",
        "spotify": "com.spotify.music", "camera": "com.android.camera2",
        "settings": "com.android.settings", "calculator": "com.android.calculator2",
        "contacts": "com.android.contacts", "messages": "com.android.messaging",
        "photos": "com.google.android.apps.photos", "drive": "com.google.android.apps.docs",
    }
    return packages.get(app_name.lower(), app_name)

"""Device settings control — Wi-Fi, Bluetooth, volume, brightness, DND, battery saver, etc."""
from __future__ import annotations
import asyncio, logging, os, subprocess, time
from ..tools.base import BaseTool, ToolResult

log = logging.getLogger(__name__)
_ADB = os.getenv("ADB_PATH", "adb")


def _adb(*args) -> str:
    result = subprocess.run([_ADB] + list(args), capture_output=True, text=True, timeout=10)
    if result.returncode != 0 and result.stderr:
        log.warning("ADB: %s", result.stderr.strip())
    return result.stdout.strip()


async def _adb_async(*args) -> str:
    loop = asyncio.get_running_loop()
    return await loop.run_in_executor(None, lambda: _adb(*args))


class SetWifiTool(BaseTool):
    name = "set_wifi"
    description = "Enable or disable Wi-Fi, or connect to a Wi-Fi network."
    parameters = {"type": "object", "properties": {
        "enabled": {"type": "boolean"},
        "ssid": {"type": "string", "description": "Network SSID to connect to"},
        "password": {"type": "string", "description": "Network password"}}}

    async def execute(self, enabled: bool = None, ssid: str = None, password: str = None) -> ToolResult:
        t0 = time.time()
        try:
            if enabled is not None:
                state = "enable" if enabled else "disable"
                await _adb_async("shell", "svc", "wifi", state)
            if ssid:
                # Connect to network (requires root or pre-configured network)
                cmd = f"cmd wifi connect-network {ssid} open" if not password else \
                      f"cmd wifi connect-network {ssid} wpa2 {password}"
                await _adb_async("shell", cmd)
            return ToolResult(True, self.name, {
                "wifi_enabled": enabled, "ssid": ssid
            }, duration_ms=(time.time() - t0) * 1000)
        except Exception as exc:
            return ToolResult(False, self.name, error=str(exc), duration_ms=(time.time() - t0) * 1000)


class SetBluetoothTool(BaseTool):
    name = "set_bluetooth"
    description = "Enable or disable Bluetooth."
    parameters = {"type": "object", "properties": {
        "enabled": {"type": "boolean"}}, "required": ["enabled"]}

    async def execute(self, enabled: bool) -> ToolResult:
        t0 = time.time()
        try:
            state = "enable" if enabled else "disable"
            await _adb_async("shell", "svc", "bluetooth", state)
            return ToolResult(True, self.name, {"bluetooth_enabled": enabled},
                              duration_ms=(time.time() - t0) * 1000)
        except Exception as exc:
            return ToolResult(False, self.name, error=str(exc), duration_ms=(time.time() - t0) * 1000)


class SetVolumeTool(BaseTool):
    name = "set_volume"
    description = "Set device volume level (0–15) for media, ring, alarm, or notification streams."
    parameters = {"type": "object", "properties": {
        "level": {"type": "integer", "minimum": 0, "maximum": 15},
        "stream": {"type": "string",
                   "enum": ["media", "ring", "alarm", "notification", "system"],
                   "default": "media"}},
        "required": ["level"]}

    _STREAM_IDS = {"media": 3, "ring": 2, "alarm": 4, "notification": 5, "system": 1}

    async def execute(self, level: int, stream: str = "media") -> ToolResult:
        t0 = time.time()
        try:
            stream_id = self._STREAM_IDS.get(stream, 3)
            await _adb_async("shell", "media", "volume", "--set", str(level), "--stream", str(stream_id))
            return ToolResult(True, self.name, {"level": level, "stream": stream},
                              duration_ms=(time.time() - t0) * 1000)
        except Exception as exc:
            return ToolResult(False, self.name, error=str(exc), duration_ms=(time.time() - t0) * 1000)


class SetBrightnessTool(BaseTool):
    name = "set_brightness"
    description = "Set screen brightness (0–255) or toggle adaptive/auto brightness."
    parameters = {"type": "object", "properties": {
        "level": {"type": "integer", "minimum": 0, "maximum": 255,
                  "description": "Brightness level 0-255, or -1 for adaptive"},
        "adaptive": {"type": "boolean", "default": False}}}

    async def execute(self, level: int = 128, adaptive: bool = False) -> ToolResult:
        t0 = time.time()
        try:
            if adaptive:
                await _adb_async("shell", "settings", "put", "system", "screen_brightness_mode", "1")
            else:
                await _adb_async("shell", "settings", "put", "system", "screen_brightness_mode", "0")
                await _adb_async("shell", "settings", "put", "system", "screen_brightness", str(level))
            return ToolResult(True, self.name, {"brightness": level, "adaptive": adaptive},
                              duration_ms=(time.time() - t0) * 1000)
        except Exception as exc:
            return ToolResult(False, self.name, error=str(exc), duration_ms=(time.time() - t0) * 1000)


class ToggleFlashlightTool(BaseTool):
    name = "toggle_flashlight"
    description = "Turn the device flashlight on or off."
    parameters = {"type": "object", "properties": {
        "on": {"type": "boolean"}}, "required": ["on"]}

    async def execute(self, on: bool) -> ToolResult:
        t0 = time.time()
        try:
            # Android 6+ supports this via ADB
            state = "1" if on else "0"
            await _adb_async("shell", "settings", "put", "global", "torch", state)
            # Alternative via input key
            if on:
                await _adb_async("shell", "cmd", "media", "volume", "--set", "0", "--stream", "0")
            return ToolResult(True, self.name, {"flashlight": "on" if on else "off"},
                              duration_ms=(time.time() - t0) * 1000)
        except Exception as exc:
            return ToolResult(False, self.name, error=str(exc), duration_ms=(time.time() - t0) * 1000)


class SetDoNotDisturbTool(BaseTool):
    name = "set_do_not_disturb"
    description = "Enable or disable Do Not Disturb mode."
    parameters = {"type": "object", "properties": {
        "enabled": {"type": "boolean"},
        "duration_minutes": {"type": "integer", "description": "Auto-disable after N minutes (0 = indefinite)"}},
        "required": ["enabled"]}

    async def execute(self, enabled: bool, duration_minutes: int = 0) -> ToolResult:
        t0 = time.time()
        try:
            mode = "1" if enabled else "0"
            await _adb_async("shell", "settings", "put", "global", "zen_mode", mode)
            log.info("DND set to %s (duration=%d min)", "on" if enabled else "off", duration_minutes)
            return ToolResult(True, self.name, {
                "dnd_enabled": enabled, "duration_minutes": duration_minutes
            }, duration_ms=(time.time() - t0) * 1000)
        except Exception as exc:
            return ToolResult(False, self.name, error=str(exc), duration_ms=(time.time() - t0) * 1000)


class SetBatterySaverTool(BaseTool):
    name = "set_battery_saver"
    description = "Enable or disable Battery Saver mode."
    parameters = {"type": "object", "properties": {
        "enabled": {"type": "boolean"}}, "required": ["enabled"]}

    async def execute(self, enabled: bool) -> ToolResult:
        t0 = time.time()
        try:
            state = "1" if enabled else "0"
            await _adb_async("shell", "settings", "put", "global", "low_power", state)
            return ToolResult(True, self.name, {"battery_saver": enabled},
                              duration_ms=(time.time() - t0) * 1000)
        except Exception as exc:
            return ToolResult(False, self.name, error=str(exc), duration_ms=(time.time() - t0) * 1000)


class SetRotationLockTool(BaseTool):
    name = "set_rotation_lock"
    description = "Enable or disable screen rotation lock."
    parameters = {"type": "object", "properties": {
        "locked": {"type": "boolean"}}, "required": ["locked"]}

    async def execute(self, locked: bool) -> ToolResult:
        t0 = time.time()
        try:
            # 0 = auto-rotate, 1 = locked
            val = "1" if locked else "0"
            await _adb_async("shell", "settings", "put", "system", "accelerometer_rotation",
                             "0" if locked else "1")
            await _adb_async("shell", "settings", "put", "system", "user_rotation", val)
            return ToolResult(True, self.name, {"rotation_locked": locked},
                              duration_ms=(time.time() - t0) * 1000)
        except Exception as exc:
            return ToolResult(False, self.name, error=str(exc), duration_ms=(time.time() - t0) * 1000)


class SetAirplaneModeT(BaseTool):
    """Airplane mode toggle (requires root on most Android versions)."""
    name = "set_airplane_mode"
    description = "Enable or disable Airplane mode (requires device root)."
    parameters = {"type": "object", "properties": {
        "enabled": {"type": "boolean"}}, "required": ["enabled"]}

    async def execute(self, enabled: bool) -> ToolResult:
        t0 = time.time()
        try:
            state = "1" if enabled else "0"
            await _adb_async("shell", "settings", "put", "global", "airplane_mode_on", state)
            await _adb_async("shell", "am", "broadcast", "-a",
                             "android.intent.action.AIRPLANE_MODE", "--ez", "state", "true" if enabled else "false")
            return ToolResult(True, self.name, {"airplane_mode": enabled},
                              duration_ms=(time.time() - t0) * 1000)
        except Exception as exc:
            return ToolResult(False, self.name, error=str(exc), duration_ms=(time.time() - t0) * 1000)


class GetDeviceInfoTool(BaseTool):
    name = "get_device_info"
    description = "Get current device status: battery, network, memory, storage, display."
    parameters = {"type": "object", "properties": {}}

    async def execute(self) -> ToolResult:
        t0 = time.time()
        try:
            battery = await _adb_async("shell", "dumpsys", "battery", "|", "grep", "level")
            wifi = await _adb_async("shell", "settings", "get", "global", "wifi_on")
            bt = await _adb_async("shell", "settings", "get", "global", "bluetooth_on")
            brightness = await _adb_async("shell", "settings", "get", "system", "screen_brightness")
            dnd = await _adb_async("shell", "settings", "get", "global", "zen_mode")
            battery_saver = await _adb_async("shell", "settings", "get", "global", "low_power")
            return ToolResult(True, self.name, {
                "battery_raw": battery.strip(),
                "wifi_on": wifi.strip() == "1",
                "bluetooth_on": bt.strip() == "1",
                "brightness": brightness.strip(),
                "do_not_disturb": dnd.strip() == "1",
                "battery_saver": battery_saver.strip() == "1"
            }, duration_ms=(time.time() - t0) * 1000)
        except Exception as exc:
            return ToolResult(False, self.name, error=str(exc), duration_ms=(time.time() - t0) * 1000)

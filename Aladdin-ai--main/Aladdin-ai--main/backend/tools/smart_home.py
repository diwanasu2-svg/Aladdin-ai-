"""Smart Home tool — control lights, plugs, thermostats, locks, cameras via Tuya, Hue, Google Home."""
from __future__ import annotations
import asyncio, logging, os, time
from typing import Any, Dict, List, Optional
from .base import BaseTool, ToolResult

log = logging.getLogger(__name__)


# ─── Philips Hue helpers ─────────────────────────────────────────────────────

def _hue_bridge():
    try:
        from phue import Bridge
        ip = os.getenv("HUE_BRIDGE_IP", "")
        if not ip:
            raise RuntimeError("HUE_BRIDGE_IP not set")
        return Bridge(ip)
    except ImportError:
        raise RuntimeError("phue not installed — run: pip install phue")


# ─── Tuya helpers ─────────────────────────────────────────────────────────────

def _tuya_device(device_id: str):
    try:
        import tinytuya
        key = os.getenv(f"TUYA_KEY_{device_id.upper()}", os.getenv("TUYA_LOCAL_KEY", ""))
        ip = os.getenv(f"TUYA_IP_{device_id.upper()}", "")
        if not ip or not key:
            raise RuntimeError(f"TUYA_IP_* and TUYA_LOCAL_KEY env vars required for device {device_id}")
        d = tinytuya.OutletDevice(device_id, ip, key)
        d.set_version(3.3)
        return d
    except ImportError:
        raise RuntimeError("tinytuya not installed — run: pip install tinytuya")


class ControlLightTool(BaseTool):
    name = "control_light"
    description = "Control a smart light — turn on/off, set brightness, change color."
    parameters = {"type": "object", "properties": {
        "light_name_or_id": {"type": "string", "description": "Light name or ID"},
        "action": {"type": "string", "enum": ["on", "off", "toggle"]},
        "brightness": {"type": "integer", "minimum": 0, "maximum": 254},
        "color_rgb": {"type": "array", "items": {"type": "integer"}, "description": "[R, G, B] 0-255"},
        "provider": {"type": "string", "enum": ["hue", "tuya"], "default": "hue"}},
        "required": ["light_name_or_id", "action"]}

    async def execute(self, light_name_or_id: str, action: str,
                      brightness: int = None, color_rgb: List[int] = None,
                      provider: str = "hue") -> ToolResult:
        t0 = time.time()
        try:
            if provider == "hue":
                def _hue():
                    b = _hue_bridge()
                    lights = b.get_light_objects("name")
                    light = lights.get(light_name_or_id)
                    if not light:
                        raise RuntimeError(f"Light '{light_name_or_id}' not found")
                    if action == "on":
                        b.set_light(light_name_or_id, "on", True)
                    elif action == "off":
                        b.set_light(light_name_or_id, "on", False)
                    elif action == "toggle":
                        state = b.get_light(light_name_or_id, "on")
                        b.set_light(light_name_or_id, "on", not state)
                    if brightness is not None:
                        b.set_light(light_name_or_id, "bri", brightness)
                    if color_rgb:
                        from phue import PhueRequestTimeout
                        r, g, bl_ = color_rgb
                        # Convert RGB to hue/sat
                        import colorsys
                        h, s, v = colorsys.rgb_to_hsv(r/255, g/255, bl_/255)
                        b.set_light(light_name_or_id, {"hue": int(h * 65535),
                                                        "sat": int(s * 254), "bri": int(v * 254)})
                    return {"light": light_name_or_id, "action": action}
                result = await asyncio.get_running_loop().run_in_executor(None, _hue)
            else:
                def _tuya():
                    d = _tuya_device(light_name_or_id)
                    if action == "on":
                        d.turn_on()
                    elif action == "off":
                        d.turn_off()
                    elif action == "toggle":
                        status = d.status()
                        on = status.get("dps", {}).get("1", False)
                        d.turn_off() if on else d.turn_on()
                    if brightness is not None:
                        d.set_value(2, brightness)
                    return {"device": light_name_or_id, "action": action}
                result = await asyncio.get_running_loop().run_in_executor(None, _tuya)
            return ToolResult(True, self.name, result, duration_ms=(time.time() - t0) * 1000)
        except Exception as exc:
            return ToolResult(False, self.name, error=str(exc), duration_ms=(time.time() - t0) * 1000)


class GetSmartDeviceStatusTool(BaseTool):
    name = "get_smart_device_status"
    description = "Get the current status of a smart home device."
    parameters = {"type": "object", "properties": {
        "device_id": {"type": "string"},
        "provider": {"type": "string", "enum": ["hue", "tuya"], "default": "tuya"}},
        "required": ["device_id"]}

    async def execute(self, device_id: str, provider: str = "tuya") -> ToolResult:
        t0 = time.time()
        try:
            if provider == "hue":
                def _hue_status():
                    b = _hue_bridge()
                    return b.get_light(device_id)
                status = await asyncio.get_running_loop().run_in_executor(None, _hue_status)
            else:
                def _tuya_status():
                    d = _tuya_device(device_id)
                    return d.status()
                status = await asyncio.get_running_loop().run_in_executor(None, _tuya_status)
            return ToolResult(True, self.name, {"device_id": device_id, "status": status},
                              duration_ms=(time.time() - t0) * 1000)
        except Exception as exc:
            return ToolResult(False, self.name, error=str(exc), duration_ms=(time.time() - t0) * 1000)


class ControlThermostatTool(BaseTool):
    name = "control_thermostat"
    description = "Set thermostat temperature or mode (heat/cool/auto/off)."
    parameters = {"type": "object", "properties": {
        "device_id": {"type": "string"},
        "temperature": {"type": "number", "description": "Target temperature in Celsius"},
        "mode": {"type": "string", "enum": ["heat", "cool", "auto", "off"]},
        "provider": {"type": "string", "default": "tuya"}},
        "required": ["device_id"]}

    async def execute(self, device_id: str, temperature: float = None,
                      mode: str = None, provider: str = "tuya") -> ToolResult:
        t0 = time.time()
        try:
            def _set():
                d = _tuya_device(device_id)
                if mode:
                    d.set_value(4, mode)
                if temperature is not None:
                    d.set_value(2, int(temperature * 10))
                return d.status()
            status = await asyncio.get_running_loop().run_in_executor(None, _set)
            return ToolResult(True, self.name, {
                "device_id": device_id, "temperature": temperature, "mode": mode, "status": status
            }, duration_ms=(time.time() - t0) * 1000)
        except Exception as exc:
            return ToolResult(False, self.name, error=str(exc), duration_ms=(time.time() - t0) * 1000)


class ControlSmartLockTool(BaseTool):
    name = "control_smart_lock"
    description = "Lock or unlock a smart door lock."
    parameters = {"type": "object", "properties": {
        "device_id": {"type": "string"},
        "action": {"type": "string", "enum": ["lock", "unlock"]},
        "provider": {"type": "string", "default": "tuya"}},
        "required": ["device_id", "action"]}

    async def execute(self, device_id: str, action: str, provider: str = "tuya") -> ToolResult:
        t0 = time.time()
        try:
            def _lock():
                d = _tuya_device(device_id)
                locked = action == "lock"
                d.set_value(1, locked)
                return {"device_id": device_id, "action": action, "locked": locked}
            result = await asyncio.get_running_loop().run_in_executor(None, _lock)
            return ToolResult(True, self.name, result, duration_ms=(time.time() - t0) * 1000)
        except Exception as exc:
            return ToolResult(False, self.name, error=str(exc), duration_ms=(time.time() - t0) * 1000)


class ControlSmartPlugTool(BaseTool):
    name = "control_smart_plug"
    description = "Turn a smart plug on or off."
    parameters = {"type": "object", "properties": {
        "device_id": {"type": "string"},
        "action": {"type": "string", "enum": ["on", "off", "toggle"]},
        "provider": {"type": "string", "default": "tuya"}},
        "required": ["device_id", "action"]}

    async def execute(self, device_id: str, action: str, provider: str = "tuya") -> ToolResult:
        t0 = time.time()
        try:
            def _plug():
                d = _tuya_device(device_id)
                if action == "on":
                    d.turn_on()
                elif action == "off":
                    d.turn_off()
                elif action == "toggle":
                    s = d.status()
                    on = s.get("dps", {}).get("1", False)
                    d.turn_off() if on else d.turn_on()
                return {"device_id": device_id, "action": action}
            result = await asyncio.get_running_loop().run_in_executor(None, _plug)
            return ToolResult(True, self.name, result, duration_ms=(time.time() - t0) * 1000)
        except Exception as exc:
            return ToolResult(False, self.name, error=str(exc), duration_ms=(time.time() - t0) * 1000)


class ListSmartDevicesTool(BaseTool):
    name = "list_smart_devices"
    description = "List all registered smart home devices (Hue lights, Tuya devices, etc.)."
    parameters = {"type": "object", "properties": {
        "provider": {"type": "string", "enum": ["hue", "all"], "default": "hue"}}}

    async def execute(self, provider: str = "hue") -> ToolResult:
        t0 = time.time()
        try:
            devices = []
            if provider in ("hue", "all"):
                def _hue_list():
                    b = _hue_bridge()
                    lights = b.get_light_objects("id")
                    return [{"id": str(k), "name": v.name, "type": "hue_light",
                             "on": v.on, "brightness": v.brightness} for k, v in lights.items()]
                try:
                    devices.extend(await asyncio.get_running_loop().run_in_executor(None, _hue_list))
                except Exception as e:
                    log.warning("Hue discovery failed: %s", e)
            return ToolResult(True, self.name, {"devices": devices, "count": len(devices)},
                              duration_ms=(time.time() - t0) * 1000)
        except Exception as exc:
            return ToolResult(False, self.name, error=str(exc), duration_ms=(time.time() - t0) * 1000)


class CreateHomeRoutineTool(BaseTool):
    name = "create_home_routine"
    description = "Create a home automation routine (sequence of smart home actions)."
    parameters = {"type": "object", "properties": {
        "name": {"type": "string"},
        "trigger": {"type": "string", "description": "Trigger condition (e.g. 'morning', 'leave_home', '07:00')"},
        "actions": {"type": "array", "items": {"type": "object"},
                    "description": "List of {tool_name, args} action dicts"}},
        "required": ["name", "trigger", "actions"]}

    _routines: Dict[str, Any] = {}

    async def execute(self, name: str, trigger: str, actions: List[Dict]) -> ToolResult:
        CreateHomeRoutineTool._routines[name] = {
            "name": name, "trigger": trigger, "actions": actions,
            "created_at": time.time(), "enabled": True
        }
        log.info("Home routine '%s' created with %d actions", name, len(actions))
        return ToolResult(True, self.name, {"routine": name, "trigger": trigger, "action_count": len(actions)})

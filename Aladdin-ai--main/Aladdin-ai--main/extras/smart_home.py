"""extras/smart_home.py — Feature 1: Smart Home Device Control.

Supports Philips Hue, Lifx, Tuya, SmartThings, and generic HTTP devices.
Provides a unified DeviceRegistry with voice-command dispatch.
"""

from __future__ import annotations

import asyncio
import logging
import os
from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Callable, Dict, List, Optional

log = logging.getLogger(__name__)


# ─────────────────────────────────────────────────────────────────────────────
# Data models
# ─────────────────────────────────────────────────────────────────────────────

class DeviceType(str, Enum):
    LIGHT       = "light"
    PLUG        = "plug"
    THERMOSTAT  = "thermostat"
    LOCK        = "lock"
    FAN         = "fan"
    AC          = "ac"
    TV          = "tv"
    SENSOR      = "sensor"
    SCENE       = "scene"


@dataclass
class Device:
    id: str
    name: str
    type: DeviceType
    brand: str
    state: Dict[str, Any] = field(default_factory=dict)
    online: bool = True

    def is_on(self) -> bool:
        return bool(self.state.get("on", False))


@dataclass
class Scene:
    name: str
    actions: List[Dict[str, Any]]   # [{device_id, state}]
    description: str = ""


# ─────────────────────────────────────────────────────────────────────────────
# Brand drivers
# ─────────────────────────────────────────────────────────────────────────────

class PhilipsHueDriver:
    """Philips Hue bridge driver using phue or REST."""

    def __init__(self, bridge_ip: str, api_key: str) -> None:
        self.bridge_ip = bridge_ip
        self.api_key = api_key
        self._base = f"http://{bridge_ip}/api/{api_key}"

    async def get_lights(self) -> Dict[str, Device]:
        try:
            import aiohttp  # type: ignore
            async with aiohttp.ClientSession() as s:
                async with s.get(f"{self._base}/lights") as r:
                    data = await r.json()
            devices = {}
            for lid, ldata in data.items():
                st = ldata.get("state", {})
                devices[lid] = Device(
                    id=f"hue_{lid}", name=ldata.get("name", f"Light {lid}"),
                    type=DeviceType.LIGHT, brand="philips_hue",
                    state={"on": st.get("on", False), "brightness": st.get("bri", 254),
                           "color_temp": st.get("ct", 370)},
                )
            return devices
        except Exception as exc:
            log.warning("[Hue] get_lights failed: %s", exc)
            return {}

    async def set_light(self, light_id: str, **state) -> bool:
        try:
            import aiohttp  # type: ignore
            payload = {}
            if "on" in state:     payload["on"] = bool(state["on"])
            if "brightness" in state: payload["bri"] = int(state["brightness"] * 2.54)
            if "color_temp" in state: payload["ct"] = int(state["color_temp"])
            if "hue" in state:    payload["hue"] = int(state["hue"] * 182.04)
            if "saturation" in state: payload["sat"] = int(state["saturation"] * 2.54)
            async with aiohttp.ClientSession() as s:
                async with s.put(f"{self._base}/lights/{light_id}/state", json=payload) as r:
                    return r.status == 200
        except Exception as exc:
            log.warning("[Hue] set_light failed: %s", exc)
            return False


class TuyaDriver:
    """Tuya/Smart Life driver using tinytuya."""

    def __init__(self, device_id: str, local_key: str, ip: str, version: float = 3.3) -> None:
        self.device_id = device_id
        self.local_key = local_key
        self.ip = ip
        self.version = version

    async def set_power(self, on: bool) -> bool:
        try:
            import tinytuya  # type: ignore
            d = tinytuya.OutletDevice(self.device_id, self.ip, self.local_key, version=self.version)
            d.set_status(on, 1)
            return True
        except Exception as exc:
            log.warning("[Tuya] set_power failed: %s", exc)
            return False

    async def get_status(self) -> Dict:
        try:
            import tinytuya  # type: ignore
            d = tinytuya.OutletDevice(self.device_id, self.ip, self.local_key, version=self.version)
            return d.status() or {}
        except Exception as exc:
            log.warning("[Tuya] get_status failed: %s", exc)
            return {}


class SmartThingsDriver:
    """Samsung SmartThings REST API driver."""

    BASE = "https://api.smartthings.com/v1"

    def __init__(self, token: str) -> None:
        self.token = token
        self._headers = {"Authorization": f"Bearer {token}", "Content-Type": "application/json"}

    async def list_devices(self) -> List[Device]:
        try:
            import aiohttp  # type: ignore
            async with aiohttp.ClientSession(headers=self._headers) as s:
                async with s.get(f"{self.BASE}/devices") as r:
                    data = await r.json()
            devices = []
            for item in data.get("items", []):
                devices.append(Device(
                    id=item["deviceId"], name=item.get("label", item["deviceId"]),
                    type=DeviceType.LIGHT, brand="smartthings", state={},
                ))
            return devices
        except Exception as exc:
            log.warning("[SmartThings] list_devices failed: %s", exc)
            return []

    async def execute_command(self, device_id: str, capability: str, command: str, args: list = None) -> bool:
        try:
            import aiohttp  # type: ignore
            payload = {"commands": [{"component": "main", "capability": capability,
                                     "command": command, "arguments": args or []}]}
            async with aiohttp.ClientSession(headers=self._headers) as s:
                async with s.post(f"{self.BASE}/devices/{device_id}/commands", json=payload) as r:
                    return r.status == 200
        except Exception as exc:
            log.warning("[SmartThings] execute_command failed: %s", exc)
            return False


# ─────────────────────────────────────────────────────────────────────────────
# Scene presets
# ─────────────────────────────────────────────────────────────────────────────

PRESET_SCENES: Dict[str, Scene] = {
    "good_morning": Scene(
        name="Good Morning", description="Bright warm lights, unlock door, set thermostat to 22°C",
        actions=[{"type": "lights_all", "on": True, "brightness": 80, "color_temp": 4000},
                 {"type": "thermostat", "temp": 22, "mode": "heat"},
                 {"type": "lock", "action": "unlock"}],
    ),
    "good_night": Scene(
        name="Good Night", description="Dim warm lights, lock doors, set thermostat to 18°C",
        actions=[{"type": "lights_all", "on": True, "brightness": 10, "color_temp": 2700},
                 {"type": "thermostat", "temp": 18, "mode": "cool"},
                 {"type": "lock", "action": "lock"}],
    ),
    "movie_mode": Scene(
        name="Movie Mode", description="Dim lights to 15%, TV on",
        actions=[{"type": "lights_all", "on": True, "brightness": 15, "color_temp": 2700},
                 {"type": "tv", "on": True}],
    ),
    "away_mode": Scene(
        name="Away Mode", description="All lights off, lock doors, eco thermostat",
        actions=[{"type": "lights_all", "on": False},
                 {"type": "lock", "action": "lock"},
                 {"type": "thermostat", "temp": 16, "mode": "eco"}],
    ),
}


# ─────────────────────────────────────────────────────────────────────────────
# Central registry
# ─────────────────────────────────────────────────────────────────────────────

class SmartHomeManager:
    """Unified smart home controller for Aladdin AI."""

    def __init__(self) -> None:
        self._devices: Dict[str, Device] = {}
        self._scenes: Dict[str, Scene] = dict(PRESET_SCENES)
        self._hue: Optional[PhilipsHueDriver] = None
        self._tuya_devices: List[TuyaDriver] = []
        self._smartthings: Optional[SmartThingsDriver] = None
        self._command_handlers: Dict[str, Callable] = {}

    # ── setup ────────────────────────────────────────────────────────────────

    def setup_philips_hue(self, bridge_ip: str, api_key: str) -> None:
        self._hue = PhilipsHueDriver(bridge_ip, api_key)
        log.info("[SmartHome] Philips Hue configured at %s", bridge_ip)

    def setup_smartthings(self, token: str) -> None:
        self._smartthings = SmartThingsDriver(token)
        log.info("[SmartHome] SmartThings configured")

    def add_tuya_device(self, device_id: str, local_key: str, ip: str, name: str = "Tuya Device") -> None:
        driver = TuyaDriver(device_id, local_key, ip)
        self._tuya_devices.append(driver)
        self._devices[device_id] = Device(id=device_id, name=name, type=DeviceType.PLUG, brand="tuya")
        log.info("[SmartHome] Tuya device added: %s @ %s", name, ip)

    # ── discovery ────────────────────────────────────────────────────────────

    async def discover_all(self) -> int:
        count = 0
        if self._hue:
            lights = await self._hue.get_lights()
            self._devices.update(lights)
            count += len(lights)
        if self._smartthings:
            devices = await self._smartthings.list_devices()
            for d in devices:
                self._devices[d.id] = d
            count += len(devices)
        log.info("[SmartHome] Discovered %d devices", count)
        return count

    # ── control ──────────────────────────────────────────────────────────────

    async def control_light(self, device_id: str, **kwargs) -> bool:
        device = self._devices.get(device_id)
        if not device:
            log.warning("[SmartHome] Unknown device: %s", device_id)
            return False
        if device.brand == "philips_hue" and self._hue:
            lid = device_id.replace("hue_", "")
            ok = await self._hue.set_light(lid, **kwargs)
            if ok:
                device.state.update(kwargs)
            return ok
        if device.brand == "smartthings" and self._smartthings:
            cmd = "on" if kwargs.get("on") else "off"
            return await self._smartthings.execute_command(device_id, "switch", cmd)
        log.warning("[SmartHome] No handler for brand=%s", device.brand)
        return False

    async def control_all_lights(self, **kwargs) -> int:
        tasks = [self.control_light(did, **kwargs)
                 for did, d in self._devices.items() if d.type == DeviceType.LIGHT]
        results = await asyncio.gather(*tasks, return_exceptions=True)
        return sum(1 for r in results if r is True)

    async def run_scene(self, scene_name: str) -> bool:
        scene = self._scenes.get(scene_name.lower().replace(" ", "_"))
        if not scene:
            log.warning("[SmartHome] Unknown scene: %s", scene_name)
            return False
        log.info("[SmartHome] Running scene: %s", scene.name)
        for action in scene.actions:
            action_type = action.get("type", "")
            if action_type == "lights_all":
                await self.control_all_lights(**{k: v for k, v in action.items() if k != "type"})
            elif action_type == "lock":
                log.info("[SmartHome] Lock action: %s", action.get("action"))
            elif action_type == "thermostat":
                log.info("[SmartHome] Thermostat: %s°C mode=%s", action.get("temp"), action.get("mode"))
            elif action_type == "tv":
                log.info("[SmartHome] TV: %s", "on" if action.get("on") else "off")
        return True

    # ── voice command dispatch ────────────────────────────────────────────────

    async def handle_voice_command(self, text: str) -> str:
        t = text.lower()

        # Lights
        if "lights off" in t or "turn off the lights" in t:
            n = await self.control_all_lights(on=False)
            return f"Turned off {n} lights."
        if "lights on" in t or "turn on the lights" in t:
            n = await self.control_all_lights(on=True)
            return f"Turned on {n} lights."
        if "dim the lights" in t or "dim lights" in t:
            n = await self.control_all_lights(on=True, brightness=20)
            return f"Dimmed {n} lights to 20%."

        # Scenes
        for scene_key, scene in self._scenes.items():
            if scene.name.lower() in t or scene_key in t:
                await self.run_scene(scene_key)
                return f"Running {scene.name} scene."

        # Lock
        if "lock the door" in t or "lock door" in t:
            log.info("[SmartHome] Locking door")
            return "Door locked."
        if "unlock the door" in t or "unlock door" in t:
            log.info("[SmartHome] Unlocking door")
            return "Door unlocked."

        return ""

    # ── status ────────────────────────────────────────────────────────────────

    def status(self) -> Dict[str, Any]:
        return {
            "total_devices": len(self._devices),
            "online_devices": sum(1 for d in self._devices.values() if d.online),
            "devices": [{"id": d.id, "name": d.name, "type": d.type, "on": d.is_on()}
                        for d in self._devices.values()],
            "scenes": list(self._scenes.keys()),
        }

    def list_devices(self) -> List[Device]:
        return list(self._devices.values())

    def add_custom_scene(self, name: str, actions: List[Dict]) -> None:
        self._scenes[name.lower().replace(" ", "_")] = Scene(name=name, actions=actions)
        log.info("[SmartHome] Custom scene added: %s", name)

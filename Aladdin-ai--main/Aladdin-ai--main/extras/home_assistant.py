"""extras/home_assistant.py — Feature 2: Home Assistant Integration.

Full WebSocket + REST integration with HA. Supports:
- Entity discovery (lights, sensors, switches, covers, climate)
- Real-time state updates via WebSocket
- Service calls and automation triggers
- Long-lived access token authentication
"""

from __future__ import annotations

import asyncio
import json
import logging
import time
from dataclasses import dataclass, field
from typing import Any, Callable, Dict, List, Optional, Set

log = logging.getLogger(__name__)


@dataclass
class HAEntity:
    entity_id: str
    state: str
    attributes: Dict[str, Any] = field(default_factory=dict)
    domain: str = ""
    last_updated: float = field(default_factory=time.time)

    def __post_init__(self):
        self.domain = self.entity_id.split(".")[0] if "." in self.entity_id else "unknown"

    @property
    def friendly_name(self) -> str:
        return self.attributes.get("friendly_name", self.entity_id)

    @property
    def is_on(self) -> bool:
        return self.state.lower() in ("on", "open", "unlocked", "home", "playing")


class HomeAssistantClient:
    """Home Assistant client combining REST + WebSocket."""

    def __init__(
        self,
        host: str,
        token: str,
        port: int = 8123,
        use_ssl: bool = False,
    ) -> None:
        scheme = "https" if use_ssl else "http"
        self.base_url = f"{scheme}://{host}:{port}"
        self.ws_url = f"{'wss' if use_ssl else 'ws'}://{host}:{port}/api/websocket"
        self.token = token
        self._headers = {"Authorization": f"Bearer {token}", "Content-Type": "application/json"}
        self._entities: Dict[str, HAEntity] = {}
        self._ws = None
        self._ws_id = 1
        self._event_listeners: Dict[str, List[Callable]] = {}
        self._connected = False
        self._pending: Dict[int, asyncio.Future] = {}

    # ── REST API ─────────────────────────────────────────────────────────────

    async def _get(self, path: str) -> Any:
        try:
            import aiohttp  # type: ignore
            async with aiohttp.ClientSession(headers=self._headers) as s:
                async with s.get(f"{self.base_url}/api{path}") as r:
                    r.raise_for_status()
                    return await r.json()
        except Exception as exc:
            log.error("[HA] GET %s failed: %s", path, exc)
            return None

    async def _post(self, path: str, data: Dict = None) -> Any:
        try:
            import aiohttp  # type: ignore
            async with aiohttp.ClientSession(headers=self._headers) as s:
                async with s.post(f"{self.base_url}/api{path}", json=data or {}) as r:
                    r.raise_for_status()
                    return await r.json()
        except Exception as exc:
            log.error("[HA] POST %s failed: %s", path, exc)
            return None

    async def ping(self) -> bool:
        result = await self._get("/")
        return result is not None and "message" in str(result)

    async def get_states(self) -> List[HAEntity]:
        data = await self._get("/states")
        if not data:
            return []
        entities = []
        for item in data:
            e = HAEntity(
                entity_id=item["entity_id"],
                state=item["state"],
                attributes=item.get("attributes", {}),
            )
            self._entities[e.entity_id] = e
            entities.append(e)
        log.info("[HA] Loaded %d entities", len(entities))
        return entities

    async def get_entity(self, entity_id: str) -> Optional[HAEntity]:
        data = await self._get(f"/states/{entity_id}")
        if not data:
            return self._entities.get(entity_id)
        e = HAEntity(entity_id=data["entity_id"], state=data["state"],
                     attributes=data.get("attributes", {}))
        self._entities[entity_id] = e
        return e

    async def call_service(self, domain: str, service: str, data: Dict = None) -> bool:
        result = await self._post(f"/services/{domain}/{service}", data or {})
        return result is not None

    async def turn_on(self, entity_id: str, **kwargs) -> bool:
        return await self.call_service("homeassistant", "turn_on",
                                       {"entity_id": entity_id, **kwargs})

    async def turn_off(self, entity_id: str) -> bool:
        return await self.call_service("homeassistant", "turn_off", {"entity_id": entity_id})

    async def toggle(self, entity_id: str) -> bool:
        return await self.call_service("homeassistant", "toggle", {"entity_id": entity_id})

    async def trigger_automation(self, automation_id: str) -> bool:
        return await self.call_service("automation", "trigger", {"entity_id": automation_id})

    async def set_light(self, entity_id: str, brightness_pct: int = None,
                        color_temp: int = None, rgb_color: list = None) -> bool:
        data: Dict[str, Any] = {"entity_id": entity_id}
        if brightness_pct is not None:
            data["brightness_pct"] = max(0, min(100, brightness_pct))
        if color_temp is not None:
            data["color_temp"] = color_temp
        if rgb_color:
            data["rgb_color"] = rgb_color
        return await self.call_service("light", "turn_on", data)

    async def set_climate(self, entity_id: str, temperature: float = None,
                           hvac_mode: str = None) -> bool:
        data: Dict[str, Any] = {"entity_id": entity_id}
        if temperature is not None:
            data["temperature"] = temperature
        if hvac_mode:
            data["hvac_mode"] = hvac_mode
        return await self.call_service("climate", "set_temperature", data)

    # ── WebSocket ────────────────────────────────────────────────────────────

    async def connect_websocket(self) -> None:
        try:
            import websockets  # type: ignore
        except ImportError:
            log.warning("[HA] websockets not installed — real-time updates unavailable")
            return

        async def _ws_loop():
            try:
                async with websockets.connect(self.ws_url) as ws:
                    self._ws = ws
                    # Auth handshake
                    msg = json.loads(await ws.recv())
                    if msg.get("type") == "auth_required":
                        await ws.send(json.dumps({"type": "auth", "access_token": self.token}))
                        auth_result = json.loads(await ws.recv())
                        if auth_result.get("type") == "auth_ok":
                            self._connected = True
                            log.info("[HA] WebSocket authenticated")
                        else:
                            log.error("[HA] WebSocket auth failed")
                            return

                    # Subscribe to state changes
                    sub_id = self._next_id()
                    await ws.send(json.dumps({"id": sub_id, "type": "subscribe_events",
                                              "event_type": "state_changed"}))

                    async for raw in ws:
                        try:
                            msg = json.loads(raw)
                            await self._handle_ws_message(msg)
                        except Exception as exc:
                            log.debug("[HA] WS parse error: %s", exc)
            except Exception as exc:
                log.error("[HA] WebSocket disconnected: %s", exc)
                self._connected = False

        asyncio.create_task(_ws_loop())

    async def _handle_ws_message(self, msg: Dict) -> None:
        msg_type = msg.get("type", "")
        if msg_type == "event":
            event = msg.get("event", {})
            data = event.get("data", {})
            entity_id = data.get("entity_id", "")
            new_state = data.get("new_state", {})
            if entity_id and new_state:
                e = HAEntity(entity_id=entity_id, state=new_state.get("state", ""),
                             attributes=new_state.get("attributes", {}))
                self._entities[entity_id] = e
                # Fire callbacks
                for cb in self._event_listeners.get("state_changed", []):
                    try:
                        await asyncio.coroutine(cb)(e) if asyncio.iscoroutinefunction(cb) else cb(e)
                    except Exception:
                        pass
        elif msg_type == "result" and msg.get("id") in self._pending:
            fut = self._pending.pop(msg["id"])
            if not fut.done():
                fut.set_result(msg.get("result"))

    def _next_id(self) -> int:
        self._ws_id += 1
        return self._ws_id

    def on_state_change(self, callback: Callable[[HAEntity], None]) -> None:
        self._event_listeners.setdefault("state_changed", []).append(callback)

    # ── Discovery ─────────────────────────────────────────────────────────────

    async def discover_devices(self) -> Dict[str, List[HAEntity]]:
        await self.get_states()
        domains: Dict[str, List[HAEntity]] = {}
        for e in self._entities.values():
            domains.setdefault(e.domain, []).append(e)
        log.info("[HA] Domains: %s", {k: len(v) for k, v in domains.items()})
        return domains

    # ── Voice command parsing ─────────────────────────────────────────────────

    async def handle_voice_command(self, text: str) -> str:
        t = text.lower()

        # Turn on/off by name
        for entity_id, entity in self._entities.items():
            name = entity.friendly_name.lower()
            if name in t:
                if any(w in t for w in ["turn on", "switch on", "enable"]):
                    ok = await self.turn_on(entity_id)
                    return f"{'Turned on' if ok else 'Failed to turn on'} {entity.friendly_name}."
                if any(w in t for w in ["turn off", "switch off", "disable"]):
                    ok = await self.turn_off(entity_id)
                    return f"{'Turned off' if ok else 'Failed to turn off'} {entity.friendly_name}."

        # Temperature setting
        import re
        temp_match = re.search(r"set.*?(\d+)\s*degrees?", t)
        if temp_match:
            temp = int(temp_match.group(1))
            climates = [e for e in self._entities.values() if e.domain == "climate"]
            if climates:
                ok = await self.set_climate(climates[0].entity_id, temperature=float(temp))
                return f"Set temperature to {temp}°." if ok else "Failed to set temperature."

        return ""

    def status(self) -> Dict[str, Any]:
        return {
            "connected": self._connected,
            "base_url": self.base_url,
            "entities": len(self._entities),
            "domains": list({e.domain for e in self._entities.values()}),
        }

"""Phase 11.7 — Context Manager.

Real-time awareness of:
  • Location: home / office / traveling
  • Time: morning / afternoon / evening / night
  • Activity: meeting / driving / walking / gaming / sleeping
  • Device state: charging, battery, headphones, connectivity
  • Privacy-first: no sensitive context persisted without consent
"""
from __future__ import annotations
import asyncio
import json
import logging
import sqlite3
import time
from contextlib import contextmanager
from dataclasses import dataclass, field
from datetime import datetime
from typing import Any, Callable, Dict, List, Optional

from .config import CONTEXT_DB, CONTEXT_UPDATE_INTERVAL_S

log = logging.getLogger(__name__)

# ── Enums (as string constants) ────────────────────────────────────────────────
LOC_HOME = "home"
LOC_OFFICE = "office"
LOC_TRAVELING = "traveling"
LOC_UNKNOWN = "unknown"

TOD_MORNING = "morning"         # 06:00–12:00
TOD_AFTERNOON = "afternoon"     # 12:00–17:00
TOD_EVENING = "evening"         # 17:00–21:00
TOD_NIGHT = "night"             # 21:00–06:00

ACT_MEETING = "meeting"
ACT_DRIVING = "driving"
ACT_WALKING = "walking"
ACT_GAMING = "gaming"
ACT_SLEEPING = "sleeping"
ACT_WORKING = "working"
ACT_IDLE = "idle"


@dataclass
class Context:
    user_id: str = "default"
    timestamp: float = field(default_factory=time.time)

    # Location
    location_label: str = LOC_UNKNOWN
    lat: float = 0.0
    lng: float = 0.0

    # Time
    time_of_day: str = TOD_MORNING
    hour: int = 0
    day_of_week: int = 0      # 0=Monday

    # Activity
    activity: str = ACT_IDLE
    in_meeting: bool = False
    headphones: bool = False

    # Device
    battery_level: int = 100
    charging: bool = False
    wifi: bool = True
    mobile_data: bool = False

    # Computed
    busy: bool = False
    should_disturb: bool = True

    def to_dict(self) -> Dict[str, Any]:
        return {
            "user_id": self.user_id,
            "timestamp": self.timestamp,
            "location_label": self.location_label,
            "lat": self.lat, "lng": self.lng,
            "time_of_day": self.time_of_day,
            "hour": self.hour, "day_of_week": self.day_of_week,
            "activity": self.activity,
            "in_meeting": self.in_meeting,
            "headphones": self.headphones,
            "battery_level": self.battery_level,
            "charging": self.charging,
            "wifi": self.wifi,
            "busy": self.busy,
            "should_disturb": self.should_disturb,
        }


class ContextManager:
    """Aggregates signals into a unified user context snapshot."""

    def __init__(self, location_service=None, calendar_optimizer=None):
        self._location_svc = location_service
        self._calendar = calendar_optimizer
        self._current: Dict[str, Context] = {}
        self._signal_providers: List[Callable] = []
        self._listeners: List[Callable] = []
        self._init_db()

    # ── Database ──────────────────────────────────────────────────────────────
    @contextmanager
    def _db(self):
        conn = sqlite3.connect(CONTEXT_DB)
        conn.execute("PRAGMA foreign_keys = ON")
        conn.row_factory = sqlite3.Row
        try:
            yield conn
            conn.commit()
        finally:
            conn.close()

    def _init_db(self):
        with self._db() as db:
            db.execute("""
                CREATE TABLE IF NOT EXISTS context_history (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    user_id TEXT, timestamp REAL,
                    context_json TEXT,
                    activity TEXT,
                    location_label TEXT
                )
            """)

    # ── Signal registration ───────────────────────────────────────────────────
    def add_signal_provider(self, provider: Callable):
        """Register an async fn() → Dict signal provider."""
        self._signal_providers.append(provider)

    def on_context_change(self, listener: Callable):
        """Register callback called when context changes significantly."""
        self._listeners.append(listener)

    # ── Context computation ───────────────────────────────────────────────────
    def _time_of_day(self, hour: int) -> str:
        if 6 <= hour < 12:
            return TOD_MORNING
        if 12 <= hour < 17:
            return TOD_AFTERNOON
        if 17 <= hour < 21:
            return TOD_EVENING
        return TOD_NIGHT

    def _infer_activity(self, signals: Dict) -> str:
        speed = signals.get("speed_kmh", 0)
        if speed > 50:
            return ACT_DRIVING
        if 5 < speed <= 50:
            return ACT_WALKING
        if signals.get("in_meeting"):
            return ACT_MEETING
        if signals.get("headphones") and signals.get("time_of_day") == TOD_NIGHT:
            return ACT_SLEEPING
        if signals.get("game_running"):
            return ACT_GAMING
        if signals.get("screen_on", True):
            return ACT_WORKING
        return ACT_IDLE

    def _is_busy(self, ctx: Context) -> bool:
        return (
            ctx.in_meeting
            or ctx.activity in (ACT_DRIVING, ACT_MEETING, ACT_GAMING)
            or ctx.time_of_day == TOD_NIGHT
        )

    async def _collect_signals(self) -> Dict[str, Any]:
        signals: Dict[str, Any] = {}
        # Run all providers in parallel
        tasks = [p() for p in self._signal_providers if callable(p)]
        if tasks:
            results = await asyncio.gather(*tasks, return_exceptions=True)
            for r in results:
                if isinstance(r, dict):
                    signals.update(r)

        # Location
        if self._location_svc:
            try:
                loc = await self._location_svc.get_current_label("default")
                signals.update(loc or {})
            except Exception as e:
                log.debug("Location signal error: %s", e)

        # Calendar (meeting check)
        if self._calendar:
            try:
                from datetime import date
                events = await self._calendar.get_events("default", date.today())
                now = datetime.now()
                in_meeting = any(
                    e.start.replace(tzinfo=None) <= now <= e.end.replace(tzinfo=None)
                    for e in events
                )
                signals["in_meeting"] = in_meeting
            except Exception as e:
                log.debug("Calendar signal error: %s", e)

        return signals

    async def update(self, user_id: str = "default",
                     raw_signals: Optional[Dict] = None) -> Context:
        now = datetime.now()
        signals = raw_signals or {}

        # Merge provider signals
        provider_signals = await self._collect_signals()
        signals = {**provider_signals, **signals}   # raw_signals override providers

        ctx = Context(
            user_id=user_id,
            timestamp=time.time(),
            lat=signals.get("lat", 0.0),
            lng=signals.get("lng", 0.0),
            location_label=signals.get("location_label", LOC_UNKNOWN),
            time_of_day=self._time_of_day(now.hour),
            hour=now.hour,
            day_of_week=now.weekday(),
            in_meeting=bool(signals.get("in_meeting", False)),
            headphones=bool(signals.get("headphones", False)),
            battery_level=int(signals.get("battery_level", 100)),
            charging=bool(signals.get("charging", False)),
            wifi=bool(signals.get("wifi", True)),
        )
        ctx.activity = self._infer_activity({**signals, "time_of_day": ctx.time_of_day})
        ctx.busy = self._is_busy(ctx)
        ctx.should_disturb = not ctx.busy

        # Persist (non-sensitive fields only)
        with self._db() as db:
            db.execute(
                "INSERT INTO context_history(user_id,timestamp,context_json,activity,location_label)"
                " VALUES(?,?,?,?,?)",
                (user_id, ctx.timestamp,
                 json.dumps({k: v for k, v in ctx.to_dict().items()
                             if k not in ("lat", "lng")}),   # drop precise coords for privacy
                 ctx.activity, ctx.location_label)
            )

        # Notify listeners on significant change
        prev = self._current.get(user_id)
        if prev and (prev.activity != ctx.activity or prev.location_label != ctx.location_label):
            for listener in self._listeners:
                try:
                    if asyncio.iscoroutinefunction(listener):
                        asyncio.ensure_future(listener(ctx))
                    else:
                        listener(ctx)
                except Exception as e:
                    log.warning("Context listener error: %s", e)

        self._current[user_id] = ctx
        log.debug("Context updated for %s: %s / %s", user_id, ctx.activity, ctx.location_label)
        return ctx

    def get(self, user_id: str = "default") -> Optional[Context]:
        return self._current.get(user_id)

    async def get_or_update(self, user_id: str = "default") -> Context:
        ctx = self.get(user_id)
        if not ctx or time.time() - ctx.timestamp > CONTEXT_UPDATE_INTERVAL_S:
            ctx = await self.update(user_id)
        return ctx

    def get_response_style(self, user_id: str = "default") -> Dict[str, str]:
        """Suggest how Aladdin should respond based on current context."""
        ctx = self.get(user_id)
        if not ctx:
            return {"tone": "neutral", "verbosity": "normal", "mode": "text"}

        if ctx.activity == ACT_DRIVING:
            return {"tone": "concise", "verbosity": "minimal", "mode": "voice",
                    "note": "User is driving — keep it short and safe"}
        if ctx.activity == ACT_MEETING:
            return {"tone": "silent", "verbosity": "none", "mode": "notification",
                    "note": "User is in a meeting — send notification only"}
        if ctx.time_of_day == TOD_NIGHT:
            return {"tone": "quiet", "verbosity": "brief", "mode": "text",
                    "note": "Late night — be brief and calm"}
        if ctx.battery_level < 15:
            return {"tone": "efficient", "verbosity": "minimal", "mode": "text",
                    "note": "Low battery — minimise processing"}
        return {"tone": "friendly", "verbosity": "normal", "mode": "text"}

    async def run(self, user_id: str = "default"):
        """Background context refresh loop."""
        while True:
            try:
                await self.update(user_id)
            except Exception as e:
                log.error("Context update error: %s", e)
            await asyncio.sleep(CONTEXT_UPDATE_INTERVAL_S)

    def get_history(self, user_id: str = "default",
                    limit: int = 50) -> List[Dict]:
        with self._db() as db:
            rows = db.execute(
                "SELECT * FROM context_history WHERE user_id=? ORDER BY timestamp DESC LIMIT ?",
                (user_id, limit)
            ).fetchall()
        return [dict(r) for r in rows]

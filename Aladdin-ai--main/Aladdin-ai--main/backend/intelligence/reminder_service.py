"""Phase 11.1 — Proactive Reminder Service.

Features:
  • Priority levels (high / medium / low)
  • Context-aware postponement (user busy → auto-snooze)
  • Location-triggered reminders (geo-fencing)
  • Intelligent follow-up on ignored reminders
  • Smart rescheduling based on user availability
"""
from __future__ import annotations
import asyncio
import json
import logging
import sqlite3
import time
import uuid
from contextlib import contextmanager
from dataclasses import asdict, dataclass, field
from datetime import datetime, timedelta
from typing import Callable, Dict, List, Optional

from .config import (
    REMINDER_DB, REMINDER_CHECK_INTERVAL_S,
    REMINDER_BUSY_POSTPONE_MIN, REMINDER_MAX_SNOOZE_COUNT,
)

log = logging.getLogger(__name__)

PRIORITY_HIGH = "high"
PRIORITY_MED = "medium"
PRIORITY_LOW = "low"
PRIORITY_WEIGHTS = {PRIORITY_HIGH: 3, PRIORITY_MED: 2, PRIORITY_LOW: 1}


@dataclass
class Reminder:
    id: str = field(default_factory=lambda: str(uuid.uuid4()))
    title: str = ""
    body: str = ""
    priority: str = PRIORITY_MED
    due_at: float = 0.0                # Unix timestamp
    location_trigger: Optional[Dict] = None   # {"lat": ..., "lng": ..., "radius_m": 200}
    repeat_rule: Optional[str] = None  # "daily", "weekly", "weekdays", cron-like
    snooze_count: int = 0
    max_snooze: int = REMINDER_MAX_SNOOZE_COUNT
    acknowledged: bool = False
    created_at: float = field(default_factory=time.time)
    tags: List[str] = field(default_factory=list)
    user_id: str = "default"
    follow_up_sent: bool = False

    def to_dict(self) -> Dict:
        d = asdict(self)
        d["location_trigger"] = json.dumps(d["location_trigger"])
        d["tags"] = json.dumps(d["tags"])
        return d

    @classmethod
    def from_row(cls, row: Dict) -> "Reminder":
        row = dict(row)
        row["location_trigger"] = json.loads(row.get("location_trigger") or "null")
        row["tags"] = json.loads(row.get("tags") or "[]")
        row["acknowledged"] = bool(row.get("acknowledged", 0))
        row["follow_up_sent"] = bool(row.get("follow_up_sent", 0))
        return cls(**row)


class ReminderService:
    """Proactive, context-aware reminder scheduler."""

    def __init__(self, context_provider: Optional[Callable] = None):
        self._ctx = context_provider   # async fn() → {"busy": bool, "location": {...}}
        self._callbacks: List[Callable] = []
        self._running = False
        self._init_db()

    # ── Database ──────────────────────────────────────────────────────────────
    @contextmanager
    def _db(self):
        conn = sqlite3.connect(REMINDER_DB)
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
                CREATE TABLE IF NOT EXISTS reminders (
                    id TEXT PRIMARY KEY,
                    title TEXT,
                    body TEXT,
                    priority TEXT DEFAULT 'medium',
                    due_at REAL,
                    location_trigger TEXT,
                    repeat_rule TEXT,
                    snooze_count INTEGER DEFAULT 0,
                    max_snooze INTEGER DEFAULT 3,
                    acknowledged INTEGER DEFAULT 0,
                    created_at REAL,
                    tags TEXT DEFAULT '[]',
                    user_id TEXT DEFAULT 'default',
                    follow_up_sent INTEGER DEFAULT 0
                )
            """)

    # ── CRUD ──────────────────────────────────────────────────────────────────
    def add(self, title: str, body: str = "", priority: str = PRIORITY_MED,
            due_at: Optional[float] = None, location_trigger: Optional[Dict] = None,
            repeat_rule: Optional[str] = None, tags: Optional[List[str]] = None,
            user_id: str = "default") -> Reminder:
        r = Reminder(
            title=title, body=body, priority=priority,
            due_at=due_at or (time.time() + 3600),
            location_trigger=location_trigger,
            repeat_rule=repeat_rule,
            tags=tags or [],
            user_id=user_id,
        )
        d = r.to_dict()
        with self._db() as db:
            db.execute(
                "INSERT INTO reminders VALUES (:id,:title,:body,:priority,:due_at,"
                ":location_trigger,:repeat_rule,:snooze_count,:max_snooze,"
                ":acknowledged,:created_at,:tags,:user_id,:follow_up_sent)",
                d,
            )
        log.info("Reminder added: %s [%s] due=%s", title, priority,
                 datetime.fromtimestamp(r.due_at).isoformat())
        return r

    def get(self, reminder_id: str) -> Optional[Reminder]:
        with self._db() as db:
            row = db.execute("SELECT * FROM reminders WHERE id=?", (reminder_id,)).fetchone()
        return Reminder.from_row(row) if row else None

    def list(self, user_id: str = "default", include_done: bool = False) -> List[Reminder]:
        with self._db() as db:
            q = "SELECT * FROM reminders WHERE user_id=?" + ("" if include_done else " AND acknowledged=0")
            rows = db.execute(q + " ORDER BY due_at ASC", (user_id,)).fetchall()
        return [Reminder.from_row(r) for r in rows]

    def acknowledge(self, reminder_id: str):
        with self._db() as db:
            db.execute("UPDATE reminders SET acknowledged=1 WHERE id=?", (reminder_id,))
        log.debug("Reminder acknowledged: %s", reminder_id)

    def snooze(self, reminder_id: str, minutes: int = 10) -> Optional[Reminder]:
        r = self.get(reminder_id)
        if not r:
            return None
        if r.snooze_count >= r.max_snooze:
            log.warning("Reminder %s hit max snooze — forcing fire", reminder_id)
            return r
        new_due = time.time() + minutes * 60
        with self._db() as db:
            db.execute(
                "UPDATE reminders SET due_at=?, snooze_count=snooze_count+1 WHERE id=?",
                (new_due, reminder_id),
            )
        log.info("Reminder snoozed +%dm → %s", minutes, datetime.fromtimestamp(new_due).isoformat())
        return self.get(reminder_id)

    def delete(self, reminder_id: str):
        with self._db() as db:
            db.execute("DELETE FROM reminders WHERE id=?", (reminder_id,))

    # ── Smart postponement ────────────────────────────────────────────────────
    async def _is_user_busy(self) -> bool:
        if not self._ctx:
            return False
        try:
            ctx = await self._ctx()
            return ctx.get("busy", False)
        except Exception as e:
            log.warning("Context provider error: %s", e)
            return False

    async def _get_user_location(self) -> Optional[Dict]:
        if not self._ctx:
            return None
        try:
            ctx = await self._ctx()
            return ctx.get("location")
        except Exception:
            return None

    def _location_matches(self, reminder: Reminder, user_loc: Optional[Dict]) -> bool:
        if not reminder.location_trigger or not user_loc:
            return False
        import math
        lat1, lng1 = reminder.location_trigger["lat"], reminder.location_trigger["lng"]
        lat2, lng2 = user_loc.get("lat", 0), user_loc.get("lng", 0)
        radius = reminder.location_trigger.get("radius_m", 200)
        # Haversine
        R = 6_371_000
        phi1, phi2 = math.radians(lat1), math.radians(lat2)
        dphi = math.radians(lat2 - lat1)
        dlam = math.radians(lng2 - lng1)
        a = math.sin(dphi/2)**2 + math.cos(phi1)*math.cos(phi2)*math.sin(dlam/2)**2
        dist = R * 2 * math.atan2(math.sqrt(a), math.sqrt(1-a))
        return dist <= radius

    def _compute_next_repeat(self, reminder: Reminder) -> Optional[float]:
        if not reminder.repeat_rule:
            return None
        now = datetime.fromtimestamp(reminder.due_at)
        if reminder.repeat_rule == "daily":
            return (now + timedelta(days=1)).timestamp()
        if reminder.repeat_rule == "weekly":
            return (now + timedelta(weeks=1)).timestamp()
        if reminder.repeat_rule == "weekdays":
            nxt = now + timedelta(days=1)
            while nxt.weekday() >= 5:
                nxt += timedelta(days=1)
            return nxt.timestamp()
        return None

    # ── Notification callbacks ────────────────────────────────────────────────
    def on_fire(self, callback: Callable):
        """Register callback called when a reminder fires: callback(reminder)."""
        self._callbacks.append(callback)

    async def _fire(self, reminder: Reminder):
        log.info("🔔 REMINDER [%s] %s — %s", reminder.priority, reminder.title, reminder.body)
        for cb in self._callbacks:
            try:
                if asyncio.iscoroutinefunction(cb):
                    await cb(reminder)
                else:
                    cb(reminder)
            except Exception as e:
                log.error("Callback error: %s", e)

    # ── Main scheduler loop ───────────────────────────────────────────────────
    async def run(self):
        self._running = True
        log.info("ReminderService started (interval=%ds)", REMINDER_CHECK_INTERVAL_S)
        while self._running:
            try:
                await self._tick()
            except Exception as e:
                log.error("ReminderService tick error: %s", e)
            await asyncio.sleep(REMINDER_CHECK_INTERVAL_S)

    async def _tick(self):
        now = time.time()
        busy = await self._is_user_busy()
        user_loc = await self._get_user_location()

        with self._db() as db:
            due = db.execute(
                "SELECT * FROM reminders WHERE acknowledged=0 AND due_at<=? ORDER BY priority DESC",
                (now + 5,)
            ).fetchall()

        for row in due:
            r = Reminder.from_row(row)

            # Location-triggered: only fire when in range
            if r.location_trigger and not self._location_matches(r, user_loc):
                continue

            # Busy + low/medium priority → postpone
            if busy and r.priority != PRIORITY_HIGH and r.snooze_count < r.max_snooze:
                self.snooze(r.id, REMINDER_BUSY_POSTPONE_MIN)
                log.info("User busy — snoozed '%s' +%dm", r.title, REMINDER_BUSY_POSTPONE_MIN)
                continue

            await self._fire(r)

            # Handle repeat
            next_due = self._compute_next_repeat(r)
            if next_due:
                with self._db() as db:
                    db.execute(
                        "UPDATE reminders SET due_at=?, snooze_count=0, acknowledged=0 WHERE id=?",
                        (next_due, r.id)
                    )
            else:
                self.acknowledge(r.id)

            # Follow-up for ignored high-priority (fire again after 5 min if not ack'd)
            if r.priority == PRIORITY_HIGH and not r.follow_up_sent:
                asyncio.ensure_future(self._schedule_followup(r))

    async def _schedule_followup(self, reminder: Reminder):
        await asyncio.sleep(300)  # 5 minutes
        current = self.get(reminder.id)
        if current and not current.acknowledged:
            log.warning("Follow-up for ignored HIGH priority reminder: %s", reminder.title)
            await self._fire(current)
            with self._db() as db:
                db.execute("UPDATE reminders SET follow_up_sent=1 WHERE id=?", (reminder.id,))

    def stop(self):
        self._running = False

    # ── Smart reschedule suggestions ──────────────────────────────────────────
    def suggest_reschedule(self, reminder_id: str, available_slots: List[float]) -> Optional[float]:
        """Pick the best slot from available_slots based on reminder priority."""
        r = self.get(reminder_id)
        if not r or not available_slots:
            return None
        # High priority → earliest slot; low priority → later slot
        slots = sorted(available_slots)
        if r.priority == PRIORITY_HIGH:
            return slots[0]
        if r.priority == PRIORITY_LOW:
            return slots[-1]
        return slots[len(slots) // 2]

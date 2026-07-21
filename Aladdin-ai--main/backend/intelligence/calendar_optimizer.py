"""Phase 11.5 — Calendar Optimizer.

Features:
  • Detect free time slots between meetings
  • Identify event conflicts
  • Suggest best meeting times (considering travel + working hours)
  • Add preparation reminders
  • Optimise daily schedule
  • Google Calendar integration (optional)
"""
from __future__ import annotations
import asyncio
import logging
import os
import time
from dataclasses import dataclass, field
from datetime import date, datetime, timedelta
from typing import Any, Dict, List, Optional, Tuple

from .config import (
    CALENDAR_WORKING_HOURS_START, CALENDAR_WORKING_HOURS_END,
    CALENDAR_MIN_SLOT_MINUTES, CALENDAR_TRAVEL_BUFFER_MINUTES,
    GOOGLE_CALENDAR_CREDENTIALS,
)

log = logging.getLogger(__name__)


@dataclass
class CalendarEvent:
    id: str
    title: str
    start: datetime
    end: datetime
    location: str = ""
    attendees: List[str] = field(default_factory=list)
    is_online: bool = False
    travel_time_min: int = 0
    needs_prep: bool = False

    @property
    def duration_min(self) -> int:
        return int((self.end - self.start).total_seconds() / 60)

    def to_dict(self) -> Dict:
        return {
            "id": self.id, "title": self.title,
            "start": self.start.isoformat(), "end": self.end.isoformat(),
            "location": self.location, "duration_min": self.duration_min,
            "is_online": self.is_online,
        }


@dataclass
class TimeSlot:
    start: datetime
    end: datetime

    @property
    def duration_min(self) -> int:
        return int((self.end - self.start).total_seconds() / 60)

    def to_dict(self) -> Dict:
        return {
            "start": self.start.isoformat(),
            "end": self.end.isoformat(),
            "duration_min": self.duration_min,
        }


@dataclass
class Conflict:
    event_a: CalendarEvent
    event_b: CalendarEvent
    overlap_min: int
    suggestion: str

    def to_dict(self) -> Dict:
        return {
            "event_a": self.event_a.title,
            "event_b": self.event_b.title,
            "overlap_min": self.overlap_min,
            "suggestion": self.suggestion,
        }


class CalendarOptimizer:
    """Smart calendar management and scheduling assistant."""

    def __init__(self):
        self._events_cache: Dict[str, List[CalendarEvent]] = {}
        self._gcal_service = None

    # ── Google Calendar integration ───────────────────────────────────────────
    def _get_gcal(self):
        if self._gcal_service:
            return self._gcal_service
        if not GOOGLE_CALENDAR_CREDENTIALS:
            return None
        try:
            from google.oauth2.credentials import Credentials
            from googleapiclient.discovery import build
            import json
            creds_data = json.loads(GOOGLE_CALENDAR_CREDENTIALS)
            creds = Credentials.from_authorized_user_info(creds_data)
            self._gcal_service = build("calendar", "v3", credentials=creds)
            return self._gcal_service
        except Exception as e:
            log.warning("Google Calendar init error: %s", e)
            return None

    async def get_today_events(self, user_id: str = "default") -> List[Dict]:
        events = await self.get_events(user_id)
        today = date.today()
        return [
            {**e.to_dict(), "start": e.start.strftime("%H:%M")}
            for e in events
            if e.start.date() == today
        ]

    async def get_events(self, user_id: str = "default",
                          target_date: Optional[date] = None) -> List[CalendarEvent]:
        td = target_date or date.today()
        cache_key = f"{user_id}:{td.isoformat()}"

        if cache_key in self._events_cache:
            return self._events_cache[cache_key]

        events = await self._fetch_from_gcal(td) or self._events_cache.get(f"{user_id}:manual", [])
        self._events_cache[cache_key] = events
        return events

    async def _fetch_from_gcal(self, target_date: date) -> List[CalendarEvent]:
        svc = self._get_gcal()
        if not svc:
            return []
        try:
            loop = asyncio.get_running_loop()
            start = datetime.combine(target_date, datetime.min.time()).isoformat() + "Z"
            end = datetime.combine(target_date, datetime.max.time()).isoformat() + "Z"
            result = await loop.run_in_executor(
                None,
                lambda: svc.events().list(
                    calendarId="primary",
                    timeMin=start, timeMax=end,
                    singleEvents=True, orderBy="startTime"
                ).execute()
            )
            events = []
            for item in result.get("items", []):
                start_str = item["start"].get("dateTime", item["start"].get("date"))
                end_str = item["end"].get("dateTime", item["end"].get("date"))
                try:
                    ev_start = datetime.fromisoformat(start_str.replace("Z", "+00:00"))
                    ev_end = datetime.fromisoformat(end_str.replace("Z", "+00:00"))
                except Exception:
                    continue
                events.append(CalendarEvent(
                    id=item.get("id", ""),
                    title=item.get("summary", "Untitled"),
                    start=ev_start, end=ev_end,
                    location=item.get("location", ""),
                    attendees=[a.get("email", "") for a in item.get("attendees", [])],
                ))
            return events
        except Exception as e:
            log.warning("Google Calendar fetch error: %s", e)
            return []

    def add_manual_event(self, user_id: str, event: CalendarEvent):
        key = f"{user_id}:manual"
        self._events_cache.setdefault(key, []).append(event)

    # ── Free slots ────────────────────────────────────────────────────────────
    async def find_free_slots(self, user_id: str = "default",
                               target_date: Optional[date] = None,
                               min_duration_min: int = CALENDAR_MIN_SLOT_MINUTES,
                               respect_working_hours: bool = True) -> List[TimeSlot]:
        td = target_date or date.today()
        events = await self.get_events(user_id, td)

        # Working hours window
        work_start = datetime.combine(td, datetime.min.time()).replace(
            hour=CALENDAR_WORKING_HOURS_START, minute=0, second=0)
        work_end = datetime.combine(td, datetime.min.time()).replace(
            hour=CALENDAR_WORKING_HOURS_END, minute=0, second=0)

        busy = sorted(events, key=lambda e: e.start)
        free_slots: List[TimeSlot] = []
        cursor = work_start if respect_working_hours else datetime.combine(td, datetime.min.time())

        for ev in busy:
            ev_start = ev.start.replace(tzinfo=None)
            ev_end = ev.end.replace(tzinfo=None)
            if ev_end <= cursor:
                continue
            if ev_start > cursor:
                slot = TimeSlot(cursor, min(ev_start, work_end if respect_working_hours else ev_start))
                if slot.duration_min >= min_duration_min:
                    free_slots.append(slot)
            cursor = max(cursor, ev_end)

        # Final slot
        end_bound = work_end if respect_working_hours else datetime.combine(td, datetime.max.time())
        if cursor < end_bound:
            slot = TimeSlot(cursor, end_bound)
            if slot.duration_min >= min_duration_min:
                free_slots.append(slot)

        log.debug("Found %d free slots on %s", len(free_slots), td)
        return free_slots

    # ── Conflict detection ────────────────────────────────────────────────────
    async def detect_conflicts(self, user_id: str = "default",
                                target_date: Optional[date] = None) -> List[Conflict]:
        events = await self.get_events(user_id, target_date or date.today())
        conflicts: List[Conflict] = []
        sorted_ev = sorted(events, key=lambda e: e.start)

        for i in range(len(sorted_ev)):
            for j in range(i + 1, len(sorted_ev)):
                a, b = sorted_ev[i], sorted_ev[j]
                a_start = a.start.replace(tzinfo=None)
                a_end = a.end.replace(tzinfo=None)
                b_start = b.start.replace(tzinfo=None)
                b_end = b.end.replace(tzinfo=None)

                # Direct overlap
                overlap_start = max(a_start, b_start)
                overlap_end = min(a_end, b_end)
                if overlap_start < overlap_end:
                    overlap_min = int((overlap_end - overlap_start).total_seconds() / 60)
                    suggestion = (
                        f"Reschedule '{b.title}' to after {a_end.strftime('%H:%M')}"
                    )
                    conflicts.append(Conflict(a, b, overlap_min, suggestion))
                    continue

                # Travel-time gap conflict
                if a.location and b.location and not a.is_online and not b.is_online:
                    gap_min = int((b_start - a_end).total_seconds() / 60)
                    needed = CALENDAR_TRAVEL_BUFFER_MINUTES
                    if gap_min < needed:
                        conflicts.append(Conflict(a, b, 0,
                            f"Only {gap_min} min gap — need {needed} min travel buffer "
                            f"between '{a.location}' and '{b.location}'"))

        return conflicts

    # ── Best meeting times ────────────────────────────────────────────────────
    async def suggest_meeting_times(self, user_id: str,
                                     duration_min: int = 60,
                                     days_ahead: int = 3,
                                     preferred_hours: Optional[Tuple[int, int]] = None) -> List[TimeSlot]:
        suggestions: List[TimeSlot] = []
        ph = preferred_hours or (CALENDAR_WORKING_HOURS_START, CALENDAR_WORKING_HOURS_END)

        for delta in range(days_ahead):
            td = date.today() + timedelta(days=delta)
            free = await self.find_free_slots(user_id, td, min_duration_min=duration_min)
            for slot in free:
                s = slot.start.replace(tzinfo=None)
                if ph[0] <= s.hour < ph[1]:
                    suggestions.append(slot)

        log.info("Suggested %d meeting slots across %d days", len(suggestions), days_ahead)
        return suggestions[:10]

    # ── Prep reminders ────────────────────────────────────────────────────────
    def generate_prep_reminders(self, event: CalendarEvent,
                                 reminder_service=None) -> List[str]:
        reminders = []
        if not reminder_service:
            return reminders
        prep_time = event.start - timedelta(minutes=15 + event.travel_time_min)
        r = reminder_service.add(
            title=f"Prepare for: {event.title}",
            body=f"Starts at {event.start.strftime('%H:%M')}. Location: {event.location or 'Online'}",
            priority="high",
            due_at=prep_time.timestamp(),
        )
        reminders.append(r.id)
        if event.travel_time_min > 0:
            depart = event.start - timedelta(minutes=event.travel_time_min + 5)
            r2 = reminder_service.add(
                title=f"Leave for {event.title}",
                body=f"Allow {event.travel_time_min} min travel time",
                priority="high",
                due_at=depart.timestamp(),
            )
            reminders.append(r2.id)
        return reminders

    # ── Schedule optimisation ─────────────────────────────────────────────────
    async def optimize_schedule(self, user_id: str = "default") -> Dict[str, Any]:
        """Return a schedule optimisation report."""
        events = await self.get_events(user_id)
        conflicts = await self.detect_conflicts(user_id)
        free = await self.find_free_slots(user_id)

        total_meeting_min = sum(e.duration_min for e in events)
        focus_blocks = [s for s in free if s.duration_min >= 90]  # 90+ min = deep work

        return {
            "date": date.today().isoformat(),
            "total_events": len(events),
            "total_meeting_hours": round(total_meeting_min / 60, 1),
            "conflicts": len(conflicts),
            "conflict_details": [c.to_dict() for c in conflicts],
            "free_slots": len(free),
            "focus_blocks_available": len(focus_blocks),
            "focus_blocks": [s.to_dict() for s in focus_blocks[:3]],
            "recommendations": self._schedule_recommendations(events, conflicts, free),
        }

    def _schedule_recommendations(self, events, conflicts, free_slots) -> List[str]:
        recs = []
        if conflicts:
            recs.append(f"⚠️ Resolve {len(conflicts)} scheduling conflict(s)")
        if len(events) > 6:
            recs.append("🧘 Consider blocking some 'no-meeting' time — you have many events today")
        focus = [s for s in free_slots if s.duration_min >= 90]
        if focus:
            recs.append(f"🎯 Best focus block: {focus[0].start.strftime('%H:%M')}–{focus[0].end.strftime('%H:%M')}")
        if not events:
            recs.append("✅ Clear calendar — good day for deep work or planning")
        return recs

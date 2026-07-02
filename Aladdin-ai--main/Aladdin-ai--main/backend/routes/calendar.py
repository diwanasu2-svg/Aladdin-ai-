"""Calendar API routes."""
from __future__ import annotations
import logging
from typing import Any, Dict, List, Optional
from fastapi import APIRouter, HTTPException, Query
from pydantic import BaseModel

log = logging.getLogger(__name__)
router = APIRouter(prefix="/calendar", tags=["Calendar"])


class EventCreate(BaseModel):
    title: str
    description: str = ""
    location: str = ""
    start_ts: float
    end_ts: Optional[float] = None
    all_day: bool = False
    reminder_minutes: int = 15
    recurrence_rule: Optional[str] = None        # daily/weekly/monthly/yearly
    recurrence_interval: int = 1
    recurrence_end_ts: Optional[float] = None


class EventUpdate(BaseModel):
    title: Optional[str] = None
    description: Optional[str] = None
    location: Optional[str] = None
    start_ts: Optional[float] = None
    end_ts: Optional[float] = None
    all_day: Optional[bool] = None
    reminder_minutes: Optional[int] = None
    recurrence_rule: Optional[str] = None
    recurrence_interval: Optional[int] = None
    recurrence_end_ts: Optional[float] = None


def _store():
    from ..main import app_state
    s = app_state.get("calendar_store")
    if not s:
        raise HTTPException(503, "Calendar store not initialized")
    return s


@router.post("/events", status_code=201)
async def create_event(data: EventCreate):
    store = _store()
    payload = data.dict(exclude_none=True)
    # Conflict check
    conflicts = store.get_conflicts(payload)
    ev = store.create(payload)
    response = dict(ev)
    if conflicts:
        response["conflicts"] = conflicts
        response["warning"] = f"{len(conflicts)} overlapping event(s) detected"
    return response


@router.get("/events")
async def list_events(
    from_ts: Optional[float] = Query(None),
    to_ts:   Optional[float] = Query(None),
    limit:   int             = Query(100),
):
    store = _store()
    events = store.list(from_ts=from_ts, to_ts=to_ts, limit=limit)
    return {"events": events, "count": len(events)}


@router.get("/events/{event_id}")
async def get_event(event_id: str):
    ev = _store().get(event_id)
    if not ev:
        raise HTTPException(404, "Event not found")
    return ev


@router.put("/events/{event_id}")
async def update_event(event_id: str, data: EventUpdate):
    store = _store()
    if not store.get(event_id):
        raise HTTPException(404, "Event not found")
    ev = store.update(event_id, data.dict(exclude_none=True))
    return ev


@router.delete("/events/{event_id}")
async def delete_event(event_id: str, soft: bool = Query(True)):
    ok = _store().delete(event_id, soft=soft)
    if not ok:
        raise HTTPException(404, "Event not found")
    return {"deleted": event_id, "soft": soft}


@router.post("/recurring", status_code=201)
async def create_recurring_event(data: EventCreate):
    if not data.recurrence_rule:
        raise HTTPException(400, "recurrence_rule is required (daily/weekly/monthly/yearly)")
    store = _store()
    ev = store.create(data.dict(exclude_none=True))
    occurrences = store.get_occurrences(ev["id"], count=5)
    return {"event": ev, "next_occurrences": occurrences}


@router.get("/events/{event_id}/occurrences")
async def get_occurrences(event_id: str, count: int = Query(10)):
    occ = _store().get_occurrences(event_id, count)
    return {"occurrences": occ, "count": len(occ)}


@router.get("/conflicts")
async def check_conflicts(from_ts: float = Query(...), to_ts: float = Query(...)):
    events = _store().list(from_ts=from_ts, to_ts=to_ts)
    from ..calendar.conflict_detector import find_conflicts
    pairs = find_conflicts(events)
    return {"conflicts": [{"a": a, "b": b} for a, b in pairs],
            "count": len(pairs)}


@router.get("/sync")
async def google_sync(calendar_id: str = "primary"):
    from ..main import app_state
    gc = app_state.get("google_calendar")
    if not gc or not gc.available:
        raise HTTPException(503, "Google Calendar not configured. "
                                  "Set GOOGLE_CREDENTIALS_PATH and authorize via OAuth2.")
    synced = await gc.sync_from_google(calendar_id)
    return {"synced": len(synced), "events": synced}


@router.post("/sync/push/{event_id}")
async def push_to_google(event_id: str, calendar_id: str = "primary"):
    from ..main import app_state
    gc = app_state.get("google_calendar")
    store = _store()
    if not gc or not gc.available:
        raise HTTPException(503, "Google Calendar not configured")
    ev = store.get(event_id)
    if not ev:
        raise HTTPException(404, "Event not found")
    result = await gc.push_to_google(ev, calendar_id)
    return result

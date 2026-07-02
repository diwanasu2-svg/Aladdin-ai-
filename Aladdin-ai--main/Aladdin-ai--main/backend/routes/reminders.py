"""Reminders API routes."""
from __future__ import annotations
import logging
from typing import Any, Dict, List, Optional
from fastapi import APIRouter, HTTPException, Query
from pydantic import BaseModel

log = logging.getLogger(__name__)
router = APIRouter(prefix="/reminders", tags=["Reminders"])


class ReminderCreate(BaseModel):
    title: str
    body: str = ""
    remind_at: Optional[float] = None
    priority: int = 0                            # 0=normal, 1=high, 2=urgent
    sound_id: str = "default"
    volume: float = 0.8
    recurrence_rule: Optional[str] = None        # daily/weekly/monthly
    recurrence_interval: int = 1
    recurrence_end_ts: Optional[float] = None


class ReminderUpdate(BaseModel):
    title: Optional[str] = None
    body: Optional[str] = None
    remind_at: Optional[float] = None
    priority: Optional[int] = None
    sound_id: Optional[str] = None
    volume: Optional[float] = None
    done: Optional[bool] = None
    recurrence_rule: Optional[str] = None
    recurrence_interval: Optional[int] = None
    recurrence_end_ts: Optional[float] = None


class SnoozeRequest(BaseModel):
    duration: str = "10min"   # 5min / 10min / 15min / 30min / 1hour / 2hour


def _mgr():
    from ..main import app_state
    m = app_state.get("reminder_manager")
    if not m:
        raise HTTPException(503, "Reminder manager not initialized")
    return m


@router.post("", status_code=201)
async def create_reminder(data: ReminderCreate):
    return _mgr().create(data.dict(exclude_none=True))


@router.get("")
async def list_reminders(include_done: bool = False, include_deleted: bool = False):
    reminders = _mgr().list(include_done=include_done, include_deleted=include_deleted)
    return {"reminders": reminders, "count": len(reminders)}


@router.get("/notifications")
async def get_notifications():
    """Return queued notification payloads (for client polling)."""
    from ..reminders.notifications import get_queue
    queue = get_queue()
    notifications = queue.pop_all()
    return {"notifications": notifications, "count": len(notifications)}


@router.get("/sounds")
async def list_sounds():
    from ..reminders.sound import get_sounds
    return {"sounds": get_sounds()}


@router.post("/recurring", status_code=201)
async def create_recurring_reminder(data: ReminderCreate):
    if not data.recurrence_rule:
        raise HTTPException(400, "recurrence_rule required (daily/weekly/monthly)")
    return _mgr().create(data.dict(exclude_none=True))


@router.get("/{reminder_id}")
async def get_reminder(reminder_id: str):
    r = _mgr().get(reminder_id)
    if not r:
        raise HTTPException(404, "Reminder not found")
    return r


@router.put("/{reminder_id}")
async def update_reminder(reminder_id: str, data: ReminderUpdate):
    mgr = _mgr()
    if not mgr.get(reminder_id):
        raise HTTPException(404, "Reminder not found")
    r = mgr.update(reminder_id, data.dict(exclude_none=True))
    return r


@router.delete("/{reminder_id}")
async def delete_reminder(reminder_id: str, soft: bool = Query(True)):
    ok = _mgr().delete(reminder_id, soft=soft)
    if not ok:
        raise HTTPException(404, "Reminder not found")
    return {"deleted": reminder_id, "soft": soft}


@router.post("/{reminder_id}/snooze")
async def snooze_reminder(reminder_id: str, req: SnoozeRequest):
    mgr = _mgr()
    result = mgr.snooze(reminder_id, req.duration)
    if not result:
        raise HTTPException(404, "Reminder not found")
    if result.get("error"):
        raise HTTPException(400, result["error"])
    return result


@router.post("/{reminder_id}/dismiss")
async def dismiss_reminder(reminder_id: str):
    mgr = _mgr()
    mgr.mark_notified(reminder_id)
    r = mgr.get(reminder_id)
    return r or {"dismissed": reminder_id}

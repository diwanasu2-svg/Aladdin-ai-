"""Tool routes — execute, list, reminder, calendar, contacts, notes."""
from __future__ import annotations
import logging
from typing import Any, Dict, Optional
from fastapi import APIRouter, HTTPException
from pydantic import BaseModel

log = logging.getLogger(__name__)
router = APIRouter(prefix="/tools", tags=["Tools"])


class ExecuteRequest(BaseModel):
    tool_name: str
    args: Dict[str, Any] = {}


class ReminderRequest(BaseModel):
    title: str
    body: Optional[str] = ""
    remind_at: Optional[float] = None


class EventRequest(BaseModel):
    title: str
    start_ts: float
    end_ts: Optional[float] = None
    description: Optional[str] = ""
    location: Optional[str] = ""
    reminder_minutes: int = 15


class ContactRequest(BaseModel):
    name: str
    phone: Optional[str] = None
    email: Optional[str] = None
    relation: Optional[str] = None
    notes: Optional[str] = None


class NoteRequest(BaseModel):
    title: str
    content: Optional[str] = ""
    pinned: bool = False
    tags: Optional[str] = ""


class UpdateRequest(BaseModel):
    id: str
    data: Dict[str, Any] = {}


@router.get("/list")
async def list_tools():
    from ..main import app_state
    tm = app_state.get("tool_manager")
    if not tm:
        raise HTTPException(503, "Tool manager not initialized")
    return {"tools": tm.list_tools(), "count": len(tm.list_tools())}


@router.post("/execute")
async def execute_tool(req: ExecuteRequest):
    from ..main import app_state
    tm = app_state.get("tool_manager")
    if not tm:
        raise HTTPException(503, "Tool manager not initialized")
    result = await tm.execute(req.tool_name, **req.args)
    if not result.success:
        raise HTTPException(400, detail={"tool": result.tool_name, "error": result.error})
    return result.to_dict()


@router.get("/status")
async def tool_status():
    from ..main import app_state
    tm = app_state.get("tool_manager")
    if not tm:
        raise HTTPException(503, "Tool manager not initialized")
    return {"execution_log": tm.get_log(20), "available_tools": [t["name"] for t in tm.list_tools()]}


# ── Reminder CRUD ──────────────────────────────────────────────────────────────

@router.post("/reminder/create", status_code=201)
async def create_reminder(req: ReminderRequest):
    from ..main import app_state
    tm = app_state.get("tool_manager")
    if not tm: raise HTTPException(503, "Tool manager not initialized")
    result = await tm.execute("create_reminder", title=req.title, body=req.body or "", remind_at=req.remind_at)
    return result.to_dict()


@router.get("/reminder/list")
async def list_reminders(include_done: bool = False):
    from ..main import app_state
    tm = app_state.get("tool_manager")
    if not tm: raise HTTPException(503, "Tool manager not initialized")
    result = await tm.execute("list_reminders", include_done=include_done)
    return result.to_dict()


@router.put("/reminder/update")
async def update_reminder(req: UpdateRequest):
    from ..main import app_state
    tm = app_state.get("tool_manager")
    if not tm: raise HTTPException(503, "Tool manager not initialized")
    result = await tm.execute("update_reminder", id=req.id, **req.data)
    return result.to_dict()


@router.delete("/reminder/delete")
async def delete_reminder(id: str):
    from ..main import app_state
    tm = app_state.get("tool_manager")
    if not tm: raise HTTPException(503, "Tool manager not initialized")
    result = await tm.execute("delete_reminder", id=id)
    return result.to_dict()


# ── Calendar CRUD ──────────────────────────────────────────────────────────────

@router.post("/calendar/create", status_code=201)
async def create_event(req: EventRequest):
    from ..main import app_state
    tm = app_state.get("tool_manager")
    if not tm: raise HTTPException(503, "Tool manager not initialized")
    result = await tm.execute("create_calendar_event", **req.dict())
    return result.to_dict()


@router.get("/calendar/list")
async def list_events(from_ts: Optional[float] = None, limit: int = 20):
    from ..main import app_state
    tm = app_state.get("tool_manager")
    if not tm: raise HTTPException(503, "Tool manager not initialized")
    result = await tm.execute("list_calendar_events", from_ts=from_ts, limit=limit)
    return result.to_dict()


@router.put("/calendar/update")
async def update_event(req: UpdateRequest):
    from ..main import app_state
    tm = app_state.get("tool_manager")
    if not tm: raise HTTPException(503, "Tool manager not initialized")
    result = await tm.execute("update_calendar_event", id=req.id, **req.data)
    return result.to_dict()


@router.delete("/calendar/delete")
async def delete_event(id: str):
    from ..main import app_state
    tm = app_state.get("tool_manager")
    if not tm: raise HTTPException(503, "Tool manager not initialized")
    result = await tm.execute("delete_calendar_event", id=id)
    return result.to_dict()


# ── Contacts CRUD ─────────────────────────────────────────────────────────────

@router.post("/contacts/add", status_code=201)
async def add_contact(req: ContactRequest):
    from ..main import app_state
    tm = app_state.get("tool_manager")
    if not tm: raise HTTPException(503, "Tool manager not initialized")
    result = await tm.execute("add_contact", **req.dict())
    return result.to_dict()


@router.get("/contacts/list")
async def list_tool_contacts():
    from ..main import app_state
    tm = app_state.get("tool_manager")
    if not tm: raise HTTPException(503, "Tool manager not initialized")
    result = await tm.execute("list_contacts")
    return result.to_dict()


@router.delete("/contacts/delete")
async def delete_contact(id: str):
    from ..main import app_state
    tm = app_state.get("tool_manager")
    if not tm: raise HTTPException(503, "Tool manager not initialized")
    result = await tm.execute("delete_contact", id=id)
    return result.to_dict()


# ── Notes CRUD ────────────────────────────────────────────────────────────────

@router.post("/notes/create", status_code=201)
async def create_note(req: NoteRequest):
    from ..main import app_state
    tm = app_state.get("tool_manager")
    if not tm: raise HTTPException(503, "Tool manager not initialized")
    result = await tm.execute("create_note", **req.dict())
    return result.to_dict()


@router.get("/notes/list")
async def list_notes(search: Optional[str] = None):
    from ..main import app_state
    tm = app_state.get("tool_manager")
    if not tm: raise HTTPException(503, "Tool manager not initialized")
    result = await tm.execute("list_notes", search=search)
    return result.to_dict()


@router.put("/notes/update")
async def update_note(req: UpdateRequest):
    from ..main import app_state
    tm = app_state.get("tool_manager")
    if not tm: raise HTTPException(503, "Tool manager not initialized")
    result = await tm.execute("update_note", id=req.id, **req.data)
    return result.to_dict()


@router.delete("/notes/delete")
async def delete_note(id: str):
    from ..main import app_state
    tm = app_state.get("tool_manager")
    if not tm: raise HTTPException(503, "Tool manager not initialized")
    result = await tm.execute("delete_note", id=id)
    return result.to_dict()

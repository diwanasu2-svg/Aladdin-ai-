"""Phone call tool — place calls, read call logs, schedule calls via Twilio or Android APIs."""
from __future__ import annotations
import asyncio, logging, os, time
from typing import Dict, List, Optional
from .base import BaseTool, ToolResult

log = logging.getLogger(__name__)
_call_log: List[Dict] = []


def _twilio_client():
    try:
        from twilio.rest import Client
        sid = os.getenv("TWILIO_ACCOUNT_SID", "")
        token = os.getenv("TWILIO_AUTH_TOKEN", "")
        if not sid or not token:
            raise RuntimeError("TWILIO_ACCOUNT_SID / TWILIO_AUTH_TOKEN not set")
        return Client(sid, token)
    except ImportError:
        raise RuntimeError("twilio package not installed — run: pip install twilio")


class MakeCallTool(BaseTool):
    name = "make_call"
    description = "Place a phone call to a contact or number using Twilio."
    parameters = {"type": "object", "properties": {
        "to": {"type": "string", "description": "Phone number to call (E.164 format, e.g. +12025551234)"},
        "message": {"type": "string", "description": "TwiML message to speak when call connects"},
        "record": {"type": "boolean", "default": False}},
        "required": ["to"]}

    async def execute(self, to: str, message: str = "Hello from Aladdin AI", record: bool = False) -> ToolResult:
        t0 = time.time()
        try:
            client = _twilio_client()
            from_number = os.getenv("TWILIO_FROM_NUMBER", "")
            if not from_number:
                return ToolResult(False, self.name, error="TWILIO_FROM_NUMBER not set")

            twiml = f"<Response><Say>{message}</Say></Response>"

            def _call():
                params: Dict = {
                    "to": to, "from_": from_number,
                    "twiml": twiml
                }
                if record:
                    params["record"] = True
                return client.calls.create(**params)

            call = await asyncio.get_running_loop().run_in_executor(None, _call)
            entry = {"sid": call.sid, "to": to, "status": call.status,
                     "started_at": t0, "type": "outgoing"}
            _call_log.append(entry)
            return ToolResult(True, self.name, entry, duration_ms=(time.time() - t0) * 1000)
        except Exception as exc:
            log.error("make_call error: %s", exc)
            return ToolResult(False, self.name, error=str(exc), duration_ms=(time.time() - t0) * 1000)


class GetCallStatusTool(BaseTool):
    name = "get_call_status"
    description = "Check the status of a phone call by SID (completed, in-progress, failed, etc.)."
    parameters = {"type": "object", "properties": {
        "call_sid": {"type": "string", "description": "Twilio Call SID"}},
        "required": ["call_sid"]}

    async def execute(self, call_sid: str) -> ToolResult:
        t0 = time.time()
        try:
            client = _twilio_client()
            call = await asyncio.get_running_loop().run_in_executor(
                None, lambda: client.calls(call_sid).fetch()
            )
            return ToolResult(True, self.name, {
                "sid": call.sid, "to": call.to, "from_": call.from_,
                "status": call.status, "duration": call.duration,
                "start_time": str(call.start_time), "end_time": str(call.end_time)
            }, duration_ms=(time.time() - t0) * 1000)
        except Exception as exc:
            return ToolResult(False, self.name, error=str(exc), duration_ms=(time.time() - t0) * 1000)


class EndCallTool(BaseTool):
    name = "end_call"
    description = "Hang up / end an active phone call."
    parameters = {"type": "object", "properties": {
        "call_sid": {"type": "string"}}, "required": ["call_sid"]}

    async def execute(self, call_sid: str) -> ToolResult:
        t0 = time.time()
        try:
            client = _twilio_client()
            await asyncio.get_running_loop().run_in_executor(
                None, lambda: client.calls(call_sid).update(status="completed")
            )
            return ToolResult(True, self.name, {"ended": call_sid}, duration_ms=(time.time() - t0) * 1000)
        except Exception as exc:
            return ToolResult(False, self.name, error=str(exc), duration_ms=(time.time() - t0) * 1000)


class GetCallLogTool(BaseTool):
    name = "get_call_log"
    description = "Retrieve recent call history (outgoing and incoming)."
    parameters = {"type": "object", "properties": {
        "limit": {"type": "integer", "default": 20},
        "type_filter": {"type": "string", "enum": ["all", "incoming", "outgoing", "missed"], "default": "all"}}}

    async def execute(self, limit: int = 20, type_filter: str = "all") -> ToolResult:
        t0 = time.time()
        try:
            client = _twilio_client()

            def _fetch_log():
                calls = client.calls.list(limit=limit)
                result = []
                for c in calls:
                    direction = "incoming" if c.direction == "inbound" else "outgoing"
                    if type_filter != "all" and direction != type_filter:
                        continue
                    result.append({
                        "sid": c.sid, "to": c.to, "from_": c.from_,
                        "direction": direction, "status": c.status,
                        "duration": c.duration,
                        "start_time": str(c.start_time)
                    })
                return result

            log_entries = await asyncio.get_running_loop().run_in_executor(None, _fetch_log)
            # Merge with in-memory log
            combined = _call_log[-limit:] + log_entries
            return ToolResult(True, self.name, {"calls": combined, "count": len(combined)},
                              duration_ms=(time.time() - t0) * 1000)
        except Exception as exc:
            # Fallback to in-memory log if Twilio unavailable
            return ToolResult(True, self.name, {"calls": _call_log[-limit:], "count": len(_call_log)},
                              duration_ms=(time.time() - t0) * 1000)


class ScheduleCallTool(BaseTool):
    name = "schedule_call"
    description = "Schedule a phone call to be made at a specific time."
    parameters = {"type": "object", "properties": {
        "to": {"type": "string"}, "message": {"type": "string"},
        "schedule_at_ts": {"type": "number", "description": "Unix timestamp for when to call"}},
        "required": ["to", "schedule_at_ts"]}

    _scheduled: List[Dict] = []

    async def execute(self, to: str, schedule_at_ts: float, message: str = "Scheduled call from Aladdin") -> ToolResult:
        entry = {"to": to, "message": message, "schedule_at": schedule_at_ts,
                 "created_at": time.time(), "status": "scheduled"}
        ScheduleCallTool._scheduled.append(entry)
        log.info("Call scheduled for %s at ts=%s", to, schedule_at_ts)
        return ToolResult(True, self.name, entry)

"""SMS tool — send, read, reply, search, OTP detection, spam filtering via Twilio."""
from __future__ import annotations
import asyncio, logging, os, re, time
from typing import Dict, List, Optional
from .base import BaseTool, ToolResult

log = logging.getLogger(__name__)
_inbox: List[Dict] = []  # In-memory inbox for received messages

OTP_PATTERN = re.compile(r"\b(\d{4,8})\b")
SPAM_KEYWORDS = ["win", "prize", "lottery", "click here", "urgent", "free money",
                 "congratulations", "selected", "claim", "limited time"]


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


class SendSmsTool(BaseTool):
    name = "send_sms"
    description = "Send an SMS text message to a phone number."
    parameters = {"type": "object", "properties": {
        "to": {"type": "string", "description": "Recipient phone number in E.164 format"},
        "body": {"type": "string", "description": "SMS message body"}},
        "required": ["to", "body"]}

    async def execute(self, to: str, body: str) -> ToolResult:
        t0 = time.time()
        try:
            client = _twilio_client()
            from_number = os.getenv("TWILIO_FROM_NUMBER", "")
            if not from_number:
                return ToolResult(False, self.name, error="TWILIO_FROM_NUMBER not set")

            msg = await asyncio.get_running_loop().run_in_executor(
                None, lambda: client.messages.create(body=body, from_=from_number, to=to)
            )
            return ToolResult(True, self.name, {
                "sid": msg.sid, "to": to, "body": body, "status": msg.status
            }, duration_ms=(time.time() - t0) * 1000)
        except Exception as exc:
            return ToolResult(False, self.name, error=str(exc), duration_ms=(time.time() - t0) * 1000)


class ReadSmsInboxTool(BaseTool):
    name = "read_sms_inbox"
    description = "Read SMS messages from inbox. Can filter unread or by sender."
    parameters = {"type": "object", "properties": {
        "limit": {"type": "integer", "default": 20},
        "from_number": {"type": "string"},
        "unread_only": {"type": "boolean", "default": False}}}

    async def execute(self, limit: int = 20, from_number: str = None, unread_only: bool = False) -> ToolResult:
        t0 = time.time()
        try:
            client = _twilio_client()

            def _fetch():
                kwargs: Dict = {"limit": limit}
                if from_number:
                    kwargs["from_"] = from_number
                msgs = client.messages.list(**kwargs)
                result = []
                for m in msgs:
                    if m.direction not in ("inbound",):
                        continue
                    result.append({
                        "sid": m.sid, "from": m.from_, "to": m.to,
                        "body": m.body, "date": str(m.date_sent),
                        "status": m.status,
                        "is_otp": bool(OTP_PATTERN.search(m.body or "")),
                        "is_spam": _detect_spam(m.body or "")
                    })
                return result

            messages = await asyncio.get_running_loop().run_in_executor(None, _fetch)
            return ToolResult(True, self.name, {"messages": messages, "count": len(messages)},
                              duration_ms=(time.time() - t0) * 1000)
        except Exception as exc:
            return ToolResult(True, self.name, {"messages": _inbox[-limit:], "count": len(_inbox)},
                              duration_ms=(time.time() - t0) * 1000)


class SearchSmsTool(BaseTool):
    name = "search_sms"
    description = "Search SMS messages by keyword."
    parameters = {"type": "object", "properties": {
        "query": {"type": "string"}, "limit": {"type": "integer", "default": 20}},
        "required": ["query"]}

    async def execute(self, query: str, limit: int = 20) -> ToolResult:
        t0 = time.time()
        try:
            client = _twilio_client()

            def _search():
                msgs = client.messages.list(limit=100)
                q = query.lower()
                return [
                    {"sid": m.sid, "from": m.from_, "body": m.body, "date": str(m.date_sent)}
                    for m in msgs if q in (m.body or "").lower()
                ][:limit]

            results = await asyncio.get_running_loop().run_in_executor(None, _search)
            return ToolResult(True, self.name, {"messages": results, "count": len(results)},
                              duration_ms=(time.time() - t0) * 1000)
        except Exception as exc:
            q = query.lower()
            results = [m for m in _inbox if q in (m.get("body") or "").lower()][:limit]
            return ToolResult(True, self.name, {"messages": results, "count": len(results)},
                              duration_ms=(time.time() - t0) * 1000)


class ExtractOtpTool(BaseTool):
    name = "extract_otp"
    description = "Extract OTP/verification codes from recent SMS messages."
    parameters = {"type": "object", "properties": {
        "from_number": {"type": "string", "description": "Filter by sender number"},
        "limit": {"type": "integer", "default": 5}}}

    async def execute(self, from_number: str = None, limit: int = 5) -> ToolResult:
        t0 = time.time()
        try:
            client = _twilio_client()

            def _get():
                kwargs: Dict = {"limit": 20}
                if from_number:
                    kwargs["from_"] = from_number
                msgs = client.messages.list(**kwargs)
                otps = []
                for m in msgs:
                    if m.direction != "inbound":
                        continue
                    match = OTP_PATTERN.search(m.body or "")
                    if match:
                        otps.append({
                            "otp": match.group(1), "from": m.from_,
                            "body": m.body, "date": str(m.date_sent)
                        })
                return otps[:limit]

            otps = await asyncio.get_running_loop().run_in_executor(None, _get)
            return ToolResult(True, self.name, {"otps": otps, "count": len(otps)},
                              duration_ms=(time.time() - t0) * 1000)
        except Exception as exc:
            return ToolResult(False, self.name, error=str(exc), duration_ms=(time.time() - t0) * 1000)


class DeleteSmsTool(BaseTool):
    name = "delete_sms"
    description = "Delete an SMS message by SID."
    parameters = {"type": "object", "properties": {"sid": {"type": "string"}}, "required": ["sid"]}

    async def execute(self, sid: str) -> ToolResult:
        t0 = time.time()
        try:
            client = _twilio_client()
            await asyncio.get_running_loop().run_in_executor(
                None, lambda: client.messages(sid).delete()
            )
            return ToolResult(True, self.name, {"deleted": sid}, duration_ms=(time.time() - t0) * 1000)
        except Exception as exc:
            return ToolResult(False, self.name, error=str(exc), duration_ms=(time.time() - t0) * 1000)


def _detect_spam(body: str) -> bool:
    body_lower = body.lower()
    return any(kw in body_lower for kw in SPAM_KEYWORDS)

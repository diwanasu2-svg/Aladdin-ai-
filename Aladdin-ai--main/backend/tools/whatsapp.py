"""WhatsApp tool — send messages, media, read chats, reply suggestions via Twilio WhatsApp API."""
from __future__ import annotations
import asyncio, logging, mimetypes, os, time
from pathlib import Path
from typing import Dict, List, Optional
from .base import BaseTool, ToolResult

log = logging.getLogger(__name__)


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


def _wa(number: str) -> str:
    """Prefix number with whatsapp: scheme."""
    return number if number.startswith("whatsapp:") else f"whatsapp:{number}"


class SendWhatsAppMessageTool(BaseTool):
    name = "send_whatsapp_message"
    description = "Send a WhatsApp text message to a phone number."
    parameters = {"type": "object", "properties": {
        "to": {"type": "string", "description": "Recipient number in E.164 format, e.g. +12025551234"},
        "body": {"type": "string", "description": "Message text"}},
        "required": ["to", "body"]}

    async def execute(self, to: str, body: str) -> ToolResult:
        t0 = time.time()
        try:
            client = _twilio_client()
            wa_from = os.getenv("TWILIO_WHATSAPP_FROM", "")
            if not wa_from:
                return ToolResult(False, self.name, error="TWILIO_WHATSAPP_FROM not set")
            msg = await asyncio.get_running_loop().run_in_executor(
                None, lambda: client.messages.create(
                    body=body, from_=_wa(wa_from), to=_wa(to))
            )
            return ToolResult(True, self.name, {"sid": msg.sid, "to": to, "status": msg.status},
                              duration_ms=(time.time() - t0) * 1000)
        except Exception as exc:
            return ToolResult(False, self.name, error=str(exc), duration_ms=(time.time() - t0) * 1000)


class SendWhatsAppMediaTool(BaseTool):
    name = "send_whatsapp_media"
    description = "Send media (image, video, document) via WhatsApp."
    parameters = {"type": "object", "properties": {
        "to": {"type": "string"},
        "media_url": {"type": "string", "description": "Publicly accessible URL of the media file"},
        "caption": {"type": "string", "default": ""}},
        "required": ["to", "media_url"]}

    async def execute(self, to: str, media_url: str, caption: str = "") -> ToolResult:
        t0 = time.time()
        try:
            client = _twilio_client()
            wa_from = os.getenv("TWILIO_WHATSAPP_FROM", "")
            if not wa_from:
                return ToolResult(False, self.name, error="TWILIO_WHATSAPP_FROM not set")
            kwargs: Dict = {"from_": _wa(wa_from), "to": _wa(to), "media_url": [media_url]}
            if caption:
                kwargs["body"] = caption
            msg = await asyncio.get_running_loop().run_in_executor(
                None, lambda: client.messages.create(**kwargs)
            )
            return ToolResult(True, self.name, {"sid": msg.sid, "media_url": media_url},
                              duration_ms=(time.time() - t0) * 1000)
        except Exception as exc:
            return ToolResult(False, self.name, error=str(exc), duration_ms=(time.time() - t0) * 1000)


class ReadWhatsAppChatsTool(BaseTool):
    name = "read_whatsapp_chats"
    description = "Read recent WhatsApp messages received on the configured number."
    parameters = {"type": "object", "properties": {
        "limit": {"type": "integer", "default": 20},
        "from_number": {"type": "string"}}}

    async def execute(self, limit: int = 20, from_number: str = None) -> ToolResult:
        t0 = time.time()
        try:
            client = _twilio_client()

            def _fetch():
                kwargs: Dict = {"limit": limit}
                if from_number:
                    kwargs["from_"] = _wa(from_number)
                msgs = client.messages.list(**kwargs)
                return [
                    {"sid": m.sid, "from": m.from_.replace("whatsapp:", ""),
                     "body": m.body, "date": str(m.date_sent), "media": m.num_media}
                    for m in msgs if "whatsapp" in (m.from_ or "")
                ]

            chats = await asyncio.get_running_loop().run_in_executor(None, _fetch)
            return ToolResult(True, self.name, {"chats": chats, "count": len(chats)},
                              duration_ms=(time.time() - t0) * 1000)
        except Exception as exc:
            return ToolResult(False, self.name, error=str(exc), duration_ms=(time.time() - t0) * 1000)


class GenerateWhatsAppReplyTool(BaseTool):
    name = "generate_whatsapp_reply"
    description = "Generate a smart reply suggestion for a received WhatsApp message."
    parameters = {"type": "object", "properties": {
        "incoming_message": {"type": "string"}, "tone": {"type": "string", "default": "friendly"}},
        "required": ["incoming_message"]}

    async def execute(self, incoming_message: str, tone: str = "friendly") -> ToolResult:
        # Simple rule-based suggestions; replace with LLM call if available
        msg = incoming_message.lower()
        suggestions = []
        if any(w in msg for w in ["hello", "hi", "hey"]):
            suggestions = ["Hey! How are you?", "Hi there!", "Hello! Great to hear from you."]
        elif any(w in msg for w in ["thank", "thanks"]):
            suggestions = ["You're welcome!", "Happy to help!", "Anytime!"]
        elif "?" in msg:
            suggestions = ["I'll get back to you on that.", "Let me check and confirm.", "Sure, one moment!"]
        else:
            suggestions = ["Got it!", "Understood, thanks.", "I'll look into it."]
        return ToolResult(True, self.name, {"suggestions": suggestions, "original": incoming_message})

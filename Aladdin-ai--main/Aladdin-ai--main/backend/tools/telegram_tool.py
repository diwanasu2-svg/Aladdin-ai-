"""Telegram tool — send/receive messages, groups, channels, media via python-telegram-bot."""
from __future__ import annotations
import asyncio, logging, os, time
from pathlib import Path
from typing import Dict, List, Optional
from .base import BaseTool, ToolResult

log = logging.getLogger(__name__)


def _bot():
    try:
        import telegram
        token = os.getenv("TELEGRAM_BOT_TOKEN", "")
        if not token:
            raise RuntimeError("TELEGRAM_BOT_TOKEN not set")
        return telegram.Bot(token=token)
    except ImportError:
        raise RuntimeError("python-telegram-bot not installed — run: pip install python-telegram-bot")


class SendTelegramMessageTool(BaseTool):
    name = "send_telegram_message"
    description = "Send a text message to a Telegram chat or user."
    parameters = {"type": "object", "properties": {
        "chat_id": {"type": "string", "description": "Telegram chat ID or @username"},
        "text": {"type": "string"}, "parse_mode": {"type": "string", "enum": ["Markdown", "HTML", ""], "default": ""}},
        "required": ["chat_id", "text"]}

    async def execute(self, chat_id: str, text: str, parse_mode: str = "") -> ToolResult:
        t0 = time.time()
        try:
            bot = _bot()
            kwargs: Dict = {"chat_id": chat_id, "text": text}
            if parse_mode:
                kwargs["parse_mode"] = parse_mode
            msg = await bot.send_message(**kwargs)
            return ToolResult(True, self.name, {
                "message_id": msg.message_id, "chat_id": chat_id, "text": text
            }, duration_ms=(time.time() - t0) * 1000)
        except Exception as exc:
            return ToolResult(False, self.name, error=str(exc), duration_ms=(time.time() - t0) * 1000)


class SendTelegramPhotoTool(BaseTool):
    name = "send_telegram_photo"
    description = "Send a photo or image to a Telegram chat."
    parameters = {"type": "object", "properties": {
        "chat_id": {"type": "string"},
        "photo_path": {"type": "string", "description": "Local file path or URL"},
        "caption": {"type": "string", "default": ""}},
        "required": ["chat_id", "photo_path"]}

    async def execute(self, chat_id: str, photo_path: str, caption: str = "") -> ToolResult:
        t0 = time.time()
        try:
            bot = _bot()
            p = Path(photo_path)
            if p.exists():
                with open(p, "rb") as f:
                    msg = await bot.send_photo(chat_id=chat_id, photo=f, caption=caption)
            else:
                msg = await bot.send_photo(chat_id=chat_id, photo=photo_path, caption=caption)
            return ToolResult(True, self.name, {"message_id": msg.message_id, "chat_id": chat_id},
                              duration_ms=(time.time() - t0) * 1000)
        except Exception as exc:
            return ToolResult(False, self.name, error=str(exc), duration_ms=(time.time() - t0) * 1000)


class SendTelegramDocumentTool(BaseTool):
    name = "send_telegram_document"
    description = "Send a document/file via Telegram."
    parameters = {"type": "object", "properties": {
        "chat_id": {"type": "string"}, "file_path": {"type": "string"}, "caption": {"type": "string", "default": ""}},
        "required": ["chat_id", "file_path"]}

    async def execute(self, chat_id: str, file_path: str, caption: str = "") -> ToolResult:
        t0 = time.time()
        try:
            bot = _bot()
            with open(file_path, "rb") as f:
                msg = await bot.send_document(chat_id=chat_id, document=f, caption=caption)
            return ToolResult(True, self.name, {"message_id": msg.message_id},
                              duration_ms=(time.time() - t0) * 1000)
        except Exception as exc:
            return ToolResult(False, self.name, error=str(exc), duration_ms=(time.time() - t0) * 1000)


class GetTelegramUpdatesTool(BaseTool):
    name = "get_telegram_updates"
    description = "Fetch recent messages received by the Telegram bot."
    parameters = {"type": "object", "properties": {
        "limit": {"type": "integer", "default": 10},
        "offset": {"type": "integer", "default": 0}}}

    async def execute(self, limit: int = 10, offset: int = 0) -> ToolResult:
        t0 = time.time()
        try:
            bot = _bot()
            updates = await bot.get_updates(limit=limit, offset=offset)
            messages = []
            for upd in updates:
                if upd.message:
                    m = upd.message
                    messages.append({
                        "update_id": upd.update_id,
                        "chat_id": m.chat.id,
                        "from": m.from_user.username if m.from_user else None,
                        "text": m.text,
                        "date": m.date.isoformat()
                    })
            return ToolResult(True, self.name, {"messages": messages, "count": len(messages)},
                              duration_ms=(time.time() - t0) * 1000)
        except Exception as exc:
            return ToolResult(False, self.name, error=str(exc), duration_ms=(time.time() - t0) * 1000)


class ForwardTelegramMessageTool(BaseTool):
    name = "forward_telegram_message"
    description = "Forward a message from one Telegram chat to another."
    parameters = {"type": "object", "properties": {
        "from_chat_id": {"type": "string"}, "to_chat_id": {"type": "string"},
        "message_id": {"type": "integer"}},
        "required": ["from_chat_id", "to_chat_id", "message_id"]}

    async def execute(self, from_chat_id: str, to_chat_id: str, message_id: int) -> ToolResult:
        t0 = time.time()
        try:
            bot = _bot()
            msg = await bot.forward_message(
                chat_id=to_chat_id, from_chat_id=from_chat_id, message_id=message_id
            )
            return ToolResult(True, self.name, {"forwarded_message_id": msg.message_id},
                              duration_ms=(time.time() - t0) * 1000)
        except Exception as exc:
            return ToolResult(False, self.name, error=str(exc), duration_ms=(time.time() - t0) * 1000)


class GetTelegramChatInfoTool(BaseTool):
    name = "get_telegram_chat_info"
    description = "Get information about a Telegram chat, group, or channel."
    parameters = {"type": "object", "properties": {
        "chat_id": {"type": "string"}}, "required": ["chat_id"]}

    async def execute(self, chat_id: str) -> ToolResult:
        t0 = time.time()
        try:
            bot = _bot()
            chat = await bot.get_chat(chat_id=chat_id)
            return ToolResult(True, self.name, {
                "id": chat.id, "type": chat.type, "title": chat.title,
                "username": chat.username, "description": chat.description,
                "member_count": getattr(chat, "member_count", None)
            }, duration_ms=(time.time() - t0) * 1000)
        except Exception as exc:
            return ToolResult(False, self.name, error=str(exc), duration_ms=(time.time() - t0) * 1000)

"""
backend/tools/telegram.py — Phase 9 fix item 9.7
=================================================
Telegram Bot tool for sending messages, receiving updates, and managing chats
via the Telegram Bot API.

Requires: TELEGRAM_BOT_TOKEN environment variable
"""

from __future__ import annotations

import logging
import os
from typing import Any, Dict, Optional

log = logging.getLogger(__name__)

try:
    import httpx
    _HTTPX_AVAILABLE = True
except ImportError:
    _HTTPX_AVAILABLE = False
    log.warning("httpx not installed — Telegram tool will use urllib fallback")


class TelegramTool:
    """Send and receive Telegram messages via Bot API."""

    name = "telegram"
    description = "Send messages, files, and manage Telegram chats via Telegram Bot API"

    BASE_URL = "https://api.telegram.org"

    def __init__(self) -> None:
        self.bot_token = os.getenv("TELEGRAM_BOT_TOKEN", "")
        if not self.bot_token:
            log.warning("TELEGRAM_BOT_TOKEN not set — Telegram tool disabled")

    @property
    def is_configured(self) -> bool:
        return bool(self.bot_token)

    def execute(self, params: Dict[str, Any]) -> Dict[str, Any]:
        """
        Execute a Telegram action.

        Params:
            action: str — "send_message" | "get_updates" | "send_photo" |
                         "get_chat" | "send_document" | "forward_message"
            chat_id: str | int — Target chat/user ID or @username
            text: str — Message text (for send_message)
            photo_url: str — URL of photo to send
            document_url: str — URL of document to send
            parse_mode: str — "HTML" | "Markdown" (optional)
        """
        if not self.is_configured:
            return {"success": False, "error": "TELEGRAM_BOT_TOKEN not configured"}

        action = params.get("action", "send_message")

        try:
            if action == "send_message":
                return self._send_message(params)
            elif action == "get_updates":
                return self._get_updates(params)
            elif action == "send_photo":
                return self._send_photo(params)
            elif action == "get_chat":
                return self._get_chat(params)
            elif action == "send_document":
                return self._send_document(params)
            elif action == "forward_message":
                return self._forward_message(params)
            elif action == "get_me":
                return self._get_me()
            else:
                return {"success": False, "error": f"Unknown Telegram action: {action}"}
        except Exception as exc:
            log.error("TelegramTool error [%s]: %s", action, exc)
            return {"success": False, "error": str(exc)}

    def _send_message(self, params: Dict[str, Any]) -> Dict[str, Any]:
        chat_id = params.get("chat_id", "")
        text = params.get("text", "")
        parse_mode = params.get("parse_mode", "HTML")

        if not chat_id or not text:
            return {"success": False, "error": "chat_id and text are required"}

        payload = {
            "chat_id": chat_id,
            "text": text,
            "parse_mode": parse_mode,
        }

        result = self._api_call("sendMessage", payload)
        if result.get("ok"):
            msg = result.get("result", {})
            log.info("Telegram message sent to %s: %s", chat_id, str(text)[:50])
            return {"success": True, "message_id": msg.get("message_id"), "chat_id": chat_id}
        return {"success": False, "error": result.get("description", "Unknown error")}

    def _get_updates(self, params: Dict[str, Any]) -> Dict[str, Any]:
        offset = params.get("offset", 0)
        limit = min(params.get("limit", 10), 100)

        result = self._api_call("getUpdates", {"offset": offset, "limit": limit})
        if result.get("ok"):
            updates = result.get("result", [])
            messages = []
            for update in updates:
                msg = update.get("message", {})
                if msg:
                    messages.append({
                        "update_id": update.get("update_id"),
                        "from": msg.get("from", {}).get("first_name", "Unknown"),
                        "chat_id": msg.get("chat", {}).get("id"),
                        "text": msg.get("text", ""),
                        "date": msg.get("date"),
                    })
            return {"success": True, "updates": messages, "count": len(messages)}
        return {"success": False, "error": result.get("description", "Unknown error")}

    def _send_photo(self, params: Dict[str, Any]) -> Dict[str, Any]:
        chat_id = params.get("chat_id", "")
        photo_url = params.get("photo_url", "")
        caption = params.get("caption", "")

        if not chat_id or not photo_url:
            return {"success": False, "error": "chat_id and photo_url are required"}

        payload = {"chat_id": chat_id, "photo": photo_url, "caption": caption}
        result = self._api_call("sendPhoto", payload)

        if result.get("ok"):
            return {"success": True, "message_id": result.get("result", {}).get("message_id")}
        return {"success": False, "error": result.get("description", "Unknown error")}

    def _get_chat(self, params: Dict[str, Any]) -> Dict[str, Any]:
        chat_id = params.get("chat_id", "")
        if not chat_id:
            return {"success": False, "error": "chat_id is required"}

        result = self._api_call("getChat", {"chat_id": chat_id})
        if result.get("ok"):
            chat = result.get("result", {})
            return {
                "success": True,
                "id": chat.get("id"),
                "title": chat.get("title") or chat.get("first_name", ""),
                "type": chat.get("type"),
                "username": chat.get("username", ""),
            }
        return {"success": False, "error": result.get("description", "Unknown error")}

    def _send_document(self, params: Dict[str, Any]) -> Dict[str, Any]:
        chat_id = params.get("chat_id", "")
        document_url = params.get("document_url", "")
        caption = params.get("caption", "")

        if not chat_id or not document_url:
            return {"success": False, "error": "chat_id and document_url are required"}

        payload = {"chat_id": chat_id, "document": document_url, "caption": caption}
        result = self._api_call("sendDocument", payload)

        if result.get("ok"):
            return {"success": True, "message_id": result.get("result", {}).get("message_id")}
        return {"success": False, "error": result.get("description", "Unknown error")}

    def _forward_message(self, params: Dict[str, Any]) -> Dict[str, Any]:
        chat_id = params.get("chat_id", "")
        from_chat_id = params.get("from_chat_id", "")
        message_id = params.get("message_id", 0)

        if not all([chat_id, from_chat_id, message_id]):
            return {"success": False, "error": "chat_id, from_chat_id, and message_id are required"}

        payload = {"chat_id": chat_id, "from_chat_id": from_chat_id, "message_id": message_id}
        result = self._api_call("forwardMessage", payload)

        if result.get("ok"):
            return {"success": True, "message_id": result.get("result", {}).get("message_id")}
        return {"success": False, "error": result.get("description", "Unknown error")}

    def _get_me(self) -> Dict[str, Any]:
        result = self._api_call("getMe", {})
        if result.get("ok"):
            bot = result.get("result", {})
            return {
                "success": True,
                "id": bot.get("id"),
                "username": bot.get("username"),
                "first_name": bot.get("first_name"),
                "can_join_groups": bot.get("can_join_groups"),
            }
        return {"success": False, "error": result.get("description", "Unknown error")}

    def _api_call(self, method: str, payload: Dict[str, Any]) -> Dict[str, Any]:
        url = f"{self.BASE_URL}/bot{self.bot_token}/{method}"

        if _HTTPX_AVAILABLE:
            with httpx.Client(timeout=10.0) as client:
                response = client.post(url, json=payload)
                response.raise_for_status()
                return response.json()
        else:
            import json
            import urllib.request
            data = json.dumps(payload).encode("utf-8")
            req = urllib.request.Request(url, data=data, headers={"Content-Type": "application/json"})
            with urllib.request.urlopen(req, timeout=10) as resp:
                return json.loads(resp.read().decode("utf-8"))


# Module-level singleton
_tool = None


def get_tool() -> TelegramTool:
    global _tool
    if _tool is None:
        _tool = TelegramTool()
    return _tool


def execute(params: Dict[str, Any]) -> Dict[str, Any]:
    """Module-level execute for plugin registration compatibility."""
    return get_tool().execute(params)

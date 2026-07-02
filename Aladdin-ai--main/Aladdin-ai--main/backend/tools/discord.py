"""
backend/tools/discord.py — Phase 9 fix item 9.8
================================================
Discord Bot tool for sending messages, managing channels, and interacting
with Discord servers via the Discord REST API.

Requires: DISCORD_BOT_TOKEN environment variable
"""

from __future__ import annotations

import logging
import os
from typing import Any, Dict, List, Optional

log = logging.getLogger(__name__)

try:
    import httpx
    _HTTPX_AVAILABLE = True
except ImportError:
    _HTTPX_AVAILABLE = False
    log.warning("httpx not installed — Discord tool will use urllib fallback")


class DiscordTool:
    """Send and receive Discord messages via Discord REST API."""

    name = "discord"
    description = "Send messages, manage channels, and interact with Discord servers"

    BASE_URL = "https://discord.com/api/v10"

    def __init__(self) -> None:
        self.bot_token = os.getenv("DISCORD_BOT_TOKEN", "")
        if not self.bot_token:
            log.warning("DISCORD_BOT_TOKEN not set — Discord tool disabled")

    @property
    def is_configured(self) -> bool:
        return bool(self.bot_token)

    @property
    def _headers(self) -> Dict[str, str]:
        return {
            "Authorization": f"Bot {self.bot_token}",
            "Content-Type": "application/json",
        }

    def execute(self, params: Dict[str, Any]) -> Dict[str, Any]:
        """
        Execute a Discord action.

        Params:
            action: str — "send_message" | "get_messages" | "send_file" |
                         "get_channels" | "create_channel" | "delete_message" |
                         "get_guilds" | "get_me"
            channel_id: str — Discord channel ID
            content: str — Message content
            guild_id: str — Server/Guild ID
            limit: int — Number of messages to retrieve (max 100)
            embed: dict — Discord embed object
        """
        if not self.is_configured:
            return {"success": False, "error": "DISCORD_BOT_TOKEN not configured"}

        action = params.get("action", "send_message")

        try:
            if action == "send_message":
                return self._send_message(params)
            elif action == "get_messages":
                return self._get_messages(params)
            elif action == "send_file":
                return self._send_file(params)
            elif action == "get_channels":
                return self._get_channels(params)
            elif action == "create_channel":
                return self._create_channel(params)
            elif action == "delete_message":
                return self._delete_message(params)
            elif action == "get_guilds":
                return self._get_guilds()
            elif action == "get_me":
                return self._get_me()
            elif action == "add_reaction":
                return self._add_reaction(params)
            elif action == "pin_message":
                return self._pin_message(params)
            else:
                return {"success": False, "error": f"Unknown Discord action: {action}"}
        except Exception as exc:
            log.error("DiscordTool error [%s]: %s", action, exc)
            return {"success": False, "error": str(exc)}

    def _send_message(self, params: Dict[str, Any]) -> Dict[str, Any]:
        channel_id = params.get("channel_id", "")
        content = params.get("content", "")
        embed = params.get("embed")

        if not channel_id:
            return {"success": False, "error": "channel_id is required"}
        if not content and not embed:
            return {"success": False, "error": "content or embed is required"}

        payload: Dict[str, Any] = {}
        if content:
            payload["content"] = content
        if embed:
            payload["embeds"] = [embed]

        result = self._api_call("POST", f"/channels/{channel_id}/messages", payload)
        if "id" in result:
            log.info("Discord message sent to channel %s", channel_id)
            return {
                "success": True,
                "message_id": result["id"],
                "channel_id": channel_id,
                "timestamp": result.get("timestamp"),
            }
        return {"success": False, "error": result.get("message", "Unknown error")}

    def _get_messages(self, params: Dict[str, Any]) -> Dict[str, Any]:
        channel_id = params.get("channel_id", "")
        limit = min(params.get("limit", 10), 100)

        if not channel_id:
            return {"success": False, "error": "channel_id is required"}

        result = self._api_call("GET", f"/channels/{channel_id}/messages?limit={limit}")

        if isinstance(result, list):
            messages = [
                {
                    "id": msg.get("id"),
                    "author": msg.get("author", {}).get("username", "Unknown"),
                    "content": msg.get("content", ""),
                    "timestamp": msg.get("timestamp"),
                    "attachments": len(msg.get("attachments", [])),
                }
                for msg in result
            ]
            return {"success": True, "messages": messages, "count": len(messages)}
        return {"success": False, "error": result.get("message", "Unknown error")}

    def _send_file(self, params: Dict[str, Any]) -> Dict[str, Any]:
        channel_id = params.get("channel_id", "")
        file_url = params.get("file_url", "")
        content = params.get("content", "")

        if not channel_id:
            return {"success": False, "error": "channel_id is required"}

        # For URL-based files, we send as embed
        payload = {
            "content": content,
            "embeds": [{"url": file_url, "description": "File attachment"}] if file_url else [],
        }

        result = self._api_call("POST", f"/channels/{channel_id}/messages", payload)
        if "id" in result:
            return {"success": True, "message_id": result["id"]}
        return {"success": False, "error": result.get("message", "Unknown error")}

    def _get_channels(self, params: Dict[str, Any]) -> Dict[str, Any]:
        guild_id = params.get("guild_id", "")
        if not guild_id:
            return {"success": False, "error": "guild_id is required"}

        result = self._api_call("GET", f"/guilds/{guild_id}/channels")

        if isinstance(result, list):
            channels = [
                {
                    "id": ch.get("id"),
                    "name": ch.get("name"),
                    "type": ch.get("type"),
                    "position": ch.get("position"),
                }
                for ch in result
                if ch.get("type") in (0, 2, 4)  # text, voice, category
            ]
            return {"success": True, "channels": channels, "count": len(channels)}
        return {"success": False, "error": result.get("message", "Unknown error")}

    def _create_channel(self, params: Dict[str, Any]) -> Dict[str, Any]:
        guild_id = params.get("guild_id", "")
        name = params.get("name", "")
        channel_type = params.get("type", 0)  # 0 = text

        if not guild_id or not name:
            return {"success": False, "error": "guild_id and name are required"}

        result = self._api_call("POST", f"/guilds/{guild_id}/channels",
                                {"name": name, "type": channel_type})
        if "id" in result:
            return {"success": True, "channel_id": result["id"], "name": result["name"]}
        return {"success": False, "error": result.get("message", "Unknown error")}

    def _delete_message(self, params: Dict[str, Any]) -> Dict[str, Any]:
        channel_id = params.get("channel_id", "")
        message_id = params.get("message_id", "")

        if not channel_id or not message_id:
            return {"success": False, "error": "channel_id and message_id are required"}

        self._api_call("DELETE", f"/channels/{channel_id}/messages/{message_id}")
        return {"success": True, "deleted_message_id": message_id}

    def _get_guilds(self) -> Dict[str, Any]:
        result = self._api_call("GET", "/users/@me/guilds")
        if isinstance(result, list):
            guilds = [
                {"id": g.get("id"), "name": g.get("name"), "owner": g.get("owner", False)}
                for g in result
            ]
            return {"success": True, "guilds": guilds, "count": len(guilds)}
        return {"success": False, "error": result.get("message", "Unknown error")}

    def _get_me(self) -> Dict[str, Any]:
        result = self._api_call("GET", "/users/@me")
        if "id" in result:
            return {
                "success": True,
                "id": result["id"],
                "username": result["username"],
                "discriminator": result.get("discriminator", "0"),
                "bot": result.get("bot", False),
            }
        return {"success": False, "error": result.get("message", "Unknown error")}

    def _add_reaction(self, params: Dict[str, Any]) -> Dict[str, Any]:
        channel_id = params.get("channel_id", "")
        message_id = params.get("message_id", "")
        emoji = params.get("emoji", "👍")

        if not channel_id or not message_id:
            return {"success": False, "error": "channel_id and message_id are required"}

        import urllib.parse
        encoded_emoji = urllib.parse.quote(emoji)
        self._api_call("PUT", f"/channels/{channel_id}/messages/{message_id}/reactions/{encoded_emoji}/@me")
        return {"success": True, "reaction": emoji, "message_id": message_id}

    def _pin_message(self, params: Dict[str, Any]) -> Dict[str, Any]:
        channel_id = params.get("channel_id", "")
        message_id = params.get("message_id", "")

        if not channel_id or not message_id:
            return {"success": False, "error": "channel_id and message_id are required"}

        self._api_call("PUT", f"/channels/{channel_id}/pins/{message_id}")
        return {"success": True, "pinned_message_id": message_id}

    def _api_call(self, method: str, endpoint: str, payload: Optional[Dict[str, Any]] = None) -> Any:
        url = f"{self.BASE_URL}{endpoint}"

        if _HTTPX_AVAILABLE:
            with httpx.Client(timeout=10.0, headers=self._headers) as client:
                if method == "GET":
                    response = client.get(url)
                elif method == "POST":
                    response = client.post(url, json=payload or {})
                elif method == "PUT":
                    response = client.put(url, json=payload or {})
                elif method == "DELETE":
                    response = client.delete(url)
                    return {}
                else:
                    raise ValueError(f"Unsupported method: {method}")

                if response.status_code in (200, 201):
                    return response.json()
                elif response.status_code == 204:
                    return {}
                else:
                    return {"message": f"HTTP {response.status_code}: {response.text[:200]}"}
        else:
            import json
            import urllib.request

            req = urllib.request.Request(url, headers=self._headers, method=method)
            if payload is not None:
                req.data = json.dumps(payload).encode("utf-8")

            try:
                with urllib.request.urlopen(req, timeout=10) as resp:
                    body = resp.read().decode("utf-8")
                    return json.loads(body) if body else {}
            except urllib.error.HTTPError as e:
                body = e.read().decode("utf-8")
                try:
                    return json.loads(body)
                except Exception:
                    return {"message": f"HTTP {e.code}: {body[:200]}"}


# Module-level singleton
_tool = None


def get_tool() -> DiscordTool:
    global _tool
    if _tool is None:
        _tool = DiscordTool()
    return _tool


def execute(params: Dict[str, Any]) -> Dict[str, Any]:
    """Module-level execute for plugin registration compatibility."""
    return get_tool().execute(params)

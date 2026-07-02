"""Discord tool — send messages, manage servers/channels, voice, reactions via discord.py."""
from __future__ import annotations
import asyncio, logging, os, time
from pathlib import Path
from typing import Dict, List, Optional
from .base import BaseTool, ToolResult

log = logging.getLogger(__name__)


def _get_token() -> str:
    token = os.getenv("DISCORD_BOT_TOKEN", "")
    if not token:
        raise RuntimeError("DISCORD_BOT_TOKEN not set")
    return token


async def _http(method: str, endpoint: str, **kwargs):
    """Thin async wrapper around the Discord REST API."""
    import aiohttp
    token = _get_token()
    base = "https://discord.com/api/v10"
    headers = {"Authorization": f"Bot {token}", "Content-Type": "application/json"}
    async with aiohttp.ClientSession(headers=headers) as session:
        fn = getattr(session, method)
        async with fn(f"{base}{endpoint}", **kwargs) as resp:
            if resp.status in (200, 201):
                return await resp.json()
            text = await resp.text()
            raise RuntimeError(f"Discord API error {resp.status}: {text}")


class SendDiscordMessageTool(BaseTool):
    name = "send_discord_message"
    description = "Send a text message to a Discord channel."
    parameters = {"type": "object", "properties": {
        "channel_id": {"type": "string"},
        "content": {"type": "string"},
        "reply_to_message_id": {"type": "string", "description": "Optional message ID to reply to"}},
        "required": ["channel_id", "content"]}

    async def execute(self, channel_id: str, content: str, reply_to_message_id: str = None) -> ToolResult:
        t0 = time.time()
        try:
            payload: Dict = {"content": content}
            if reply_to_message_id:
                payload["message_reference"] = {"message_id": reply_to_message_id}
            data = await _http("post", f"/channels/{channel_id}/messages", json=payload)
            return ToolResult(True, self.name, {"message_id": data["id"], "channel_id": channel_id},
                              duration_ms=(time.time() - t0) * 1000)
        except Exception as exc:
            return ToolResult(False, self.name, error=str(exc), duration_ms=(time.time() - t0) * 1000)


class SendDiscordFileTool(BaseTool):
    name = "send_discord_file"
    description = "Upload and send a file to a Discord channel."
    parameters = {"type": "object", "properties": {
        "channel_id": {"type": "string"}, "file_path": {"type": "string"},
        "message": {"type": "string", "default": ""}},
        "required": ["channel_id", "file_path"]}

    async def execute(self, channel_id: str, file_path: str, message: str = "") -> ToolResult:
        t0 = time.time()
        try:
            import aiohttp
            token = _get_token()
            headers = {"Authorization": f"Bot {token}"}
            p = Path(file_path)
            with open(p, "rb") as f:
                form = aiohttp.FormData()
                if message:
                    form.add_field("content", message)
                form.add_field("file", f, filename=p.name)
                async with aiohttp.ClientSession() as session:
                    async with session.post(
                        f"https://discord.com/api/v10/channels/{channel_id}/messages",
                        headers=headers, data=form
                    ) as resp:
                        data = await resp.json()
            return ToolResult(True, self.name, {"message_id": data.get("id"), "file": p.name},
                              duration_ms=(time.time() - t0) * 1000)
        except Exception as exc:
            return ToolResult(False, self.name, error=str(exc), duration_ms=(time.time() - t0) * 1000)


class GetDiscordMessagesTool(BaseTool):
    name = "get_discord_messages"
    description = "Fetch recent messages from a Discord channel."
    parameters = {"type": "object", "properties": {
        "channel_id": {"type": "string"}, "limit": {"type": "integer", "default": 20}},
        "required": ["channel_id"]}

    async def execute(self, channel_id: str, limit: int = 20) -> ToolResult:
        t0 = time.time()
        try:
            data = await _http("get", f"/channels/{channel_id}/messages", params={"limit": min(limit, 100)})
            messages = [
                {"id": m["id"], "author": m["author"]["username"],
                 "content": m["content"], "timestamp": m["timestamp"]}
                for m in data
            ]
            return ToolResult(True, self.name, {"messages": messages, "count": len(messages)},
                              duration_ms=(time.time() - t0) * 1000)
        except Exception as exc:
            return ToolResult(False, self.name, error=str(exc), duration_ms=(time.time() - t0) * 1000)


class AddDiscordReactionTool(BaseTool):
    name = "add_discord_reaction"
    description = "React to a Discord message with an emoji."
    parameters = {"type": "object", "properties": {
        "channel_id": {"type": "string"}, "message_id": {"type": "string"},
        "emoji": {"type": "string", "description": "Emoji character or name:id"}},
        "required": ["channel_id", "message_id", "emoji"]}

    async def execute(self, channel_id: str, message_id: str, emoji: str) -> ToolResult:
        t0 = time.time()
        try:
            import urllib.parse
            encoded = urllib.parse.quote(emoji)
            await _http("put", f"/channels/{channel_id}/messages/{message_id}/reactions/{encoded}/@me")
            return ToolResult(True, self.name, {"reacted": emoji, "message_id": message_id},
                              duration_ms=(time.time() - t0) * 1000)
        except Exception as exc:
            return ToolResult(False, self.name, error=str(exc), duration_ms=(time.time() - t0) * 1000)


class ListDiscordServersTool(BaseTool):
    name = "list_discord_servers"
    description = "List all Discord servers (guilds) the bot is a member of."
    parameters = {"type": "object", "properties": {}}

    async def execute(self) -> ToolResult:
        t0 = time.time()
        try:
            guilds = await _http("get", "/users/@me/guilds")
            servers = [{"id": g["id"], "name": g["name"], "icon": g.get("icon")} for g in guilds]
            return ToolResult(True, self.name, {"servers": servers, "count": len(servers)},
                              duration_ms=(time.time() - t0) * 1000)
        except Exception as exc:
            return ToolResult(False, self.name, error=str(exc), duration_ms=(time.time() - t0) * 1000)


class ListDiscordChannelsTool(BaseTool):
    name = "list_discord_channels"
    description = "List all channels in a Discord server."
    parameters = {"type": "object", "properties": {
        "guild_id": {"type": "string"}}, "required": ["guild_id"]}

    async def execute(self, guild_id: str) -> ToolResult:
        t0 = time.time()
        try:
            channels = await _http("get", f"/guilds/{guild_id}/channels")
            result = [{"id": c["id"], "name": c["name"], "type": c["type"]} for c in channels]
            return ToolResult(True, self.name, {"channels": result, "count": len(result)},
                              duration_ms=(time.time() - t0) * 1000)
        except Exception as exc:
            return ToolResult(False, self.name, error=str(exc), duration_ms=(time.time() - t0) * 1000)

"""Built-in tool/function definitions for Aladdin's tool-calling system."""

from __future__ import annotations

import datetime
import logging
import os
import platform
from pathlib import Path
from typing import TYPE_CHECKING, Any, Callable, Dict, Optional

if TYPE_CHECKING:
    from .llm import OllamaClient
    from .memory import ConversationMemory
    from .search import InternetSearch
    from .calendar_manager import CalendarManager, ReminderManager

log = logging.getLogger(__name__)


def register_all_tools(
    llm: "OllamaClient",
    memory: "ConversationMemory",
    search: "InternetSearch",
    calendar: Optional["CalendarManager"] = None,
    reminder: Optional["ReminderManager"] = None,
) -> None:
    """Register all built-in tools with the LLM client."""

    # ── Time & Date ─────────────────────────────────────────────────
    llm.register_tool(
        name="get_current_time",
        description="Get the current date and time.",
        parameters={"type": "object", "properties": {}, "required": []},
        handler=lambda: datetime.datetime.now().strftime("%A, %B %d %Y, %I:%M %p"),
    )

    # ── Memory tools ─────────────────────────────────────────────────
    llm.register_tool(
        name="remember_fact",
        description="Store an important fact about the user for future conversations.",
        parameters={
            "type": "object",
            "properties": {
                "key": {
                    "type": "string",
                    "description": "Short unique key for the fact",
                },
                "value": {"type": "string", "description": "The fact to remember"},
                "category": {
                    "type": "string",
                    "description": "Category: identity|preference|project|general",
                },
            },
            "required": ["key", "value"],
        },
        handler=lambda key, value, category="general": (
            memory.remember(key, value, category=category),
            f"Remembered: {key} = {value}",
        )[-1],
    )

    llm.register_tool(
        name="recall_fact",
        description="Recall a previously stored fact.",
        parameters={
            "type": "object",
            "properties": {
                "key": {"type": "string", "description": "Key to look up"},
            },
            "required": ["key"],
        },
        handler=lambda key: memory.recall(key) or f"No fact found for '{key}'",
    )

    # ── Search ───────────────────────────────────────────────────────
    llm.register_tool(
        name="web_search",
        description="Search the internet for current information.",
        parameters={
            "type": "object",
            "properties": {
                "query": {"type": "string", "description": "Search query"},
            },
            "required": ["query"],
        },
        handler=lambda query: search.answer(query) or "No search results found.",
    )

    # ── Calendar ─────────────────────────────────────────────────────
    if calendar:
        llm.register_tool(
            name="list_upcoming_events",
            description="List upcoming calendar events.",
            parameters={
                "type": "object",
                "properties": {
                    "days": {
                        "type": "integer",
                        "description": "Number of days to look ahead (default 7)",
                    },
                },
                "required": [],
            },
            handler=lambda days=7: calendar.format_upcoming(days),
        )

    # ── Reminders ────────────────────────────────────────────────────
    if reminder:
        llm.register_tool(
            name="set_reminder",
            description="Set a reminder in N minutes.",
            parameters={
                "type": "object",
                "properties": {
                    "message": {"type": "string", "description": "Reminder message"},
                    "minutes": {"type": "integer", "description": "Minutes from now"},
                },
                "required": ["message", "minutes"],
            },
            handler=lambda message, minutes: (
                reminder.add_in(message, minutes),
                f"Reminder set: '{message}' in {minutes} minutes.",
            )[-1],
        )

    # ── System ───────────────────────────────────────────────────────
    llm.register_tool(
        name="get_system_info",
        description="Get basic system information.",
        parameters={"type": "object", "properties": {}, "required": []},
        handler=lambda: f"OS: {platform.system()} {platform.release()}, Python: {platform.python_version()}",
    )

    log.debug("Registered %d tools", len(llm._tools))

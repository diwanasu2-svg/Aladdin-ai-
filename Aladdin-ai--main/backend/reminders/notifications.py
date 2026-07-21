"""Reminder notification dispatcher — checks due reminders and queues notifications."""
from __future__ import annotations
import asyncio
import logging
import time
from typing import Any, Callable, Dict, List, Optional

log = logging.getLogger(__name__)


class NotificationQueue:
    def __init__(self) -> None:
        self._queue: List[Dict] = []
        self._callbacks: List[Callable] = []

    def push(self, payload: Dict) -> None:
        self._queue.append({**payload, "queued_at": time.time()})
        for cb in self._callbacks:
            try:
                cb(payload)
            except Exception as exc:
                log.warning("Notification callback error: %s", exc)

    def pop_all(self) -> List[Dict]:
        items = list(self._queue)
        self._queue.clear()
        return items

    def peek(self) -> List[Dict]:
        return list(self._queue)

    def register_callback(self, cb: Callable) -> None:
        self._callbacks.append(cb)


_notification_queue = NotificationQueue()


def get_queue() -> NotificationQueue:
    return _notification_queue


async def check_due_reminders(reminder_manager, interval_seconds: int = 30) -> None:
    """Background task — poll for due reminders and push notifications."""
    from .sound import build_notification_payload
    while True:
        try:
            due = reminder_manager.get_due(before_ts=time.time())
            for rem in due:
                payload = build_notification_payload(rem)
                _notification_queue.push(payload)
                reminder_manager.mark_notified(rem["id"])
                log.info("Notification pushed: %s", rem.get("title"))
        except Exception as exc:
            log.error("check_due_reminders error: %s", exc)
        await asyncio.sleep(interval_seconds)

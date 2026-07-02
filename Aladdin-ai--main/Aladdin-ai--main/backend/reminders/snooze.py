"""Snooze logic for reminders."""
from __future__ import annotations
import time
from typing import Dict, Optional

SNOOZE_PRESETS = {
    "5min": 300, "10min": 600, "15min": 900,
    "30min": 1800, "1hour": 3600, "2hour": 7200,
}


def snooze(reminder: Dict, duration: str = "10min", max_snoozes: int = 5) -> Dict:
    """Return updated reminder dict with snoozed time."""
    snooze_count = reminder.get("snooze_count", 0)
    if snooze_count >= max_snoozes:
        return {**reminder, "error": f"Max snoozes ({max_snoozes}) reached"}
    seconds = SNOOZE_PRESETS.get(duration, 600)
    new_time = time.time() + seconds
    return {
        **reminder,
        "remind_at": new_time,
        "snoozed": True,
        "snooze_count": snooze_count + 1,
        "snooze_duration": duration,
        "last_snoozed_at": time.time(),
    }

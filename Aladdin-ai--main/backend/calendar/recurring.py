"""Recurring event logic — expand recurrence rules into occurrences."""
from __future__ import annotations
import time
from typing import Any, Dict, List, Optional


def next_occurrence(start_ts: float, rule: str, interval: int = 1,
                    after_ts: Optional[float] = None) -> Optional[float]:
    """Return the next occurrence timestamp after after_ts."""
    from datetime import datetime, timedelta
    import calendar as cal

    after = after_ts or time.time()
    dt = datetime.fromtimestamp(start_ts)
    rule = rule.lower()

    for _ in range(10000):  # safety cap
        if rule == "daily":
            dt = dt + timedelta(days=interval)
        elif rule == "weekly":
            dt = dt + timedelta(weeks=interval)
        elif rule == "monthly":
            month = dt.month + interval
            year = dt.year + (month - 1) // 12
            month = (month - 1) % 12 + 1
            day = min(dt.day, cal.monthrange(year, month)[1])
            dt = dt.replace(year=year, month=month, day=day)
        elif rule == "yearly":
            dt = dt.replace(year=dt.year + interval)
        else:
            return None
        ts = dt.timestamp()
        if ts > after:
            return ts
    return None


def expand_recurrence(event: Dict[str, Any], count: int = 10) -> List[Dict[str, Any]]:
    """Return `count` future occurrences of a recurring event."""
    rule = event.get("recurrence_rule")
    if not rule:
        return [event]
    interval = event.get("recurrence_interval", 1)
    end_ts = event.get("recurrence_end_ts")
    duration = (event.get("end_ts") or event["start_ts"] + 3600) - event["start_ts"]

    occurrences = []
    current_ts = event["start_ts"]
    for _ in range(count):
        nxt = next_occurrence(current_ts, rule, interval, after_ts=current_ts - 1)
        if nxt is None:
            break
        if end_ts and nxt > end_ts:
            break
        occ = dict(event)
        occ["start_ts"] = nxt
        occ["end_ts"] = nxt + duration
        occ["is_occurrence"] = True
        occ["parent_id"] = event.get("id")
        occurrences.append(occ)
        current_ts = nxt
    return occurrences

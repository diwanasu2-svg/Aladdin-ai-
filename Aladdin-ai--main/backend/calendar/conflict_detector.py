"""Detect scheduling conflicts between calendar events."""
from __future__ import annotations
from typing import Any, Dict, List, Tuple


def events_overlap(a: Dict, b: Dict) -> bool:
    a_start = a.get("start_ts", 0)
    a_end   = a.get("end_ts") or a_start + 3600
    b_start = b.get("start_ts", 0)
    b_end   = b.get("end_ts") or b_start + 3600
    return a_start < b_end and b_start < a_end


def find_conflicts(events: List[Dict[str, Any]]) -> List[Tuple[Dict, Dict]]:
    """Return pairs of events that overlap in time."""
    conflicts = []
    evs = sorted(events, key=lambda e: e.get("start_ts", 0))
    for i in range(len(evs)):
        for j in range(i + 1, len(evs)):
            if evs[j].get("start_ts", 0) >= (evs[i].get("end_ts") or evs[i].get("start_ts", 0) + 3600):
                break
            if events_overlap(evs[i], evs[j]):
                conflicts.append((evs[i], evs[j]))
    return conflicts


def suggest_alternative(event: Dict, existing: List[Dict], buffer_minutes: int = 15) -> float:
    """Suggest a start time after the last conflicting event."""
    from datetime import timedelta
    import time
    duration = (event.get("end_ts") or event["start_ts"] + 3600) - event["start_ts"]
    busy_end = max((e.get("end_ts") or e.get("start_ts", 0) + 3600) for e in existing) if existing else 0
    suggestion = busy_end + buffer_minutes * 60
    return max(suggestion, event["start_ts"])

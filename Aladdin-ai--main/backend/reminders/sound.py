"""Reminder sound configuration (metadata only — actual playback is client-side)."""
from typing import Dict, List

BUILT_IN_SOUNDS = [
    {"id": "default", "name": "Default Bell", "file": "/static/sounds/bell.mp3"},
    {"id": "chime",   "name": "Chime",         "file": "/static/sounds/chime.mp3"},
    {"id": "ding",    "name": "Ding",           "file": "/static/sounds/ding.mp3"},
    {"id": "soft",    "name": "Soft Alert",     "file": "/static/sounds/soft.mp3"},
    {"id": "urgent",  "name": "Urgent",         "file": "/static/sounds/urgent.mp3"},
    {"id": "silent",  "name": "Silent",         "file": None},
]


def get_sounds() -> List[Dict]:
    return BUILT_IN_SOUNDS


def get_sound_url(sound_id: str) -> str | None:
    for s in BUILT_IN_SOUNDS:
        if s["id"] == sound_id:
            return s["file"]
    return BUILT_IN_SOUNDS[0]["file"]


def build_notification_payload(reminder: Dict) -> Dict:
    """Build the client-side notification payload for push/browser."""
    return {
        "title": reminder.get("title", "Reminder"),
        "body": reminder.get("body", ""),
        "sound_url": get_sound_url(reminder.get("sound_id", "default")),
        "sound_id": reminder.get("sound_id", "default"),
        "volume": reminder.get("volume", 0.8),
        "silent": reminder.get("sound_id") == "silent",
        "remind_at": reminder.get("remind_at"),
        "reminder_id": reminder.get("id"),
        "actions": [
            {"action": "dismiss", "title": "Dismiss"},
            {"action": "snooze_10min", "title": "Snooze 10 min"},
            {"action": "snooze_1hour", "title": "Snooze 1 hour"},
        ],
    }

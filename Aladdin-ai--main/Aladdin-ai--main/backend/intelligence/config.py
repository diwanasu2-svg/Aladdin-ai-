"""Phase 11 Intelligence — Central configuration for all intelligence modules."""
from __future__ import annotations
import os
from pathlib import Path
from typing import List

BASE_DIR = Path(__file__).parent
DATA_DIR = BASE_DIR / "data"
MODELS_DIR = BASE_DIR / "models"

DATA_DIR.mkdir(exist_ok=True)
MODELS_DIR.mkdir(exist_ok=True)

# ── Reminder service ──────────────────────────────────────────────────────────
REMINDER_DB = str(DATA_DIR / "reminders.db")
REMINDER_CHECK_INTERVAL_S = 60          # seconds between reminder checks
REMINDER_BUSY_POSTPONE_MIN = 15         # minutes to postpone when busy
REMINDER_MAX_SNOOZE_COUNT = 3           # max times a reminder is auto-snoozed

# ── Habit predictor ───────────────────────────────────────────────────────────
HABIT_DB = str(DATA_DIR / "habits.db")
HABIT_MIN_OCCURRENCES = 3              # minimum occurrences before pattern recognised
HABIT_LOOKBACK_DAYS = 30              # rolling window for pattern analysis
HABIT_MODEL_PATH = str(MODELS_DIR / "habit_model.pkl")
HABIT_PREDICTION_THRESHOLD = 0.65     # minimum confidence to surface a suggestion

# ── Daily briefing ────────────────────────────────────────────────────────────
BRIEFING_TIME = "07:00"               # default briefing generation time
BRIEFING_NEWS_COUNT = 5               # max news items in briefing
BRIEFING_CACHE_TTL_S = 3600          # seconds to cache generated briefing

# ── News aggregator ───────────────────────────────────────────────────────────
NEWS_API_KEY = os.getenv("NEWS_API_KEY", "")
NEWS_SOURCES = ["bbc-news", "techcrunch", "the-verge", "reuters", "associated-press"]
NEWS_MAX_ARTICLES = 50
NEWS_SUMMARY_MAX_WORDS = 150
NEWS_CACHE_TTL_S = 1800               # 30 minute cache

# ── Calendar optimizer ────────────────────────────────────────────────────────
CALENDAR_WORKING_HOURS_START = 9      # 09:00
CALENDAR_WORKING_HOURS_END = 18       # 18:00
CALENDAR_MIN_SLOT_MINUTES = 30
CALENDAR_TRAVEL_BUFFER_MINUTES = 15
GOOGLE_CALENDAR_CREDENTIALS = os.getenv("GOOGLE_CALENDAR_CREDENTIALS_JSON", "")

# ── Recommendation engine ─────────────────────────────────────────────────────
RECO_DB = str(DATA_DIR / "recommendations.db")
RECO_MODEL_PATH = str(MODELS_DIR / "reco_model.pkl")
RECO_MAX_ITEMS = 10
RECO_MIN_INTERACTIONS = 5

# ── Context manager ───────────────────────────────────────────────────────────
CONTEXT_UPDATE_INTERVAL_S = 300       # re-evaluate context every 5 min
CONTEXT_DB = str(DATA_DIR / "context.db")

# ── Location service ──────────────────────────────────────────────────────────
LOCATION_DB = str(DATA_DIR / "locations.db")
LOCATION_UPDATE_INTERVAL_S = 120
GEOFENCE_RADIUS_M = 200               # metres for home/office detection
OPENWEATHER_API_KEY = os.getenv("OPENWEATHER_API_KEY", "")

# ── Mood analyzer ─────────────────────────────────────────────────────────────
MOOD_DB = str(DATA_DIR / "mood.db")
MOOD_HISTORY_DAYS = 30
MOOD_MODEL_PATH = str(MODELS_DIR / "mood_model.pkl")

# ── Personalization manager ───────────────────────────────────────────────────
PERSONA_DB = str(DATA_DIR / "personalization.db")
PERSONA_PREFERENCE_DECAY_DAYS = 90    # older preferences weighted less
DEFAULT_USER_ID = "default"

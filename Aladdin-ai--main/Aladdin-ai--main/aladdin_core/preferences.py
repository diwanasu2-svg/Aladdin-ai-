"""User preferences management — voice, theme, response style, units, time format, etc."""

from __future__ import annotations

import json
import logging
import sqlite3
from datetime import datetime
from pathlib import Path
from typing import Any, Dict, Optional

log = logging.getLogger(__name__)


class PreferencesManager:
    """Manages user preferences."""

    # Default preferences
    DEFAULTS = {
        "voice": "en_US-amy-medium",
        "theme": "light",
        "response_style": "friendly",
        "units": "metric",
        "time_format": "24h",
        "verbosity": "normal",
        "auto_search": True,
        "notifications_enabled": True,
    }

    def __init__(self, db_path: str):
        self.db_path = db_path
        self._db = sqlite3.connect(str(db_path), check_same_thread=False)
        self._db.execute("PRAGMA foreign_keys = ON")
        self._db.row_factory = sqlite3.Row
        self._init_schema()
        self._cache: Dict[str, Any] = {}
        self._load_cache()

    def _init_schema(self) -> None:
        """Initialize the preferences table."""
        self._db.execute("""
            CREATE TABLE IF NOT EXISTS preferences (
                key             TEXT PRIMARY KEY,
                value           TEXT NOT NULL,
                data_type       TEXT DEFAULT 'str',
                created_at      DATETIME DEFAULT CURRENT_TIMESTAMP,
                updated_at      DATETIME DEFAULT CURRENT_TIMESTAMP
            )
        """)
        self._db.commit()
        # Initialize defaults if not present
        for key, default_val in self.DEFAULTS.items():
            cur = self._db.execute("SELECT 1 FROM preferences WHERE key = ?", (key,))
            if not cur.fetchone():
                data_type = "bool" if isinstance(default_val, bool) else "str"
                self._db.execute(
                    "INSERT INTO preferences(key, value, data_type) VALUES(?, ?, ?)",
                    (key, json.dumps(default_val), data_type),
                )
        self._db.commit()

    def _load_cache(self) -> None:
        """Load all preferences into memory cache."""
        cur = self._db.execute("SELECT key, value, data_type FROM preferences")
        for row in cur.fetchall():
            key = row["key"]
            value_str = row["value"]
            data_type = row["data_type"]
            try:
                self._cache[key] = json.loads(value_str)
            except json.JSONDecodeError:
                self._cache[key] = value_str

    def _update_timestamp(self, key: str) -> None:
        """Update the updated_at timestamp for a preference."""
        self._db.execute(
            "UPDATE preferences SET updated_at = CURRENT_TIMESTAMP WHERE key = ?",
            (key,),
        )
        self._db.commit()

    # ------------------------------------------------------------------
    # Preference getters
    # ------------------------------------------------------------------

    def get(self, key: str, default: Any = None) -> Any:
        """Get a preference value."""
        return self._cache.get(key, default)

    def get_all(self) -> Dict[str, Any]:
        """Get all preferences."""
        return dict(self._cache)

    def get_voice(self) -> str:
        """Get preferred voice model."""
        return self.get("voice", self.DEFAULTS["voice"])

    def get_theme(self) -> str:
        """Get preferred theme (light, dark, etc.)."""
        return self.get("theme", self.DEFAULTS["theme"])

    def get_response_style(self) -> str:
        """Get response style (friendly, professional, etc.)."""
        return self.get("response_style", self.DEFAULTS["response_style"])

    def get_units(self) -> str:
        """Get preferred units (metric, imperial)."""
        return self.get("units", self.DEFAULTS["units"])

    def get_time_format(self) -> str:
        """Get time format (12h, 24h)."""
        return self.get("time_format", self.DEFAULTS["time_format"])

    # ------------------------------------------------------------------
    # Preference setters
    # ------------------------------------------------------------------

    def set(self, key: str, value: Any) -> None:
        """Set a preference value."""
        self._cache[key] = value
        data_type = "bool" if isinstance(value, bool) else "str"
        self._db.execute(
            "INSERT INTO preferences(key, value, data_type) VALUES(?, ?, ?) "
            "ON CONFLICT(key) DO UPDATE SET value=excluded.value, data_type=excluded.data_type",
            (key, json.dumps(value), data_type),
        )
        self._update_timestamp(key)
        log.debug("Preference set: %s = %s", key, value)

    def set_voice(self, voice: str) -> None:
        """Set preferred voice model."""
        self.set("voice", voice)
        log.info("Voice preference set to: %s", voice)

    def set_theme(self, theme: str) -> None:
        """Set preferred theme."""
        self.set("theme", theme)
        log.info("Theme preference set to: %s", theme)

    def set_response_style(self, style: str) -> None:
        """Set response style."""
        self.set("response_style", style)
        log.info("Response style set to: %s", style)

    def set_units(self, units: str) -> None:
        """Set preferred units."""
        self.set("units", units)
        log.info("Units set to: %s", units)

    def set_time_format(self, fmt: str) -> None:
        """Set time format."""
        self.set("time_format", fmt)
        log.info("Time format set to: %s", fmt)

    def update_multiple(self, data: Dict[str, Any]) -> None:
        """Update multiple preferences at once."""
        for key, value in data.items():
            self.set(key, value)
        log.info("Updated %d preferences", len(data))

    def reset_to_defaults(self) -> None:
        """Reset all preferences to defaults."""
        for key, value in self.DEFAULTS.items():
            self.set(key, value)
        log.info("Preferences reset to defaults")

    def clear(self) -> None:
        """Clear all preferences."""
        self._db.execute("DELETE FROM preferences")
        self._db.commit()
        self._cache.clear()
        log.warning("All preferences cleared")

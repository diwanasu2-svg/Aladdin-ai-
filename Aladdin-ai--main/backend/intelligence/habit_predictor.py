"""Phase 11.2 — Habit Predictor.

Observes daily routines, identifies repeating patterns, predicts future actions
and surfaces proactive suggestions before the user asks.

ML backend: scikit-learn RandomForest + frequency analysis.
Falls back to simple frequency counting when scikit-learn is unavailable.
"""
from __future__ import annotations
import asyncio
import json
import logging
import joblib
import sqlite3
import time
from collections import Counter, defaultdict
from contextlib import contextmanager
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from typing import Any, Dict, List, Optional, Tuple

from .config import (
    HABIT_DB, HABIT_MIN_OCCURRENCES, HABIT_LOOKBACK_DAYS,
    HABIT_MODEL_PATH, HABIT_PREDICTION_THRESHOLD,
)

log = logging.getLogger(__name__)


@dataclass
class HabitEvent:
    action: str                      # e.g. "open_app:spotify", "send_message:whatsapp"
    timestamp: float = field(default_factory=time.time)
    context: Dict[str, Any] = field(default_factory=dict)
    user_id: str = "default"


@dataclass
class HabitPrediction:
    action: str
    confidence: float
    reason: str
    suggested_at: float = field(default_factory=time.time)
    user_id: str = "default"


class HabitPredictor:
    """Learn and predict habitual user behaviours."""

    def __init__(self):
        self._init_db()
        self._models: Dict[str, Any] = {}     # user_id → sklearn model
        self._load_models()

    # ── Database ──────────────────────────────────────────────────────────────
    @contextmanager
    def _db(self):
        conn = sqlite3.connect(HABIT_DB)
        conn.execute("PRAGMA foreign_keys = ON")
        conn.row_factory = sqlite3.Row
        try:
            yield conn
            conn.commit()
        finally:
            conn.close()

    def _init_db(self):
        with self._db() as db:
            db.execute("""
                CREATE TABLE IF NOT EXISTS events (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    action TEXT NOT NULL,
                    timestamp REAL NOT NULL,
                    hour INTEGER,
                    minute INTEGER,
                    day_of_week INTEGER,
                    context TEXT DEFAULT '{}',
                    user_id TEXT DEFAULT 'default'
                )
            """)
            db.execute("""
                CREATE TABLE IF NOT EXISTS patterns (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    user_id TEXT,
                    action TEXT,
                    typical_hour INTEGER,
                    typical_dow INTEGER,
                    frequency INTEGER,
                    confidence REAL,
                    last_updated REAL
                )
            """)

    # ── Event recording ───────────────────────────────────────────────────────
    def record(self, action: str, context: Optional[Dict] = None, user_id: str = "default"):
        now = datetime.now()
        with self._db() as db:
            db.execute(
                "INSERT INTO events(action,timestamp,hour,minute,day_of_week,context,user_id) "
                "VALUES(?,?,?,?,?,?,?)",
                (action, time.time(), now.hour, now.minute, now.weekday(),
                 json.dumps(context or {}), user_id)
            )
        log.debug("Habit event recorded: %s @ %s", action, now.strftime("%H:%M"))

    # ── Pattern analysis ──────────────────────────────────────────────────────
    def analyse_patterns(self, user_id: str = "default") -> List[Dict]:
        cutoff = time.time() - HABIT_LOOKBACK_DAYS * 86400
        with self._db() as db:
            rows = db.execute(
                "SELECT action, hour, day_of_week FROM events "
                "WHERE user_id=? AND timestamp>?",
                (user_id, cutoff)
            ).fetchall()

        if not rows:
            return []

        # Group by (action, hour-bucket, day_of_week)
        counts: Counter = Counter()
        for r in rows:
            bucket = r["hour"] // 2 * 2    # 2-hour buckets
            counts[(r["action"], bucket, r["day_of_week"])] += 1

        patterns = []
        total_days = HABIT_LOOKBACK_DAYS
        for (action, hour, dow), freq in counts.most_common(50):
            if freq < HABIT_MIN_OCCURRENCES:
                continue
            confidence = min(freq / (total_days / 7), 1.0)
            patterns.append({
                "action": action, "typical_hour": hour, "typical_dow": dow,
                "frequency": freq, "confidence": round(confidence, 3)
            })
            # Persist
            with self._db() as db:
                db.execute(
                    "INSERT OR REPLACE INTO patterns"
                    "(user_id,action,typical_hour,typical_dow,frequency,confidence,last_updated)"
                    " VALUES(?,?,?,?,?,?,?)",
                    (user_id, action, hour, dow, freq, confidence, time.time())
                )
        log.info("Analysed %d patterns for user %s", len(patterns), user_id)
        return patterns

    # ── ML model (sklearn) ────────────────────────────────────────────────────
    def train(self, user_id: str = "default") -> bool:
        """Train a RandomForest classifier to predict next action from (hour, dow, prev_action)."""
        try:
            from sklearn.ensemble import RandomForestClassifier
            from sklearn.preprocessing import LabelEncoder
            import numpy as np
        except ImportError:
            log.warning("scikit-learn not available — using frequency-only predictions")
            return False

        cutoff = time.time() - HABIT_LOOKBACK_DAYS * 86400
        with self._db() as db:
            rows = db.execute(
                "SELECT action, hour, day_of_week FROM events "
                "WHERE user_id=? AND timestamp>? ORDER BY timestamp ASC",
                (user_id, cutoff)
            ).fetchall()

        if len(rows) < 20:
            log.info("Not enough data to train (%d events)", len(rows))
            return False

        actions = [r["action"] for r in rows]
        hours = [r["hour"] for r in rows]
        dows = [r["day_of_week"] for r in rows]

        le = LabelEncoder()
        encoded = le.fit_transform(actions)

        # Feature: (hour, dow, prev_action_encoded)
        X, y = [], []
        for i in range(1, len(encoded)):
            X.append([hours[i], dows[i], encoded[i-1]])
            y.append(encoded[i])

        if len(set(y)) < 2:
            return False

        clf = RandomForestClassifier(n_estimators=50, random_state=42)
        clf.fit(X, y)

        self._models[user_id] = {"clf": clf, "le": le}
        joblib.dump(self._models[user_id], HABIT_MODEL_PATH.replace(".pkl", f"_{user_id}.pkl"))
        log.info("Habit model trained for user %s (%d samples)", user_id, len(X))
        return True

    def _load_models(self):
        import glob
        for path in glob.glob(HABIT_MODEL_PATH.replace(".pkl", "_*.pkl")):
            user_id = path.split("_")[-1].replace(".pkl", "")
            try:
                self._models[user_id]  = joblib.load(path)
                log.debug("Loaded habit model for user %s", user_id)
            except Exception as e:
                log.warning("Could not load habit model %s: %s", path, e)

    # ── Prediction ────────────────────────────────────────────────────────────
    def predict_next(self, user_id: str = "default",
                     last_action: Optional[str] = None) -> List[HabitPrediction]:
        """Predict likely next actions right now."""
        now = datetime.now()
        predictions = []

        # ML prediction
        model = self._models.get(user_id)
        if model and last_action:
            try:
                le = model["le"]
                clf = model["clf"]
                if last_action in le.classes_:
                    prev_enc = le.transform([last_action])[0]
                    X = [[now.hour, now.weekday(), prev_enc]]
                    probs = clf.predict_proba(X)[0]
                    top_idx = probs.argsort()[-3:][::-1]
                    for idx in top_idx:
                        if probs[idx] >= HABIT_PREDICTION_THRESHOLD:
                            action = le.inverse_transform([idx])[0]
                            predictions.append(HabitPrediction(
                                action=action,
                                confidence=round(float(probs[idx]), 3),
                                reason=f"ML model (last_action={last_action})",
                                user_id=user_id
                            ))
            except Exception as e:
                log.warning("ML prediction error: %s", e)

        # Frequency-based fallback
        if not predictions:
            with self._db() as db:
                rows = db.execute(
                    "SELECT action, frequency, confidence FROM patterns "
                    "WHERE user_id=? AND typical_hour<=? AND typical_hour+2>=? "
                    "AND (typical_dow=? OR typical_dow=-1) "
                    "ORDER BY confidence DESC LIMIT 5",
                    (user_id, now.hour, now.hour, now.weekday())
                ).fetchall()
            for r in rows:
                if r["confidence"] >= HABIT_PREDICTION_THRESHOLD:
                    predictions.append(HabitPrediction(
                        action=r["action"],
                        confidence=r["confidence"],
                        reason="Frequency analysis",
                        user_id=user_id
                    ))

        log.debug("Predicted %d actions for user %s", len(predictions), user_id)
        return predictions[:5]

    # ── Proactive suggestions ─────────────────────────────────────────────────
    def get_proactive_suggestions(self, user_id: str = "default",
                                  last_action: Optional[str] = None) -> List[str]:
        preds = self.predict_next(user_id, last_action)
        suggestions = []
        for p in preds:
            action = p.action.replace("_", " ").replace(":", " → ")
            suggestions.append(
                f"[{int(p.confidence*100)}%] You usually {action} around this time."
            )
        return suggestions

    # ── Training schedule ─────────────────────────────────────────────────────
    async def auto_train_loop(self, interval_hours: int = 6):
        """Re-train models periodically in background."""
        while True:
            await asyncio.sleep(interval_hours * 3600)
            with self._db() as db:
                users = [r[0] for r in db.execute("SELECT DISTINCT user_id FROM events").fetchall()]
            for uid in users:
                self.analyse_patterns(uid)
                self.train(uid)

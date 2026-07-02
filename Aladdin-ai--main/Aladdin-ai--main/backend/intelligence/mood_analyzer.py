"""Phase 11.9 — Mood Analyzer.

Signals analysed:
  • Text sentiment (TextBlob / VADER / simple lexicon)
  • Speech rate and energy (audio features)
  • Typing speed and error rate (keyboard signals)
  • Time of day and recent activity history

Output: mood label + intensity + adapted response hints.
Privacy-first: mood data is stored locally, never uploaded.
Mood is treated as a suggestion, not a diagnosis.
"""
from __future__ import annotations
import asyncio
import json
import logging
import math
import joblib
import sqlite3
import time
from contextlib import contextmanager
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from typing import Any, Dict, List, Optional, Tuple

from .config import MOOD_DB, MOOD_HISTORY_DAYS, MOOD_MODEL_PATH

log = logging.getLogger(__name__)

# ── Mood constants ─────────────────────────────────────────────────────────────
MOOD_HAPPY = "happy"
MOOD_CALM = "calm"
MOOD_NEUTRAL = "neutral"
MOOD_TIRED = "tired"
MOOD_STRESSED = "stressed"
MOOD_SAD = "sad"
MOOD_EXCITED = "excited"
MOOD_FRUSTRATED = "frustrated"

MOOD_LABELS = [MOOD_HAPPY, MOOD_CALM, MOOD_NEUTRAL, MOOD_TIRED,
               MOOD_STRESSED, MOOD_SAD, MOOD_EXCITED, MOOD_FRUSTRATED]

# Simple sentiment lexicons (VADER-inspired, lightweight)
_POSITIVE = {"great", "amazing", "wonderful", "love", "excellent", "fantastic",
             "happy", "joy", "excited", "perfect", "thank", "thanks", "awesome",
             "good", "nice", "brilliant", "beautiful", "cool", "glad", "pleased"}
_NEGATIVE = {"bad", "terrible", "awful", "hate", "angry", "frustrated", "sad",
             "tired", "exhausted", "stressed", "worried", "upset", "awful",
             "horrible", "wrong", "broken", "fail", "stupid", "dumb", "useless"}
_STRESS_WORDS = {"urgent", "asap", "deadline", "emergency", "immediately", "critical",
                 "crisis", "help", "stuck", "broken", "not working", "problem"}
_INTENSIFIERS = {"very", "so", "extremely", "really", "super", "absolutely"}


@dataclass
class MoodSignal:
    source: str              # "text" / "audio" / "typing" / "context"
    mood: str
    confidence: float        # 0–1
    valence: float           # -1 (negative) to +1 (positive)
    arousal: float           # 0 (calm) to 1 (excited/agitated)
    raw: Dict = field(default_factory=dict)


@dataclass
class MoodSnapshot:
    user_id: str
    timestamp: float = field(default_factory=time.time)
    mood: str = MOOD_NEUTRAL
    confidence: float = 0.5
    valence: float = 0.0
    arousal: float = 0.3
    signals: List[MoodSignal] = field(default_factory=list)
    uncertainty: str = "moderate"

    def to_dict(self) -> Dict:
        return {
            "user_id": self.user_id,
            "timestamp": self.timestamp,
            "mood": self.mood,
            "confidence": round(self.confidence, 2),
            "valence": round(self.valence, 2),
            "arousal": round(self.arousal, 2),
            "uncertainty": self.uncertainty,
            "source_count": len(self.signals),
        }


class MoodAnalyzer:
    """Multi-signal mood detection with privacy-first design."""

    def __init__(self):
        self._init_db()
        self._model = None
        self._load_model()
        self._current: Dict[str, MoodSnapshot] = {}

    # ── Database ──────────────────────────────────────────────────────────────
    @contextmanager
    def _db(self):
        conn = sqlite3.connect(MOOD_DB)
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
                CREATE TABLE IF NOT EXISTS mood_history (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    user_id TEXT,
                    timestamp REAL,
                    mood TEXT,
                    confidence REAL,
                    valence REAL,
                    arousal REAL,
                    signals_json TEXT
                )
            """)

    # ── Text sentiment analysis ───────────────────────────────────────────────
    def _analyse_text(self, text: str) -> MoodSignal:
        if not text:
            return MoodSignal("text", MOOD_NEUTRAL, 0.3, 0.0, 0.3)

        words = text.lower().split()
        intensifier = any(w in _INTENSIFIERS for w in words)
        mult = 1.4 if intensifier else 1.0

        pos = sum(1 for w in words if w in _POSITIVE)
        neg = sum(1 for w in words if w in _NEGATIVE)
        stress = sum(1 for w in words if w in _STRESS_WORDS)

        # Punctuation signals
        excl = text.count("!")
        q = text.count("?")
        caps_ratio = sum(1 for c in text if c.isupper()) / max(len(text), 1)

        # Valence: positive – negative
        raw_val = (pos - neg * 1.2) * mult
        valence = max(-1.0, min(1.0, raw_val / max(len(words), 1) * 5))

        # Arousal: exclamations, caps, stress
        arousal = min(1.0, (excl * 0.2 + caps_ratio * 0.5 + stress * 0.3) * mult)

        # Mood mapping
        if stress >= 2:
            mood = MOOD_STRESSED
        elif valence > 0.5 and arousal > 0.5:
            mood = MOOD_EXCITED
        elif valence > 0.3:
            mood = MOOD_HAPPY
        elif valence > 0:
            mood = MOOD_CALM
        elif valence < -0.4 and stress >= 1:
            mood = MOOD_FRUSTRATED
        elif valence < -0.3:
            mood = MOOD_SAD
        else:
            mood = MOOD_NEUTRAL

        conf = min(1.0, 0.3 + (abs(pos - neg) / max(len(words), 1)) * 2)

        return MoodSignal(
            source="text", mood=mood, confidence=conf,
            valence=round(valence, 2), arousal=round(arousal, 2),
            raw={"pos": pos, "neg": neg, "stress": stress, "excl": excl}
        )

    def _analyse_text_with_nltk(self, text: str) -> Optional[MoodSignal]:
        """Use NLTK VADER if available for better accuracy."""
        try:
            from nltk.sentiment.vader import SentimentIntensityAnalyzer
            sia = SentimentIntensityAnalyzer()
            scores = sia.polarity_scores(text)
            compound = scores["compound"]
            valence = compound
            arousal = (scores["pos"] + scores["neg"]) / max(scores["neu"], 0.01) * 0.5
            arousal = min(1.0, arousal)

            if compound >= 0.5:
                mood = MOOD_HAPPY if arousal < 0.5 else MOOD_EXCITED
            elif compound >= 0.1:
                mood = MOOD_CALM
            elif compound <= -0.5:
                mood = MOOD_SAD if arousal < 0.4 else MOOD_FRUSTRATED
            elif compound <= -0.2:
                mood = MOOD_STRESSED
            else:
                mood = MOOD_NEUTRAL

            return MoodSignal(
                source="text_vader", mood=mood,
                confidence=min(1.0, abs(compound) + 0.3),
                valence=round(valence, 2), arousal=round(arousal, 2),
                raw=scores
            )
        except ImportError:
            return None
        except Exception as e:
            log.debug("VADER error: %s", e)
            return None

    # ── Audio signal analysis ─────────────────────────────────────────────────
    def analyse_audio(self, audio_features: Dict) -> MoodSignal:
        """
        audio_features should contain:
          speech_rate_wpm: float
          energy_db: float
          pitch_hz: float  (fundamental frequency)
          pause_ratio: float  (fraction of silence)
        """
        rate = audio_features.get("speech_rate_wpm", 130)
        energy = audio_features.get("energy_db", -20)
        pitch = audio_features.get("pitch_hz", 130)
        pauses = audio_features.get("pause_ratio", 0.2)

        # Normalise relative to typical values
        rate_z = (rate - 130) / 40         # z-score approx
        energy_z = (energy + 20) / 15
        pitch_z = (pitch - 130) / 50

        arousal = min(1.0, max(0.0, 0.5 + (rate_z * 0.3 + energy_z * 0.3 + pitch_z * 0.2)))
        valence = -0.2 if pauses > 0.4 else (0.2 if energy_z > 0 else 0.0)

        if arousal > 0.7 and valence > 0:
            mood = MOOD_EXCITED
        elif arousal > 0.7:
            mood = MOOD_STRESSED
        elif arousal < 0.3 and pauses > 0.4:
            mood = MOOD_TIRED
        elif arousal < 0.3:
            mood = MOOD_CALM
        elif valence > 0.3:
            mood = MOOD_HAPPY
        else:
            mood = MOOD_NEUTRAL

        return MoodSignal(
            source="audio", mood=mood,
            confidence=0.6,
            valence=round(valence, 2),
            arousal=round(arousal, 2),
            raw=audio_features
        )

    # ── Typing speed analysis ─────────────────────────────────────────────────
    def analyse_typing(self, wpm: float, error_rate: float = 0.0) -> MoodSignal:
        """High WPM + low errors → engaged/excited. Low WPM + high errors → tired/stressed."""
        speed_z = (wpm - 50) / 20
        arousal = min(1.0, max(0.0, 0.5 + speed_z * 0.3))
        stress_signal = error_rate > 0.1
        valence = -0.3 if stress_signal else (0.2 if wpm > 60 else 0.0)

        if stress_signal and wpm > 70:
            mood = MOOD_FRUSTRATED
        elif wpm < 25:
            mood = MOOD_TIRED
        elif wpm > 80 and not stress_signal:
            mood = MOOD_EXCITED
        else:
            mood = MOOD_NEUTRAL

        return MoodSignal(
            source="typing", mood=mood, confidence=0.45,
            valence=round(valence, 2), arousal=round(arousal, 2),
            raw={"wpm": wpm, "error_rate": error_rate}
        )

    # ── Signal fusion ─────────────────────────────────────────────────────────
    def fuse(self, signals: List[MoodSignal],
             weights: Optional[Dict[str, float]] = None) -> Tuple[str, float, float, float]:
        """Weighted combination of mood signals → (mood, confidence, valence, arousal)."""
        default_weights = {"text_vader": 1.0, "text": 0.7, "audio": 1.2, "typing": 0.5, "context": 0.4}
        if weights:
            default_weights.update(weights)

        if not signals:
            return MOOD_NEUTRAL, 0.3, 0.0, 0.3

        total_w = 0.0
        val_sum = 0.0
        arou_sum = 0.0
        mood_votes: Dict[str, float] = {}

        for s in signals:
            w = default_weights.get(s.source, 0.7) * s.confidence
            val_sum += s.valence * w
            arou_sum += s.arousal * w
            total_w += w
            mood_votes[s.mood] = mood_votes.get(s.mood, 0) + w

        if total_w == 0:
            return MOOD_NEUTRAL, 0.3, 0.0, 0.3

        final_val = val_sum / total_w
        final_arou = arou_sum / total_w
        final_mood = max(mood_votes, key=mood_votes.get)
        max_vote = mood_votes[final_mood]
        confidence = min(1.0, max_vote / total_w)

        # Uncertainty
        top2 = sorted(mood_votes.values(), reverse=True)[:2]
        spread = (top2[0] - top2[1]) / max(top2[0], 0.001) if len(top2) > 1 else 1.0

        return final_mood, round(confidence, 2), round(final_val, 2), round(final_arou, 2)

    # ── Main API ──────────────────────────────────────────────────────────────
    async def analyse(self, user_id: str = "default",
                       text: Optional[str] = None,
                       audio_features: Optional[Dict] = None,
                       typing_wpm: Optional[float] = None,
                       typing_error_rate: float = 0.0,
                       context: Optional[Dict] = None) -> MoodSnapshot:
        signals: List[MoodSignal] = []

        # Text analysis
        if text:
            # Try VADER first, fall back to lexicon
            vader_signal = self._analyse_text_with_nltk(text)
            if vader_signal:
                signals.append(vader_signal)
            else:
                signals.append(self._analyse_text(text))

        # Audio
        if audio_features:
            signals.append(self.analyse_audio(audio_features))

        # Typing
        if typing_wpm is not None:
            signals.append(self.analyse_typing(typing_wpm, typing_error_rate))

        # Context-based signal (time of day, activity)
        if context:
            ctx_signal = self._context_signal(context)
            if ctx_signal:
                signals.append(ctx_signal)

        mood, conf, valence, arousal = self.fuse(signals)

        # Uncertainty assessment
        uncertainty = "high" if conf < 0.4 else ("moderate" if conf < 0.7 else "low")

        snapshot = MoodSnapshot(
            user_id=user_id, mood=mood, confidence=conf,
            valence=valence, arousal=arousal,
            signals=signals, uncertainty=uncertainty
        )
        self._current[user_id] = snapshot
        self._persist(snapshot)
        log.debug("Mood analysed for %s: %s (conf=%.2f, uncertainty=%s)",
                  user_id, mood, conf, uncertainty)
        return snapshot

    def _context_signal(self, context: Dict) -> Optional[MoodSignal]:
        tod = context.get("time_of_day", "")
        activity = context.get("activity", "")
        battery = context.get("battery_level", 100)

        if tod == "night" or battery < 10:
            return MoodSignal("context", MOOD_TIRED, 0.3, -0.2, 0.2)
        if activity == "meeting":
            return MoodSignal("context", MOOD_STRESSED, 0.3, -0.1, 0.5)
        if tod == "morning":
            return MoodSignal("context", MOOD_CALM, 0.25, 0.1, 0.3)
        return None

    def _persist(self, snapshot: MoodSnapshot):
        with self._db() as db:
            db.execute(
                "INSERT INTO mood_history(user_id,timestamp,mood,confidence,valence,arousal,signals_json)"
                " VALUES(?,?,?,?,?,?,?)",
                (snapshot.user_id, snapshot.timestamp, snapshot.mood,
                 snapshot.confidence, snapshot.valence, snapshot.arousal,
                 json.dumps([{"source": s.source, "mood": s.mood} for s in snapshot.signals]))
            )

    def _load_model(self):
        try:
            self._model  = joblib.load(MOOD_MODEL_PATH)
            log.debug("Mood model loaded")
        except FileNotFoundError:
            pass
        except Exception as e:
            log.warning("Could not load mood model: %s", e)

    # ── History & adaptation ──────────────────────────────────────────────────
    def get_history(self, user_id: str = "default", days: int = 7) -> List[Dict]:
        cutoff = time.time() - days * 86400
        with self._db() as db:
            rows = db.execute(
                "SELECT timestamp, mood, confidence, valence FROM mood_history "
                "WHERE user_id=? AND timestamp>? ORDER BY timestamp DESC",
                (user_id, cutoff)
            ).fetchall()
        return [dict(r) for r in rows]

    def get_mood_trend(self, user_id: str = "default", days: int = 7) -> Dict:
        history = self.get_history(user_id, days)
        if not history:
            return {"trend": "unknown", "dominant_mood": MOOD_NEUTRAL}
        moods = [h["mood"] for h in history]
        from collections import Counter
        dominant = Counter(moods).most_common(1)[0][0]
        vals = [h["valence"] for h in history]
        avg_val = sum(vals) / len(vals)
        recent_avg = sum(vals[:7]) / max(len(vals[:7]), 1)
        trend = "improving" if recent_avg > avg_val + 0.1 else (
            "declining" if recent_avg < avg_val - 0.1 else "stable"
        )
        return {
            "trend": trend, "dominant_mood": dominant,
            "avg_valence": round(avg_val, 2),
            "sample_count": len(history)
        }

    # ── Response adaptation ───────────────────────────────────────────────────
    def adapt_response(self, user_id: str = "default",
                        base_response: str = "") -> Dict[str, str]:
        snapshot = self._current.get(user_id)
        if not snapshot:
            return {"response": base_response, "tone": "neutral", "note": ""}

        mood = snapshot.mood
        prefix = ""
        tone = "neutral"
        note = ""

        if mood == MOOD_STRESSED:
            prefix = "I can see you might be under pressure. Let me help efficiently. "
            tone = "calm and concise"
            note = "User appears stressed — keep it simple"
        elif mood == MOOD_SAD:
            prefix = "I'm here to help. "
            tone = "gentle and supportive"
            note = "User may be feeling down — be encouraging"
        elif mood == MOOD_FRUSTRATED:
            prefix = "Let's sort this out quickly. "
            tone = "direct and solution-focused"
            note = "User frustrated — skip pleasantries, get to the point"
        elif mood == MOOD_HAPPY or mood == MOOD_EXCITED:
            tone = "enthusiastic"
            note = "User in good mood — can be more conversational"
        elif mood == MOOD_TIRED:
            tone = "brief and gentle"
            note = "User tired — keep responses short"

        full = (prefix + base_response).strip()
        return {
            "response": full, "tone": tone, "note": note,
            "mood_detected": mood,
            "confidence": snapshot.confidence,
            "uncertainty": snapshot.uncertainty,
            "disclaimer": "Mood detection is approximate — treat as a suggestion only."
        }

"""extras/emotion_voice.py — Feature 9: Emotion-Aware Voice Synthesis.

Detects emotion/tone from text content and adjusts TTS synthesis with:
- Speed, pitch, and volume modulation
- SSML markup generation
- Multiple voice personalities (calm, happy, urgent, serious)
- Context-aware prosody (questions ↑, alerts loud, etc.)
"""

from __future__ import annotations

import logging
import re
from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Callable, Dict, List, Optional, Tuple

log = logging.getLogger(__name__)


class Emotion(str, Enum):
    NEUTRAL   = "neutral"
    HAPPY     = "happy"
    CALM      = "calm"
    SERIOUS   = "serious"
    URGENT    = "urgent"
    EXCITED   = "excited"
    SAD       = "sad"
    FRIENDLY  = "friendly"
    QUESTION  = "question"


@dataclass
class VoiceParams:
    rate: float      = 1.0    # speech speed (0.5 – 2.0)
    pitch: float     = 0.0    # semitones (-12 – +12)
    volume: float    = 1.0    # 0.0 – 2.0
    emphasis: str    = "moderate"  # none | reduced | moderate | strong
    break_ms: int    = 0      # pause before utterance (ms)

    def to_ssml_prosody(self) -> str:
        rate_pct = int((self.rate - 1.0) * 100)
        rate_str = f"{'+' if rate_pct >= 0 else ''}{rate_pct}%" if rate_pct != 0 else "medium"
        pitch_str = f"{'+' if self.pitch >= 0 else ''}{self.pitch:.1f}st" if self.pitch != 0 else "medium"
        vol_db = round((self.volume - 1.0) * 6, 1)
        vol_str = f"{'+' if vol_db >= 0 else ''}{vol_db}dB" if vol_db != 0 else "medium"
        parts = []
        if rate_str != "medium": parts.append(f'rate="{rate_str}"')
        if pitch_str != "medium": parts.append(f'pitch="{pitch_str}"')
        if vol_str != "medium":  parts.append(f'volume="{vol_str}"')
        return " ".join(parts)


# Emotion presets
EMOTION_PRESETS: Dict[Emotion, VoiceParams] = {
    Emotion.NEUTRAL:  VoiceParams(rate=1.0,  pitch=0.0,   volume=1.0,  emphasis="moderate"),
    Emotion.HAPPY:    VoiceParams(rate=1.1,  pitch=2.0,   volume=1.1,  emphasis="strong"),
    Emotion.CALM:     VoiceParams(rate=0.9,  pitch=-1.0,  volume=0.9,  emphasis="reduced"),
    Emotion.SERIOUS:  VoiceParams(rate=0.95, pitch=-1.5,  volume=1.0,  emphasis="moderate"),
    Emotion.URGENT:   VoiceParams(rate=1.2,  pitch=1.0,   volume=1.3,  emphasis="strong",  break_ms=0),
    Emotion.EXCITED:  VoiceParams(rate=1.25, pitch=3.0,   volume=1.2,  emphasis="strong"),
    Emotion.SAD:      VoiceParams(rate=0.85, pitch=-2.0,  volume=0.85, emphasis="reduced"),
    Emotion.FRIENDLY: VoiceParams(rate=1.05, pitch=1.0,   volume=1.0,  emphasis="moderate"),
    Emotion.QUESTION: VoiceParams(rate=1.0,  pitch=1.5,   volume=1.0,  emphasis="moderate"),
}


# ─────────────────────────────────────────────────────────────────────────────
# Emotion detector
# ─────────────────────────────────────────────────────────────────────────────

class EmotionDetector:
    """Rule-based + optional ML emotion detection from text."""

    # Keyword signals
    _SIGNALS: Dict[Emotion, List[str]] = {
        Emotion.URGENT:   ["emergency", "urgent", "immediately", "alert", "warning", "danger",
                           "critical", "stop", "fire", "alarm", "now!", "asap"],
        Emotion.HAPPY:    ["great", "excellent", "wonderful", "amazing", "fantastic",
                           "love", "yay", "😀", "🎉", "awesome", "perfect", "congratulations"],
        Emotion.SAD:      ["sorry", "unfortunately", "sad", "fail", "error", "broken",
                           "couldn't", "unable", "apologies", "😢", "😔"],
        Emotion.EXCITED:  ["wow", "incredible", "unbelievable", "🚀", "🔥", "yes!", "done!"],
        Emotion.CALM:     ["relax", "breathe", "take it easy", "no worries", "calm", "gentle"],
        Emotion.SERIOUS:  ["important", "note that", "please be aware", "regarding", "formal"],
        Emotion.QUESTION: ["?", "what", "when", "where", "who", "why", "how", "could you", "can you"],
        Emotion.FRIENDLY: ["hello", "hi", "hey", "welcome", "nice to meet", "how are you"],
    }

    def detect(self, text: str) -> Tuple[Emotion, float]:
        t = text.lower()
        scores: Dict[Emotion, float] = {e: 0.0 for e in Emotion}

        for emotion, keywords in self._SIGNALS.items():
            for kw in keywords:
                if kw in t:
                    scores[emotion] += 1.0

        # Boost QUESTION if text ends with ?
        if text.strip().endswith("?"):
            scores[Emotion.QUESTION] += 2.0

        # Exclamation → excited / urgent
        excl = text.count("!")
        if excl > 0:
            scores[Emotion.EXCITED] += excl * 0.5
            scores[Emotion.URGENT]  += excl * 0.3

        # CAPS ratio → urgency
        caps_ratio = sum(1 for c in text if c.isupper()) / max(len(text), 1)
        if caps_ratio > 0.5:
            scores[Emotion.URGENT] += 2.0

        best_emotion = max(scores, key=scores.get)
        confidence = scores[best_emotion] / max(sum(scores.values()), 1)

        if scores[best_emotion] == 0:
            return Emotion.NEUTRAL, 1.0

        log.debug("[Emotion] Detected: %s (conf=%.2f)", best_emotion.value, confidence)
        return best_emotion, confidence

    def detect_ml(self, text: str) -> Tuple[Emotion, float]:
        """ML-based detection using transformers (optional)."""
        try:
            from transformers import pipeline  # type: ignore
            classifier = pipeline("text-classification",
                                  model="j-hartmann/emotion-english-distilroberta-base",
                                  top_k=1)
            result = classifier(text[:512])[0][0]
            label = result["label"].lower()
            score = result["score"]
            # Map to our emotions
            mapping = {"joy": Emotion.HAPPY, "fear": Emotion.URGENT, "anger": Emotion.URGENT,
                       "sadness": Emotion.SAD, "surprise": Emotion.EXCITED, "neutral": Emotion.NEUTRAL}
            emotion = mapping.get(label, Emotion.NEUTRAL)
            return emotion, score
        except ImportError:
            return self.detect(text)
        except Exception as exc:
            log.debug("[Emotion] ML detect failed: %s", exc)
            return self.detect(text)


# ─────────────────────────────────────────────────────────────────────────────
# SSML builder
# ─────────────────────────────────────────────────────────────────────────────

class SSMLBuilder:
    """Builds SSML markup for TTS engines that support it."""

    def build(self, text: str, emotion: Emotion, params: VoiceParams) -> str:
        prosody = params.to_ssml_prosody()
        break_tag = f'<break time="{params.break_ms}ms"/>' if params.break_ms > 0 else ""

        # Wrap in SSML structure
        ssml_parts = ['<?xml version="1.0"?>', '<speak>']
        if params.break_ms > 0:
            ssml_parts.append(break_tag)

        if prosody:
            ssml_parts.append(f'<prosody {prosody}>')

        # Add emotion-specific markup
        if emotion == Emotion.URGENT:
            ssml_parts.append(f'<emphasis level="strong">{self._escape(text)}</emphasis>')
        elif emotion == Emotion.EXCITED:
            ssml_parts.append(f'<emphasis level="strong">{self._escape(text)}</emphasis>')
        elif emotion == Emotion.CALM:
            # Add breathing pauses for long text
            sentences = re.split(r'(?<=[.!?])\s+', text)
            ssml_parts.append('  '.join(
                f'{self._escape(s)}<break time="200ms"/>' for s in sentences
            ))
        elif emotion == Emotion.QUESTION:
            # Add rising intonation hint
            ssml_parts.append(self._escape(text))
        else:
            ssml_parts.append(self._escape(text))

        if prosody:
            ssml_parts.append('</prosody>')
        ssml_parts.append('</speak>')
        return "\n".join(ssml_parts)

    @staticmethod
    def _escape(text: str) -> str:
        return (text.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace('"', "&quot;").replace("'", "&apos;"))


# ─────────────────────────────────────────────────────────────────────────────
# Emotion-aware TTS engine
# ─────────────────────────────────────────────────────────────────────────────

class EmotionAwareVoice:
    """Wraps any TTS engine with emotion detection and prosody adjustment."""

    def __init__(
        self,
        tts_fn: Optional[Callable[[str], bytes]] = None,
        ssml_tts_fn: Optional[Callable[[str], bytes]] = None,
        use_ml_emotion: bool = False,
    ) -> None:
        self._tts_fn = tts_fn               # plain text → wav bytes
        self._ssml_fn = ssml_tts_fn         # SSML → wav bytes
        self._detector = EmotionDetector()
        self._ssml = SSMLBuilder()
        self._use_ml = use_ml_emotion
        self._emotion_history: List[Tuple[str, Emotion, float]] = []

    # ── Synthesis ─────────────────────────────────────────────────────────────

    def synthesize(self, text: str, force_emotion: Optional[Emotion] = None) -> Optional[bytes]:
        if force_emotion:
            emotion, confidence = force_emotion, 1.0
        elif self._use_ml:
            emotion, confidence = self._detector.detect_ml(text)
        else:
            emotion, confidence = self._detector.detect(text)

        params = EMOTION_PRESETS[emotion]
        self._emotion_history.append((text[:50], emotion, confidence))
        if len(self._emotion_history) > 100:
            self._emotion_history = self._emotion_history[-50:]

        log.info("[EmotionVoice] %s → emotion=%s conf=%.2f rate=%.1f pitch=%+.1f",
                 text[:30], emotion.value, confidence, params.rate, params.pitch)

        # Try SSML first
        if self._ssml_fn:
            try:
                ssml = self._ssml.build(text, emotion, params)
                return self._ssml_fn(ssml)
            except Exception as exc:
                log.warning("[EmotionVoice] SSML failed: %s", exc)

        # Fallback to plain TTS
        if self._tts_fn:
            try:
                return self._tts_fn(text)
            except Exception as exc:
                log.error("[EmotionVoice] TTS failed: %s", exc)

        return None

    # ── pyttsx3 integration ───────────────────────────────────────────────────

    def synthesize_pyttsx3(self, text: str, output_path: str = "") -> bool:
        """Use pyttsx3 with pitch/rate adjustments."""
        try:
            import pyttsx3  # type: ignore
            emotion, _ = self._detector.detect(text)
            params = EMOTION_PRESETS[emotion]
            engine = pyttsx3.init()
            # Rate: default ≈ 200 wpm
            engine.setProperty("rate", int(200 * params.rate))
            # Volume
            engine.setProperty("volume", min(1.0, params.volume))
            if output_path:
                engine.save_to_file(text, output_path)
            else:
                engine.say(text)
            engine.runAndWait()
            return True
        except ImportError:
            log.warning("[EmotionVoice] pyttsx3 not installed")
            return False
        except Exception as exc:
            log.error("[EmotionVoice] pyttsx3 error: %s", exc)
            return False

    # ── gTTS integration ──────────────────────────────────────────────────────

    def synthesize_gtts(self, text: str, lang: str = "en", slow: bool = False) -> Optional[bytes]:
        try:
            from gtts import gTTS  # type: ignore
            import io
            emotion, _ = self._detector.detect(text)
            # Slow = calm/sad
            use_slow = slow or emotion in (Emotion.CALM, Emotion.SAD)
            tts = gTTS(text=text, lang=lang, slow=use_slow)
            buf = io.BytesIO()
            tts.write_to_fp(buf)
            return buf.getvalue()
        except ImportError:
            log.warning("[EmotionVoice] gTTS not installed")
            return None

    # ── API helpers ───────────────────────────────────────────────────────────

    def get_emotion(self, text: str) -> Dict[str, Any]:
        emotion, confidence = self._detector.detect(text)
        params = EMOTION_PRESETS[emotion]
        return {
            "emotion": emotion.value,
            "confidence": round(confidence, 3),
            "rate": params.rate,
            "pitch": params.pitch,
            "volume": params.volume,
            "ssml": self._ssml.build(text, emotion, params),
        }

    def status(self) -> Dict[str, Any]:
        last = self._emotion_history[-5:] if self._emotion_history else []
        return {
            "available_emotions": [e.value for e in Emotion],
            "tts_configured": self._tts_fn is not None,
            "ssml_configured": self._ssml_fn is not None,
            "recent_emotions": [{"text": h[0], "emotion": h[1].value} for h in last],
        }

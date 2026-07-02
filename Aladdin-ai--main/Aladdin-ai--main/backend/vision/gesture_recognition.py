"""
Phase 7.8 — Gesture Recognition
=================================
Detect hand gestures, map to configurable actions, combine
gesture + voice commands, reduce false detections with debounce.
"""
import logging
from __future__ import annotations
import asyncio, logging, time
from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Callable, Dict, List, Optional, Tuple
import numpy as np

log = logging.getLogger(__name__)


class Gesture(str, Enum):
    THUMBS_UP = "thumbs_up"
    THUMBS_DOWN = "thumbs_down"
    OPEN_HAND = "open_hand"
    CLOSED_FIST = "closed_fist"
    POINTING_UP = "pointing_up"
    PEACE = "peace"
    OK = "ok"
    SWIPE_LEFT = "swipe_left"
    SWIPE_RIGHT = "swipe_right"
    UNKNOWN = "unknown"
    NONE = "none"


@dataclass
class GestureEvent:
    gesture: Gesture
    confidence: float
    hand: str = "unknown"
    action_triggered: Optional[str] = None
    timestamp: float = field(default_factory=time.time)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "gesture": self.gesture.value,
            "confidence": round(self.confidence, 3),
            "hand": self.hand,
            "action_triggered": self.action_triggered,
            "timestamp": self.timestamp,
        }


class _Debouncer:
    def __init__(self, cooldown: float = 1.0) -> None:
        self._cooldown = cooldown
        self._last: Dict[Gesture, float] = {}

    def allow(self, g: Gesture) -> bool:
        now = time.time()
        if now - self._last.get(g, 0.0) >= self._cooldown:
            self._last[g] = now
            return True
        return False


class GestureRecognitionModule:
    """
    Hand gesture recognition using MediaPipe (primary) or OpenCV (fallback).

    Usage::

        module = GestureRecognitionModule()
        module.register_action(Gesture.THUMBS_UP, "volume_up", my_fn)

        events = await module.process_frame_bytes(frame_bytes)
        for e in events:
            log.info(e["gesture"], e["action_triggered"])


        module.close()
    """

    def __init__(self, confidence_threshold: float = 0.7,
                 debounce_seconds: float = 1.0) -> None:
        self._threshold = confidence_threshold
        self._debouncer = _Debouncer(debounce_seconds)
        self._actions: Dict[Gesture, Dict[str, Any]] = {}
        self._pending_voice: Optional[Gesture] = None
        self._mp_hands = None
        self._backend = self._detect_backend()
        self._register_defaults()
        log.info("GestureRecognitionModule: backend=%s", self._backend)

    def _detect_backend(self) -> str:
        try:
            import mediapipe as _  # type: ignore  # noqa
            return "mediapipe"
        except ImportError:
            pass
        try:
            import cv2 as _  # type: ignore  # noqa
            return "opencv"
        except ImportError:
            pass
        return "stub"

    def _init_mp(self) -> None:
        if self._mp_hands is not None or self._backend != "mediapipe":
            return
        try:
            import mediapipe as mp  # type: ignore
            self._mp_hands = mp.solutions.hands.Hands(
                static_image_mode=False, max_num_hands=2,
                min_detection_confidence=0.7, min_tracking_confidence=0.5,
            )
        except Exception as exc:
            log.error("MediaPipe init error: %s", exc)
            self._backend = "stub"

    def _register_defaults(self) -> None:
        defaults = [
            (Gesture.THUMBS_UP, "acknowledge", lambda: None),
            (Gesture.THUMBS_DOWN, "dismiss", lambda: None),
            (Gesture.OPEN_HAND, "pause", lambda: None),
            (Gesture.CLOSED_FIST, "stop", lambda: None),
            (Gesture.PEACE, "screenshot", lambda: None),
        ]
        for g, name, fn in defaults:
            self.register_action(g, name, fn)

    # ── Public API ────────────────────────────────────────────────────────────

    def register_action(self, gesture: Gesture, action_name: str, handler: Callable,
                        requires_voice: bool = False,
                        voice_keyword: Optional[str] = None) -> None:
        self._actions[gesture] = {
            "name": action_name, "handler": handler,
            "requires_voice": requires_voice, "voice_keyword": voice_keyword,
        }

    async def process_frame_bytes(self, frame_bytes: bytes) -> List[Dict[str, Any]]:
        """Process a JPEG/PNG frame and return detected gesture events."""
        def _run():
            try:
                import cv2  # type: ignore
                arr = np.frombuffer(frame_bytes, np.uint8)
                img = cv2.imdecode(arr, cv2.IMREAD_COLOR)
                if img is None:
                    return []
                rgb = cv2.cvtColor(img, cv2.COLOR_BGR2RGB)
            except Exception:
                return []
            return self._process_rgb(rgb)

        return await asyncio.get_running_loop().run_in_executor(None, _run)

    def process_rgb(self, frame_rgb: np.ndarray) -> List[Dict[str, Any]]:
        """Synchronous process — call from your frame loop."""
        return self._process_rgb(frame_rgb)

    def _process_rgb(self, rgb: np.ndarray) -> List[Dict[str, Any]]:
        self._init_mp()
        if self._backend == "mediapipe":
            classified = self._classify_mp(rgb)
        elif self._backend == "opencv":
            classified = self._classify_cv(rgb)
        else:
            return []

        events = []
        for gesture, confidence, hand in classified:
            if gesture == Gesture.UNKNOWN or confidence < self._threshold:
                continue
            if not self._debouncer.allow(gesture):
                continue
            action_name = None
            action = self._actions.get(gesture)
            if action:
                if action["requires_voice"]:
                    self._pending_voice = gesture
                else:
                    try:
                        action["handler"]()
                        action_name = action["name"]
                    except Exception:
                        pass
            e = GestureEvent(gesture=gesture, confidence=confidence,
                             hand=hand, action_triggered=action_name)
            events.append(e.to_dict())
        return events

    def handle_voice_combo(self, voice: str) -> bool:
        """Complete a pending gesture+voice combo. Returns True if triggered."""
        if self._pending_voice is None:
            return False
        action = self._actions.get(self._pending_voice)
        if not action:
            self._pending_voice = None
            return False
        kw = action.get("voice_keyword", "")
        if kw and kw.lower() in voice.lower():
            try:
                action["handler"]()
            except Exception:
                pass
            self._pending_voice = None
            return True
        self._pending_voice = None
        return False

    # ── Classifiers ───────────────────────────────────────────────────────────

    def _classify_mp(self, rgb: np.ndarray) -> List[Tuple[Gesture, float, str]]:
        results_list = []
        try:
            out = self._mp_hands.process(rgb)
            if not out.multi_hand_landmarks:
                return results_list
            for i, hand_lm in enumerate(out.multi_hand_landmarks):
                hand_label = "unknown"
                if out.multi_handedness and i < len(out.multi_handedness):
                    hand_label = out.multi_handedness[i].classification[0].label.lower()
                lm = [(l.x, l.y, l.z) for l in hand_lm.landmark]
                gesture, conf = self._lm_to_gesture(lm)
                results_list.append((gesture, conf, hand_label))
        except Exception as exc:
            log.debug("MP classify error: %s", exc)
        return results_list

    @staticmethod
    def _lm_to_gesture(lm: List[Tuple]) -> Tuple[Gesture, float]:
        if len(lm) < 21:
            return Gesture.UNKNOWN, 0.0
        tips = [4, 8, 12, 16, 20]
        pips = [3, 6, 10, 14, 18]
        ext = []
        for tip, pip in zip(tips, pips):
            ext.append(lm[tip][0] < lm[pip][0] if tip == 4 else lm[tip][1] < lm[pip][1])
        thumb, index, middle, ring, pinky = ext
        if all(ext):
            return Gesture.OPEN_HAND, 0.9
        if not any(ext):
            return Gesture.CLOSED_FIST, 0.9
        if thumb and not index and not middle and not ring and not pinky:
            return Gesture.THUMBS_UP, 0.85
        if not thumb and not index and not middle and not ring and not pinky:
            if lm[4][1] > lm[3][1]:
                return Gesture.THUMBS_DOWN, 0.85
        if index and middle and not ring and not pinky:
            return Gesture.PEACE, 0.85
        if index and not middle and not ring and not pinky:
            return Gesture.POINTING_UP, 0.8
        if thumb and index and not middle and not ring and not pinky:
            return Gesture.OK, 0.75
        return Gesture.UNKNOWN, 0.5

    def _classify_cv(self, rgb: np.ndarray) -> List[Tuple[Gesture, float, str]]:
        try:
            import cv2  # type: ignore
            bgr = rgb[:, :, ::-1]
            hsv = cv2.cvtColor(bgr, cv2.COLOR_BGR2HSV)
            mask = cv2.inRange(hsv, np.array([0, 20, 70]), np.array([20, 255, 255]))
            mask = cv2.GaussianBlur(mask, (5, 5), 0)
            contours, _ = cv2.findContours(mask, cv2.RETR_TREE, cv2.CHAIN_APPROX_SIMPLE)
            if not contours:
                return []
            largest = max(contours, key=cv2.contourArea)
            if cv2.contourArea(largest) < 3000:
                return []
            hull = cv2.convexHull(largest, returnPoints=False)
            if hull is None or len(hull) < 3:
                return []
            defects = cv2.convexityDefects(largest, hull)
            fingers = sum(1 for d in (defects or []) if d[0][3] > 10000)
            if fingers >= 4:
                return [(Gesture.OPEN_HAND, 0.65, "unknown")]
            elif fingers == 0:
                return [(Gesture.CLOSED_FIST, 0.65, "unknown")]
        except Exception:
            pass
        return [(Gesture.UNKNOWN, 0.4, "unknown")]

    def list_actions(self) -> List[Dict[str, Any]]:
        return [{"gesture": g.value, "action": a["name"],
                 "requires_voice": a["requires_voice"]}
                for g, a in self._actions.items()]

    def close(self) -> None:
        if self._mp_hands:
            try:
                self._mp_hands.close()
            except Exception:
                pass

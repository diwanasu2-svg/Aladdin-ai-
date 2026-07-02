"""
Phase 7.4 — Scene Understanding
================================
Understand full image context: indoor/outdoor, object relationships,
activity detection, scene summary. Uses Gemini Vision (primary)
or keyword-based classifier (fallback).
"""
from __future__ import annotations
import asyncio, io, logging, time
from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Dict, List, Optional

log = logging.getLogger(__name__)


class Environment(str, Enum):
    INDOOR = "indoor"
    OUTDOOR = "outdoor"
    UNKNOWN = "unknown"


class Activity(str, Enum):
    MEETING = "meeting"
    COOKING = "cooking"
    WALKING = "walking"
    READING = "reading"
    WORKING = "working"
    EATING = "eating"
    EXERCISING = "exercising"
    SLEEPING = "sleeping"
    WATCHING_SCREEN = "watching_screen"
    UNKNOWN = "unknown"


_INDOOR_KW = {"chair", "table", "sofa", "bed", "desk", "lamp", "keyboard", "monitor",
              "tv", "ceiling", "floor", "wall", "door", "refrigerator", "sink", "cabinet"}
_OUTDOOR_KW = {"sky", "tree", "grass", "road", "car", "building", "sidewalk", "cloud",
               "sun", "mountain", "river", "ocean", "park", "garden", "fence"}
_ACTIVITY_KW: Dict[Activity, List[str]] = {
    Activity.MEETING: ["people", "table", "presentation", "laptop", "conference"],
    Activity.COOKING: ["stove", "pan", "kitchen", "cooking", "pot", "oven"],
    Activity.WALKING: ["walking", "pedestrian", "street", "sidewalk"],
    Activity.READING: ["book", "magazine", "newspaper", "page"],
    Activity.WORKING: ["laptop", "computer", "keyboard", "monitor", "desk"],
    Activity.EATING: ["food", "fork", "spoon", "plate", "bowl", "meal"],
    Activity.EXERCISING: ["gym", "weights", "treadmill", "exercise", "yoga"],
    Activity.SLEEPING: ["bed", "pillow", "blanket", "sleeping"],
    Activity.WATCHING_SCREEN: ["tv", "television", "screen", "watching", "couch"],
}

_SCENE_PROMPT = (
    "Analyze this image and respond ONLY with valid JSON (no markdown) with fields:\n"
    '"environment": "indoor" or "outdoor",\n'
    '"activities": list of activity strings,\n'
    '"objects": list of visible objects,\n'
    '"relationships": list of {subject, relation, target},\n'
    '"summary": one paragraph scene description,\n'
    '"confidence": float 0.0-1.0'
)


class SceneUnderstandingModule:
    """
    Scene understanding via Gemini Vision or keyword-based fallback.
    """

    def __init__(self, gemini_key: Optional[str] = None) -> None:
        self._key = gemini_key
        log.info("SceneUnderstandingModule initialised")

    def configure(self, gemini_key: str) -> None:
        self._key = gemini_key

    async def analyze(self, image_bytes: bytes) -> Dict[str, Any]:
        """Full scene analysis — tries Gemini first, falls back to OpenCV/keyword."""
        if self._key:
            result = await self._gemini_analyze(image_bytes)
            if result:
                return result
        return await self._fallback_analyze(image_bytes)

    async def analyze_from_objects(self, objects: List[str]) -> Dict[str, Any]:
        """Derive scene context from a list of detected object labels."""
        def _run():
            ol = [o.lower() for o in objects]
            env = self._classify_env(ol)
            acts = self._classify_acts(ol)
            return {
                "environment": env.value,
                "activities": [a.value for a in acts],
                "objects": objects,
                "relationships": [],
                "summary": self._summary(env, acts, objects),
                "confidence": 0.6,
                "method": "keyword_classifier",
            }
        return await asyncio.get_running_loop().run_in_executor(None, _run)

    async def _gemini_analyze(self, image_bytes: bytes) -> Optional[Dict[str, Any]]:
        def _run():
            try:
                import json as _json
                import google.generativeai as genai  # type: ignore
                import PIL.Image  # type: ignore
                genai.configure(api_key=self._key)
                model = genai.GenerativeModel("gemini-1.5-flash")
                image = PIL.Image.open(io.BytesIO(image_bytes))
                response = model.generate_content([_SCENE_PROMPT, image])
                raw = response.text.strip().lstrip("```json").lstrip("```").rstrip("```").strip()
                data = _json.loads(raw)
                data["method"] = "gemini"
                return data
            except ImportError:
                log.warning("google-generativeai not installed")
            except Exception as exc:
                log.error("Gemini scene analysis error: %s", exc)
            return None
        return await asyncio.get_running_loop().run_in_executor(None, _run)

    async def _fallback_analyze(self, image_bytes: bytes) -> Dict[str, Any]:
        def _run():
            objects: List[str] = []
            try:
                import cv2, numpy as np  # type: ignore
                arr = np.frombuffer(image_bytes, np.uint8)
                img = cv2.imdecode(arr, cv2.IMREAD_COLOR)
                if img is not None:
                    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
                    objects.append("bright scene" if gray.mean() > 128 else "dark scene")
            except Exception:
                pass
            ol = [o.lower() for o in objects]
            env = self._classify_env(ol)
            acts = self._classify_acts(ol)
            return {
                "environment": env.value,
                "activities": [a.value for a in acts],
                "objects": objects,
                "relationships": [],
                "summary": self._summary(env, acts, objects),
                "confidence": 0.4,
                "method": "opencv_fallback",
            }
        return await asyncio.get_running_loop().run_in_executor(None, _run)

    @staticmethod
    def _classify_env(ol: List[str]) -> Environment:
        i = sum(1 for o in ol if o in _INDOOR_KW)
        e = sum(1 for o in ol if o in _OUTDOOR_KW)
        if i > e:
            return Environment.INDOOR
        if e > i:
            return Environment.OUTDOOR
        return Environment.UNKNOWN

    @staticmethod
    def _classify_acts(ol: List[str]) -> List[Activity]:
        found = [a for a, kws in _ACTIVITY_KW.items()
                 if any(k in ol or any(k in o for o in ol) for k in kws)]
        return found or [Activity.UNKNOWN]

    @staticmethod
    def _summary(env: Environment, acts: List[Activity], objects: List[str]) -> str:
        parts = []
        if env != Environment.UNKNOWN:
            parts.append(f"This appears to be an {env.value} scene.")
        if objects:
            parts.append(f"Visible objects: {', '.join(objects[:8])}.")
        act_strs = [a.value for a in acts if a != Activity.UNKNOWN]
        if act_strs:
            parts.append(f"Detected activities: {', '.join(act_strs)}.")
        return " ".join(parts) or "Scene context could not be determined."

"""
Phase 7.6 — Live Camera Analysis
==================================
Analyse camera frames in real-time, detect objects continuously,
monitor scene changes, combine voice commands with live analysis,
optimise performance to avoid lag.
"""
import logging
from __future__ import annotations
import asyncio, logging, queue, threading, time
from dataclasses import dataclass, field
from typing import Any, Callable, Dict, List, Optional
import numpy as np

log = logging.getLogger(__name__)


@dataclass
class FrameAnalysis:
    frame_id: int
    timestamp: float
    objects: List[Dict[str, Any]] = field(default_factory=list)
    scene_changed: bool = False
    change_magnitude: float = 0.0
    processing_ms: float = 0.0

    def to_dict(self) -> Dict[str, Any]:
        return {
            "frame_id": self.frame_id,
            "timestamp": self.timestamp,
            "objects": self.objects,
            "scene_changed": self.scene_changed,
            "change_magnitude": round(self.change_magnitude, 4),
            "processing_ms": round(self.processing_ms, 1),
        }


class _SceneChangeDetector:
    def __init__(self, threshold: float = 0.15) -> None:
        self._threshold = threshold
        self._prev: Optional[np.ndarray] = None

    def detect(self, frame: np.ndarray) -> float:
        try:
            import cv2  # type: ignore
            gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
            gray = cv2.GaussianBlur(gray, (21, 21), 0)
            if self._prev is None or self._prev.shape != gray.shape:
                self._prev = gray
                return 0.0
            diff = np.abs(gray.astype(np.int32) - self._prev.astype(np.int32))
            mag = float(diff.mean()) / 255.0
            self._prev = gray
            return mag
        except Exception:
            return 0.0

    @property
    def threshold(self) -> float:
        return self._threshold

    def reset(self) -> None:
        self._prev = None


class _YOLODetector:
    """YOLO-based detector; falls back to stub if ultralytics not installed."""
    def __init__(self) -> None:
        self._model = None
        self._tried = False

    def _load(self) -> None:
        if self._tried:
            return
        self._tried = True
        try:
            from ultralytics import YOLO  # type: ignore
            self._model = YOLO("yolov8n.pt")
            log.info("YOLO model loaded for live camera")
        except ImportError:
            log.info("ultralytics not installed — live camera stub mode")
        except Exception as exc:
            log.warning("YOLO load error: %s", exc)

    def detect(self, frame: np.ndarray) -> List[Dict[str, Any]]:
        self._load()
        if self._model is None:
            return []
        try:
            results = self._model(frame, verbose=False, stream=False)
            out = []
            for r in results:
                for box in r.boxes:
                    cls_id = int(box.cls[0])
                    label = r.names.get(cls_id, str(cls_id))
                    conf = float(box.conf[0])
                    x1, y1, x2, y2 = [int(v) for v in box.xyxy[0].tolist()]
                    out.append({"label": label, "confidence": conf,
                                "bbox": [x1, y1, x2 - x1, y2 - y1]})
            return out
        except Exception as exc:
            log.debug("YOLO detect error: %s", exc)
            return []


class LiveCameraAnalyzer:
    """
    Real-time camera analyser running in a background thread.

    Usage::

        analyzer = LiveCameraAnalyzer()
        analyzer.set_on_scene_change(lambda a: log.info("CHANGED:", a))
        analyzer.start()
        analyzer.push_frame(frame_bgr)   # call from your camera loop
        analyzer.stop()
    """

    def __init__(self, target_fps: float = 5.0, change_threshold: float = 0.15,
                 queue_size: int = 4) -> None:
        self._fps = target_fps
        self._interval = 1.0 / max(target_fps, 0.1)
        self._q: "queue.Queue[np.ndarray]" = queue.Queue(maxsize=queue_size)
        self._change = _SceneChangeDetector(change_threshold)
        self._detector = _YOLODetector()
        self._running = False
        self._worker: Optional[threading.Thread] = None
        self._counter = 0
        self._last_push = 0.0
        self._current: Optional[FrameAnalysis] = None
        self._lock = threading.RLock()
        self._on_detection: Optional[Callable] = None
        self._on_scene_change: Optional[Callable] = None
        log.info("LiveCameraAnalyzer initialised (fps=%g)", target_fps)

    def start(self) -> None:
        if self._running:
            return
        self._running = True
        self._worker = threading.Thread(target=self._loop, daemon=True, name="LiveCam")
        self._worker.start()

    def stop(self) -> None:
        self._running = False
        if self._worker:
            self._worker.join(timeout=3.0)

    def push_frame(self, frame: np.ndarray) -> None:
        now = time.time()
        if now - self._last_push < self._interval:
            return
        self._last_push = now
        try:
            self._q.put_nowait(frame)
        except queue.Full:
            try:
                self._q.get_nowait()
                self._q.put_nowait(frame)
            except queue.Empty:
                pass

    def _loop(self) -> None:
        while self._running:
            try:
                frame = self._q.get(timeout=0.5)
            except queue.Empty:
                continue
            t0 = time.time()
            self._counter += 1
            objects = self._detector.detect(frame)
            mag = self._change.detect(frame)
            changed = mag >= self._change.threshold
            analysis = FrameAnalysis(
                frame_id=self._counter, timestamp=t0,
                objects=objects, scene_changed=changed,
                change_magnitude=mag, processing_ms=(time.time() - t0) * 1000,
            )
            with self._lock:
                self._current = analysis
            if self._on_detection:
                try:
                    self._on_detection(analysis.to_dict())
                except Exception:
                    pass
            if changed and self._on_scene_change:
                try:
                    self._on_scene_change(analysis.to_dict())
                except Exception:
                    pass

    async def get_current_analysis(self) -> Optional[Dict[str, Any]]:
        with self._lock:
            return self._current.to_dict() if self._current else None

    async def handle_voice_command(self, command: str) -> Dict[str, Any]:
        """Return current analysis when triggered by voice command."""
        with self._lock:
            if self._current:
                return {"triggered_by": command, **self._current.to_dict()}
        return {"triggered_by": command, "error": "No frame available yet"}

    def set_on_detection(self, fn: Callable) -> None:
        self._on_detection = fn

    def set_on_scene_change(self, fn: Callable) -> None:
        self._on_scene_change = fn

    @property
    def is_running(self) -> bool:
        return self._running

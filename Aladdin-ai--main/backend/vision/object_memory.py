"""
Phase 7.9 — Object Memory
===========================
Remember previously seen objects, store name/location/history,
recognise same object when seen again, track movement,
link vision memories to long-term memory system.
"""
from __future__ import annotations
import asyncio, json, logging, os, threading, time
from dataclasses import asdict, dataclass, field
from typing import Any, Callable, Dict, List, Optional, Tuple
import numpy as np

log = logging.getLogger(__name__)
BBox = Tuple[int, int, int, int]


@dataclass
class ObjectSighting:
    timestamp: float
    bounding_box: List[int]
    confidence: float
    scene_context: str = ""
    frame_id: Optional[int] = None


@dataclass
class MemorizedObject:
    object_id: str
    label: str
    first_seen: float = field(default_factory=time.time)
    last_seen: float = field(default_factory=time.time)
    sighting_count: int = 0
    sightings: List[Dict] = field(default_factory=list)
    current_location: Optional[List[int]] = None
    movement_history: List[List[int]] = field(default_factory=list)

    def record(self, bbox: BBox, confidence: float, scene: str = "", frame_id: Optional[int] = None) -> None:
        now = time.time()
        self.last_seen = now
        self.sighting_count += 1
        if self.current_location:
            self.movement_history.append(self.current_location)
            if len(self.movement_history) > 50:
                self.movement_history = self.movement_history[-50:]
        self.current_location = list(bbox)
        self.sightings.append({
            "timestamp": now, "bounding_box": list(bbox),
            "confidence": confidence, "scene_context": scene, "frame_id": frame_id,
        })
        if len(self.sightings) > 100:
            self.sightings = self.sightings[-100:]

    def to_dict(self) -> Dict[str, Any]:
        return {
            "object_id": self.object_id,
            "label": self.label,
            "first_seen": self.first_seen,
            "last_seen": self.last_seen,
            "sighting_count": self.sighting_count,
            "current_location": self.current_location,
            "seconds_since_seen": round(time.time() - self.last_seen, 1),
        }


def _iou(a: BBox, b: BBox) -> float:
    ax, ay, aw, ah = a
    bx, by, bw, bh = b
    ix1, iy1 = max(ax, bx), max(ay, by)
    ix2, iy2 = min(ax + aw, bx + bw), min(ay + ah, by + bh)
    if ix2 <= ix1 or iy2 <= iy1:
        return 0.0
    inter = (ix2 - ix1) * (iy2 - iy1)
    union = aw * ah + bw * bh - inter
    return inter / union if union > 0 else 0.0


class ObjectMemoryModule:
    """
    Persistent object memory with IoU-based re-identification.

    Usage::

        mem = ObjectMemoryModule(db_path="object_memory.json")
        mem.set_long_term_memory_fn(ai_memory.store)

        await mem.update([{"label": "cup", "confidence": 0.9, "bbox": [100, 200, 50, 60]}])
        result = await mem.find_by_label("cup")
        summary = await mem.summarize()
    """

    def __init__(self, db_path: Optional[str] = "object_memory.json",
                 iou_threshold: float = 0.4, max_age_seconds: float = 3600.0) -> None:
        self._db_path = db_path
        self._iou = iou_threshold
        self._max_age = max_age_seconds
        self._objects: Dict[str, MemorizedObject] = {}
        self._lock = threading.RLock()
        self._next_id = 1
        self._long_term_fn: Optional[Callable[[str, Any], None]] = None
        if db_path:
            self._load()

    def _load(self) -> None:
        if not self._db_path or not os.path.exists(self._db_path):
            return
        try:
            with open(self._db_path, "r", encoding="utf-8") as f:
                data = json.load(f)
            with self._lock:
                for oid, obj_data in data.get("objects", {}).items():
                    obj_data.pop("sightings_full", None)
                    sightings = obj_data.pop("sightings", [])
                    obj = MemorizedObject(**obj_data)
                    obj.sightings = sightings
                    self._objects[oid] = obj
                self._next_id = data.get("next_id", 1)
            log.info("ObjectMemory: loaded %d objects", len(self._objects))
        except Exception as exc:
            log.error("ObjectMemory load error: %s", exc)

    def _save(self) -> None:
        if not self._db_path:
            return
        try:
            with self._lock:
                data = {
                    "objects": {oid: asdict(obj) for oid, obj in self._objects.items()},
                    "next_id": self._next_id,
                    "saved_at": time.time(),
                }
            with open(self._db_path, "w", encoding="utf-8") as f:
                json.dump(data, f, indent=2)
        except Exception as exc:
            log.error("ObjectMemory save error: %s", exc)

    async def update(self, detections: List[Dict[str, Any]],
                     scene_context: str = "", frame_id: Optional[int] = None) -> Dict[str, Any]:
        """Feed a list of {label, confidence, bbox} detections."""
        def _run():
            updated = []
            with self._lock:
                for det in detections:
                    label = str(det.get("label", "unknown"))
                    conf = float(det.get("confidence", 0.5))
                    raw_bbox = det.get("bbox", [0, 0, 0, 0])
                    bbox: BBox = tuple(int(v) for v in raw_bbox[:4])  # type: ignore
                    matched = self._find_match(label, bbox)
                    if matched:
                        matched.record(bbox, conf, scene_context, frame_id)
                        updated.append(matched.to_dict())
                    else:
                        oid = f"obj_{self._next_id:06d}"
                        self._next_id += 1
                        obj = MemorizedObject(object_id=oid, label=label)
                        obj.record(bbox, conf, scene_context, frame_id)
                        self._objects[oid] = obj
                        updated.append(obj.to_dict())
                        if self._long_term_fn:
                            try:
                                self._long_term_fn(f"vision.objects.{oid}",
                                                   {"label": label, "first_seen": obj.first_seen})
                            except Exception:
                                pass
            return {"updated": updated, "total_in_memory": len(self._objects)}

        return await asyncio.get_running_loop().run_in_executor(None, _run)

    def _find_match(self, label: str, bbox: BBox) -> Optional[MemorizedObject]:
        candidates = [o for o in self._objects.values()
                      if o.label.lower() == label.lower() and o.current_location]
        best, best_iou = None, self._iou
        for obj in candidates:
            existing = tuple(obj.current_location)  # type: ignore
            score = _iou(existing, bbox)
            if score >= best_iou:
                best_iou = score
                best = obj
        return best

    async def find_by_label(self, label: str) -> List[Dict[str, Any]]:
        with self._lock:
            return [o.to_dict() for o in self._objects.values()
                    if label.lower() in o.label.lower()]

    async def recent_objects(self, seconds: float = 60.0) -> List[Dict[str, Any]]:
        cutoff = time.time() - seconds
        with self._lock:
            return sorted(
                [o.to_dict() for o in self._objects.values() if o.last_seen >= cutoff],
                key=lambda x: x["last_seen"], reverse=True,
            )

    async def summarize(self) -> str:
        with self._lock:
            total = len(self._objects)
            if total == 0:
                return "No objects in visual memory."
            counts: Dict[str, int] = {}
            for o in self._objects.values():
                counts[o.label] = counts.get(o.label, 0) + 1
            top = sorted(counts.items(), key=lambda x: x[1], reverse=True)[:5]
            top_str = ", ".join(f"{l} ({c})" for l, c in top)
        recent = await self.recent_objects(300)
        return (f"Visual memory: {total} unique objects. "
                f"Most common: {top_str}. "
                f"{len(recent)} seen in last 5 minutes.")

    async def prune_old(self) -> int:
        with self._lock:
            old = [oid for oid, o in self._objects.items()
                   if time.time() - o.last_seen > self._max_age]
            for oid in old:
                del self._objects[oid]
        return len(old)

    def set_long_term_memory_fn(self, fn: Callable[[str, Any], None]) -> None:
        self._long_term_fn = fn

    def save(self) -> None:
        self._save()

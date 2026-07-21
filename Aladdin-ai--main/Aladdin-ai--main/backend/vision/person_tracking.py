"""
Phase 7.3 — Person Tracking
================================
Continuously track people in camera feed, assign unique IDs,
maintain tracking if person moves, handle leaving frame.
"""
from __future__ import annotations
import asyncio, logging, time
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional, Tuple
import numpy as np

log = logging.getLogger(__name__)
BBox = Tuple[int, int, int, int]  # x, y, w, h


@dataclass
class TrackedPerson:
    track_id: int
    bounding_box: BBox
    centroid: Tuple[int, int]
    age: int = 0                   # frames since last matched detection
    total_frames: int = 0
    first_seen: float = field(default_factory=time.time)
    last_seen: float = field(default_factory=time.time)
    velocity: Tuple[float, float] = (0.0, 0.0)
    is_active: bool = True
    face_name: Optional[str] = None
    history: List[Tuple[int, int]] = field(default_factory=list)

    def update(self, bbox: BBox) -> None:
        prev = self.centroid
        x, y, w, h = bbox
        new_c = (x + w // 2, y + h // 2)
        self.velocity = (float(new_c[0] - prev[0]), float(new_c[1] - prev[1]))
        self.centroid = new_c
        self.bounding_box = bbox
        self.last_seen = time.time()
        self.age = 0
        self.total_frames += 1
        self.history.append(new_c)
        if len(self.history) > 60:
            self.history = self.history[-60:]

    def to_dict(self) -> Dict[str, Any]:
        return {
            "track_id": self.track_id,
            "bounding_box": list(self.bounding_box),
            "centroid": list(self.centroid),
            "velocity": list(self.velocity),
            "is_active": self.is_active,
            "face_name": self.face_name,
            "total_frames": self.total_frames,
            "age_seconds": round(time.time() - self.first_seen, 1),
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


class PersonTrackingModule:
    """
    IoU-based multi-person tracker.

    Usage::

        tracker = PersonTrackingModule()
        result = await tracker.update(detections_as_bboxes)
        for person in result["active_tracks"]:
            log.info(person["track_id"], person["centroid"])

    """

    def __init__(self, max_age: int = 30, iou_threshold: float = 0.3) -> None:
        self._tracks: Dict[int, TrackedPerson] = {}
        self._next_id = 1
        self._max_age = max_age
        self._iou_threshold = iou_threshold
        self._frame_count = 0
        log.info("PersonTrackingModule initialised")

    async def update(self, detections: List[BBox]) -> Dict[str, Any]:
        """
        Update tracker with new detections.
        detections: list of (x, y, w, h) bounding boxes
        """
        def _run():
            self._frame_count += 1
            matched_track_ids: set = set()
            matched_det_indices: set = set()
            active = {tid: t for tid, t in self._tracks.items() if t.is_active}

            if active and detections:
                track_ids = list(active.keys())
                iou_mat = np.zeros((len(track_ids), len(detections)), dtype=np.float32)
                for i, tid in enumerate(track_ids):
                    for j, det in enumerate(detections):
                        iou_mat[i, j] = _iou(active[tid].bounding_box, det)
                while True:
                    if iou_mat.size == 0:
                        break
                    idx = np.unravel_index(np.argmax(iou_mat), iou_mat.shape)
                    i, j = int(idx[0]), int(idx[1])
                    if iou_mat[i, j] < self._iou_threshold:
                        break
                    tid = track_ids[i]
                    if tid not in matched_track_ids and j not in matched_det_indices:
                        active[tid].update(detections[j])
                        matched_track_ids.add(tid)
                        matched_det_indices.add(j)
                    iou_mat[i, :] = -1
                    iou_mat[:, j] = -1

            for tid, track in active.items():
                if tid not in matched_track_ids:
                    track.age += 1
                    if track.age > self._max_age:
                        track.is_active = False

            for j, det in enumerate(detections):
                if j not in matched_det_indices:
                    x, y, w, h = det
                    t = TrackedPerson(
                        track_id=self._next_id,
                        bounding_box=det,
                        centroid=(x + w // 2, y + h // 2),
                    )
                    self._tracks[self._next_id] = t
                    self._next_id += 1

            active_list = [t.to_dict() for t in self._tracks.values() if t.is_active]
            return {
                "active_tracks": active_list,
                "active_count": len(active_list),
                "total_seen": len(self._tracks),
                "frame": self._frame_count,
            }

        return await asyncio.get_running_loop().run_in_executor(None, _run)

    async def get_track(self, track_id: int) -> Optional[Dict[str, Any]]:
        track = self._tracks.get(track_id)
        return track.to_dict() if track else None

    async def assign_name(self, track_id: int, name: str) -> bool:
        if track_id in self._tracks:
            self._tracks[track_id].face_name = name
            return True
        return False

    def reset(self) -> None:
        self._tracks.clear()
        self._next_id = 1
        self._frame_count = 0

    @property
    def active_count(self) -> int:
        return sum(1 for t in self._tracks.values() if t.is_active)

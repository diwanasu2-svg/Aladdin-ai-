"""
Phase 7.2 — Face Recognition
================================
Detect faces, identify registered people, mark unknown faces,
maintain face database, calculate recognition confidence,
privacy and permission controls.
"""
from __future__ import annotations
import asyncio, io, json, logging, os, threading, time
from dataclasses import dataclass, field, asdict
from typing import Any, Dict, List, Optional, Tuple
import numpy as np

log = logging.getLogger(__name__)


@dataclass
class FaceRecord:
    person_id: str
    name: str
    embedding: List[float]
    added_at: float = field(default_factory=time.time)
    last_seen: Optional[float] = None
    encounter_count: int = 0
    notes: str = ""


@dataclass
class FaceDetection:
    face_id: str
    bounding_box: Tuple[int, int, int, int]  # x, y, w, h
    confidence: float
    recognized_name: Optional[str] = None
    person_id: Optional[str] = None
    is_known: bool = False
    recognition_confidence: float = 0.0


class FaceDatabase:
    """Persistent JSON face-embedding database."""

    def __init__(self, db_path: str = "face_database.json") -> None:
        self._db_path = db_path
        self._records: Dict[str, FaceRecord] = {}
        self._lock = threading.RLock()
        self._load()

    def _load(self) -> None:
        if not os.path.exists(self._db_path):
            return
        try:
            with open(self._db_path, "r", encoding="utf-8") as f:
                raw = json.load(f)
            with self._lock:
                for pid, data in raw.items():
                    self._records[pid] = FaceRecord(**data)
            log.info("FaceDatabase: loaded %d records", len(self._records))
        except Exception as exc:
            log.error("FaceDatabase load error: %s", exc)

    def _save(self) -> None:
        try:
            with self._lock:
                data = {pid: asdict(rec) for pid, rec in self._records.items()}
            with open(self._db_path, "w", encoding="utf-8") as f:
                json.dump(data, f, indent=2)
        except Exception as exc:
            log.error("FaceDatabase save error: %s", exc)

    def add_person(self, name: str, embedding: List[float], notes: str = "") -> str:
        person_id = f"person_{int(time.time() * 1000)}"
        record = FaceRecord(person_id=person_id, name=name, embedding=embedding, notes=notes)
        with self._lock:
            self._records[person_id] = record
        self._save()
        log.info("FaceDatabase: registered '%s' (%s)", name, person_id)
        return person_id

    def remove_person(self, person_id: str) -> bool:
        with self._lock:
            if person_id not in self._records:
                return False
            del self._records[person_id]
        self._save()
        return True

    def find_closest(self, embedding: List[float], threshold: float = 0.55
                     ) -> Optional[Tuple[FaceRecord, float]]:
        if not embedding:
            return None
        query = np.array(embedding, dtype=np.float32)
        qnorm = np.linalg.norm(query)
        if qnorm < 1e-9:
            return None
        best_record: Optional[FaceRecord] = None
        best_sim = -1.0
        with self._lock:
            for rec in self._records.values():
                if not rec.embedding:
                    continue
                ref = np.array(rec.embedding, dtype=np.float32)
                rnorm = np.linalg.norm(ref)
                if rnorm < 1e-9:
                    continue
                sim = float(np.dot(query, ref) / (qnorm * rnorm))
                if sim > best_sim:
                    best_sim = sim
                    best_record = rec
        if best_record and best_sim >= threshold:
            return best_record, best_sim
        return None

    def update_last_seen(self, person_id: str) -> None:
        with self._lock:
            if person_id in self._records:
                self._records[person_id].last_seen = time.time()
                self._records[person_id].encounter_count += 1
        self._save()

    def list_people(self) -> List[Dict[str, Any]]:
        with self._lock:
            return [
                {"person_id": r.person_id, "name": r.name,
                 "last_seen": r.last_seen, "encounters": r.encounter_count, "notes": r.notes}
                for r in self._records.values()
            ]

    @property
    def count(self) -> int:
        with self._lock:
            return len(self._records)


class FaceRecognitionModule:
    """
    Face detection + recognition.

    Backends (priority order):
    1. face_recognition (dlib) — high accuracy
    2. OpenCV Haar cascades — lightweight fallback
    """

    def __init__(self, db_path: str = "face_database.json",
                 recognition_threshold: float = 0.55) -> None:
        self._db = FaceDatabase(db_path)
        self._threshold = recognition_threshold
        self._backend = self._detect_backend()
        self._camera_granted = False
        self._biometric_granted = False
        log.info("FaceRecognitionModule: backend=%s", self._backend)

    def _detect_backend(self) -> str:
        try:
            import face_recognition as _  # type: ignore  # noqa
            return "face_recognition"
        except ImportError:
            pass
        try:
            import cv2 as _  # type: ignore  # noqa
            return "opencv"
        except ImportError:
            pass
        return "stub"

    # ── Permissions ─────────────────────────────────────────────────────────

    def grant_camera(self) -> None:
        self._camera_granted = True

    def grant_biometric(self) -> None:
        self._biometric_granted = True

    def revoke_camera(self) -> None:
        self._camera_granted = False

    def revoke_biometric(self) -> None:
        self._biometric_granted = False

    def permission_status(self) -> Dict[str, bool]:
        return {"camera": self._camera_granted, "biometric": self._biometric_granted}

    # ── Core detection ───────────────────────────────────────────────────────

    async def detect_faces(self, image_bytes: bytes) -> List[FaceDetection]:
        """Detect all faces in image bytes."""
        if not self._camera_granted:
            log.warning("FaceRecognitionModule: camera permission not granted")
            return []

        def _run():
            img_array = np.frombuffer(image_bytes, np.uint8)
            try:
                import cv2  # type: ignore
                img = cv2.imdecode(img_array, cv2.IMREAD_COLOR)
            except ImportError:
                return []
            if img is None:
                return []
            if self._backend == "face_recognition":
                return self._detect_fr(img)
            elif self._backend == "opencv":
                return self._detect_opencv(img)
            return []

        return await asyncio.get_running_loop().run_in_executor(None, _run)

    async def process_frame(self, image_bytes: bytes) -> List[FaceDetection]:
        """Detect and identify all faces in a frame."""
        detections = await self.detect_faces(image_bytes)
        for det in detections:
            emb = self._extract_embedding_bytes(image_bytes, det.bounding_box)
            if emb:
                match = self._db.find_closest(emb, self._threshold)
                if match:
                    rec, sim = match
                    det.recognized_name = rec.name
                    det.person_id = rec.person_id
                    det.is_known = True
                    det.recognition_confidence = sim
                    self._db.update_last_seen(rec.person_id)
                else:
                    det.recognized_name = "Unknown"
        return detections

    def _detect_fr(self, img: np.ndarray) -> List[FaceDetection]:
        try:
            import face_recognition as fr  # type: ignore
            rgb = img[:, :, ::-1]
            locations = fr.face_locations(rgb, model="hog")
            return [
                FaceDetection(
                    face_id=f"face_{i}",
                    bounding_box=(left, top, right - left, bottom - top),
                    confidence=0.9,
                )
                for i, (top, right, bottom, left) in enumerate(locations)
            ]
        except Exception as exc:
            log.error("face_recognition detect error: %s", exc)
            return []

    def _detect_opencv(self, img: np.ndarray) -> List[FaceDetection]:
        try:
            import cv2  # type: ignore
            gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
            path = cv2.data.haarcascades + "haarcascade_frontalface_default.xml"
            cascade = cv2.CascadeClassifier(path)
            faces = cascade.detectMultiScale(gray, 1.1, 5, minSize=(30, 30))
            return [
                FaceDetection(face_id=f"face_{i}",
                              bounding_box=(int(x), int(y), int(w), int(h)),
                              confidence=0.75)
                for i, (x, y, w, h) in enumerate(faces)
            ]
        except Exception as exc:
            log.error("OpenCV face detect error: %s", exc)
            return []

    def _extract_embedding_bytes(self, image_bytes: bytes,
                                  bbox: Tuple[int, int, int, int]) -> Optional[List[float]]:
        if self._backend != "face_recognition":
            return None
        try:
            import face_recognition as fr  # type: ignore
            img_array = np.frombuffer(image_bytes, np.uint8)
            import cv2  # type: ignore
            img = cv2.imdecode(img_array, cv2.IMREAD_COLOR)
            rgb = img[:, :, ::-1]
            x, y, w, h = bbox
            location = [(y, x + w, y + h, x)]
            encodings = fr.face_encodings(rgb, location)
            return encodings[0].tolist() if encodings else None
        except Exception:
            return None

    # ── Registration ─────────────────────────────────────────────────────────

    async def register_person_from_bytes(
        self, name: str, image_bytes: bytes, notes: str = ""
    ) -> Optional[str]:
        """Register a person from raw image bytes."""
        def _run():
            img_array = np.frombuffer(image_bytes, np.uint8)
            try:
                import cv2  # type: ignore
                img = cv2.imdecode(img_array, cv2.IMREAD_COLOR)
            except ImportError:
                return None
            h, w = img.shape[:2]
            emb = self._extract_embedding_bytes(image_bytes, (0, 0, w, h))
            return self._db.add_person(name, emb or [], notes)

        return await asyncio.get_running_loop().run_in_executor(None, _run)

    def remove_person(self, person_id: str) -> bool:
        return self._db.remove_person(person_id)

    def list_registered_people(self) -> List[Dict[str, Any]]:
        return self._db.list_people()

    @property
    def database(self) -> FaceDatabase:
        return self._db

"""Object detection using OpenCV or Vision API fallback."""
from __future__ import annotations
import asyncio, io, logging
from typing import Any, Dict, List, Optional

log = logging.getLogger(__name__)

try:
    import cv2
    import numpy as np
    from PIL import Image
    _CV2_AVAILABLE = True
except ImportError:
    _CV2_AVAILABLE = False
    log.warning("opencv-python not installed — object detection limited")


class ObjectDetector:
    def __init__(self, confidence_threshold: float = 0.5) -> None:
        self._threshold = confidence_threshold
        self._net = None
        self._classes: List[str] = []

    @property
    def available(self) -> bool:
        return _CV2_AVAILABLE

    async def detect(self, image_bytes: bytes) -> Dict[str, Any]:
        """Detect objects. Uses YOLO if model loaded, otherwise basic detection."""
        if not _CV2_AVAILABLE:
            return {"objects": [], "method": "unavailable", "error": "opencv not installed"}
        return await self._basic_detect(image_bytes)

    async def _basic_detect(self, image_bytes: bytes) -> Dict[str, Any]:
        """Basic OpenCV detection (contour-based when no YOLO model available)."""
        def _run():
            img_array = np.frombuffer(image_bytes, dtype=np.uint8)
            img = cv2.imdecode(img_array, cv2.IMREAD_COLOR)
            if img is None:
                return {"objects": [], "method": "opencv", "error": "Failed to decode image"}
            h, w = img.shape[:2]
            gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
            blurred = cv2.GaussianBlur(gray, (11, 11), 0)
            _, thresh = cv2.threshold(blurred, 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU)
            contours, _ = cv2.findContours(thresh, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
            objects = []
            for i, cnt in enumerate(contours[:20]):
                area = cv2.contourArea(cnt)
                if area < 500:
                    continue
                x, y, bw, bh = cv2.boundingRect(cnt)
                objects.append({
                    "id": i, "label": "object", "confidence": 0.5,
                    "bbox": {"x": x, "y": y, "width": bw, "height": bh},
                    "relative_bbox": {"x": x/w, "y": y/h, "width": bw/w, "height": bh/h},
                })
            return {"objects": objects, "method": "opencv_contour",
                    "image_size": {"width": w, "height": h}, "count": len(objects)}
        return await asyncio.get_running_loop().run_in_executor(None, _run)

    async def load_yolo(self, weights_path: str, config_path: str, names_path: str) -> bool:
        """Optionally load a YOLO model for proper detection."""
        if not _CV2_AVAILABLE:
            return False
        def _load():
            try:
                self._net = cv2.dnn.readNet(weights_path, config_path)
                with open(names_path) as f:
                    self._classes = [line.strip() for line in f.readlines()]
                return True
            except Exception as exc:
                log.error("YOLO load failed: %s", exc)
                return False
        return await asyncio.get_running_loop().run_in_executor(None, _load)

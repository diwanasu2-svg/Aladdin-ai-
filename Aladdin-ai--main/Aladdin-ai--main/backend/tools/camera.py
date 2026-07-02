"""Camera tool — capture photos, record video, scan QR codes, apply filters via OpenCV/PIL."""
from __future__ import annotations
import asyncio, base64, logging, os, time
from pathlib import Path
from typing import Dict, List, Optional
from .base import BaseTool, ToolResult

log = logging.getLogger(__name__)
PHOTOS_DIR = Path(os.getenv("PHOTOS_DIR", "/tmp/aladdin_photos"))
PHOTOS_DIR.mkdir(parents=True, exist_ok=True)


class CapturePhotoTool(BaseTool):
    name = "capture_photo"
    description = "Capture a photo using the device camera."
    parameters = {"type": "object", "properties": {
        "camera": {"type": "string", "enum": ["front", "rear"], "default": "rear"},
        "flash": {"type": "string", "enum": ["on", "off", "auto"], "default": "auto"},
        "filename": {"type": "string", "description": "Optional filename override"}}}

    async def execute(self, camera: str = "rear", flash: str = "auto", filename: str = None) -> ToolResult:
        t0 = time.time()
        try:
            import cv2
            cam_idx = 1 if camera == "front" else 0
            cap = cv2.VideoCapture(cam_idx)
            if not cap.isOpened():
                return ToolResult(False, self.name, error=f"Camera {camera} not available")
            ret, frame = cap.read()
            cap.release()
            if not ret:
                return ToolResult(False, self.name, error="Failed to capture frame")
            fname = filename or f"photo_{int(time.time())}.jpg"
            fpath = PHOTOS_DIR / fname
            cv2.imwrite(str(fpath), frame)
            _, buf = cv2.imencode(".jpg", frame)
            b64 = base64.b64encode(buf).decode()
            return ToolResult(True, self.name, {
                "path": str(fpath), "filename": fname,
                "camera": camera, "preview_base64": b64[:200] + "..."
            }, duration_ms=(time.time() - t0) * 1000)
        except ImportError:
            return ToolResult(False, self.name, error="opencv-python not installed — run: pip install opencv-python")
        except Exception as exc:
            return ToolResult(False, self.name, error=str(exc), duration_ms=(time.time() - t0) * 1000)


class RecordVideoTool(BaseTool):
    name = "record_video"
    description = "Record a short video clip from the device camera."
    parameters = {"type": "object", "properties": {
        "duration_seconds": {"type": "integer", "default": 5, "description": "Recording duration in seconds"},
        "camera": {"type": "string", "enum": ["front", "rear"], "default": "rear"},
        "filename": {"type": "string"}}}

    async def execute(self, duration_seconds: int = 5, camera: str = "rear", filename: str = None) -> ToolResult:
        t0 = time.time()
        try:
            import cv2
            cam_idx = 1 if camera == "front" else 0
            cap = cv2.VideoCapture(cam_idx)
            if not cap.isOpened():
                return ToolResult(False, self.name, error=f"Camera {camera} not available")
            fps = cap.get(cv2.CAP_PROP_FPS) or 20.0
            w = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
            h = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
            fname = filename or f"video_{int(time.time())}.mp4"
            fpath = PHOTOS_DIR / fname
            fourcc = cv2.VideoWriter_fourcc(*"mp4v")
            out = cv2.VideoWriter(str(fpath), fourcc, fps, (w, h))
            max_frames = int(fps * duration_seconds)
            for _ in range(max_frames):
                ret, frame = cap.read()
                if not ret:
                    break
                out.write(frame)
            cap.release()
            out.release()
            size = fpath.stat().st_size if fpath.exists() else 0
            return ToolResult(True, self.name, {
                "path": str(fpath), "filename": fname,
                "duration_seconds": duration_seconds, "size_bytes": size
            }, duration_ms=(time.time() - t0) * 1000)
        except ImportError:
            return ToolResult(False, self.name, error="opencv-python not installed — run: pip install opencv-python")
        except Exception as exc:
            return ToolResult(False, self.name, error=str(exc), duration_ms=(time.time() - t0) * 1000)


class ScanQrCodeTool(BaseTool):
    name = "scan_qr_code"
    description = "Scan a QR code or barcode using the camera or from an image file."
    parameters = {"type": "object", "properties": {
        "image_path": {"type": "string", "description": "Optional: path to image file to scan. If omitted, uses camera."}}}

    async def execute(self, image_path: str = None) -> ToolResult:
        t0 = time.time()
        try:
            import cv2
            if image_path:
                img = cv2.imread(image_path)
            else:
                cap = cv2.VideoCapture(0)
                ret, img = cap.read()
                cap.release()
                if not ret:
                    return ToolResult(False, self.name, error="Could not capture frame")

            detector = cv2.QRCodeDetector()
            data, bbox, _ = detector.detectAndDecode(img)
            if data:
                return ToolResult(True, self.name, {"qr_data": data, "found": True},
                                  duration_ms=(time.time() - t0) * 1000)
            # Try pyzbar as fallback
            try:
                from pyzbar.pyzbar import decode
                decoded = decode(img)
                if decoded:
                    return ToolResult(True, self.name, {
                        "qr_data": decoded[0].data.decode(),
                        "type": decoded[0].type, "found": True
                    }, duration_ms=(time.time() - t0) * 1000)
            except ImportError:
                pass
            return ToolResult(True, self.name, {"found": False, "qr_data": None},
                              duration_ms=(time.time() - t0) * 1000)
        except ImportError:
            return ToolResult(False, self.name, error="opencv-python not installed")
        except Exception as exc:
            return ToolResult(False, self.name, error=str(exc), duration_ms=(time.time() - t0) * 1000)


class ApplyFilterTool(BaseTool):
    name = "apply_filter"
    description = "Apply a visual filter/effect to a photo."
    parameters = {"type": "object", "properties": {
        "input_path": {"type": "string"}, "output_path": {"type": "string"},
        "filter": {"type": "string", "enum": ["grayscale", "blur", "sharpen", "sepia", "edge", "brightness"]}},
        "required": ["input_path", "filter"]}

    async def execute(self, input_path: str, filter: str, output_path: str = None) -> ToolResult:
        t0 = time.time()
        try:
            import cv2
            import numpy as np
            img = cv2.imread(input_path)
            if img is None:
                return ToolResult(False, self.name, error=f"Could not read image: {input_path}")
            if filter == "grayscale":
                result = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
                result = cv2.cvtColor(result, cv2.COLOR_GRAY2BGR)
            elif filter == "blur":
                result = cv2.GaussianBlur(img, (15, 15), 0)
            elif filter == "sharpen":
                kernel = np.array([[-1,-1,-1],[-1,9,-1],[-1,-1,-1]])
                result = cv2.filter2D(img, -1, kernel)
            elif filter == "sepia":
                sepia = np.array([[0.272, 0.534, 0.131],
                                  [0.349, 0.686, 0.168],
                                  [0.393, 0.769, 0.189]])
                result = cv2.transform(img, sepia)
                result = np.clip(result, 0, 255).astype(np.uint8)
            elif filter == "edge":
                result = cv2.Canny(img, 100, 200)
                result = cv2.cvtColor(result, cv2.COLOR_GRAY2BGR)
            elif filter == "brightness":
                result = cv2.convertScaleAbs(img, alpha=1.3, beta=30)
            else:
                result = img
            out = output_path or f"{Path(input_path).stem}_{filter}{Path(input_path).suffix}"
            cv2.imwrite(out, result)
            return ToolResult(True, self.name, {"output_path": out, "filter": filter},
                              duration_ms=(time.time() - t0) * 1000)
        except Exception as exc:
            return ToolResult(False, self.name, error=str(exc), duration_ms=(time.time() - t0) * 1000)


class ListPhotosTool(BaseTool):
    name = "list_photos"
    description = "List photos saved by Aladdin's camera."
    parameters = {"type": "object", "properties": {
        "limit": {"type": "integer", "default": 20}}}

    async def execute(self, limit: int = 20) -> ToolResult:
        photos = sorted(PHOTOS_DIR.glob("*.jpg"), key=lambda f: f.stat().st_mtime, reverse=True)
        result = [{"filename": p.name, "path": str(p), "size_bytes": p.stat().st_size,
                   "created": p.stat().st_mtime} for p in photos[:limit]]
        return ToolResult(True, self.name, {"photos": result, "count": len(result)})

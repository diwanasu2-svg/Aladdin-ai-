"""Vision manager — orchestrates all vision providers (Phase 6 + Phase 7)."""
from __future__ import annotations
import logging
from typing import Any, Dict, List, Optional

log = logging.getLogger(__name__)


class VisionManager:
    def __init__(self) -> None:
        # Phase 6 providers
        self._gpt4 = None
        self._gemini = None
        self._ocr = None
        self._detector = None
        self._pdf = None
        self._screenshot = None
        # Phase 7 modules
        self._face = None
        self._tracker = None
        self._scene = None
        self._live_camera = None
        self._gesture = None
        self._obj_memory = None
        self._vision_voice = None

    def setup(self, openai_key: Optional[str] = None,
              gemini_key: Optional[str] = None) -> None:
        """Initialise all vision providers and Phase 7 modules."""
        # ── Phase 6 providers ─────────────────────────────────────────────
        if openai_key:
            try:
                from .gpt4_vision import GPT4VisionClient
                self._gpt4 = GPT4VisionClient(api_key=openai_key)
                log.info("GPT-4 Vision ready")
            except Exception as exc:
                log.warning("GPT-4 Vision init failed: %s", exc)

        if gemini_key:
            try:
                from .gemini_vision import GeminiVisionClient
                self._gemini = GeminiVisionClient(api_key=gemini_key)
                log.info("Gemini Vision ready")
            except Exception as exc:
                log.warning("Gemini Vision init failed: %s", exc)

        # OCR (Phase 7 improved version)
        try:
            from .ocr import OCREngine
            self._ocr = OCREngine(gemini_key=gemini_key)
            log.info("OCR Engine (Phase 7) ready")
        except Exception as exc:
            log.warning("OCR init failed: %s", exc)

        try:
            from .object_detection import ObjectDetector
            self._detector = ObjectDetector()
        except Exception as exc:
            log.warning("Object detector init failed: %s", exc)

        try:
            from .pdf_understanding import PDFAnalyzer
            self._pdf = PDFAnalyzer()
        except Exception as exc:
            log.warning("PDF analyzer init failed: %s", exc)

        # Screenshot (Phase 7 improved version)
        try:
            from .screenshot import ScreenshotAnalyzer
            self._screenshot = ScreenshotAnalyzer(
                vision_manager=self, gemini_key=gemini_key
            )
            log.info("ScreenshotAnalyzer (Phase 7) ready")
        except Exception as exc:
            log.warning("Screenshot analyzer init failed: %s", exc)

        # ── Phase 7 modules ───────────────────────────────────────────────
        try:
            from .face_recognition import FaceRecognitionModule
            self._face = FaceRecognitionModule()
            log.info("FaceRecognitionModule ready")
        except Exception as exc:
            log.warning("FaceRecognition init failed: %s", exc)

        try:
            from .person_tracking import PersonTrackingModule
            self._tracker = PersonTrackingModule()
            log.info("PersonTrackingModule ready")
        except Exception as exc:
            log.warning("PersonTracking init failed: %s", exc)

        try:
            from .scene_understanding import SceneUnderstandingModule
            self._scene = SceneUnderstandingModule(gemini_key=gemini_key)
            log.info("SceneUnderstandingModule ready")
        except Exception as exc:
            log.warning("SceneUnderstanding init failed: %s", exc)

        try:
            from .live_camera import LiveCameraAnalyzer
            self._live_camera = LiveCameraAnalyzer()
            log.info("LiveCameraAnalyzer ready")
        except Exception as exc:
            log.warning("LiveCamera init failed: %s", exc)

        try:
            from .gesture_recognition import GestureRecognitionModule
            self._gesture = GestureRecognitionModule()
            log.info("GestureRecognitionModule ready")
        except Exception as exc:
            log.warning("GestureRecognition init failed: %s", exc)

        try:
            from .object_memory import ObjectMemoryModule
            self._obj_memory = ObjectMemoryModule()
            log.info("ObjectMemoryModule ready")
        except Exception as exc:
            log.warning("ObjectMemory init failed: %s", exc)

        try:
            from .vision_voice import VisionVoiceIntegration
            self._vision_voice = VisionVoiceIntegration()
            self._vision_voice.configure(gemini_key=gemini_key)
            log.info("VisionVoiceIntegration ready")
        except Exception as exc:
            log.warning("VisionVoice init failed: %s", exc)

    # ── Phase 6 core methods ─────────────────────────────────────────────────

    def _pick_vision_client(self, preferred: Optional[str] = None):
        if preferred == "gemini" and self._gemini:
            return self._gemini
        if preferred == "gpt4" and self._gpt4:
            return self._gpt4
        return self._gpt4 or self._gemini

    async def analyze(self, image_bytes: bytes, prompt: str = "Describe this image.",
                      provider: Optional[str] = None, mime_type: str = "image/jpeg") -> Dict[str, Any]:
        client = self._pick_vision_client(provider)
        if client is None:
            raise RuntimeError("No vision provider configured. Set OPENAI_API_KEY or GEMINI_API_KEY.")
        return await client.analyze(image_bytes, prompt=prompt, mime_type=mime_type)

    async def analyze_url(self, url: str, prompt: str = "Describe this image.",
                          provider: Optional[str] = None) -> Dict[str, Any]:
        client = self._pick_vision_client(provider)
        if client is None:
            raise RuntimeError("No vision provider configured.")
        return await client.analyze_url(url, prompt)

    async def ocr(self, image_bytes: bytes, language: Optional[str] = None) -> Dict[str, Any]:
        if self._ocr:
            return await self._ocr.extract_text(image_bytes, language)
        client = self._pick_vision_client()
        if client:
            return await client.analyze(image_bytes,
                                        prompt="Extract all text from this image exactly as it appears.")
        raise RuntimeError("No OCR or vision provider available.")

    async def detect_objects(self, image_bytes: bytes) -> Dict[str, Any]:
        if self._detector and self._detector.available:
            return await self._detector.detect(image_bytes)
        client = self._pick_vision_client()
        if client:
            return await client.analyze(image_bytes,
                prompt="List all objects in this image with their locations. Format as JSON.")
        raise RuntimeError("No object detection available.")

    async def analyze_screenshot(self, image_bytes: bytes, context: Optional[str] = None,
                                  mime_type: str = "image/png") -> Dict[str, Any]:
        if self._screenshot:
            return await self._screenshot.analyze(image_bytes, context, mime_type)
        raise RuntimeError("Screenshot analyzer not initialized.")

    async def analyze_pdf(self, pdf_bytes: bytes) -> Dict[str, Any]:
        if self._pdf and self._pdf.available:
            return await self._pdf.extract(pdf_bytes)
        raise RuntimeError("PDF analyzer not available. Install pdfplumber.")

    # ── Phase 7 methods ──────────────────────────────────────────────────────

    async def detect_faces(self, image_bytes: bytes) -> Dict[str, Any]:
        """Phase 7.2 — Detect and identify faces."""
        if self._face is None:
            raise RuntimeError("FaceRecognitionModule not initialized.")
        self._face.grant_camera()
        detections = await self._face.process_frame(image_bytes)
        return {
            "faces": [
                {
                    "face_id": d.face_id,
                    "bounding_box": list(d.bounding_box),
                    "confidence": d.confidence,
                    "recognized_name": d.recognized_name,
                    "person_id": d.person_id,
                    "is_known": d.is_known,
                    "recognition_confidence": d.recognition_confidence,
                }
                for d in detections
            ],
            "count": len(detections),
        }

    async def register_person(self, name: str, image_bytes: bytes,
                               notes: str = "") -> Dict[str, Any]:
        """Phase 7.2 — Register a person in the face database."""
        if self._face is None:
            raise RuntimeError("FaceRecognitionModule not initialized.")
        person_id = await self._face.register_person_from_bytes(name, image_bytes, notes)
        return {"person_id": person_id, "name": name, "status": "registered"}

    async def list_registered_people(self) -> Dict[str, Any]:
        """Phase 7.2 — List all registered people."""
        if self._face is None:
            raise RuntimeError("FaceRecognitionModule not initialized.")
        return {"people": self._face.list_registered_people()}

    async def track_persons(self, detections: List[Dict[str, Any]]) -> Dict[str, Any]:
        """Phase 7.3 — Update person tracker with new bounding-box detections."""
        if self._tracker is None:
            raise RuntimeError("PersonTrackingModule not initialized.")
        bboxes = [tuple(d.get("bbox", [0, 0, 0, 0])[:4]) for d in detections]
        return await self._tracker.update(bboxes)  # type: ignore

    async def understand_scene(self, image_bytes: bytes) -> Dict[str, Any]:
        """Phase 7.4 — Full scene understanding."""
        if self._scene is None:
            raise RuntimeError("SceneUnderstandingModule not initialized.")
        return await self._scene.analyze(image_bytes)

    async def ocr_advanced(self, image_bytes: bytes, language: str = "eng",
                            extract_tables: bool = False,
                            extract_forms: bool = False) -> Dict[str, Any]:
        """Phase 7.5 — Advanced OCR with language, tables, forms support."""
        if self._ocr is None:
            raise RuntimeError("OCREngine not initialized.")
        if extract_tables:
            return await self._ocr.extract_tables(image_bytes)
        if extract_forms:
            return await self._ocr.extract_form_fields(image_bytes)
        return await self._ocr.extract_text(image_bytes, language)

    async def get_live_analysis(self) -> Dict[str, Any]:
        """Phase 7.6 — Get the latest live camera analysis frame."""
        if self._live_camera is None:
            raise RuntimeError("LiveCameraAnalyzer not initialized.")
        result = await self._live_camera.get_current_analysis()
        return result or {"error": "No frame analyzed yet. Push frames first."}

    async def explain_screenshot(self, image_bytes: bytes,
                                  goal: str = "") -> Dict[str, Any]:
        """Phase 7.7 — Screenshot reasoning with user guidance."""
        if self._screenshot is None:
            raise RuntimeError("ScreenshotAnalyzer not initialized.")
        if goal:
            return await self._screenshot.guide_user(image_bytes, goal)
        return await self._screenshot.explain_error(image_bytes)

    async def process_gesture(self, frame_bytes: bytes) -> Dict[str, Any]:
        """Phase 7.8 — Detect hand gestures in a frame."""
        if self._gesture is None:
            raise RuntimeError("GestureRecognitionModule not initialized.")
        events = await self._gesture.process_frame_bytes(frame_bytes)
        return {"gestures": events, "count": len(events)}

    async def update_object_memory(self, detections: List[Dict[str, Any]],
                                    scene_context: str = "") -> Dict[str, Any]:
        """Phase 7.9 — Feed detections into persistent object memory."""
        if self._obj_memory is None:
            raise RuntimeError("ObjectMemoryModule not initialized.")
        return await self._obj_memory.update(detections, scene_context)

    async def query_object_memory(self, label: Optional[str] = None) -> Dict[str, Any]:
        """Phase 7.9 — Query object memory."""
        if self._obj_memory is None:
            raise RuntimeError("ObjectMemoryModule not initialized.")
        if label:
            objects = await self._obj_memory.find_by_label(label)
            return {"label": label, "objects": objects, "count": len(objects)}
        summary = await self._obj_memory.summarize()
        return {"summary": summary}

    async def vision_voice_handle(self, voice_command: str,
                                   image_bytes: Optional[bytes] = None) -> Dict[str, Any]:
        """Phase 7.10 — Multimodal voice + vision command handler."""
        if self._vision_voice is None:
            raise RuntimeError("VisionVoiceIntegration not initialized.")
        response = await self._vision_voice.handle(voice_command, image_bytes)
        return response.to_dict()

    # ── Capabilities ─────────────────────────────────────────────────────────

    @property
    def capabilities(self) -> Dict[str, bool]:
        return {
            # Phase 6
            "gpt4_vision": self._gpt4 is not None,
            "gemini_vision": self._gemini is not None,
            "ocr": self._ocr is not None,
            "object_detection": self._detector is not None and self._detector.available,
            "screenshot": self._screenshot is not None,
            "pdf": self._pdf is not None and self._pdf.available,
            # Phase 7
            "face_recognition": self._face is not None,
            "person_tracking": self._tracker is not None,
            "scene_understanding": self._scene is not None,
            "live_camera": self._live_camera is not None,
            "gesture_recognition": self._gesture is not None,
            "object_memory": self._obj_memory is not None,
            "vision_voice": self._vision_voice is not None,
        }

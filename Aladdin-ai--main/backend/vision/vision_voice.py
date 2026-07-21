"""
Phase 7.10 — Vision + Voice Integration
=========================================
Unified multimodal pipeline: voice command → camera → AI reasoning → TTS response.

Covers:
- Voice command triggers camera analysis
- AI image understanding → spoken response
- Camera + speech in one workflow
- Visual context in conversation
- Full pipeline: camera → OCR → AI → TTS
"""
from __future__ import annotations
import asyncio, logging, time
from dataclasses import dataclass, field
from typing import Any, Callable, Dict, List, Optional
import numpy as np

log = logging.getLogger(__name__)

_COMMAND_ROUTES = {
    # What do you see
    "what do you see": "analyze_scene",
    "kya dikh raha": "analyze_scene",
    "dekho": "analyze_scene",
    "analyze": "analyze_scene",
    # Read text
    "read": "read_text",
    "padho": "read_text",
    "kya likha": "read_text",
    "what does it say": "read_text",
    # Person
    "who is": "identify_person",
    "kaun hai": "identify_person",
    # Document
    "summarize": "summarize_document",
    "explain this": "summarize_document",
    "samjhao": "summarize_document",
    # Screenshot
    "screenshot": "analyze_screenshot",
    "screen pe kya": "analyze_screenshot",
    "what's on screen": "analyze_screenshot",
    # Object memory
    "have you seen": "recall_object",
    "pehle dekha": "recall_object",
    "memory": "memory_summary",
}


@dataclass
class MultimodalResponse:
    text: str
    ocr_text: str = ""
    scene_summary: str = ""
    spoken: bool = False
    processing_ms: float = 0.0
    timestamp: float = field(default_factory=time.time)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "text": self.text,
            "ocr_text": self.ocr_text,
            "scene_summary": self.scene_summary,
            "spoken": self.spoken,
            "processing_ms": round(self.processing_ms, 1),
        }


class VisionVoiceIntegration:
    """
    Multimodal Vision + Voice pipeline for Aladdin.

    Wire up::

        integration = VisionVoiceIntegration()
        integration.configure(
            ai_fn=lambda prompt: ai_engine.generate(prompt),
            tts_fn=lambda text: streaming_tts.speak(text),
            camera_fn=lambda: cv2.VideoCapture(0).read()[1],
            gemini_key="YOUR_KEY",
        )

        # On voice input:
        response = await integration.handle("Aladdin, is document summarize karo", image_bytes)
        # → OCR → AI → TTS

    """

    def __init__(self) -> None:
        self._ai_fn: Optional[Callable[[str], str]] = None
        self._tts_fn: Optional[Callable[[str], None]] = None
        self._camera_fn: Optional[Callable[[], Optional[np.ndarray]]] = None
        self._gemini_key: Optional[str] = None

        # Sub-modules (lazy-initialised)
        self._scene: Optional[Any] = None
        self._ocr: Optional[Any] = None
        self._face: Optional[Any] = None
        self._screenshot: Optional[Any] = None
        self._obj_memory: Optional[Any] = None

        log.info("VisionVoiceIntegration initialised")

    def configure(
        self,
        ai_fn: Optional[Callable[[str], str]] = None,
        tts_fn: Optional[Callable[[str], None]] = None,
        camera_fn: Optional[Callable[[], Optional[np.ndarray]]] = None,
        gemini_key: Optional[str] = None,
    ) -> None:
        self._ai_fn = ai_fn
        self._tts_fn = tts_fn
        self._camera_fn = camera_fn
        self._gemini_key = gemini_key
        if gemini_key:
            self._init_modules(gemini_key)

    def _init_modules(self, key: str) -> None:
        from .scene_understanding import SceneUnderstandingModule
        from .ocr import OCREngine
        from .face_recognition import FaceRecognitionModule
        from .screenshot import ScreenshotAnalyzer
        from .object_memory import ObjectMemoryModule

        self._scene = SceneUnderstandingModule(gemini_key=key)
        self._ocr = OCREngine(gemini_key=key)
        self._face = FaceRecognitionModule()
        self._screenshot = ScreenshotAnalyzer(gemini_key=key)
        self._obj_memory = ObjectMemoryModule()

    # ── Main handler ──────────────────────────────────────────────────────────

    async def handle(
        self,
        voice_command: str,
        image_bytes: Optional[bytes] = None,
    ) -> MultimodalResponse:
        """
        Route a voice command through the vision pipeline.
        Returns a MultimodalResponse; also calls tts_fn if configured.
        """
        t0 = time.time()

        # Capture frame if not provided
        if image_bytes is None:
            frame = self._capture_frame()
            if frame is not None:
                image_bytes = self._frame_to_bytes(frame)

        action = self._route(voice_command)
        try:
            resp = await self._dispatch(action, voice_command, image_bytes)
        except Exception as exc:
            log.error("VisionVoice dispatch error: %s", exc)
            resp = MultimodalResponse(text=f"Sorry, I encountered an error: {exc}")

        resp.processing_ms = (time.time() - t0) * 1000

        # Speak the response
        if self._tts_fn and resp.text:
            try:
                self._tts_fn(resp.text)
                resp.spoken = True
            except Exception as exc:
                log.error("TTS error: %s", exc)

        return resp

    def _route(self, command: str) -> str:
        cmd_lower = command.lower()
        for keyword, action in _COMMAND_ROUTES.items():
            if keyword in cmd_lower:
                return action
        return "generic"

    async def _dispatch(self, action: str, command: str,
                        image_bytes: Optional[bytes]) -> MultimodalResponse:
        handlers = {
            "analyze_scene": self._handle_analyze_scene,
            "read_text": self._handle_read_text,
            "identify_person": self._handle_identify_person,
            "summarize_document": self._handle_summarize_document,
            "analyze_screenshot": self._handle_analyze_screenshot,
            "recall_object": self._handle_recall_object,
            "memory_summary": self._handle_memory_summary,
            "generic": self._handle_generic,
        }
        handler = handlers.get(action, self._handle_generic)
        return await handler(command, image_bytes)

    # ── Handlers ──────────────────────────────────────────────────────────────

    async def _handle_analyze_scene(self, command: str,
                                     image_bytes: Optional[bytes]) -> MultimodalResponse:
        if image_bytes is None or self._scene is None:
            return MultimodalResponse(text="I need a camera image to analyze the scene.")
        result = await self._scene.analyze(image_bytes)
        summary = result.get("summary", "")
        objects = ", ".join(result.get("objects", [])[:8])
        text = f"{summary}" + (f" I can see: {objects}." if objects else "")
        return MultimodalResponse(text=text or "I analyzed the scene.", scene_summary=text)

    async def _handle_read_text(self, command: str,
                                 image_bytes: Optional[bytes]) -> MultimodalResponse:
        if image_bytes is None or self._ocr is None:
            return MultimodalResponse(text="I need an image to read text from.")
        result = await self._ocr.extract_text(image_bytes)
        text = result.get("text", "").strip()
        if not text:
            return MultimodalResponse(text="I couldn't find any readable text in the image.")
        spoken = f"The text reads: {text[:500]}"
        if len(text) > 500:
            spoken += " ... and more."
        return MultimodalResponse(text=spoken, ocr_text=text)

    async def _handle_identify_person(self, command: str,
                                       image_bytes: Optional[bytes]) -> MultimodalResponse:
        if image_bytes is None or self._face is None:
            return MultimodalResponse(text="I need a camera image to identify people.")
        self._face.grant_camera()
        detections = await self._face.process_frame(image_bytes)
        if not detections:
            return MultimodalResponse(text="I don't see anyone in frame.")
        names = []
        for d in detections:
            if d.is_known and d.recognized_name:
                names.append(f"{d.recognized_name} ({d.recognition_confidence:.0%})")
            else:
                names.append("an unknown person")
        return MultimodalResponse(text=f"I can see: {', '.join(names)}.")

    async def _handle_summarize_document(self, command: str,
                                          image_bytes: Optional[bytes]) -> MultimodalResponse:
        if image_bytes is None:
            return MultimodalResponse(text="Please show me the document.")
        ocr_text = ""
        if self._ocr:
            res = await self._ocr.extract_text(image_bytes)
            ocr_text = res.get("text", "")
        if not ocr_text.strip():
            if self._scene:
                res = await self._scene.analyze(image_bytes)
                summary = res.get("summary", "I couldn't read the document.")
            else:
                summary = "I couldn't extract text from the document."
        else:
            if self._ai_fn:
                prompt = (f"Summarise this document in 3-4 clear sentences:\n\n{ocr_text[:2000]}")
                try:
                    summary = self._ai_fn(prompt)
                except Exception:
                    summary = ocr_text[:400] + "..."
            else:
                summary = ocr_text[:400] + ("..." if len(ocr_text) > 400 else "")
        return MultimodalResponse(text=summary, ocr_text=ocr_text)

    async def _handle_analyze_screenshot(self, command: str,
                                          image_bytes: Optional[bytes]) -> MultimodalResponse:
        if image_bytes is None or self._screenshot is None:
            return MultimodalResponse(text="I need a screenshot to analyze.")
        result = await self._screenshot.analyze(image_bytes)
        text = result.get("summary", "") + " " + result.get("guidance", "")
        return MultimodalResponse(text=text.strip() or "I analyzed the screenshot.")

    async def _handle_recall_object(self, command: str,
                                     image_bytes: Optional[bytes]) -> MultimodalResponse:
        if self._obj_memory is None:
            return MultimodalResponse(text="Object memory is not active.")
        cmd_lower = command.lower()
        for keyword in ["seen", "dekha", "seen a", "seen the"]:
            if keyword in cmd_lower:
                idx = cmd_lower.index(keyword) + len(keyword)
                label = cmd_lower[idx:].strip().rstrip("?").strip()
                if label:
                    matches = await self._obj_memory.find_by_label(label)
                    if matches:
                        obj = matches[0]
                        text = (f"Yes, I've seen a {obj['label']} "
                                f"{obj['sighting_count']} time(s). "
                                f"Last seen {obj['seconds_since_seen']:.0f} seconds ago.")
                    else:
                        text = f"I don't have any memory of seeing a {label}."
                    return MultimodalResponse(text=text)
        return MultimodalResponse(text=await self._obj_memory.summarize())

    async def _handle_memory_summary(self, command: str,
                                      image_bytes: Optional[bytes]) -> MultimodalResponse:
        if self._obj_memory is None:
            return MultimodalResponse(text="Object memory is not active.")
        return MultimodalResponse(text=await self._obj_memory.summarize())

    async def _handle_generic(self, command: str,
                               image_bytes: Optional[bytes]) -> MultimodalResponse:
        if image_bytes and self._scene:
            result = await self._scene.analyze(image_bytes)
            context = result.get("summary", "")
            if self._ai_fn:
                prompt = f"Image context: {context}\nUser asked: {command}"
                try:
                    answer = self._ai_fn(prompt)
                    return MultimodalResponse(text=answer)
                except Exception:
                    pass
            return MultimodalResponse(text=context or "I analyzed the image.")
        if self._ai_fn:
            try:
                return MultimodalResponse(text=self._ai_fn(command))
            except Exception:
                pass
        return MultimodalResponse(text="I'm not sure how to handle that request.")

    # ── Utilities ─────────────────────────────────────────────────────────────

    def _capture_frame(self) -> Optional[np.ndarray]:
        if self._camera_fn:
            try:
                return self._camera_fn()
            except Exception as exc:
                log.error("Camera capture error: %s", exc)
        return None

    @staticmethod
    def _frame_to_bytes(frame: np.ndarray) -> Optional[bytes]:
        try:
            import cv2  # type: ignore
            _, buf = cv2.imencode(".jpg", frame)
            return buf.tobytes()
        except Exception:
            return None

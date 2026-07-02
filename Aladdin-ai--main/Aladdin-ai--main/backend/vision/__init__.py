"""Vision subsystem for Aladdin AI Backend — Phase 6 + Phase 7."""
from .manager import VisionManager

# Phase 7 modules (direct imports for convenience)
from .face_recognition import FaceRecognitionModule
from .person_tracking import PersonTrackingModule
from .scene_understanding import SceneUnderstandingModule
from .ocr import OCREngine
from .live_camera import LiveCameraAnalyzer
from .screenshot import ScreenshotAnalyzer
from .gesture_recognition import GestureRecognitionModule
from .object_memory import ObjectMemoryModule
from .vision_voice import VisionVoiceIntegration

__all__ = [
    "VisionManager",
    # Phase 7
    "FaceRecognitionModule",
    "PersonTrackingModule",
    "SceneUnderstandingModule",
    "OCREngine",
    "LiveCameraAnalyzer",
    "ScreenshotAnalyzer",
    "GestureRecognitionModule",
    "ObjectMemoryModule",
    "VisionVoiceIntegration",
]

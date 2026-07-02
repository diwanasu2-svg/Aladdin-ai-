"""Voice subsystem for Aladdin AI Backend."""
from .stt import SpeechToText
from .vad import VoiceActivityDetector

__all__ = ["SpeechToText", "VoiceActivityDetector"]

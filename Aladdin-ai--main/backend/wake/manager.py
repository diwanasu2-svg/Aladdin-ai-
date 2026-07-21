"""Wake word manager — coordinates detection engines."""
from __future__ import annotations
import logging
from typing import Callable, Dict, List, Optional

log = logging.getLogger(__name__)


class WakeManager:
    def __init__(self) -> None:
        self._oww = None
        self._porcupine = None
        self._browser_cfg = None
        self._active = False
        self._active_engine: Optional[str] = None

    def setup_openwakeword(self, model_paths=None, threshold: float = 0.5):
        from .openwakeword import OpenWakeWordDetector
        self._oww = OpenWakeWordDetector(model_paths=model_paths, threshold=threshold)
        if self._oww.available:
            log.info("OpenWakeWord ready")
        return self._oww.available

    def setup_porcupine(self, access_key: str, keywords=None, keyword_paths=None, sensitivities=None):
        from .porcupine import PorcupineDetector
        self._porcupine = PorcupineDetector(
            access_key=access_key, keywords=keywords,
            keyword_paths=keyword_paths, sensitivities=sensitivities
        )
        if self._porcupine.available:
            log.info("Porcupine ready")
        return self._porcupine.available

    def setup_browser(self, wake_words=None, sensitivity: float = 0.7):
        from .browser_wake import BrowserWakeConfig
        self._browser_cfg = BrowserWakeConfig(wake_words=wake_words, sensitivity=sensitivity)
        log.info("Browser wake word config ready")

    def start(self, engine: Optional[str] = None) -> str:
        if engine == "openwakeword" and self._oww and self._oww.available:
            self._active_engine = "openwakeword"
        elif engine == "porcupine" and self._porcupine and self._porcupine.available:
            self._active_engine = "porcupine"
        elif engine == "browser" and self._browser_cfg:
            self._active_engine = "browser"
        else:
            # Auto-pick best available
            if self._oww and self._oww.available:
                self._active_engine = "openwakeword"
            elif self._porcupine and self._porcupine.available:
                self._active_engine = "porcupine"
            else:
                self._active_engine = "browser"
        self._active = True
        log.info("Wake word detection started (engine=%s)", self._active_engine)
        return self._active_engine

    def stop(self):
        self._active = False
        if self._porcupine:
            try:
                self._porcupine.stop()
            except Exception:
                pass
        log.info("Wake word detection stopped")

    @property
    def status(self) -> Dict:
        return {
            "active": self._active,
            "engine": self._active_engine,
            "openwakeword_available": bool(self._oww and self._oww.available),
            "porcupine_available": bool(self._porcupine and self._porcupine.available),
            "browser_available": self._browser_cfg is not None,
        }

    def get_browser_config(self) -> Optional[Dict]:
        if self._browser_cfg:
            return self._browser_cfg.get_config()
        return {"engine": "browser", "wake_words": ["aladdin"], "sensitivity": 0.7}

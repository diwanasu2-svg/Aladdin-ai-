"""Image parser — metadata + OCR text extraction."""
from __future__ import annotations
import asyncio, io, logging
from typing import Any, Dict
log = logging.getLogger(__name__)

try:
    from PIL import Image as PILImage
    _PIL = True
except ImportError:
    _PIL = False

try:
    import pytesseract
    _TESS = True
except ImportError:
    _TESS = False


class ImageParser:
    @property
    def available(self): return _PIL

    async def parse(self, data: bytes) -> Dict[str, Any]:
        def _run():
            if not _PIL:
                return {"error": "Pillow not installed"}
            img = PILImage.open(io.BytesIO(data))
            meta = {"format": img.format, "mode": img.mode,
                    "size": {"width": img.width, "height": img.height},
                    "size_bytes": len(data)}
            text = ""
            if _TESS:
                try:
                    text = pytesseract.image_to_string(img).strip()
                except Exception as e:
                    log.warning("OCR error: %s", e)
            return {**meta, "ocr_text": text, "has_text": bool(text)}
        return await asyncio.get_running_loop().run_in_executor(None, _run)

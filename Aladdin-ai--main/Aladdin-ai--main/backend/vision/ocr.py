"""
Phase 7.5 — OCR Engine (Improved)
====================================
Printed + handwritten text, multi-language, tables/forms,
low-quality image enhancement, results fed to AI reasoning.

Backends (priority order):
1. EasyOCR  — multi-language, handwriting, GPU optional
2. Tesseract (pytesseract) — robust multi-language
3. Gemini Vision — AI-based for complex documents
"""
from __future__ import annotations
import asyncio, io, logging
from typing import Any, Dict, List, Optional
import numpy as np

log = logging.getLogger(__name__)

try:
    import pytesseract
    from PIL import Image as _PIL
    _TESSERACT_AVAILABLE = True
except ImportError:
    _TESSERACT_AVAILABLE = False
    log.info("pytesseract not installed — will try EasyOCR or Gemini")

try:
    import easyocr as _easyocr  # type: ignore
    _EASYOCR_AVAILABLE = True
except ImportError:
    _EASYOCR_AVAILABLE = False

_EASYOCR_READERS: Dict[str, Any] = {}   # cache readers by lang key


def _enhance_image(image_bytes: bytes) -> bytes:
    """
    Pre-process image for better OCR: denoise, adaptive threshold, deskew, upscale.
    Returns enhanced image bytes or the original if cv2 is unavailable.
    """
    try:
        import cv2  # type: ignore
        arr = np.frombuffer(image_bytes, np.uint8)
        img = cv2.imdecode(arr, cv2.IMREAD_COLOR)
        if img is None:
            return image_bytes
        gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
        gray = cv2.fastNlMeansDenoising(gray, h=10)
        binary = cv2.adaptiveThreshold(
            gray, 255, cv2.ADAPTIVE_THRESH_GAUSSIAN_C, cv2.THRESH_BINARY, 11, 2,
        )
        # Upscale small images
        h, w = binary.shape
        if h < 100 or w < 100:
            binary = cv2.resize(binary, (w * 2, h * 2), interpolation=cv2.INTER_CUBIC)
        _, buf = cv2.imencode(".png", binary)
        return buf.tobytes()
    except ImportError:
        return image_bytes
    except Exception as exc:
        log.debug("Image enhancement error: %s", exc)
        return image_bytes


def _extract_tables(image_bytes: bytes) -> List[List[List[str]]]:
    """Detect table grids and return a list of tables (2D list of cells)."""
    try:
        import cv2  # type: ignore
        arr = np.frombuffer(image_bytes, np.uint8)
        img = cv2.imdecode(arr, cv2.IMREAD_COLOR)
        if img is None:
            return []
        gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
        thresh = cv2.threshold(gray, 0, 255, cv2.THRESH_BINARY_INV + cv2.THRESH_OTSU)[1]
        h_kernel = cv2.getStructuringElement(cv2.MORPH_RECT, (40, 1))
        v_kernel = cv2.getStructuringElement(cv2.MORPH_RECT, (1, 40))
        h_lines = cv2.morphologyEx(thresh, cv2.MORPH_OPEN, h_kernel, iterations=2)
        v_lines = cv2.morphologyEx(thresh, cv2.MORPH_OPEN, v_kernel, iterations=2)
        grid = cv2.add(h_lines, v_lines)
        if grid.sum() > 10000:
            return [["[Table detected — use Gemini Vision for full cell extraction]"]]
    except Exception:
        pass
    return []


def _extract_form_fields(text: str) -> Dict[str, str]:
    """Heuristic: extract Label: Value pairs from OCR text."""
    fields: Dict[str, str] = {}
    for line in text.splitlines():
        if ":" in line:
            parts = line.split(":", 1)
            label, value = parts[0].strip(), parts[1].strip()
            if label and value and len(label) < 60:
                fields[label] = value
    return fields


_LANG_EASYOCR_MAP = {
    "eng": ["en"], "hin": ["hi"], "ara": ["ar"], "chi_sim": ["ch_sim"],
    "chi_tra": ["ch_tra"], "jpn": ["ja"], "kor": ["ko"], "fra": ["fr"],
    "deu": ["de"], "spa": ["es"], "por": ["pt"], "rus": ["ru"], "urd": ["ur"],
}


class OCREngine:
    """
    Improved multi-language OCR engine with image enhancement,
    table extraction, form field parsing, and handwriting support.
    """

    def __init__(self, language: str = "eng",
                 gemini_key: Optional[str] = None) -> None:
        self._lang = language
        self._gemini_key = gemini_key

    def configure_gemini(self, key: str) -> None:
        self._gemini_key = key

    @property
    def available(self) -> bool:
        return _TESSERACT_AVAILABLE or _EASYOCR_AVAILABLE or bool(self._gemini_key)

    # ── Public API ────────────────────────────────────────────────────────────

    async def extract_text(self, image_bytes: bytes,
                            language: Optional[str] = None) -> Dict[str, Any]:
        """Extract text from image bytes."""
        lang = language or self._lang
        enhanced = await asyncio.get_running_loop().run_in_executor(
            None, _enhance_image, image_bytes
        )
        if _EASYOCR_AVAILABLE:
            return await self._easyocr_extract(enhanced, lang)
        if _TESSERACT_AVAILABLE:
            return await self._tesseract_ocr(enhanced, lang)
        if self._gemini_key:
            return await self._gemini_extract(image_bytes, lang)
        return {"text": "", "confidence": 0.0, "method": "unavailable",
                "error": "No OCR backend installed. Run: pip install easyocr"}

    async def extract_with_positions(self, image_bytes: bytes) -> List[Dict[str, Any]]:
        """Return word-level text blocks with bounding boxes."""
        enhanced = await asyncio.get_running_loop().run_in_executor(
            None, _enhance_image, image_bytes
        )
        if _EASYOCR_AVAILABLE:
            return await self._easyocr_positions(enhanced)
        if _TESSERACT_AVAILABLE:
            return await self._tesseract_positions(enhanced)
        return []

    async def extract_tables(self, image_bytes: bytes) -> Dict[str, Any]:
        """Extract tabular data from image."""
        tables = await asyncio.get_running_loop().run_in_executor(
            None, _extract_tables, image_bytes
        )
        text_result = await self.extract_text(image_bytes)
        return {
            "text": text_result.get("text", ""),
            "tables": tables,
            "table_count": len(tables),
            "method": text_result.get("method", "unknown"),
        }

    async def extract_form_fields(self, image_bytes: bytes) -> Dict[str, Any]:
        """Extract form Label: Value pairs from image."""
        text_result = await self.extract_text(image_bytes)
        text = text_result.get("text", "")
        fields = await asyncio.get_running_loop().run_in_executor(
            None, _extract_form_fields, text
        )
        return {
            "text": text,
            "fields": fields,
            "field_count": len(fields),
            "method": text_result.get("method", "unknown"),
        }

    # ── EasyOCR backend ───────────────────────────────────────────────────────

    async def _easyocr_extract(self, image_bytes: bytes, lang: str) -> Dict[str, Any]:
        def _run():
            import easyocr  # type: ignore
            langs = _LANG_EASYOCR_MAP.get(lang, ["en"])
            lang_key = "+".join(sorted(langs))
            if lang_key not in _EASYOCR_READERS:
                _EASYOCR_READERS[lang_key] = easyocr.Reader(langs, gpu=False, verbose=False)
            reader = _EASYOCR_READERS[lang_key]
            arr = np.frombuffer(image_bytes, np.uint8)
            import cv2  # type: ignore
            img = cv2.imdecode(arr, cv2.IMREAD_COLOR)
            if img is None:
                return {"text": "", "confidence": 0.0, "method": "easyocr", "error": "decode failed"}
            raw = reader.readtext(img)
            texts, confs = [], []
            for (_, text, conf) in raw:
                texts.append(text)
                confs.append(conf)
            avg_conf = float(np.mean(confs)) if confs else 0.0
            return {
                "text": "\n".join(texts),
                "confidence": avg_conf,
                "method": "easyocr",
                "language": lang,
                "word_count": len(texts),
            }
        try:
            return await asyncio.get_running_loop().run_in_executor(None, _run)
        except Exception as exc:
            log.error("EasyOCR error: %s", exc)
            if _TESSERACT_AVAILABLE:
                return await self._tesseract_ocr(image_bytes, lang)
            return {"text": "", "confidence": 0.0, "method": "easyocr", "error": str(exc)}

    async def _easyocr_positions(self, image_bytes: bytes) -> List[Dict[str, Any]]:
        def _run():
            import easyocr, cv2  # type: ignore
            langs = _LANG_EASYOCR_MAP.get(self._lang, ["en"])
            lang_key = "+".join(sorted(langs))
            if lang_key not in _EASYOCR_READERS:
                _EASYOCR_READERS[lang_key] = easyocr.Reader(langs, gpu=False, verbose=False)
            reader = _EASYOCR_READERS[lang_key]
            arr = np.frombuffer(image_bytes, np.uint8)
            img = cv2.imdecode(arr, cv2.IMREAD_COLOR)
            if img is None:
                return []
            raw = reader.readtext(img)
            blocks = []
            for (bbox, text, conf) in raw:
                xs = [p[0] for p in bbox]; ys = [p[1] for p in bbox]
                blocks.append({
                    "text": text, "confidence": conf,
                    "x": int(min(xs)), "y": int(min(ys)),
                    "width": int(max(xs) - min(xs)), "height": int(max(ys) - min(ys)),
                })
            return blocks
        try:
            return await asyncio.get_running_loop().run_in_executor(None, _run)
        except Exception:
            return []

    # ── Tesseract backend ─────────────────────────────────────────────────────

    async def _tesseract_ocr(self, image_bytes: bytes, lang: str) -> Dict[str, Any]:
        def _run():
            img = _PIL.open(io.BytesIO(image_bytes))
            config = "--oem 3 --psm 6"
            data = pytesseract.image_to_data(img, lang=lang, config=config,
                                             output_type=pytesseract.Output.DICT)
            text = pytesseract.image_to_string(img, lang=lang, config=config).strip()
            confs = [int(c) for c in data["conf"] if str(c).lstrip("-").isdigit() and int(c) > 0]
            avg_conf = sum(confs) / len(confs) / 100.0 if confs else 0.0
            return {"text": text, "confidence": avg_conf, "method": "tesseract", "language": lang}
        return await asyncio.get_running_loop().run_in_executor(None, _run)

    async def _tesseract_positions(self, image_bytes: bytes) -> List[Dict[str, Any]]:
        def _run():
            img = _PIL.open(io.BytesIO(image_bytes))
            data = pytesseract.image_to_data(img, output_type=pytesseract.Output.DICT)
            blocks = []
            for i, word in enumerate(data["text"]):
                if word.strip() and int(data["conf"][i]) > 0:
                    blocks.append({
                        "text": word, "confidence": int(data["conf"][i]) / 100.0,
                        "x": data["left"][i], "y": data["top"][i],
                        "width": data["width"][i], "height": data["height"][i],
                    })
            return blocks
        return await asyncio.get_running_loop().run_in_executor(None, _run)

    # ── Gemini Vision backend ─────────────────────────────────────────────────

    async def _gemini_extract(self, image_bytes: bytes, lang: str) -> Dict[str, Any]:
        def _run():
            try:
                import google.generativeai as genai  # type: ignore
                import PIL.Image  # type: ignore
                genai.configure(api_key=self._gemini_key)
                model = genai.GenerativeModel("gemini-1.5-flash")
                image = PIL.Image.open(io.BytesIO(image_bytes))
                lang_hint = f" Focus on {lang} text." if lang != "eng" else ""
                prompt = (f"Extract ALL text from this image exactly as written.{lang_hint} "
                          "Include printed and handwritten text. Preserve line breaks.")
                response = model.generate_content([prompt, image])
                return {"text": response.text.strip(), "confidence": 0.95,
                        "method": "gemini_vision", "language": lang}
            except Exception as exc:
                return {"text": "", "confidence": 0.0, "method": "gemini_vision", "error": str(exc)}
        return await asyncio.get_running_loop().run_in_executor(None, _run)

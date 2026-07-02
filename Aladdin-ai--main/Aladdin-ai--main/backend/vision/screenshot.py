"""
Phase 7.7 — Screenshot Reasoning (Improved)
=============================================
Analyse screenshot content, identify UI elements, explain errors and messages,
understand forms/chats/web pages, guide user based on screenshot.
"""
from __future__ import annotations
import io, logging
from typing import Any, Dict, List, Optional

log = logging.getLogger(__name__)

SCREENSHOT_PROMPT = """Analyze this screenshot carefully and provide a JSON response with:
{
  "screenshot_type": "<web_page|chat|form|error_dialog|menu|dashboard|document|terminal|unknown>",
  "application": "<detected app or website name>",
  "ui_elements": [{"type": "button|input|label|link|menu", "text": "...", "is_interactive": true}],
  "errors": ["list of error messages or warnings visible"],
  "text_content": "<all visible text in reading order>",
  "summary": "<1-2 sentence description of what is on screen>",
  "guidance": "<practical advice for the user based on what you see>"
}
If you see an error, explain what it means in plain language. Be specific and helpful."""

_ERROR_PATTERNS = [
    "error", "exception", "failed", "cannot", "not found", "undefined",
    "null", "crash", "warning", "denied", "forbidden", "timeout",
    "connection refused", "404", "500", "503", "traceback",
]

_TYPE_KEYWORDS = {
    "error_dialog": ["error", "exception", "failed", "warning", "traceback"],
    "chat": ["send", "message", "reply", "chat", "type a message"],
    "form": ["submit", "form", "required", "enter your", "sign up", "login"],
    "web_page": ["http", "www", "browser", "search", "home", "navigation"],
    "terminal": ["$", "#", ">>", "bash", "zsh", "cmd", "powershell", "shell"],
    "dashboard": ["dashboard", "analytics", "metrics", "chart", "report", "overview"],
    "document": ["page", "chapter", "paragraph", "document", "pdf"],
}


class ScreenshotAnalyzer:
    """
    Phase 7 screenshot reasoning:
    - Gemini Vision for deep analysis (primary)
    - OCR + keyword classifier fallback
    """

    def __init__(self, vision_manager=None, gemini_key: Optional[str] = None) -> None:
        self._vm = vision_manager
        self._gemini_key = gemini_key

    def configure_gemini(self, key: str) -> None:
        self._gemini_key = key

    async def analyze(self, image_bytes: bytes, context: Optional[str] = None,
                      mime_type: str = "image/png") -> Dict[str, Any]:
        """Full screenshot analysis with UI element detection and guidance."""
        prompt = SCREENSHOT_PROMPT
        if context:
            prompt += f"\n\nAdditional user context: {context}"

        # Try Gemini-structured analysis first
        if self._gemini_key:
            result = await self._gemini_analyze(image_bytes, prompt)
            if result:
                result["type"] = "screenshot_analysis"
                return result

        # Fallback via vision manager (GPT-4V or Gemini generic)
        if self._vm is not None:
            try:
                result = await self._vm.analyze(image_bytes, prompt=prompt, mime_type=mime_type)
                result["type"] = "screenshot_analysis"
                # Add guidance if missing
                if "guidance" not in result and "text" in result:
                    result["guidance"] = self._keyword_guidance(result.get("text", ""))
                return result
            except Exception as exc:
                log.error("Vision manager screenshot error: %s", exc)

        # Pure OCR fallback
        return await self._ocr_fallback(image_bytes)

    async def explain_error(self, image_bytes: bytes) -> Dict[str, Any]:
        """Focus specifically on errors in the screenshot."""
        result = await self.analyze(image_bytes)
        errors = result.get("errors", [])
        if not errors and "text" in result:
            errors = self._find_errors(result.get("text", ""))
        return {
            **result,
            "errors": errors,
            "error_explanation": (
                f"Found {len(errors)} error(s): {'; '.join(errors[:3])}"
                if errors else "No errors detected in this screenshot."
            ),
        }

    async def guide_user(self, image_bytes: bytes, goal: str = "") -> Dict[str, Any]:
        """Analyse screenshot and provide step-by-step guidance toward a goal."""
        result = await self.analyze(image_bytes)
        if goal:
            result["goal_guidance"] = self._goal_guidance(result, goal)
        return result

    async def identify_ui_elements(self, image_bytes: bytes) -> Dict[str, Any]:
        """Return only the UI element list from the analysis."""
        result = await self.analyze(image_bytes)
        return {
            "ui_elements": result.get("ui_elements", []),
            "screenshot_type": result.get("screenshot_type", "unknown"),
            "application": result.get("application", "unknown"),
        }

    # ── Gemini structured analysis ─────────────────────────────────────────

    async def _gemini_analyze(self, image_bytes: bytes, prompt: str) -> Optional[Dict[str, Any]]:
        import asyncio

        def _run():
            try:
                import json as _json
                import google.generativeai as genai  # type: ignore
                import PIL.Image  # type: ignore
                genai.configure(api_key=self._gemini_key)
                model = genai.GenerativeModel("gemini-1.5-flash")
                image = PIL.Image.open(io.BytesIO(image_bytes))
                response = model.generate_content([prompt, image])
                raw = response.text.strip().lstrip("```json").lstrip("```").rstrip("```").strip()
                return _json.loads(raw)
            except ImportError:
                log.warning("google-generativeai not installed")
            except Exception as exc:
                log.error("Gemini screenshot error: %s", exc)
            return None

        return await asyncio.get_running_loop().run_in_executor(None, _run)

    # ── OCR fallback ───────────────────────────────────────────────────────

    async def _ocr_fallback(self, image_bytes: bytes) -> Dict[str, Any]:
        from .ocr import OCREngine
        ocr = OCREngine()
        ocr_result = await ocr.extract_text(image_bytes)
        text = ocr_result.get("text", "")
        errors = self._find_errors(text)
        ss_type = self._classify_type(text)
        return {
            "screenshot_type": ss_type,
            "application": "unknown",
            "ui_elements": [],
            "errors": errors,
            "text_content": text,
            "summary": f"Screenshot contains {len(text.split())} words of text.",
            "guidance": self._keyword_guidance(text),
            "type": "screenshot_analysis",
            "method": "ocr_fallback",
        }

    # ── Helpers ────────────────────────────────────────────────────────────

    @staticmethod
    def _find_errors(text: str) -> List[str]:
        errors = []
        for line in text.splitlines():
            if any(p in line.lower() for p in _ERROR_PATTERNS):
                cleaned = line.strip()
                if cleaned and cleaned not in errors:
                    errors.append(cleaned[:200])
        return errors[:10]

    @staticmethod
    def _classify_type(text: str) -> str:
        tl = text.lower()
        for ss_type, keywords in _TYPE_KEYWORDS.items():
            if any(k in tl for k in keywords):
                return ss_type
        return "unknown"

    @staticmethod
    def _keyword_guidance(text: str) -> str:
        tl = text.lower()
        if any(p in tl for p in _ERROR_PATTERNS):
            return "An error is visible. Check the error message and logs for more detail."
        if any(k in tl for k in ["submit", "form", "required"]):
            return "Fill in all required fields and click Submit."
        if any(k in tl for k in ["send", "message", "chat"]):
            return "Type your message in the input field and press Send."
        if any(k in tl for k in ["$", "bash", "shell", "cmd"]):
            return "Check the terminal output for error messages or warnings."
        return "No specific issues detected. Let me know if you need help with something specific."

    @staticmethod
    def _goal_guidance(result: Dict[str, Any], goal: str) -> str:
        goal_lower = goal.lower()
        ui = result.get("ui_elements", [])
        interactive = [e.get("text", "") for e in ui if e.get("is_interactive")]
        if "click" in goal_lower or "press" in goal_lower:
            if interactive:
                return f"Available clickable elements: {', '.join(interactive[:5])}"
        if "fill" in goal_lower or "enter" in goal_lower:
            return "Find the relevant input field and type your information."
        if "error" in goal_lower:
            errors = result.get("errors", [])
            if errors:
                return f"Errors: {'; '.join(errors[:3])}"
            return "No errors found on this screen."
        return result.get("guidance", "")

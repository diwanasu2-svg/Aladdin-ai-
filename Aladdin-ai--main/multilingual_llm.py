"""Multilingual LLM Integration — Feature 2.

Wraps Ollama LLM calls with:
  - Language-appropriate system prompts (Hindi, Gujarati, English)
  - Code-switching support (Hinglish, Gujlish)
  - Language tagging of responses
  - Language-consistent response generation
  - Cultural context for each language

Supported models: llama3.1, qwen2.5, gemma3 (all support Indian languages).
"""

from __future__ import annotations

import json
import logging
from pathlib import Path
from typing import Generator, Iterator, Optional

from language_detector import LANG_ENGLISH, LANG_GUJARATI, LANG_HINDI, DetectionResult

log = logging.getLogger(__name__)


# ── System prompts per language — Feature 2, 4 ───────────────────────────────

SYSTEM_PROMPTS = {
    LANG_HINDI: """आप अलादीन हैं, एक बुद्धिमान और मददगार AI वॉयस असिस्टेंट।
नियम:
1. हमेशा उसी भाषा में जवाब दें जिसमें उपयोगकर्ता बात करता है।
2. जवाब छोटे और स्पष्ट रखें — 2-3 वाक्यों से अधिक नहीं, जब तक विस्तार न मांगा जाए।
3. बातचीत का लहजा गर्मजोशी भरा और दोस्ताना हो।
4. हिंदी-अंग्रेजी मिश्रण (Hinglish) को स्वाभाविक रूप से समझें और जवाब दें।
5. यदि आप कुछ नहीं जानते, तो ईमानदारी से कहें।
6. TTS के लिए उपयुक्त वाक्य बनाएं — बोले जाने वाले, पठनीय नहीं।""",
    LANG_GUJARATI: """તમે અલ્લાઉદ્દીન છો, એક બુદ્ધિશાળી અને મદદગાર AI વૉઇસ આસિસ્ટન્ટ.
નિયમો:
1. હંમેશા એ જ ભાષામાં જવાબ આપો જેમાં વપરાશકર્તા વાત કરે છે.
2. જવાબ ટૂંકો અને સ્પષ્ટ રાખો — 2-3 વાક્યોથી વધુ નહીં, જ્યાં સુધી વિગત ન માગવામાં આવે.
3. વાર્તાલાપ ગરમ અને મૈત્રીપૂર્ણ રાખો.
4. ગુજરાતી-અંગ્રેજી મિશ્રણ (Gujlish) ને સ્વાભાવિક રીતે સમજો અને જવાબ આપો.
5. જો તમે કંઈ જાણતા ન હોવ, તો પ્રામાણિકતાથી કહો.
6. TTS માટે યોગ્ય વાક્ય બનાવો — બોલવા માટે, વાંચવા માટે નહીં.""",
    LANG_ENGLISH: """You are Aladdin, an intelligent and helpful AI voice assistant.
Rules:
1. Always reply in the same language the user speaks.
2. Keep replies short and clear — 2-3 sentences max unless detail is requested.
3. Be conversational, warm, and friendly.
4. Understand and naturally handle Hinglish or Gujlish (code-mixed speech).
5. If you don't know something, say so honestly.
6. Craft sentences suitable for TTS — spoken, not written style.""",
}

# Mixed language system prompt addition
MIXED_LANGUAGE_ADDENDUM = """
Note: The user may mix languages (Hinglish or Gujlish). Match their mixing style naturally.
If they mix Hindi+English, respond in the same mix. If they mix Gujarati+English, match that.
"""


def get_system_prompt(
    language: str,
    user_name: str = "User",
    assistant_name: str = "Aladdin",
    is_mixed: bool = False,
) -> str:
    """Return the system prompt for *language*.

    Args:
        language:       Detected language code ("hi", "gu", "en").
        user_name:      User's name for personalisation.
        assistant_name: Assistant's name.
        is_mixed:       True if code-mixed speech detected.

    Returns:
        Full system prompt string.
    """
    base = SYSTEM_PROMPTS.get(language, SYSTEM_PROMPTS[LANG_ENGLISH])
    base = base.replace("{name}", assistant_name).replace("{user}", user_name)
    if is_mixed:
        base += MIXED_LANGUAGE_ADDENDUM
    return base


# ── Language-specific prompt wrappers ─────────────────────────────────────────


def build_language_instruction(language: str, is_mixed: bool = False) -> str:
    """Return a short instruction to inject into the prompt for language control."""
    if language == LANG_HINDI:
        if is_mixed:
            return "हिंदी और अंग्रेजी मिलाकर जवाब दें जैसे उपयोगकर्ता ने बोला।"
        return "कृपया हिंदी में जवाब दें।"
    elif language == LANG_GUJARATI:
        if is_mixed:
            return (
                "ગુજરાતી અને અંગ્રેજી મિક્સ કરીને જવાબ આપો જેવી રીતે વપરાશકર્તા બોલ્યા."
            )
        return "કૃપા કરીને ગુજરાતીમાં જવાબ આપો."
    else:
        return "Please reply in English."


# ── Ollama multilingual client ────────────────────────────────────────────────


class MultilingualOllamaClient:
    """Ollama LLM client with multilingual support.

    Wraps the standard Ollama HTTP API with:
      - Language-appropriate system prompt injection
      - Streaming support
      - Language consistency enforcement
    """

    def __init__(
        self,
        host: str = "http://localhost:11434",
        model: str = "llama3.1",
        temperature: float = 0.7,
        max_tokens: int = 512,
        timeout: int = 300,
    ):
        self.host = host.rstrip("/")
        self.model = model
        self.temperature = temperature
        self.max_tokens = max_tokens
        self.timeout = timeout
        log.info("MultilingualOllamaClient ready (model=%s, host=%s)", model, host)

    def chat(
        self,
        user_message: str,
        language: str,
        is_mixed: bool = False,
        conversation_history: Optional[list] = None,
        system_prompt_override: Optional[str] = None,
        stream: bool = False,
    ) -> str | Iterator[str]:
        """Generate a multilingual response.

        Args:
            user_message:          The user's transcribed speech.
            language:              Detected language code.
            is_mixed:              True if code-mixed speech.
            conversation_history:  Prior messages [{"role": "user"|"assistant", "content": ...}]
            system_prompt_override: Override the default system prompt.
            stream:                If True, returns an iterator of text chunks.

        Returns:
            Response text (or iterator if stream=True).
        """
        import urllib.request

        system = system_prompt_override or get_system_prompt(
            language, is_mixed=is_mixed
        )
        lang_instruction = build_language_instruction(language, is_mixed)

        messages = [{"role": "system", "content": system}]

        # Add conversation history with language tags
        if conversation_history:
            for msg in conversation_history[-10:]:  # last 10 turns
                messages.append(
                    {
                        "role": msg["role"],
                        "content": msg["content"],
                    }
                )

        # Inject language instruction into user message
        full_user_message = (
            f"{lang_instruction}\n\n{user_message}"
            if lang_instruction
            else user_message
        )
        messages.append({"role": "user", "content": full_user_message})

        payload = json.dumps(
            {
                "model": self.model,
                "messages": messages,
                "stream": stream,
                "options": {
                    "temperature": self.temperature,
                    "num_predict": self.max_tokens,
                },
            }
        ).encode("utf-8")

        req = urllib.request.Request(
            f"{self.host}/api/chat",
            data=payload,
            headers={"Content-Type": "application/json"},
            method="POST",
        )

        try:
            if stream:
                return self._stream_response(req)
            else:
                return self._batch_response(req)
        except Exception as e:
            log.error("Ollama request failed: %s", e)
            return self._fallback_response(language)

    def _batch_response(self, req) -> str:
        import urllib.request

        with urllib.request.urlopen(req, timeout=self.timeout) as resp:
            data = json.loads(resp.read())
            return data.get("message", {}).get("content", "").strip()

    def _stream_response(self, req) -> Iterator[str]:
        import urllib.request

        with urllib.request.urlopen(req, timeout=self.timeout) as resp:
            for line in resp:
                line = line.strip()
                if not line:
                    continue
                try:
                    chunk = json.loads(line)
                    content = chunk.get("message", {}).get("content", "")
                    if content:
                        yield content
                    if chunk.get("done", False):
                        break
                except json.JSONDecodeError:
                    continue

    @staticmethod
    def _fallback_response(language: str) -> str:
        """Return a polite error message in the appropriate language."""
        if language == LANG_HINDI:
            return "माफ़ करें, मुझे अभी जवाब देने में परेशानी हो रही है। कृपया थोड़ी देर बाद कोशिश करें।"
        elif language == LANG_GUJARATI:
            return "માફ કરો, મને અત્યારે જવાબ આપવામાં મુશ્કેલી પડી રही છે. કૃपया થોડી વાર पछी試 करो."
        else:
            return "Sorry, I'm having trouble responding right now. Please try again in a moment."

    def health_check(self) -> bool:
        """Check if Ollama is running and the model is available."""
        import urllib.request

        try:
            with urllib.request.urlopen(f"{self.host}/api/tags", timeout=5) as resp:
                data = json.loads(resp.read())
                models = [m["name"].split(":")[0] for m in data.get("models", [])]
                available = self.model.split(":")[0] in models
                if not available:
                    log.warning(
                        "Model '%s' not found in Ollama. Available: %s",
                        self.model,
                        models,
                    )
                return available
        except Exception as e:
            log.error("Ollama health check failed: %s", e)
            return False


# ── Convenience singleton ─────────────────────────────────────────────────────

_client: Optional[MultilingualOllamaClient] = None


def get_llm_client(
    host: str = "http://localhost:11434",
    model: str = "llama3.1",
    **kwargs,
) -> MultilingualOllamaClient:
    global _client
    if _client is None:
        _client = MultilingualOllamaClient(host=host, model=model, **kwargs)
    return _client

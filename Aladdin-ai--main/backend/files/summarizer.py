"""File summarizer — LLM-powered file summary generation."""
from __future__ import annotations
import logging
from typing import Any, Dict, Optional
log = logging.getLogger(__name__)


class FileSummarizer:
    def __init__(self, llm_client=None) -> None:
        self._llm = llm_client

    def set_llm(self, llm_client) -> None:
        self._llm = llm_client

    async def summarize(self, parsed_result: Dict[str, Any], filename: str = "",
                        max_text_chars: int = 6000, custom_prompt: Optional[str] = None) -> Dict[str, Any]:
        text = parsed_result.get("text", "")
        if not text:
            # Try sheets (Excel)
            sheets = parsed_result.get("sheets", [])
            if sheets:
                rows_preview = []
                for sheet in sheets[:2]:
                    rows_preview.append(f"Sheet '{sheet['name']}':")
                    for row in sheet.get("rows", [])[:10]:
                        rows_preview.append("  | ".join(str(c) for c in row))
                text = "\n".join(rows_preview)

        if not text:
            return {**parsed_result, "summary": "No text content to summarize.",
                    "filename": filename}

        if self._llm is None:
            # Extractive fallback: first + last paragraphs
            lines = [l.strip() for l in text.splitlines() if l.strip()]
            summary = " ".join(lines[:5])
            if len(lines) > 10:
                summary += " ... " + " ".join(lines[-3:])
            return {**parsed_result, "summary": summary[:1000], "method": "extractive",
                    "filename": filename}

        prompt = custom_prompt or f"Summarize this {filename} document concisely in 3-5 sentences, highlighting key points:\n\n{text[:max_text_chars]}"
        messages = [{"role": "system", "content": "You are a document summarization assistant."},
                    {"role": "user", "content": prompt}]
        try:
            result = await self._llm.chat(messages)
            return {**parsed_result, "summary": result.content, "method": "llm",
                    "filename": filename}
        except Exception as exc:
            log.error("LLM summarization failed: %s", exc)
            return {**parsed_result, "summary": text[:500] + "...", "method": "truncation",
                    "filename": filename, "error": str(exc)}

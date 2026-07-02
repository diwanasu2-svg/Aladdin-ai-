"""File Q&A — answer questions about file content using LLM."""
from __future__ import annotations
import logging
from typing import Any, Dict, List, Optional
log = logging.getLogger(__name__)


class FileQA:
    def __init__(self, llm_client=None) -> None:
        self._llm = llm_client

    def set_llm(self, llm_client) -> None:
        self._llm = llm_client

    async def ask(self, question: str, parsed_result: Dict[str, Any],
                  filename: str = "", max_context_chars: int = 8000) -> Dict[str, Any]:
        text = parsed_result.get("text", "")
        # Excel fallback
        if not text:
            sheets = parsed_result.get("sheets", [])
            if sheets:
                rows = []
                for sheet in sheets[:2]:
                    rows.append(f"Sheet: {sheet['name']}")
                    for row in sheet.get("rows", [])[:50]:
                        rows.append(" | ".join(str(c) for c in row))
                text = "\n".join(rows)

        if not text:
            return {"question": question, "answer": "No content available in this file.",
                    "filename": filename}

        if self._llm is None:
            # Keyword fallback
            lines = [l for l in text.splitlines() if question.lower()[:10] in l.lower()]
            answer = "\n".join(lines[:5]) if lines else "Cannot answer without LLM — no relevant text found."
            return {"question": question, "answer": answer, "method": "keyword", "filename": filename}

        context = text[:max_context_chars]
        prompt = f"""Based on this document content, answer the question accurately and concisely.

Document: {filename}
Content:
{context}

Question: {question}

Answer:"""
        messages = [{"role": "system", "content": "You are a document Q&A assistant. Answer questions based only on the provided document content."},
                    {"role": "user", "content": prompt}]
        try:
            result = await self._llm.chat(messages)
            return {"question": question, "answer": result.content,
                    "method": "llm", "filename": filename}
        except Exception as exc:
            log.error("File Q&A LLM failed: %s", exc)
            return {"question": question, "answer": f"Error: {exc}",
                    "method": "error", "filename": filename}

    async def search(self, query: str, parsed_result: Dict[str, Any]) -> List[Dict]:
        """Find lines/sentences in file content matching query."""
        text = parsed_result.get("text", "")
        lines = text.splitlines()
        q = query.lower()
        matches = []
        for i, line in enumerate(lines):
            if q in line.lower() and line.strip():
                matches.append({"line_number": i+1, "text": line.strip()[:300]})
            if len(matches) >= 20:
                break
        return matches

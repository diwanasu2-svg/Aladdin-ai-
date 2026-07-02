"""PDF text and content extraction."""
from __future__ import annotations
import asyncio, logging
from pathlib import Path
from typing import Any, Dict, List, Optional

log = logging.getLogger(__name__)

try:
    import pdfplumber
    _PDF_AVAILABLE = True
except ImportError:
    _PDF_AVAILABLE = False
    log.warning("pdfplumber not installed — PDF support disabled")


class PDFAnalyzer:
    @property
    def available(self) -> bool:
        return _PDF_AVAILABLE

    async def extract(self, pdf_bytes: bytes, page_range: Optional[tuple] = None) -> Dict[str, Any]:
        if not _PDF_AVAILABLE:
            return {"error": "pdfplumber not installed", "text": "", "pages": []}
        def _run():
            import io
            pages_data = []
            full_text = []
            with pdfplumber.open(io.BytesIO(pdf_bytes)) as pdf:
                total_pages = len(pdf.pages)
                start, end = (page_range or (0, total_pages))
                end = min(end, total_pages)
                for i, page in enumerate(pdf.pages[start:end], start=start):
                    text = page.extract_text() or ""
                    tables = page.extract_tables() or []
                    pages_data.append({
                        "page": i + 1, "text": text,
                        "table_count": len(tables),
                        "tables": [{"rows": t} for t in tables[:3]],  # limit tables
                    })
                    full_text.append(text)
            return {
                "text": "\n\n".join(full_text),
                "pages": pages_data,
                "total_pages": total_pages,
                "method": "pdfplumber",
            }
        return await asyncio.get_running_loop().run_in_executor(None, _run)

    async def summarize_with_llm(self, pdf_bytes: bytes, llm_client=None,
                                  question: str = "Summarize this document.") -> Dict[str, Any]:
        extracted = await self.extract(pdf_bytes)
        if "error" in extracted or not llm_client:
            return extracted
        text = extracted["text"][:8000]  # trim to context window
        messages = [
            {"role": "system", "content": "You are a document analysis assistant."},
            {"role": "user", "content": f"{question}\n\nDocument:\n{text}"},
        ]
        resp = await llm_client.chat(messages)
        return {**extracted, "analysis": resp.content, "question": question}

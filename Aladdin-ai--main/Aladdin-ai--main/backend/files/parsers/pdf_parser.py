"""PDF parser — text, metadata, tables."""
from __future__ import annotations
import asyncio, io, logging
from typing import Any, Dict, List, Optional
log = logging.getLogger(__name__)

try:
    import pdfplumber
    _PDF = True
except ImportError:
    _PDF = False
    log.warning("pdfplumber not installed")


class PDFParser:
    @property
    def available(self): return _PDF

    async def parse(self, data: bytes, page_range: Optional[tuple] = None) -> Dict[str, Any]:
        if not _PDF:
            return {"error": "pdfplumber not installed", "text": "", "pages": []}
        def _run():
            pages_out, full = [], []
            with pdfplumber.open(io.BytesIO(data)) as pdf:
                total = len(pdf.pages)
                start, end = page_range or (0, total)
                for i, page in enumerate(pdf.pages[start:min(end, total)], start):
                    text = page.extract_text() or ""
                    tables = page.extract_tables() or []
                    pages_out.append({"page": i+1, "text": text,
                                      "tables": [{"rows": t} for t in tables[:3]]})
                    full.append(text)
            return {"text": "\n\n".join(full), "pages": pages_out,
                    "total_pages": total, "method": "pdfplumber"}
        return await asyncio.get_running_loop().run_in_executor(None, _run)

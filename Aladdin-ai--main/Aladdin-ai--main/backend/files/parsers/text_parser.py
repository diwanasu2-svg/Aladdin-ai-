"""Text/CSV/JSON/Markdown file parser."""
from __future__ import annotations
import asyncio, json, logging
from typing import Any, Dict
log = logging.getLogger(__name__)


class TextParser:
    @property
    def available(self): return True

    async def parse(self, data: bytes, filename: str = "") -> Dict[str, Any]:
        def _run():
            # Detect encoding
            for enc in ("utf-8", "utf-16", "latin-1", "cp1252"):
                try:
                    text = data.decode(enc)
                    encoding = enc
                    break
                except Exception:
                    continue
            else:
                text = data.decode("utf-8", errors="replace")
                encoding = "utf-8 (lossy)"
            lines = text.splitlines()
            ext = filename.rsplit(".", 1)[-1].lower() if "." in filename else ""
            extra = {}
            if ext == "json":
                try:
                    extra["parsed"] = json.loads(text)
                except Exception:
                    pass
            elif ext == "csv":
                import csv, io as _io
                reader = csv.reader(_io.StringIO(text))
                rows = list(reader)
                extra["rows"] = rows[:500]
                extra["row_count"] = len(rows)
            return {"text": text, "line_count": len(lines), "size_bytes": len(data),
                    "encoding": encoding, **extra}
        return await asyncio.get_running_loop().run_in_executor(None, _run)

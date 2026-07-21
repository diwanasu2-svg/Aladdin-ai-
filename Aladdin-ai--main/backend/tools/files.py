"""File tools — read, write, list, delete."""
from __future__ import annotations
import logging, os, time
from pathlib import Path
from typing import Optional
from .base import BaseTool, ToolResult

log = logging.getLogger(__name__)
_FILES_DIR: Optional[Path] = None


def init(files_dir: Path):
    global _FILES_DIR
    _FILES_DIR = files_dir
    files_dir.mkdir(parents=True, exist_ok=True)


def _safe_path(filename: str) -> Path:
    p = (_FILES_DIR / filename).resolve()
    if not str(p).startswith(str(_FILES_DIR.resolve())):
        raise ValueError("Path traversal detected")
    return p


class ReadFileTool(BaseTool):
    name = "read_file"
    description = "Read content from a file in the workspace."
    parameters = {"type": "object", "properties": {
        "filename": {"type": "string"}, "max_chars": {"type": "integer", "default": 5000}},
        "required": ["filename"]}

    async def execute(self, filename: str, max_chars: int = 5000) -> ToolResult:
        if not _FILES_DIR: return ToolResult(False, self.name, error="Files dir not initialized")
        try:
            p = _safe_path(filename)
            if not p.exists(): return ToolResult(False, self.name, error=f"File not found: {filename}")
            text = p.read_text(encoding="utf-8", errors="ignore")
            return ToolResult(True, self.name, {
                "filename": filename, "content": text[:max_chars],
                "size_bytes": p.stat().st_size, "truncated": len(text) > max_chars})
        except Exception as exc:
            return ToolResult(False, self.name, error=str(exc))


class WriteFileTool(BaseTool):
    name = "write_file"
    description = "Write content to a file in the workspace."
    parameters = {"type": "object", "properties": {
        "filename": {"type": "string"}, "content": {"type": "string"},
        "append": {"type": "boolean", "default": False}}, "required": ["filename", "content"]}

    async def execute(self, filename: str, content: str, append: bool = False) -> ToolResult:
        if not _FILES_DIR: return ToolResult(False, self.name, error="Files dir not initialized")
        try:
            p = _safe_path(filename)
            p.parent.mkdir(parents=True, exist_ok=True)
            mode = "a" if append else "w"
            p.write_text(content, encoding="utf-8") if not append else open(p, "a").write(content)
            return ToolResult(True, self.name, {"filename": filename, "size_bytes": p.stat().st_size})
        except Exception as exc:
            return ToolResult(False, self.name, error=str(exc))


class ListFilesTool(BaseTool):
    name = "list_files"
    description = "List files in the workspace."
    parameters = {"type": "object", "properties": {
        "pattern": {"type": "string", "description": "Glob pattern", "default": "*"}}}

    async def execute(self, pattern: str = "*") -> ToolResult:
        if not _FILES_DIR: return ToolResult(False, self.name, error="Files dir not initialized")
        try:
            files = [{"name": f.name, "size_bytes": f.stat().st_size,
                      "modified": f.stat().st_mtime}
                     for f in _FILES_DIR.glob(pattern) if f.is_file()]
            return ToolResult(True, self.name, {"files": files, "count": len(files)})
        except Exception as exc:
            return ToolResult(False, self.name, error=str(exc))


class DeleteFileTool(BaseTool):
    name = "delete_file"
    description = "Delete a file from the workspace."
    parameters = {"type": "object", "properties": {"filename": {"type": "string"}}, "required": ["filename"]}

    async def execute(self, filename: str) -> ToolResult:
        if not _FILES_DIR: return ToolResult(False, self.name, error="Files dir not initialized")
        try:
            p = _safe_path(filename)
            if not p.exists(): return ToolResult(False, self.name, error="File not found")
            p.unlink()
            return ToolResult(True, self.name, {"deleted": filename})
        except Exception as exc:
            return ToolResult(False, self.name, error=str(exc))

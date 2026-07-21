"""File manager — create, rename, move, delete, search, compress, extract files."""
from __future__ import annotations
import asyncio, logging, os, shutil, time, zipfile, tarfile
from pathlib import Path
from typing import Dict, List, Optional
from ..tools.base import BaseTool, ToolResult

log = logging.getLogger(__name__)


class CreateFileTool(BaseTool):
    name = "create_file"
    description = "Create a new file with optional content, or create a directory."
    parameters = {"type": "object", "properties": {
        "path": {"type": "string"},
        "content": {"type": "string", "default": ""},
        "is_directory": {"type": "boolean", "default": False}},
        "required": ["path"]}

    async def execute(self, path: str, content: str = "", is_directory: bool = False) -> ToolResult:
        t0 = time.time()
        try:
            p = Path(path)
            if is_directory:
                p.mkdir(parents=True, exist_ok=True)
                return ToolResult(True, self.name, {"created_dir": str(p)}, duration_ms=(time.time() - t0) * 1000)
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text(content, encoding="utf-8")
            return ToolResult(True, self.name, {"path": str(p), "size_bytes": p.stat().st_size},
                              duration_ms=(time.time() - t0) * 1000)
        except Exception as exc:
            return ToolResult(False, self.name, error=str(exc), duration_ms=(time.time() - t0) * 1000)


class RenameFileTool(BaseTool):
    name = "rename_file"
    description = "Rename a file or folder."
    parameters = {"type": "object", "properties": {
        "path": {"type": "string"}, "new_name": {"type": "string"}},
        "required": ["path", "new_name"]}

    async def execute(self, path: str, new_name: str) -> ToolResult:
        t0 = time.time()
        try:
            p = Path(path)
            if not p.exists():
                return ToolResult(False, self.name, error=f"Path not found: {path}")
            new_path = p.parent / new_name
            p.rename(new_path)
            return ToolResult(True, self.name, {"old": path, "new": str(new_path)},
                              duration_ms=(time.time() - t0) * 1000)
        except Exception as exc:
            return ToolResult(False, self.name, error=str(exc), duration_ms=(time.time() - t0) * 1000)


class MoveFileTool(BaseTool):
    name = "move_file"
    description = "Move or copy a file or folder to a new location."
    parameters = {"type": "object", "properties": {
        "src": {"type": "string"}, "dst": {"type": "string"},
        "copy": {"type": "boolean", "default": False, "description": "If true, copy instead of move"}},
        "required": ["src", "dst"]}

    async def execute(self, src: str, dst: str, copy: bool = False) -> ToolResult:
        t0 = time.time()
        try:
            sp = Path(src)
            dp = Path(dst)
            if not sp.exists():
                return ToolResult(False, self.name, error=f"Source not found: {src}")
            dp.parent.mkdir(parents=True, exist_ok=True)
            if copy:
                if sp.is_dir():
                    shutil.copytree(str(sp), str(dp))
                else:
                    shutil.copy2(str(sp), str(dp))
                op = "copied"
            else:
                shutil.move(str(sp), str(dp))
                op = "moved"
            return ToolResult(True, self.name, {"operation": op, "src": src, "dst": str(dp)},
                              duration_ms=(time.time() - t0) * 1000)
        except Exception as exc:
            return ToolResult(False, self.name, error=str(exc), duration_ms=(time.time() - t0) * 1000)


class DeleteFsTool(BaseTool):
    name = "delete_fs"
    description = "Delete a file or folder (with recursive option for directories)."
    parameters = {"type": "object", "properties": {
        "path": {"type": "string"}, "recursive": {"type": "boolean", "default": False}},
        "required": ["path"]}

    async def execute(self, path: str, recursive: bool = False) -> ToolResult:
        t0 = time.time()
        try:
            p = Path(path)
            if not p.exists():
                return ToolResult(False, self.name, error=f"Not found: {path}")
            if p.is_dir():
                if recursive:
                    shutil.rmtree(str(p))
                else:
                    p.rmdir()
            else:
                p.unlink()
            return ToolResult(True, self.name, {"deleted": path}, duration_ms=(time.time() - t0) * 1000)
        except Exception as exc:
            return ToolResult(False, self.name, error=str(exc), duration_ms=(time.time() - t0) * 1000)


class SearchFilesTool(BaseTool):
    name = "search_files"
    description = "Search for files by name, extension, size, or modification date."
    parameters = {"type": "object", "properties": {
        "root": {"type": "string", "description": "Directory to search in", "default": "."},
        "name_pattern": {"type": "string", "description": "Glob pattern, e.g. '*.pdf'"},
        "min_size_kb": {"type": "number"}, "max_size_kb": {"type": "number"},
        "max_results": {"type": "integer", "default": 50}}}

    async def execute(self, root: str = ".", name_pattern: str = "*",
                      min_size_kb: float = None, max_size_kb: float = None,
                      max_results: int = 50) -> ToolResult:
        t0 = time.time()
        try:
            rp = Path(root)
            results = []
            for f in rp.rglob(name_pattern):
                if not f.is_file():
                    continue
                size_kb = f.stat().st_size / 1024
                if min_size_kb is not None and size_kb < min_size_kb:
                    continue
                if max_size_kb is not None and size_kb > max_size_kb:
                    continue
                results.append({
                    "path": str(f), "name": f.name,
                    "size_kb": round(size_kb, 2),
                    "modified": f.stat().st_mtime
                })
                if len(results) >= max_results:
                    break
            return ToolResult(True, self.name, {"files": results, "count": len(results)},
                              duration_ms=(time.time() - t0) * 1000)
        except Exception as exc:
            return ToolResult(False, self.name, error=str(exc), duration_ms=(time.time() - t0) * 1000)


class OpenFileTool(BaseTool):
    name = "open_file"
    description = "Open a file with the default system application."
    parameters = {"type": "object", "properties": {"path": {"type": "string"}}, "required": ["path"]}

    async def execute(self, path: str) -> ToolResult:
        t0 = time.time()
        try:
            import subprocess, platform
            sys = platform.system()
            p = str(Path(path).resolve())
            if sys == "Windows":
                os.startfile(p)
            elif sys == "Darwin":
                subprocess.Popen(["open", p])
            else:
                subprocess.Popen(["xdg-open", p])
            return ToolResult(True, self.name, {"opened": path}, duration_ms=(time.time() - t0) * 1000)
        except Exception as exc:
            return ToolResult(False, self.name, error=str(exc), duration_ms=(time.time() - t0) * 1000)


class GetStorageInfoTool(BaseTool):
    name = "get_storage_info"
    description = "Get disk usage and storage statistics for a path."
    parameters = {"type": "object", "properties": {
        "path": {"type": "string", "default": "/"}}}

    async def execute(self, path: str = "/") -> ToolResult:
        t0 = time.time()
        try:
            usage = shutil.disk_usage(path)
            return ToolResult(True, self.name, {
                "path": path,
                "total_gb": round(usage.total / 1e9, 2),
                "used_gb": round(usage.used / 1e9, 2),
                "free_gb": round(usage.free / 1e9, 2),
                "used_percent": round(usage.used / usage.total * 100, 1)
            }, duration_ms=(time.time() - t0) * 1000)
        except Exception as exc:
            return ToolResult(False, self.name, error=str(exc), duration_ms=(time.time() - t0) * 1000)


class CompressFilesTool(BaseTool):
    name = "compress_files"
    description = "Compress files or a directory into a ZIP or TAR.GZ archive."
    parameters = {"type": "object", "properties": {
        "sources": {"type": "array", "items": {"type": "string"}},
        "output": {"type": "string", "description": "Output archive path (.zip or .tar.gz)"},
        "format": {"type": "string", "enum": ["zip", "tar.gz"], "default": "zip"}},
        "required": ["sources", "output"]}

    async def execute(self, sources: List[str], output: str, format: str = "zip") -> ToolResult:
        t0 = time.time()
        try:
            op = Path(output)
            if format == "zip":
                with zipfile.ZipFile(str(op), "w", zipfile.ZIP_DEFLATED) as zf:
                    for src in sources:
                        sp = Path(src)
                        if sp.is_dir():
                            for f in sp.rglob("*"):
                                if f.is_file():
                                    zf.write(str(f), str(f.relative_to(sp.parent)))
                        elif sp.is_file():
                            zf.write(str(sp), sp.name)
            else:
                with tarfile.open(str(op), "w:gz") as tf:
                    for src in sources:
                        tf.add(src, arcname=Path(src).name)
            return ToolResult(True, self.name, {
                "output": str(op), "size_bytes": op.stat().st_size,
                "sources": len(sources), "format": format
            }, duration_ms=(time.time() - t0) * 1000)
        except Exception as exc:
            return ToolResult(False, self.name, error=str(exc), duration_ms=(time.time() - t0) * 1000)


class ExtractArchiveTool(BaseTool):
    name = "extract_archive"
    description = "Extract a ZIP or TAR.GZ archive to a directory."
    parameters = {"type": "object", "properties": {
        "archive": {"type": "string"}, "dest": {"type": "string", "default": "."}},
        "required": ["archive"]}

    async def execute(self, archive: str, dest: str = ".") -> ToolResult:
        t0 = time.time()
        try:
            ap = Path(archive)
            dp = Path(dest)
            dp.mkdir(parents=True, exist_ok=True)
            if archive.endswith(".zip"):
                with zipfile.ZipFile(str(ap), "r") as zf:
                    zf.extractall(str(dp))
                    names = zf.namelist()
            elif archive.endswith((".tar.gz", ".tgz", ".tar.bz2", ".tar")):
                with tarfile.open(str(ap)) as tf:
                    tf.extractall(str(dp))
                    names = tf.getnames()
            else:
                shutil.unpack_archive(str(ap), str(dp))
                names = []
            return ToolResult(True, self.name, {
                "archive": archive, "dest": str(dp), "extracted": len(names)
            }, duration_ms=(time.time() - t0) * 1000)
        except Exception as exc:
            return ToolResult(False, self.name, error=str(exc), duration_ms=(time.time() - t0) * 1000)

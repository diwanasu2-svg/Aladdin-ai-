"""File storage — upload, list, delete, metadata."""
from __future__ import annotations
import hashlib, json, logging, time, uuid
from pathlib import Path
from typing import Any, Dict, List, Optional

log = logging.getLogger(__name__)


class FileStore:
    def __init__(self, storage_dir: Path) -> None:
        self._dir = storage_dir
        self._dir.mkdir(parents=True, exist_ok=True)
        self._meta_file = self._dir / ".meta.json"
        self._meta: Dict[str, Any] = {}
        if self._meta_file.exists():
            try:
                self._meta = json.loads(self._meta_file.read_text())
            except Exception:
                self._meta = {}

    def _save_meta(self):
        self._meta_file.write_text(json.dumps(self._meta, indent=2))

    def save(self, data: bytes, original_name: str, content_type: str = "") -> Dict[str, Any]:
        file_id = str(uuid.uuid4())
        ext = Path(original_name).suffix.lower()
        safe_name = f"{file_id}{ext}"
        dest = self._dir / safe_name
        dest.write_bytes(data)
        sha256 = hashlib.sha256(data).hexdigest()
        record = {"id": file_id, "original_name": original_name,
                  "stored_name": safe_name, "content_type": content_type,
                  "size_bytes": len(data), "sha256": sha256,
                  "extension": ext.lstrip("."), "uploaded_at": time.time()}
        self._meta[file_id] = record
        self._save_meta()
        log.info("File stored: %s (%d bytes)", original_name, len(data))
        return record

    def get_path(self, file_id: str) -> Optional[Path]:
        record = self._meta.get(file_id)
        if not record:
            return None
        p = self._dir / record["stored_name"]
        return p if p.exists() else None

    def get_record(self, file_id: str) -> Optional[Dict]:
        return self._meta.get(file_id)

    def list_files(self) -> List[Dict]:
        return sorted(self._meta.values(), key=lambda x: x.get("uploaded_at", 0), reverse=True)

    def delete(self, file_id: str) -> bool:
        record = self._meta.pop(file_id, None)
        if not record:
            return False
        p = self._dir / record["stored_name"]
        if p.exists():
            p.unlink()
        self._save_meta()
        return True

    def read_bytes(self, file_id: str) -> Optional[bytes]:
        p = self.get_path(file_id)
        return p.read_bytes() if p else None

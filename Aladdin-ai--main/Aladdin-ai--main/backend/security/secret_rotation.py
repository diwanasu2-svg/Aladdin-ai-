"""
Phase 12 Item 6 — Secret Rotation.
Automatic rotation of API keys, JWT secrets, and config secrets with versioning.
"""

import os
import json
import time
import secrets
import logging
import hashlib
from pathlib import Path
from typing import Dict, List, Optional, Tuple
from dataclasses import dataclass, field

logger = logging.getLogger(__name__)

SECRETS_DIR    = os.getenv("SECRETS_DIR", "data/secrets")
MAX_VERSIONS   = int(os.getenv("SECRET_MAX_VERSIONS", "3"))
ROTATION_DAYS  = int(os.getenv("SECRET_ROTATION_DAYS", "30"))


@dataclass
class SecretVersion:
    version:    int
    value_hash: str       # SHA-256 hash of the value (never store plaintext)
    created_at: float = field(default_factory=time.time)
    expires_at: float = 0.0
    active:     bool  = True
    hint:       str   = ""    # non-sensitive hint for debugging


@dataclass
class SecretMeta:
    name:          str
    versions:      List[SecretVersion] = field(default_factory=list)
    rotation_days: int = ROTATION_DAYS
    last_rotated:  float = field(default_factory=time.time)
    auto_rotate:   bool = True


class SecretRotationManager:
    """
    Manages secret lifecycle:
    - Generation of cryptographically secure secrets
    - Version tracking with max-version purging
    - Scheduled rotation (check against rotation_days)
    - Secure distribution via environment variable injection
    """

    def __init__(self, secrets_dir: str = SECRETS_DIR):
        self.secrets_dir = Path(secrets_dir)
        self.secrets_dir.mkdir(parents=True, exist_ok=True)
        self._meta_cache: Dict[str, SecretMeta] = {}
        self._load_all_meta()

    # ── Secret generation ─────────────────────────────────────────────────────
    def generate_secret(self, name: str, length: int = 32, hint: str = "") -> str:
        """Generate a new cryptographically secure secret and record its version."""
        new_value = secrets.token_hex(length)
        self._record_version(name, new_value, hint)
        logger.info("Generated new secret for '%s' (version %d)", name, self._get_meta(name).versions[-1].version)
        return new_value

    def generate_api_key(self, name: str, prefix: str = "ak") -> str:
        """Generate an API key with a readable prefix."""
        key = f"{prefix}_{secrets.token_urlsafe(32)}"
        self._record_version(name, key, hint=f"prefix={prefix}")
        return key

    def generate_jwt_secret(self, name: str = "jwt_secret") -> str:
        """Generate a strong JWT signing secret (64 bytes = 512 bits)."""
        value = secrets.token_hex(64)
        self._record_version(name, value, hint="JWT HS256 signing key")
        logger.info("JWT secret rotated for '%s'", name)
        return value

    # ── Version management ────────────────────────────────────────────────────
    def _record_version(self, name: str, value: str, hint: str = "") -> SecretVersion:
        meta = self._get_meta(name)
        next_v = (meta.versions[-1].version + 1) if meta.versions else 1
        new_version = SecretVersion(
            version    = next_v,
            value_hash = hashlib.sha256(value.encode()).hexdigest(),
            created_at = time.time(),
            expires_at = time.time() + meta.rotation_days * 86400,
            active     = True,
            hint       = hint
        )
        # Deactivate previous versions
        for v in meta.versions:
            v.active = False
        meta.versions.append(new_version)
        # Purge old versions
        if len(meta.versions) > MAX_VERSIONS:
            meta.versions = meta.versions[-MAX_VERSIONS:]
        meta.last_rotated = time.time()
        self._save_meta(name, meta)
        return new_version

    def get_version_count(self, name: str) -> int:
        return len(self._get_meta(name).versions)

    def list_versions(self, name: str) -> List[Dict]:
        meta = self._get_meta(name)
        return [
            {
                "version":    v.version,
                "created_at": time.strftime("%Y-%m-%d %H:%M:%S", time.gmtime(v.created_at)),
                "expires_at": time.strftime("%Y-%m-%d %H:%M:%S", time.gmtime(v.expires_at)),
                "active":     v.active,
                "hint":       v.hint
            }
            for v in meta.versions
        ]

    # ── Rotation scheduling ───────────────────────────────────────────────────
    def needs_rotation(self, name: str) -> bool:
        meta = self._get_meta(name)
        if not meta.versions:
            return True
        latest    = meta.versions[-1]
        age_days  = (time.time() - latest.created_at) / 86400
        overdue   = age_days >= meta.rotation_days
        if overdue:
            logger.warning("Secret '%s' is overdue for rotation (%.1f days old)", name, age_days)
        return overdue

    def rotate_if_needed(self, name: str, generator=None) -> Optional[str]:
        """Rotate a secret if it's past its rotation period."""
        if not self.needs_rotation(name):
            return None
        if generator:
            new_value = generator()
            self._record_version(name, new_value)
            return new_value
        else:
            return self.generate_secret(name)

    def rotate_all_due(self) -> Dict[str, bool]:
        """Check all tracked secrets and rotate any that are overdue."""
        results = {}
        for name in list(self._meta_cache.keys()):
            meta = self._meta_cache[name]
            if meta.auto_rotate and self.needs_rotation(name):
                try:
                    self.generate_secret(name)
                    results[name] = True
                    logger.info("Auto-rotated secret '%s'", name)
                except Exception as e:
                    results[name] = False
                    logger.error("Auto-rotation failed for '%s': %s", name, e)
            else:
                results[name] = False  # Not rotated (not due)
        return results

    # ── Secure distribution ───────────────────────────────────────────────────
    def inject_into_env(self, name: str, value: str, env_key: Optional[str] = None) -> None:
        """Inject a secret value into the process environment."""
        key = env_key or name.upper().replace("-", "_")
        os.environ[key] = value
        logger.debug("Secret '%s' injected as env var '%s'", name, key)

    # ── Metadata persistence ──────────────────────────────────────────────────
    def _get_meta(self, name: str) -> SecretMeta:
        if name not in self._meta_cache:
            self._meta_cache[name] = SecretMeta(name=name)
        return self._meta_cache[name]

    def _save_meta(self, name: str, meta: SecretMeta) -> None:
        path = self.secrets_dir / f"{name}.meta.json"
        data = {
            "name":          meta.name,
            "rotation_days": meta.rotation_days,
            "last_rotated":  meta.last_rotated,
            "auto_rotate":   meta.auto_rotate,
            "versions": [
                {"version": v.version, "value_hash": v.value_hash, "created_at": v.created_at,
                 "expires_at": v.expires_at, "active": v.active, "hint": v.hint}
                for v in meta.versions
            ]
        }
        path.write_text(json.dumps(data, indent=2))

    def _load_all_meta(self) -> None:
        for f in self.secrets_dir.glob("*.meta.json"):
            try:
                data = json.loads(f.read_text())
                meta = SecretMeta(
                    name          = data["name"],
                    rotation_days = data.get("rotation_days", ROTATION_DAYS),
                    last_rotated  = data.get("last_rotated", time.time()),
                    auto_rotate   = data.get("auto_rotate", True),
                    versions      = [
                        SecretVersion(**v) for v in data.get("versions", [])
                    ]
                )
                self._meta_cache[meta.name] = meta
            except Exception as e:
                logger.error("Failed to load secret meta %s: %s", f, e)

    def summary(self) -> Dict:
        return {
            name: {
                "versions":      len(meta.versions),
                "last_rotated":  time.strftime("%Y-%m-%d", time.gmtime(meta.last_rotated)),
                "needs_rotation": self.needs_rotation(name),
                "auto_rotate":   meta.auto_rotate
            }
            for name, meta in self._meta_cache.items()
        }

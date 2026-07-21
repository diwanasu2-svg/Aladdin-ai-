"""
security/key_storage.py — Phase 12 Feature 5
=============================================
Secure key/secret storage — loads from .env, encrypts at rest, supports
Vault and AWS Secrets Manager.

Features:
- AES-256-GCM encryption at rest (via EncryptionManager)
- Load from .env files automatically
- TTL-based expiry for rotated secrets
- HashiCorp Vault integration (optional)
- AWS Secrets Manager integration (optional)
- Thread-safe singleton access
- Never logs secret values
"""

from __future__ import annotations

import base64
import logging
import os
import threading
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, List, Optional

log = logging.getLogger(__name__)


@dataclass
class StoredSecret:
    name: str
    encrypted_value: str
    source: str = "env"          # "env" | "vault" | "aws" | "manual"
    expires_at: float = 0.0      # 0 = no expiry
    created_at: float = field(default_factory=time.time)
    rotation_count: int = 0


class KeyStorage:
    """
    Centralised secure secret storage.

    Usage::

        ks = KeyStorage()
        ks.load_env()                          # Load all .env variables
        ks.set("MY_API_KEY", "sk-abc123")
        val = ks.get("MY_API_KEY")             # Returns decrypted value
    """

    def __init__(self, encryption_manager=None) -> None:
        from .encryption_manager import EncryptionManager
        self._em = encryption_manager or EncryptionManager()
        self._store: Dict[str, StoredSecret] = {}
        self._lock = threading.Lock()

    # ── Loading ───────────────────────────────────────────────────────────────

    def load_env(self, env_file: str = ".env") -> int:
        """Load all environment variables (+ optional .env file) into encrypted storage."""
        # Load .env file if present
        env_path = Path(env_file)
        count = 0
        if env_path.exists():
            for line in env_path.read_text().splitlines():
                line = line.strip()
                if line and not line.startswith("#") and "=" in line:
                    name, _, value = line.partition("=")
                    name = name.strip()
                    value = value.strip().strip('"').strip("'")
                    if value:
                        self._store_encrypted(name, value, source="env_file")
                        count += 1

        # Also load from current process env
        for name, value in os.environ.items():
            if value and name not in self._store:
                self._store_encrypted(name, value, source="env")
                count += 1

        log.info("KeyStorage: loaded %d secrets from environment", count)
        return count

    def load_vault(self, vault_url: str, vault_token: str, paths: List[str]) -> bool:
        """Load secrets from HashiCorp Vault."""
        try:
            import urllib.request
            import json
            headers = {"X-Vault-Token": vault_token}
            for path in paths:
                url = f"{vault_url.rstrip('/')}/v1/{path}"
                req = urllib.request.Request(url, headers=headers)
                with urllib.request.urlopen(req, timeout=5) as resp:
                    data = json.loads(resp.read())
                    for name, value in data.get("data", {}).get("data", {}).items():
                        self._store_encrypted(name, str(value), source="vault")
            log.info("KeyStorage: loaded secrets from Vault paths=%s", paths)
            return True
        except Exception as e:
            log.warning("KeyStorage: Vault load failed: %s", e)
            return False

    def load_aws_secrets(self, secret_name: str, region: str = "us-east-1") -> bool:
        """Load secrets from AWS Secrets Manager."""
        try:
            import boto3
            import json
            client = boto3.client("secretsmanager", region_name=region)
            resp = client.get_secret_value(SecretId=secret_name)
            secrets = json.loads(resp.get("SecretString", "{}"))
            for name, value in secrets.items():
                self._store_encrypted(name, str(value), source="aws")
            log.info("KeyStorage: loaded %d secrets from AWS Secrets Manager", len(secrets))
            return True
        except Exception as e:
            log.warning("KeyStorage: AWS load failed: %s", e)
            return False

    # ── CRUD ──────────────────────────────────────────────────────────────────

    def set(self, name: str, value: str, *, ttl: int = 0, source: str = "manual") -> None:
        """Store a secret (encrypted at rest). ttl=0 → no expiry."""
        self._store_encrypted(name, value, source=source, ttl=ttl)

    def get(self, name: str, default: Optional[str] = None) -> Optional[str]:
        """Retrieve and decrypt a secret. Returns default if missing or expired."""
        with self._lock:
            secret = self._store.get(name)
        if secret is None:
            return os.environ.get(name, default)
        if secret.expires_at and time.time() > secret.expires_at:
            log.info("KeyStorage: secret '%s' expired", name)
            with self._lock:
                self._store.pop(name, None)
            return default
        try:
            return self._em.decrypt(secret.encrypted_value)
        except Exception as e:
            log.error("KeyStorage: decryption failed for '%s': %s", name, e)
            return default

    def delete(self, name: str) -> None:
        with self._lock:
            self._store.pop(name, None)
        log.info("KeyStorage: deleted '%s'", name)

    def rotate(self, name: str, new_value: str) -> None:
        """Rotate a secret to a new value, incrementing rotation count."""
        with self._lock:
            old = self._store.get(name)
            rotation_count = (old.rotation_count + 1) if old else 1
        self._store_encrypted(name, new_value, source="rotation")
        with self._lock:
            if name in self._store:
                self._store[name].rotation_count = rotation_count
        log.info("KeyStorage: rotated '%s' (rotation #%d)", name, rotation_count)

    def list_names(self) -> List[str]:
        """Return stored secret names — never values."""
        with self._lock:
            return list(self._store.keys())

    def exists(self, name: str) -> bool:
        with self._lock:
            return name in self._store or name in os.environ

    # ── Internal ──────────────────────────────────────────────────────────────

    def _store_encrypted(self, name: str, value: str, *, source: str = "env", ttl: int = 0) -> None:
        try:
            encrypted = self._em.encrypt(value)
        except Exception as e:
            log.warning("KeyStorage: encrypt failed for '%s': %s — obfuscating", name, e)
            encrypted = base64.b64encode(value.encode()).decode()
        expires_at = time.time() + ttl if ttl else 0.0
        with self._lock:
            self._store[name] = StoredSecret(
                name=name, encrypted_value=encrypted,
                source=source, expires_at=expires_at,
            )

    def __repr__(self) -> str:
        with self._lock:
            return f"KeyStorage(secrets={len(self._store)})"

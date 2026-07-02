"""
security/encryption_manager.py — Phase 12 Feature 4
====================================================
Encryption at rest and in transit.

Features:
- AES-256-GCM authenticated encryption (AEAD)
- PBKDF2-SHA256 key derivation from passwords
- ECDH key exchange helper (Perfect Forward Secrecy reference)
- TLS configuration helpers (TLS 1.3 enforcement, cert pinning)
- Field-level encrypt/decrypt for sensitive DB fields
- Fernet-compatible fallback when cryptography not installed
- Certificate pinning validation helper for Android/requests
"""

from __future__ import annotations

import base64
import hashlib
import hmac
import logging
import os
import struct
import time
from typing import Optional, Tuple

log = logging.getLogger(__name__)

try:
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    from cryptography.hazmat.primitives.kdf.pbkdf2 import PBKDF2HMAC
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey
    from cryptography.hazmat.backends import default_backend
    _CRYPTO_AVAILABLE = True
except ImportError:
    _CRYPTO_AVAILABLE = False
    log.warning("cryptography not installed — pip install cryptography")


SALT_LEN = 16
NONCE_LEN = 12
KEY_LEN = 32  # AES-256
PBKDF2_ITERATIONS = 600_000


# ── EncryptionManager ─────────────────────────────────────────────────────────

class EncryptionManager:
    """
    AES-256-GCM encryption manager for data at rest.

    Usage::

        em = EncryptionManager(master_key=b"32-byte-secret-key-here!!!!!!!!!")
        ciphertext = em.encrypt("sensitive data")
        plaintext  = em.decrypt(ciphertext)

    Or derive from a password::

        em = EncryptionManager.from_password("my-password")
    """

    def __init__(self, master_key: Optional[bytes] = None) -> None:
        if master_key:
            if len(master_key) not in (16, 24, 32):
                raise ValueError("master_key must be 16, 24, or 32 bytes")
            self._key = master_key
        else:
            env_key = os.environ.get("ENCRYPTION_MASTER_KEY", "")
            if env_key:
                self._key = base64.b64decode(env_key)
            else:
                self._key = os.urandom(KEY_LEN)
                log.warning("EncryptionManager: generated ephemeral key — set ENCRYPTION_MASTER_KEY in .env")

    @classmethod
    def from_password(cls, password: str, salt: Optional[bytes] = None) -> "EncryptionManager":
        """Derive an AES-256 key from a password using PBKDF2-SHA256."""
        salt = salt or os.urandom(SALT_LEN)
        key = cls._derive_key(password.encode(), salt)
        return cls(master_key=key)

    @staticmethod
    def _derive_key(password: bytes, salt: bytes) -> bytes:
        if not _CRYPTO_AVAILABLE:
            return hashlib.pbkdf2_hmac("sha256", password, salt, PBKDF2_ITERATIONS, KEY_LEN)
        kdf = PBKDF2HMAC(algorithm=hashes.SHA256(), length=KEY_LEN, salt=salt, iterations=PBKDF2_ITERATIONS)
        return kdf.derive(password)

    def encrypt(self, plaintext: str) -> str:
        """Encrypt plaintext → base64-encoded ciphertext (nonce + tag + data)."""
        data = plaintext.encode("utf-8")
        if _CRYPTO_AVAILABLE:
            return self._encrypt_aesgcm(data)
        return self._encrypt_fallback(data)

    def decrypt(self, ciphertext: str) -> str:
        """Decrypt base64-encoded ciphertext → plaintext string."""
        raw = base64.b64decode(ciphertext)
        if _CRYPTO_AVAILABLE:
            return self._decrypt_aesgcm(raw).decode("utf-8")
        return self._decrypt_fallback(raw).decode("utf-8")

    def encrypt_bytes(self, data: bytes) -> bytes:
        if _CRYPTO_AVAILABLE:
            return base64.b64decode(self._encrypt_aesgcm(data))
        return base64.b64decode(self._encrypt_fallback(data))

    def decrypt_bytes(self, data: bytes) -> bytes:
        if _CRYPTO_AVAILABLE:
            return self._decrypt_aesgcm(data)
        return self._decrypt_fallback(data)

    # ── AES-256-GCM ──────────────────────────────────────────────────────────

    def _encrypt_aesgcm(self, plaintext: bytes) -> str:
        nonce = os.urandom(NONCE_LEN)
        aesgcm = AESGCM(self._key)
        ct = aesgcm.encrypt(nonce, plaintext, None)  # includes 16-byte GCM tag
        return base64.b64encode(nonce + ct).decode()

    def _decrypt_aesgcm(self, raw: bytes) -> bytes:
        nonce, ct = raw[:NONCE_LEN], raw[NONCE_LEN:]
        aesgcm = AESGCM(self._key)
        return aesgcm.decrypt(nonce, ct, None)

    # ── HMAC-SHA256 fallback (no authenticated encryption — for environments without cryptography) ──

    def _encrypt_fallback(self, plaintext: bytes) -> str:
        nonce = os.urandom(NONCE_LEN)
        # XOR stream cipher (NOT production-grade — only for dev without cryptography)
        keystream = hashlib.sha256(self._key + nonce).digest()
        ct = bytes(a ^ b for a, b in zip(plaintext, keystream[:len(plaintext)]))
        mac = hmac.new(self._key, nonce + ct, hashlib.sha256).digest()[:8]
        return base64.b64encode(nonce + mac + ct).decode()

    def _decrypt_fallback(self, raw: bytes) -> bytes:
        nonce = raw[:NONCE_LEN]
        mac_stored = raw[NONCE_LEN:NONCE_LEN + 8]
        ct = raw[NONCE_LEN + 8:]
        mac_computed = hmac.new(self._key, nonce + ct, hashlib.sha256).digest()[:8]
        if not hmac.compare_digest(mac_stored, mac_computed):
            raise ValueError("MAC verification failed — data may be tampered")
        keystream = hashlib.sha256(self._key + nonce).digest()
        return bytes(a ^ b for a, b in zip(ct, keystream[:len(ct)]))

    # ── ECDH / PFS helper ────────────────────────────────────────────────────

    @staticmethod
    def generate_ecdh_keypair() -> Tuple[bytes, bytes]:
        """
        Generate an X25519 key pair for ECDHE (Perfect Forward Secrecy).
        Returns (private_key_bytes, public_key_bytes).
        """
        if not _CRYPTO_AVAILABLE:
            raise RuntimeError("pip install cryptography for ECDH support")
        priv = X25519PrivateKey.generate()
        pub = priv.public_key()
        priv_bytes = priv.private_bytes(
            encoding=serialization.Encoding.Raw,
            format=serialization.PrivateFormat.Raw,
            encryption_algorithm=serialization.NoEncryption(),
        )
        pub_bytes = pub.public_bytes(
            encoding=serialization.Encoding.Raw,
            format=serialization.PublicFormat.Raw,
        )
        return priv_bytes, pub_bytes

    @staticmethod
    def ecdh_shared_secret(private_key_bytes: bytes, peer_public_key_bytes: bytes) -> bytes:
        """Derive shared secret from X25519 key exchange."""
        if not _CRYPTO_AVAILABLE:
            raise RuntimeError("pip install cryptography for ECDH support")
        from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey, X25519PublicKey
        priv = X25519PrivateKey.from_private_bytes(private_key_bytes)
        pub = X25519PublicKey.from_public_bytes(peer_public_key_bytes)
        return priv.exchange(pub)


# ── TLS configuration helper ─────────────────────────────────────────────────

def get_tls_context(certfile: str, keyfile: str, *, min_version: str = "TLSv1_3"):
    """
    Build a hardened SSL context (TLS 1.3 preferred).
    Use for Flask/gunicorn server startup::

        ssl_ctx = get_tls_context("cert.pem", "key.pem")
        app.run(ssl_context=ssl_ctx)
    """
    import ssl
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.load_cert_chain(certfile, keyfile)
    ctx.minimum_version = ssl.TLSVersion.TLSv1_3
    ctx.set_ciphers("TLS_AES_256_GCM_SHA384:TLS_CHACHA20_POLY1305_SHA256")
    ctx.options |= ssl.OP_NO_COMPRESSION
    ctx.options |= ssl.OP_CIPHER_SERVER_PREFERENCE
    return ctx


def pin_certificate(expected_fingerprint_sha256: str, cert_pem: bytes) -> bool:
    """
    Certificate pinning: verify a DER/PEM cert matches a known SHA-256 fingerprint.
    Use in mobile apps / requests adapters to prevent MITM.
    """
    import ssl
    import hashlib
    der = ssl.PEM_cert_to_DER_cert(cert_pem.decode()) if b"BEGIN" in cert_pem else cert_pem
    actual = hashlib.sha256(der).hexdigest()
    match = hmac.compare_digest(actual.lower(), expected_fingerprint_sha256.lower())
    if not match:
        log.error("CERT PINNING FAILED: expected=%s got=%s", expected_fingerprint_sha256[:16], actual[:16])
    return match

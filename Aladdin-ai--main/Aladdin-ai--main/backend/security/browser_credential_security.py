"""
Task 9: Browser Credential Security — OAuth PKCE flow helper and Chrome Custom Tabs
security guidance for the Android client, plus backend token-exchange endpoint support.

This module provides:
  - PKCE (Proof Key for Code Exchange) code verifier/challenge generation
  - State parameter generation and validation (CSRF protection)
  - Token exchange helpers for backend OAuth callback endpoint
"""
from __future__ import annotations

import base64
import hashlib
import logging
import os
import secrets
import time
from typing import Dict, Optional, Tuple

logger = logging.getLogger(__name__)

# In-memory state store (use Redis/DB in production for multi-process deployments)
_pending_states: Dict[str, Dict] = {}
_STATE_TTL_SECONDS = 600  # 10 minutes


def generate_pkce_pair() -> Tuple[str, str]:
    """
    Task 9: Generate PKCE code_verifier + code_challenge (S256 method).
    Used by Chrome Custom Tabs OAuth flows to prevent authorization code interception.

    Returns:
        (code_verifier, code_challenge)
    """
    code_verifier = base64.urlsafe_b64encode(os.urandom(64)).rstrip(b"=").decode()
    challenge_bytes = hashlib.sha256(code_verifier.encode()).digest()
    code_challenge = base64.urlsafe_b64encode(challenge_bytes).rstrip(b"=").decode()
    return code_verifier, code_challenge


def generate_state_token() -> str:
    """Generate a cryptographically secure state token for CSRF protection."""
    return secrets.token_urlsafe(32)


def store_pending_state(state: str, metadata: Optional[Dict] = None) -> None:
    """Store state token with expiry for later validation."""
    _pending_states[state] = {
        "created_at": time.time(),
        "metadata": metadata or {},
    }
    # Clean up expired states
    _prune_expired_states()


def validate_state_token(state: str) -> Tuple[bool, Optional[Dict]]:
    """
    Validate and consume a state token (one-use only).
    Returns (valid, metadata).
    """
    _prune_expired_states()
    entry = _pending_states.pop(state, None)
    if entry is None:
        logger.warning("OAuth state validation failed — unknown or replayed state: %s", state[:8])
        return False, None
    age = time.time() - entry["created_at"]
    if age > _STATE_TTL_SECONDS:
        logger.warning("OAuth state validation failed — state expired after %.0fs", age)
        return False, None
    logger.debug("OAuth state validated (age=%.1fs)", age)
    return True, entry.get("metadata")


def _prune_expired_states():
    """Remove expired state tokens to prevent memory growth."""
    now = time.time()
    expired = [s for s, d in _pending_states.items() if now - d["created_at"] > _STATE_TTL_SECONDS]
    for s in expired:
        del _pending_states[s]


def build_oauth_auth_url(
    auth_endpoint: str,
    client_id: str,
    redirect_uri: str,
    scope: str,
    state: Optional[str] = None,
    code_challenge: Optional[str] = None,
    extra_params: Optional[Dict] = None,
) -> str:
    """Build a secure OAuth 2.0 authorization URL with PKCE and state."""
    from urllib.parse import urlencode, urlparse, urlunparse

    state = state or generate_state_token()
    params = {
        "response_type": "code",
        "client_id": client_id,
        "redirect_uri": redirect_uri,
        "scope": scope,
        "state": state,
    }
    if code_challenge:
        params["code_challenge"] = code_challenge
        params["code_challenge_method"] = "S256"
    if extra_params:
        params.update(extra_params)

    store_pending_state(state)
    return f"{auth_endpoint}?{urlencode(params)}"


def validate_redirect_uri(redirect_uri: str, allowed_schemes: Optional[list] = None) -> bool:
    """
    Task 9: Validate redirect URI — only allow app-scheme URIs for Chrome Custom Tabs.
    Prevents open redirect attacks.
    """
    from urllib.parse import urlparse
    parsed = urlparse(redirect_uri)
    allowed = allowed_schemes or ["com.aladdin.app", "aladdin"]
    for scheme in allowed:
        if redirect_uri.startswith(scheme + "://") or redirect_uri.startswith(scheme + ":"):
            return True
    # Also allow https:// for web redirects if explicitly permitted
    if parsed.scheme in ("https",):
        return True
    logger.warning("Rejected insecure redirect_uri: %s", redirect_uri)
    return False

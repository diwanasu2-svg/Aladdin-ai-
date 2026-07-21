"""
Phase 12 — Task 2: HTTPS Enforcement rewritten for FastAPI.
HSTS headers, HTTP→HTTPS redirect, SSL validation, security headers via ASGI middleware.
"""
from __future__ import annotations

import logging
import os
import ssl
from typing import Optional

logger = logging.getLogger(__name__)

IS_PRODUCTION       = os.getenv("APP_ENV", os.getenv("FASTAPI_ENV", "development")).lower() == "production"
HSTS_MAX_AGE        = int(os.getenv("HSTS_MAX_AGE", str(31_536_000)))
HSTS_INCLUDE_SUB    = os.getenv("HSTS_INCLUDE_SUBDOMAINS", "true").lower() == "true"
HSTS_PRELOAD        = os.getenv("HSTS_PRELOAD", "false").lower() == "true"
FORCE_HTTPS         = os.getenv("FORCE_HTTPS", "false").lower() == "true"


def _build_hsts_header() -> str:
    value = f"max-age={HSTS_MAX_AGE}"
    if HSTS_INCLUDE_SUB:
        value += "; includeSubDomains"
    if HSTS_PRELOAD:
        value += "; preload"
    return value


def _is_https(headers: dict) -> bool:
    proto = headers.get(b"x-forwarded-proto", b"").decode().lower()
    if proto == "https":
        return True
    cf = headers.get(b"cf-visitor", b"").decode()
    if '"scheme":"https"' in cf:
        return True
    return False


class HTTPSEnforcerMiddleware:
    """
    ASGI middleware that:
    1. Redirects HTTP → HTTPS when FORCE_HTTPS=true
    2. Adds security headers (HSTS, CSP, X-Frame-Options, etc.) to all responses
    3. Strips server fingerprinting headers
    """

    SKIP_PATHS = {"/health", "/api/health", "/api/status"}

    def __init__(self, app):
        self.app = app
        logger.info(
            "HTTPSEnforcerMiddleware initialized (force=%s production=%s)",
            FORCE_HTTPS, IS_PRODUCTION,
        )

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return

        path = scope.get("path", "")
        method = scope.get("method", "GET")
        raw_headers = dict(scope.get("headers", []))

        if FORCE_HTTPS and path not in self.SKIP_PATHS and not _is_https(raw_headers):
            if method in ("GET", "HEAD"):
                host = raw_headers.get(b"host", b"localhost").decode()
                new_url = f"https://{host}{path}"
                qs = scope.get("query_string", b"")
                if qs:
                    new_url += f"?{qs.decode()}"
                logger.info("HTTP→HTTPS redirect: %s", new_url)
                body = b"Redirecting to HTTPS"
                await send({
                    "type": "http.response.start",
                    "status": 301,
                    "headers": [
                        (b"location", new_url.encode()),
                        (b"content-type", b"text/plain"),
                    ],
                })
                await send({"type": "http.response.body", "body": body})
                return
            else:
                import json
                body = json.dumps({"error": "HTTPS required"}).encode()
                await send({
                    "type": "http.response.start",
                    "status": 426,
                    "headers": [(b"content-type", b"application/json")],
                })
                await send({"type": "http.response.body", "body": body})
                return

        async def send_with_security_headers(message):
            if message["type"] == "http.response.start":
                headers = list(message.get("headers", []))
                security_headers = self._build_security_headers(raw_headers)
                headers_dict = {k.lower(): v for k, v in headers}
                for k, v in security_headers:
                    if k.lower() not in headers_dict:
                        headers.append((k, v))
                headers = [(k, v) for k, v in headers
                           if k.lower() not in (b"server", b"x-powered-by")]
                message = dict(message, headers=headers)
            await send(message)

        await self.app(scope, receive, send_with_security_headers)

    def _build_security_headers(self, request_headers: dict) -> list:
        headers = [
            (b"x-content-type-options", b"nosniff"),
            (b"x-frame-options", b"DENY"),
            (b"x-xss-protection", b"1; mode=block"),
            (b"referrer-policy", b"strict-origin-when-cross-origin"),
            (b"permissions-policy", b"camera=(), microphone=(), geolocation=()"),
            (b"content-security-policy",
             b"default-src 'self'; script-src 'self'; object-src 'none'; frame-ancestors 'none';"),
        ]
        if IS_PRODUCTION or _is_https(request_headers):
            headers.append((b"strict-transport-security", _build_hsts_header().encode()))
        return headers


def create_ssl_context(
    cert_file: Optional[str] = None,
    key_file: Optional[str] = None,
    ca_file: Optional[str] = None,
) -> Optional[ssl.SSLContext]:
    """Create an SSL context for uvicorn (dev/testing only)."""
    cert = cert_file or os.getenv("SSL_CERT_FILE")
    key = key_file or os.getenv("SSL_KEY_FILE")

    if not cert or not key:
        if IS_PRODUCTION:
            logger.warning("No SSL cert/key configured — running without TLS!")
        return None

    try:
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        ctx.minimum_version = ssl.TLSVersion.TLSv1_2
        ctx.load_cert_chain(cert, key)
        if ca_file:
            ctx.load_verify_locations(ca_file)
            ctx.verify_mode = ssl.CERT_REQUIRED
        logger.info("SSL context created from cert=%s", cert)
        return ctx
    except Exception as exc:
        logger.error("SSL context creation failed: %s", exc)
        return None

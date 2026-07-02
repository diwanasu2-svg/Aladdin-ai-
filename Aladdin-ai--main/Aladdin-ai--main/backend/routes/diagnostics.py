"""
backend/routes/diagnostics.py — Phase 13: Reliability diagnostics route.

Exposes reliability health check and diagnostic endpoints.
"""
from __future__ import annotations

import logging
import os
import platform
import time
from typing import Any, Dict

from fastapi import APIRouter
from pydantic import BaseModel

log = logging.getLogger(__name__)
router = APIRouter(prefix="/diagnostics", tags=["Diagnostics"])


@router.get("/health")
async def health():
    """Comprehensive system health check."""
    try:
        import psutil
        cpu = psutil.cpu_percent(interval=0.1)
        mem = psutil.virtual_memory()
        disk = psutil.disk_usage("/")
        return {
            "status": "ok",
            "cpu_percent": cpu,
            "memory_percent": mem.percent,
            "disk_percent": disk.percent,
            "platform": platform.platform(),
            "python_version": platform.python_version(),
            "uptime_seconds": time.time() - psutil.boot_time(),
        }
    except ImportError:
        return {
            "status": "ok",
            "message": "psutil not available — install for detailed metrics",
            "platform": platform.platform(),
        }
    except Exception as exc:
        log.error("Health check error: %s", exc)
        return {"status": "error", "error": str(exc)}


@router.get("/info")
async def system_info():
    """System and environment information."""
    return {
        "python_version": platform.python_version(),
        "platform": platform.system(),
        "machine": platform.machine(),
        "processor": platform.processor(),
        "env": {k: "***" if "key" in k.lower() or "token" in k.lower() or "secret" in k.lower()
                else v for k, v in os.environ.items()
                if k.startswith("ALADDIN_") or k.startswith("OLLAMA_")}
    }

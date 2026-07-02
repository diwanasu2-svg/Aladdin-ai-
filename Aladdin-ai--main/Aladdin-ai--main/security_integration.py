"""
security_integration.py — Phase 12 & 13 Integration Hook
==========================================================
Single-import integration point — call initialise_security_and_reliability()
from main.py to activate both Phase 12 (Security) and Phase 13 (Reliability).

This module wires SecurityManager and ReliabilityManager into the existing
Aladdin pipeline with zero changes to existing files required.
"""

from __future__ import annotations

import logging
import os
from typing import Optional

log = logging.getLogger(__name__)

# ── Module-level singletons (set after initialise()) ─────────────────────────
security_manager = None
reliability_manager = None
jwt_handler = None
rate_limiter = None
audit_logger = None
performance_monitor = None
backup_system = None
watchdog = None


def initialise_security_and_reliability(
    *,
    # Security
    jwt_secret: Optional[str] = None,
    encryption_key: Optional[str] = None,
    audit_log_path: str = "logs/audit.log",
    # Reliability
    backup_dir: str = "backups",
    health_check_interval: int = 60,
    watchdog_check_interval: int = 15,
    auto_backup_interval: int = 3600,
    enable_watchdog: bool = True,
    enable_backup: bool = True,
    enable_performance: bool = True,
    # Rate limits
    default_rpm: int = 60,
    # Logging
    log_path: str = "logs/aladdin.log",
    log_level: str = "INFO",
) -> dict:
    """
    Initialise Phase 12 (Security) and Phase 13 (Reliability) subsystems.

    Call this early in main.py, before starting the AI pipeline.

    Returns a dict of all initialised subsystems for convenience:
      {
        "security_manager": SecurityManager,
        "reliability_manager": ReliabilityManager,
        "jwt_handler": JWTHandler,
        "rate_limiter": RateLimiter,
        "audit_logger": AuditLogger,
        "backup_system": BackupSystem,
        "performance_monitor": PerformanceMonitor,
        "watchdog": Watchdog,
      }
    """
    global security_manager, reliability_manager, jwt_handler
    global rate_limiter, audit_logger, performance_monitor, backup_system, watchdog

    log.info("═══ Initialising Phase 12 (Security) + Phase 13 (Reliability) ═══")

    # ── Phase 13: Structured logging first ───────────────────────────────────
    try:
        from reliability_ext.logging_system import setup_json_logging
        setup_json_logging(level=log_level, log_path=log_path)
        log.info("Phase 13: structured JSON logging active")
    except Exception as exc:
        log.warning("Phase 13: logging setup skipped: %s", exc)

    # ── Phase 12: Security subsystems ────────────────────────────────────────
    try:
        from security.security_manager import SecurityManager
        from security.rate_limiter import RateLimitConfig

        sm = SecurityManager()
        sm.initialise(
            secret_key=jwt_secret or os.environ.get("JWT_SECRET_KEY"),
            log_path=audit_log_path,
        )
        security_manager = sm
        jwt_handler = sm._jwt
        rate_limiter = sm._rate_limiter
        audit_logger = sm._audit_logger

        # Configure per-endpoint rate limits
        from security.rate_limiter import RateLimiter
        if rate_limiter:
            rate_limiter.set_endpoint_limit("/api/auth/login",    RateLimitConfig(5,  60, block_seconds=300, description="Login endpoint"))
            rate_limiter.set_endpoint_limit("/api/auth/refresh",  RateLimitConfig(10, 60, block_seconds=120, description="Token refresh"))
            rate_limiter.set_endpoint_limit("/api/chat",          RateLimitConfig(30, 60, block_seconds=30,  description="Chat endpoint"))
            rate_limiter.set_endpoint_limit("/api/voice",         RateLimitConfig(20, 60, block_seconds=30,  description="Voice endpoint"))
            rate_limiter.set_endpoint_limit("/api/memory",        RateLimitConfig(60, 60, block_seconds=10,  description="Memory read"))
            rate_limiter.set_endpoint_limit("/api/admin",         RateLimitConfig(10, 60, block_seconds=600, description="Admin endpoint"))

        log.info("Phase 12: SecurityManager ready (JWT + rate limiter + audit logger + input validator)")

    except Exception as exc:
        log.warning("Phase 12: Security initialisation partial: %s", exc)

    # ── Phase 13: Reliability subsystems ─────────────────────────────────────
    try:
        from reliability_ext.reliability_manager import ReliabilityManager
        from reliability_ext.health_monitor import (
            HealthMonitor, CpuHealthCheck, MemoryHealthCheck, NetworkHealthCheck,
            OllamaHealthCheck, DatabaseHealthCheck
        )
        from reliability_ext.crash_recovery import CrashRecovery
        from reliability_ext.watchdog import Watchdog as _Watchdog
        from reliability_ext.auto_restart import AutoRestart
        from reliability_ext.performance_monitor import PerformanceMonitor as _PM

        rm = ReliabilityManager(check_interval=health_check_interval)

        # Health checks
        hm = HealthMonitor(check_interval=health_check_interval)
        hm.add_check(CpuHealthCheck(warn_threshold=80, critical_threshold=95))
        hm.add_check(MemoryHealthCheck(warn_threshold=80, critical_threshold=95))
        hm.add_check(NetworkHealthCheck())
        hm.add_check(OllamaHealthCheck())

        # Crash recovery
        cr = CrashRecovery(state_dir=f"{backup_dir}/crash_state")
        cr.install_handlers()

        # Watchdog
        if enable_watchdog:
            wd = _Watchdog(check_interval=watchdog_check_interval)
            watchdog = wd
        else:
            wd = None

        # Performance monitor
        if enable_performance:
            pm = _PM()
            performance_monitor = pm
        else:
            pm = None

        rm._health_monitor = hm
        rm._crash_recovery = cr
        rm._watchdog = wd
        rm._performance_monitor = pm
        reliability_manager = rm
        rm.start()

        log.info("Phase 13: ReliabilityManager ready (health monitor + crash recovery + watchdog + perf)")

    except Exception as exc:
        log.warning("Phase 13: Reliability initialisation partial: %s", exc)

    # ── Phase 13: Backup system ───────────────────────────────────────────────
    if enable_backup:
        try:
            from reliability_ext.backup_system import BackupSystem, BackupConfig

            bs = BackupSystem(BackupConfig(
                backup_dir=backup_dir,
                interval_seconds=auto_backup_interval,
                retention_count=10,
                compress=True,
                verify=True,
            ))
            # Register standard Aladdin data targets
            bs.add_target("memory_db",     "data/memory.db",         backup_type="sqlite")
            bs.add_target("conversation_db","data/conversations.db",  backup_type="sqlite")
            bs.add_target("settings",      "config/config.yaml",     backup_type="file")
            bs.add_target("user_profiles", "data/profiles",          backup_type="directory")
            bs.start()
            backup_system = bs
            log.info("Phase 13: BackupSystem started (interval=%ds, dir=%s)", auto_backup_interval, backup_dir)
        except Exception as exc:
            log.warning("Phase 13: BackupSystem initialisation skipped: %s", exc)

    log.info("═══ Phase 12 + 13 initialisation complete ═══")

    return {
        "security_manager":   security_manager,
        "reliability_manager": reliability_manager,
        "jwt_handler":        jwt_handler,
        "rate_limiter":       rate_limiter,
        "audit_logger":       audit_logger,
        "backup_system":      backup_system,
        "performance_monitor": performance_monitor,
        "watchdog":           watchdog,
    }


def shutdown_security_and_reliability() -> None:
    """Gracefully shut down Phase 12/13 subsystems. Call on application exit."""
    global reliability_manager, backup_system

    log.info("Shutting down Phase 12/13 subsystems...")
    try:
        if reliability_manager:
            reliability_manager.stop()
    except Exception as exc:
        log.warning("ReliabilityManager shutdown error: %s", exc)

    try:
        if backup_system:
            backup_system.stop()
            # Final backup on shutdown
            backup_system.backup_all()
    except Exception as exc:
        log.warning("BackupSystem shutdown error: %s", exc)

    log.info("Phase 12/13 subsystems shut down cleanly")

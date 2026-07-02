"""
reliability_ext — Phase 13: Reliability Foundation
===================================================
Provides reliability manager, crash recovery, watchdog, health monitoring,
diagnostics, auto-restart, backup/restore, structured logging,
and performance monitoring.

Quick start::

    from reliability_ext import ReliabilityManager

    rm = ReliabilityManager()
    rm.register_component("ai_engine", recovery_fn=restart_ai)
    rm.initialise_subsystems()
    rm.start()

    health = rm.get_system_health()
    dashboard = rm.get_dashboard()
"""

from .reliability_manager import ReliabilityManager, ComponentStatus, SystemHealth, ComponentRecord
from .crash_recovery import CrashRecovery, CrashReport, CheckpointState
from .watchdog import Watchdog, WatchdogTarget, WatchdogEvent, WatchdogAlertLevel
from .health_monitor import HealthMonitor, ComponentHealth, HealthStatus, HealthMetric
from .diagnostics import Diagnostics, DiagnosticReport
from .auto_restart import AutoRestart, RestartState
from .backup_system import BackupSystem, BackupManifest, BackupEntry
from .restore_system import RestoreSystem, RestoreResult
from .logging_system import LoggingSystem, ComponentLogger, correlation_id, JSONFormatter
from .performance_monitor import PerformanceMonitor, MetricStats

__all__ = [
    # Manager
    "ReliabilityManager", "ComponentStatus", "SystemHealth", "ComponentRecord",
    # Crash
    "CrashRecovery", "CrashReport", "CheckpointState",
    # Watchdog
    "Watchdog", "WatchdogTarget", "WatchdogEvent", "WatchdogAlertLevel",
    # Health
    "HealthMonitor", "ComponentHealth", "HealthStatus", "HealthMetric",
    # Diagnostics
    "Diagnostics", "DiagnosticReport",
    # Auto restart
    "AutoRestart", "RestartState",
    # Backup
    "BackupSystem", "BackupManifest", "BackupEntry",
    # Restore
    "RestoreSystem", "RestoreResult",
    # Logging
    "LoggingSystem", "ComponentLogger", "correlation_id", "JSONFormatter",
    # Performance
    "PerformanceMonitor", "MetricStats",
]

__version__ = "13.0.0"

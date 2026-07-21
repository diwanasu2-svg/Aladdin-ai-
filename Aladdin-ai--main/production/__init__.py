"""Phase 15 — Production package.

Contains battery/memory optimization, crash reporting, CI/CD config,
APK hardening, and all test suites.
"""

from .battery_manager import BatteryManager
from .memory_manager import MemoryManager
from .crashlytics_integration import CrashlyticsIntegration

__all__ = ["BatteryManager", "MemoryManager", "CrashlyticsIntegration"]

"""
full_duplex.py — Deprecated shim.
===================================
All logic has been unified into ``barge_in.py`` (``BargeInManager``).
This file re-exports everything so legacy imports continue to work.
"""

from __future__ import annotations

import logging

# Re-export unified implementations
from barge_in import (  # noqa: F401
    BargeInManager,
    BargeInDetector,
    EchoCanceller,
    InterruptionHandler,
    FullDuplexAudioManager,
)

log = logging.getLogger(__name__)
log.warning(
    "full_duplex.py is deprecated. "
    "Import BargeInManager (or FullDuplexAudioManager) from barge_in instead."
)

__all__ = [
    "BargeInManager",
    "BargeInDetector",
    "EchoCanceller",
    "InterruptionHandler",
    "FullDuplexAudioManager",
]

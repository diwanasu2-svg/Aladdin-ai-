"""Logging setup for Aladdin."""

from __future__ import annotations

import logging
import logging.handlers
import sys
from pathlib import Path

from .config import LoggingCfg


def setup_logging(cfg: LoggingCfg) -> None:
    """Configure root logger with console + rotating file handlers."""
    level = getattr(logging, cfg.level.upper(), logging.INFO)

    fmt = logging.Formatter(
        "%(asctime)s [%(levelname)-8s] %(name)s: %(message)s",
        datefmt="%Y-%m-%d %H:%M:%S",
    )

    root = logging.getLogger()
    root.setLevel(level)
    root.handlers.clear()

    # Console handler
    console = logging.StreamHandler(sys.stdout)
    console.setFormatter(fmt)
    console.setLevel(level)
    root.addHandler(console)

    # File handler
    if cfg.file:
        log_path = Path(cfg.file)
        log_path.parent.mkdir(parents=True, exist_ok=True)
        file_handler = logging.handlers.RotatingFileHandler(
            str(log_path),
            maxBytes=cfg.max_bytes,
            backupCount=cfg.backup_count,
            encoding="utf-8",
        )
        file_handler.setFormatter(fmt)
        file_handler.setLevel(level)
        root.addHandler(file_handler)

    # Silence noisy third-party loggers
    for noisy in ("httpx", "httpcore", "urllib3", "requests", "openai"):
        logging.getLogger(noisy).setLevel(logging.WARNING)

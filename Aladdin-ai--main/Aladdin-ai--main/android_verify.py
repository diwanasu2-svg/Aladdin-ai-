"""
Phase 8 — Android Device Verification (Python diagnostic helper)
=================================================================
Verifies the Python backend is reachable from Android and reports
key service health indicators.  Run on the host machine alongside
the Android app to validate the full stack.
"""

from __future__ import annotations

import json
import logging
import os
import sys
from pathlib import Path

log = logging.getLogger(__name__)


def verify_piper() -> dict:
    """Verify Piper TTS binary and voice model."""
    candidates = [
        Path("models/piper/piper"),
        Path("/usr/local/bin/piper"),
    ]
    import shutil

    binary = next((p for p in candidates if p.is_file()), None)
    if not binary:
        found = shutil.which("piper")
        binary = Path(found) if found else None

    model = Path("voices/en_US-amy-medium.onnx")
    return {
        "ok": binary is not None and model.exists(),
        "binary": str(binary) if binary else "NOT FOUND",
        "model": str(model) if model.exists() else "NOT FOUND",
    }


def verify_whisper() -> dict:
    """Verify Whisper STT availability."""
    for module in ("faster_whisper", "whisper"):
        try:
            __import__(module)
            return {"ok": True, "engine": module}
        except ImportError:
            continue
    return {"ok": False, "engine": "none"}


def verify_ollama(host: str = "http://localhost:11434") -> dict:
    """Verify Ollama LLM server is reachable."""
    import requests

    try:
        r = requests.get(f"{host}/api/tags", timeout=5)
        models = [m["name"] for m in r.json().get("models", [])]
        return {"ok": True, "host": host, "models": models}
    except Exception as exc:
        return {"ok": False, "host": host, "error": str(exc)}


def verify_audio() -> dict:
    """Verify audio I/O (sounddevice)."""
    try:
        import sounddevice as sd

        devices = sd.query_devices()
        inputs = sum(1 for d in devices if d["max_input_channels"] > 0)
        outputs = sum(1 for d in devices if d["max_output_channels"] > 0)
        return {"ok": True, "input_devices": inputs, "output_devices": outputs}
    except ImportError:
        return {"ok": False, "error": "sounddevice not installed"}
    except Exception as exc:
        return {"ok": False, "error": str(exc)}


def verify_dependencies() -> dict:
    """Verify key Python dependencies."""
    from reliability import validate_dependencies

    ok, warnings = validate_dependencies()
    return {"ok": ok, "warnings": warnings}


def run_full_verification(ollama_host: str = "http://localhost:11434") -> dict:
    """Run all verification checks and return a consolidated report."""
    report = {
        "piper_tts": verify_piper(),
        "whisper_stt": verify_whisper(),
        "ollama_llm": verify_ollama(ollama_host),
        "audio_io": verify_audio(),
    }
    try:
        report["dependencies"] = verify_dependencies()
    except Exception as exc:
        report["dependencies"] = {"ok": False, "error": str(exc)}

    all_ok = all(v.get("ok", False) for v in report.values())
    report["overall_ok"] = all_ok
    return report


if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser(description="Aladdin Android Stack Verifier")
    parser.add_argument("--ollama-host", default="http://localhost:11434")
    parser.add_argument("--json", action="store_true", help="Output as JSON")
    args = parser.parse_args()

    logging.basicConfig(level=logging.WARNING)
    report = run_full_verification(args.ollama_host)

    if args.json:
        log.info(json.dumps(report, indent=2))

    else:
        log.info("\n══════════════════════════════════════")

        log.info("  Aladdin Android Stack Verification")

        log.info("══════════════════════════════════════")

        for name, result in report.items():
            if name == "overall_ok":
                continue
            status = "✅" if result.get("ok") else "❌"
            log.info(f"  {status} {name.replace('_', ' ').title()}")

            for k, v in result.items():
                if k != "ok":
                    log.info(f"       {k}: {v}")

        overall = "✅ ALL SYSTEMS GO" if report["overall_ok"] else "❌ ISSUES FOUND"
        log.info(f"\n  {overall}\n")

        sys.exit(0 if report["overall_ok"] else 1)

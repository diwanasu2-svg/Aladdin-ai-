"""phase14_phase15_integration.py — Wiring point for Phase 14 & 15.

Single import that initialises all AI model subsystems (Phase 14) and
production monitoring (Phase 15) alongside the existing app.

Usage in main.py::

    from phase14_phase15_integration import initialise_ai_and_production
    systems = initialise_ai_and_production(config)
    llm_manager = systems["llm"]
"""

from __future__ import annotations

import logging
import os
from typing import Any, Dict, Optional

log = logging.getLogger(__name__)


def initialise_ai_and_production(
    config: Optional[Dict[str, Any]] = None,
    enable_local_llm: bool = True,
    enable_mlc: bool = False,
    enable_gpu: bool = True,
    enable_battery_monitor: bool = True,
    enable_memory_monitor: bool = True,
    enable_crashlytics: bool = True,
    enable_cache: bool = True,
    app_version: str = "14.15.0",
    cache_dir: str = ".cache/aladdin",
    crash_log: str = "logs/crashes.jsonl",
) -> Dict[str, Any]:
    """Initialise and return all Phase 14 + Phase 15 subsystems.

    Returns a dict with keys:
      - llm: LLMManager (Phase 14)
      - battery: BatteryManager (Phase 15)
      - memory: MemoryManager (Phase 15)
      - crashlytics: CrashlyticsIntegration (Phase 15)
      - quant: QuantizationManager (Phase 14)
      - gpu: GPUAccelerator (Phase 14)
    """
    cfg = config or {}
    systems: Dict[str, Any] = {}

    # ── Phase 14: GPU & Quantization ────────────────────────────────────────
    log.info("[Init] Phase 14: Initialising GPU Accelerator...")
    from models.gpu_accelerator import GPUAccelerator
    gpu = GPUAccelerator()
    if enable_gpu:
        gpu.start_monitoring()
    systems["gpu"] = gpu

    from models.quantization import QuantizationManager
    quant = QuantizationManager()
    systems["quant"] = quant
    log.info("[Init] Quantization: level=%s backend=%s",
             quant.select_quant_level().value, quant.select_backend().value)

    # ── Phase 14: LLM Manager ────────────────────────────────────────────────
    log.info("[Init] Phase 14: Initialising LLM Manager...")
    from models.llm_manager import LLMManager
    system_prompt = cfg.get(
        "system_prompt",
        "You are Aladdin, a helpful, multilingual AI assistant running on Android. "
        "Be concise, accurate, and friendly.",
    )
    llm = LLMManager(
        system_prompt=system_prompt,
        model_family=cfg.get("model_family", "ollama"),
        max_context_tokens=cfg.get("max_context_tokens", 4096),
        cache_dir=cache_dir,
        enable_gpu=enable_gpu,
        enable_cache=enable_cache,
    )
    systems["llm"] = llm

    # ── Phase 14: Local LLM ─────────────────────────────────────────────────
    if enable_local_llm:
        log.info("[Init] Phase 14: Initialising Local LLM...")
        try:
            from models.local_llm import LocalLLM
            gpu_flags = quant.llama_cpp_gpu_flags() if enable_gpu else {}
            local_llm = LocalLLM(
                model_dir=cfg.get("local_llm_dir", "models/gguf"),
                n_gpu_layers=gpu_flags.get("n_gpu_layers", 0),
            )
            if local_llm.is_downloaded():
                local_llm.load()
                llm.register_local_llm(local_llm)
                log.info("[Init] Local LLM loaded and registered")
            else:
                log.info("[Init] No local GGUF model downloaded yet — skipping local LLM")
            systems["local_llm"] = local_llm
        except Exception as exc:
            log.warning("[Init] Local LLM init failed: %s", exc)

    # ── Phase 14: MLC LLM ────────────────────────────────────────────────────
    if enable_mlc:
        log.info("[Init] Phase 14: Initialising MLC LLM...")
        try:
            from models.mlc_llm import MLCModel, MLCConfig
            mlc = MLCModel(MLCConfig(
                model_key=cfg.get("mlc_model_key", "phi2-mlc"),
                model_dir=cfg.get("mlc_model_dir", "models/mlc"),
            ))
            if mlc.is_available():
                llm.register_mlc(mlc)
                log.info("[Init] MLC LLM registered (backend=%s)", mlc.backend.value)
            systems["mlc"] = mlc
        except Exception as exc:
            log.warning("[Init] MLC LLM init failed: %s", exc)

    # ── Phase 14: OpenAI (if API key present) ───────────────────────────────
    openai_key = cfg.get("openai_api_key") or os.environ.get("OPENAI_API_KEY", "")
    if openai_key:
        try:
            llm.register_openai(openai_key, model=cfg.get("openai_model", "gpt-4o-mini"))
            log.info("[Init] OpenAI provider registered")
        except Exception as exc:
            log.warning("[Init] OpenAI registration failed: %s", exc)

    # ── Phase 14: Rule-based fallback ───────────────────────────────────────
    def _rule_based(text: str) -> str:
        t = text.lower()
        if any(w in t for w in ("hello", "hi", "hey")):
            return "Hello! I'm Aladdin, your AI assistant."
        if "time" in t or "date" in t:
            import time
            return f"The current time is {time.strftime('%H:%M')} on {time.strftime('%Y-%m-%d')}."
        return "I'm sorry, I'm unable to process that right now. Please try again."

    llm.register_rule_based(_rule_based)

    # ── Phase 15: Battery Monitor ────────────────────────────────────────────
    if enable_battery_monitor:
        log.info("[Init] Phase 15: Initialising Battery Manager...")
        from production.battery_manager import BatteryManager
        battery = BatteryManager()
        battery.start_monitoring()
        systems["battery"] = battery

    # ── Phase 15: Memory Monitor ─────────────────────────────────────────────
    if enable_memory_monitor:
        log.info("[Init] Phase 15: Initialising Memory Manager...")
        from production.memory_manager import MemoryManager
        mem_mgr = MemoryManager()
        mem_mgr.limiter.register_cache(llm.cache) if llm.cache else None
        mem_mgr.start_monitoring()
        systems["memory"] = mem_mgr

    # ── Phase 15: Crashlytics ────────────────────────────────────────────────
    if enable_crashlytics:
        log.info("[Init] Phase 15: Initialising Crashlytics...")
        from production.crashlytics_integration import CrashlyticsIntegration
        crashlytics = CrashlyticsIntegration(
            app_version=app_version,
            local_log_path=crash_log,
        )
        crashlytics.set_custom_key("phase", "14.15")
        crashlytics.set_custom_key("gpu_backend", gpu.backend)
        systems["crashlytics"] = crashlytics

    log.info("[Init] Phase 14 + 15 initialisation complete. Systems: %s",
             list(systems.keys()))
    return systems

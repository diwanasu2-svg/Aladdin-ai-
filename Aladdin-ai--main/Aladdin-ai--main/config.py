"""Configuration loading and dataclasses for Aladdin - updated through Multilingual Support."""

from __future__ import annotations

import logging
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Dict, List, Optional

import yaml

log = logging.getLogger(__name__)


@dataclass
class WhisperCfg:
    """Speech-to-Text (Whisper) configuration — Feature 1."""

    model: str = "base"
    device: str = "cpu"
    language: Optional[str] = None  # None = auto-detect
    # Multilingual — Feature 1
    multilingual: bool = True
    supported_languages: List[str] = field(default_factory=lambda: ["en", "hi", "gu"])
    initial_prompt_hi: str = "यह एक भारतीय हिंदी भाषा का ऑडियो है।"
    initial_prompt_gu: str = "આ ગુજરાતી ભાષામાં ઑડિઓ છે."
    initial_prompt_en: str = "This is English audio from an Indian speaker."
    beam_size: int = 5
    best_of: int = 5
    temperature: float = 0
    no_speech_threshold: float = 0.6
    compression_ratio_threshold: float = 2.4
    condition_on_previous_text: bool = True


@dataclass
class OllamaCfg:
    """Language Model (Ollama) configuration — Feature 2."""

    host: str = "http://localhost:11434"
    # Multilingual models — Feature 2: llama3.1, qwen2.5, gemma3
    model: str = "llama3.1"
    temperature: float = 0.7
    timeout: int = 300
    max_tokens: int = 512


@dataclass
class PiperCfg:
    """Text-to-Speech (Piper) configuration."""

    model_path: str = "voices/en_US-amy-medium.onnx"
    config_path: Optional[str] = None
    speaker_id: Optional[int] = None
    enabled: bool = True
    streaming_enabled: bool = True


@dataclass
class MultilingualTTSCfg:
    """Multilingual TTS configuration — Features 5, 6, 7, 8, 9, 13."""

    piper_binary: str = "models/piper/piper"
    # English TTS
    en_voice: str = "models/piper/en_US-lessac-medium.onnx"
    en_config: str = "models/piper/en_US-lessac-medium.onnx.json"
    # Hindi TTS — Feature 5
    hi_voice: str = "models/piper/hi_IN-hindi_male-medium.onnx"
    hi_config: str = "models/piper/hi_IN-hindi_male-medium.onnx.json"
    # Gujarati TTS — Feature 6
    gu_voice: str = "models/piper/gu_IN-cmu_indic-medium.onnx"
    gu_config: str = "models/piper/gu_IN-cmu_indic-medium.onnx.json"
    # gTTS fallback for Gujarati — Feature 6, 9
    gtts_enabled: bool = True
    gtts_slow: bool = False
    # Preloading — Feature 13
    preload_on_startup: bool = True
    # Speaking rate
    speaking_rate: float = 1.0
    # Fallback notification — Feature 9, 15
    fallback_notification: bool = True


@dataclass
class LanguageDetectionCfg:
    """Language detection configuration — Feature 3."""

    methods: List[str] = field(
        default_factory=lambda: ["unicode", "vocab", "ngram", "langdetect"]
    )
    confidence_threshold: float = 0.55
    history_window: int = 5
    history_weight: float = 0.2
    max_latency_ms: int = 200


@dataclass
class LanguageFallbackCfg:
    """Language fallback chain — Feature 9."""

    gujarati: List[str] = field(
        default_factory=lambda: ["gujarati", "hindi", "english"]
    )
    hindi: List[str] = field(default_factory=lambda: ["hindi", "english"])
    english: List[str] = field(default_factory=lambda: ["english"])
    fallback_notification: bool = True


@dataclass
class MultilingualMemoryCfg:
    """Multilingual memory configuration — Feature 10, 11."""

    multilingual_db_path: str = "data/multilingual_memory.sqlite"
    store_language_tag: bool = True
    unicode_encoding: str = "utf-8"
    preserve_native_script: bool = True
    context_language_mixing: bool = True


@dataclass
class AudioCfg:
    """Audio I/O and Voice Core configuration."""

    sample_rate: int = 16000
    silence_threshold: float = 0.01
    silence_seconds: float = 1.2
    max_seconds: float = 30.0

    wake_word: str = "aladdin"
    wake_word_enabled: bool = True
    wake_word_model: str = "microphone"
    wake_word_list: list = None  # type: ignore[assignment]
    wake_sensitivity: str = "balanced"
    wake_word_threshold: float = 0.55
    wake_word_cooldown: float = 2.0
    wake_session_timeout: float = 10.0
    microphone_gain: float = 1.0
    noise_suppression_level: int = 1
    continuous_listening: bool = True
    audio_chunk_size: int = 512
    interrupt_enabled: bool = True

    vad_enabled: bool = True
    vad_engine: str = "silero"
    vad_threshold: float = 0.5
    vad_min_speech_ms: int = 100
    vad_speech_timeout_ms: int = 500
    silence_timeout_ms: int = 800
    min_speech_duration_ms: int = 200
    max_recording_seconds: float = 30.0
    noise_suppression_enabled: bool = True
    noise_suppression_strength: int = 2
    echo_cancellation_enabled: bool = True
    echo_cancellation_timeout_ms: int = 100
    auto_sleep_enabled: bool = True
    auto_sleep_timeout_ms: int = 3000
    auto_resume_enabled: bool = True
    auto_resume_delay_ms: int = 100

    def __post_init__(self):
        if self.wake_word_list is None:
            self.wake_word_list = []


@dataclass
class MemoryCfg:
    """Conversation memory configuration."""

    db_path: str = "data/aladdin_memory.sqlite"
    window: int = 12
    semantic_search_enabled: bool = True
    max_facts: int = 500
    summarize_after: int = 50
    profile_path: str = "data/user_profile.json"
    # Multilingual memory
    multilingual_db_path: str = "data/multilingual_memory.sqlite"
    store_language_tag: bool = True
    unicode_encoding: str = "utf-8"
    preserve_native_script: bool = True
    context_language_mixing: bool = True
    # Phase 3
    project_memory_enabled: bool = True
    project_memory_db_path: str = "data/project_memory.sqlite"
    location_memory_enabled: bool = True
    location_memory_db_path: str = "data/location_memory.sqlite"
    reminder_memory_enabled: bool = True
    reminder_memory_db_path: str = "data/reminder_memory.sqlite"
    calendar_memory_enabled: bool = True
    calendar_memory_db_path: str = "data/calendar_memory.sqlite"
    conversation_summary_enabled: bool = True
    conversation_summary_db_path: str = "data/conversation_summary.sqlite"
    summary_trigger_messages: int = 30
    summary_max_length: int = 500
    importance_scoring_enabled: bool = True
    vector_store_enabled: bool = True
    vector_store_db_path: str = "data/memory_vectors.sqlite"
    embedding_dimensions: int = 256
    embedding_cache_size: int = 1024
    semantic_search_default_limit: int = 5
    semantic_search_min_similarity: float = 0.12
    semantic_search_rebuild_on_start: bool = True
    ranking_similarity_bias: float = 0.55
    ranking_importance_bias: float = 0.30
    ranking_recency_bias: float = 0.15


@dataclass
class SearchCfg:
    enabled: bool = True
    provider: str = "duckduckgo"
    api_key: Optional[str] = None
    max_results: int = 3
    timeout: int = 10


@dataclass
class TelegramCfg:
    enabled: bool = False
    bot_token: Optional[str] = None
    allowed_users: List[int] = field(default_factory=list)


@dataclass
class CalendarCfg:
    enabled: bool = True
    db_path: str = "data/calendar.sqlite"


@dataclass
class ReminderCfg:
    enabled: bool = True
    db_path: str = "data/reminders.sqlite"
    check_interval: int = 30


@dataclass
class PluginsCfg:
    enabled: List[str] = field(default_factory=list)
    plugin_dir: str = "aladdin_core/plugins"


@dataclass
class LoggingCfg:
    level: str = "INFO"
    file: str = "logs/aladdin.log"
    max_bytes: int = 10_485_760
    backup_count: int = 5


@dataclass
class AppConfig:
    """Top-level application configuration."""

    whisper: WhisperCfg = field(default_factory=WhisperCfg)
    ollama: OllamaCfg = field(default_factory=OllamaCfg)
    piper: PiperCfg = field(default_factory=PiperCfg)
    # Multilingual TTS — Features 5, 6, 7, 8
    multilingual_tts: MultilingualTTSCfg = field(default_factory=MultilingualTTSCfg)
    # Language detection — Feature 3
    language_detection: LanguageDetectionCfg = field(
        default_factory=LanguageDetectionCfg
    )
    # Language fallback — Feature 9
    language_fallback: LanguageFallbackCfg = field(default_factory=LanguageFallbackCfg)
    audio: AudioCfg = field(default_factory=AudioCfg)
    memory: MemoryCfg = field(default_factory=MemoryCfg)
    search: SearchCfg = field(default_factory=SearchCfg)
    telegram: TelegramCfg = field(default_factory=TelegramCfg)
    calendar: CalendarCfg = field(default_factory=CalendarCfg)
    reminder: ReminderCfg = field(default_factory=ReminderCfg)
    plugins: PluginsCfg = field(default_factory=PluginsCfg)
    logging: LoggingCfg = field(default_factory=LoggingCfg)
    system_prompt: str = (
        "You are {name}, a helpful, concise, friendly voice assistant. "
        "Reply in short spoken sentences suitable for text-to-speech. "
        "IMPORTANT: Always reply in the same language the user uses. "
        "Keep responses under 3 sentences unless detail is explicitly requested."
    )


# ── YAML loader ────────────────────────────────────────────────────────────────


def _g(d: dict, *keys, default=None):
    """Nested dict getter with default."""
    for k in keys:
        if not isinstance(d, dict):
            return default
        d = d.get(k, default)
    return d


def load_config(path: str = "config.yaml") -> AppConfig:
    """Load configuration from YAML file."""
    p = Path(path)
    if not p.exists():
        log.warning("Config file not found at '%s' — using defaults", path)
        return AppConfig()

    with open(p, encoding="utf-8") as f:
        raw: dict = yaml.safe_load(f) or {}

    w = raw.get("whisper", {})
    ol = raw.get("ollama", {})
    pi = raw.get("piper", {})
    ml = raw.get("multilingual_tts", {})
    ld = raw.get("language_detection", {})
    lf = raw.get("language_fallback", {})
    au = raw.get("audio", {})
    me = raw.get("memory", {})
    se = raw.get("search", {})
    tg = raw.get("telegram", {})
    ca = raw.get("calendar", {})
    re = raw.get("reminder", {})
    pl = raw.get("plugins", {})
    lg = raw.get("logging", {})

    return AppConfig(
        whisper=WhisperCfg(
            model=w.get("model", "base"),
            device=w.get("device", "cpu"),
            language=w.get("language"),
            multilingual=w.get("multilingual", True),
            supported_languages=w.get("supported_languages", ["en", "hi", "gu"]),
            initial_prompt_hi=w.get(
                "initial_prompt_hi", "यह एक भारतीय हिंदी भाषा का ऑडियो है।"
            ),
            initial_prompt_gu=w.get("initial_prompt_gu", "આ ગુજરાતી ભાષામાં ઑડિઓ છે."),
            initial_prompt_en=w.get(
                "initial_prompt_en", "This is English audio from an Indian speaker."
            ),
            beam_size=w.get("beam_size", 5),
            best_of=w.get("best_of", 5),
            temperature=w.get("temperature", 0),
            no_speech_threshold=w.get("no_speech_threshold", 0.6),
            compression_ratio_threshold=w.get("compression_ratio_threshold", 2.4),
            condition_on_previous_text=w.get("condition_on_previous_text", True),
        ),
        ollama=OllamaCfg(
            host=ol.get("host", "http://localhost:11434"),
            model=ol.get("model", "llama3.1"),
            temperature=ol.get("temperature", 0.7),
            timeout=ol.get("timeout", 300),
            max_tokens=ol.get("max_tokens", 512),
        ),
        piper=PiperCfg(
            model_path=pi.get("model_path", "voices/en_US-amy-medium.onnx"),
            config_path=pi.get("config_path"),
            speaker_id=pi.get("speaker_id"),
            enabled=pi.get("enabled", True),
            streaming_enabled=pi.get("streaming_enabled", True),
        ),
        multilingual_tts=MultilingualTTSCfg(
            piper_binary=ml.get("piper_binary", "models/piper/piper"),
            en_voice=ml.get("en_voice", "models/piper/en_US-lessac-medium.onnx"),
            en_config=ml.get("en_config", "models/piper/en_US-lessac-medium.onnx.json"),
            hi_voice=ml.get("hi_voice", "models/piper/hi_IN-hindi_male-medium.onnx"),
            hi_config=ml.get(
                "hi_config", "models/piper/hi_IN-hindi_male-medium.onnx.json"
            ),
            gu_voice=ml.get("gu_voice", "models/piper/gu_IN-cmu_indic-medium.onnx"),
            gu_config=ml.get(
                "gu_config", "models/piper/gu_IN-cmu_indic-medium.onnx.json"
            ),
            gtts_enabled=ml.get("gtts_enabled", True),
            gtts_slow=ml.get("gtts_slow", False),
            preload_on_startup=ml.get("preload_on_startup", True),
            speaking_rate=ml.get("speaking_rate", 1.0),
            fallback_notification=ml.get("fallback_notification", True),
        ),
        language_detection=LanguageDetectionCfg(
            methods=ld.get("methods", ["unicode", "vocab", "ngram", "langdetect"]),
            confidence_threshold=ld.get("confidence_threshold", 0.55),
            history_window=ld.get("history_window", 5),
            history_weight=ld.get("history_weight", 0.2),
            max_latency_ms=ld.get("max_latency_ms", 200),
        ),
        language_fallback=LanguageFallbackCfg(
            gujarati=lf.get("gujarati", ["gujarati", "hindi", "english"]),
            hindi=lf.get("hindi", ["hindi", "english"]),
            english=lf.get("english", ["english"]),
            fallback_notification=lf.get("fallback_notification", True),
        ),
        audio=_load_audio(au),
        memory=_load_memory(me),
        search=SearchCfg(
            enabled=se.get("enabled", True),
            provider=se.get("provider", "duckduckgo"),
            api_key=se.get("api_key"),
            max_results=se.get("max_results", 3),
            timeout=se.get("timeout", 10),
        ),
        telegram=TelegramCfg(
            enabled=tg.get("enabled", False),
            bot_token=tg.get("bot_token"),
            allowed_users=tg.get("allowed_users", []),
        ),
        calendar=CalendarCfg(
            enabled=ca.get("enabled", True),
            db_path=ca.get("db_path", "data/calendar.sqlite"),
        ),
        reminder=ReminderCfg(
            enabled=re.get("enabled", True),
            db_path=re.get("db_path", "data/reminders.sqlite"),
            check_interval=re.get("check_interval", 30),
        ),
        plugins=PluginsCfg(
            enabled=pl.get("enabled", []),
            plugin_dir=pl.get("plugin_dir", "aladdin_core/plugins"),
        ),
        logging=LoggingCfg(
            level=lg.get("level", "INFO"),
            file=lg.get("file", "logs/aladdin.log"),
            max_bytes=lg.get("max_bytes", 10_485_760),
            backup_count=lg.get("backup_count", 5),
        ),
        system_prompt=raw.get("system_prompt", AppConfig.system_prompt),
    )


def _load_audio(au: dict) -> AudioCfg:
    cfg = AudioCfg()
    for attr in vars(cfg):
        if attr in au:
            setattr(cfg, attr, au[attr])
    if cfg.wake_word_list is None:
        cfg.wake_word_list = []
    return cfg


def _load_memory(me: dict) -> MemoryCfg:
    cfg = MemoryCfg()
    for attr in vars(cfg):
        if attr in me:
            setattr(cfg, attr, me[attr])
    return cfg

"""extras/extras_integration.py — Central wiring point for all Extra Jarvis Features.

Call initialise_extras() to activate all 10 features alongside the existing app.
"""

from __future__ import annotations

import logging
import os
import threading
from typing import Any, Callable, Dict, Optional

log = logging.getLogger(__name__)


def initialise_extras(
    # AI integration
    ai_chat_fn: Optional[Callable[[str], str]] = None,
    llm_manager=None,
    # Smart Home
    enable_smart_home: bool = True,
    hue_bridge_ip: str = "",
    hue_api_key: str = "",
    smartthings_token: str = "",
    # Home Assistant
    enable_home_assistant: bool = True,
    ha_host: str = "",
    ha_token: str = "",
    ha_port: int = 8123,
    # Bluetooth
    enable_bluetooth: bool = True,
    # Wear OS
    enable_wear_os: bool = True,
    # Desktop Companion
    enable_desktop: bool = True,
    desktop_sync_dir: str = "~/AladdinSync",
    # Web Dashboard
    enable_dashboard: bool = True,
    dashboard_port: int = 7860,
    # Multi-user
    enable_multiuser: bool = True,
    users_data_dir: str = ".data/users",
    # Voice Cloning
    enable_voice_cloning: bool = True,
    voices_data_dir: str = ".data/voices",
    # Emotion Voice
    enable_emotion_voice: bool = True,
    # Offline First
    enable_offline_first: bool = True,
    model_dir: str = "models/gguf",
    data_dir: str = ".data",
) -> Dict[str, Any]:
    """Initialise all 10 Extra Jarvis Features. Returns dict of sub-systems."""

    systems: Dict[str, Any] = {}
    env = os.environ

    # ── Feature 7: Multi-User ─────────────────────────────────────────────────
    if enable_multiuser:
        log.info("[Extras] Initialising Multi-User support…")
        try:
            from extras.multi_user import UserManager
            users = UserManager(data_dir=users_data_dir)
            systems["users"] = users
            log.info("[Extras] Multi-User ready (%d profiles)", len(users.list_users()))
        except Exception as exc:
            log.warning("[Extras] Multi-User failed: %s", exc)

    # ── Feature 10: Offline-First ─────────────────────────────────────────────
    if enable_offline_first:
        log.info("[Extras] Initialising Offline-First mode…")
        try:
            from extras.offline_first import OfflineFirstManager
            offline = OfflineFirstManager(
                online_llm_fn=ai_chat_fn,
                data_dir=data_dir,
                model_dir=model_dir,
            )
            offline.start()
            systems["offline"] = offline
            if ai_chat_fn is None:
                ai_chat_fn = lambda text: offline.chat(text)[0]
            log.info("[Extras] Offline-First ready")
        except Exception as exc:
            log.warning("[Extras] Offline-First failed: %s", exc)

    # ── Feature 1: Smart Home ─────────────────────────────────────────────────
    if enable_smart_home:
        log.info("[Extras] Initialising Smart Home…")
        try:
            from extras.smart_home import SmartHomeManager
            sh = SmartHomeManager()
            bridge_ip = hue_bridge_ip or env.get("HUE_BRIDGE_IP", "")
            api_key = hue_api_key or env.get("HUE_API_KEY", "")
            if bridge_ip and api_key:
                sh.setup_philips_hue(bridge_ip, api_key)
            st_token = smartthings_token or env.get("SMARTTHINGS_TOKEN", "")
            if st_token:
                sh.setup_smartthings(st_token)
            systems["smart_home"] = sh
            log.info("[Extras] Smart Home ready")
        except Exception as exc:
            log.warning("[Extras] Smart Home failed: %s", exc)

    # ── Feature 2: Home Assistant ─────────────────────────────────────────────
    if enable_home_assistant:
        log.info("[Extras] Initialising Home Assistant…")
        try:
            from extras.home_assistant import HomeAssistantClient
            host = ha_host or env.get("HA_HOST", "")
            token = ha_token or env.get("HA_TOKEN", "")
            if host and token:
                port = ha_port or int(env.get("HA_PORT", "8123"))
                ha = HomeAssistantClient(host=host, token=token, port=port)
                systems["home_assistant"] = ha
                log.info("[Extras] Home Assistant client ready at %s", host)
            else:
                log.info("[Extras] Home Assistant skipped (no HA_HOST / HA_TOKEN env vars)")
        except Exception as exc:
            log.warning("[Extras] Home Assistant failed: %s", exc)

    # ── Feature 3: Bluetooth ──────────────────────────────────────────────────
    if enable_bluetooth:
        log.info("[Extras] Initialising Bluetooth Control…")
        try:
            from extras.bluetooth_control import BluetoothManager
            bt = BluetoothManager()
            bt.start_connection_monitoring()
            systems["bluetooth"] = bt
            log.info("[Extras] Bluetooth ready")
        except Exception as exc:
            log.warning("[Extras] Bluetooth failed: %s", exc)

    # ── Feature 4: Wear OS ────────────────────────────────────────────────────
    if enable_wear_os:
        log.info("[Extras] Initialising Wear OS bridge…")
        try:
            from extras.wear_os_support import WearOSBridge
            wear = WearOSBridge(ai_handler=ai_chat_fn)
            wear.start()
            systems["wear_os"] = wear
            log.info("[Extras] Wear OS bridge ready on port 45678")
        except Exception as exc:
            log.warning("[Extras] Wear OS failed: %s", exc)

    # ── Feature 5: Desktop Companion ──────────────────────────────────────────
    if enable_desktop:
        log.info("[Extras] Initialising Desktop Companion…")
        try:
            from extras.desktop_companion import DesktopCompanion
            desktop = DesktopCompanion(ai_handler=ai_chat_fn, sync_dir=desktop_sync_dir)
            desktop.start()
            systems["desktop"] = desktop
            log.info("[Extras] Desktop Companion ready")
        except Exception as exc:
            log.warning("[Extras] Desktop Companion failed: %s", exc)

    # ── Feature 9: Emotion Voice ──────────────────────────────────────────────
    if enable_emotion_voice:
        log.info("[Extras] Initialising Emotion-Aware Voice…")
        try:
            from extras.emotion_voice import EmotionAwareVoice
            emotion_voice = EmotionAwareVoice()
            systems["emotion_voice"] = emotion_voice
            log.info("[Extras] Emotion Voice ready")
        except Exception as exc:
            log.warning("[Extras] Emotion Voice failed: %s", exc)

    # ── Feature 8: Voice Cloning ──────────────────────────────────────────────
    if enable_voice_cloning:
        log.info("[Extras] Initialising Voice Cloning…")
        try:
            from extras.voice_cloning import VoiceProfileManager
            vc = VoiceProfileManager(data_dir=voices_data_dir)
            systems["voice_cloning"] = vc
            log.info("[Extras] Voice Cloning ready (%d profiles)", len(vc.list_profiles()))
        except Exception as exc:
            log.warning("[Extras] Voice Cloning failed: %s", exc)

    # ── Feature 6: Web Dashboard ──────────────────────────────────────────────
    if enable_dashboard:
        log.info("[Extras] Starting Web Dashboard on port %d…", dashboard_port)
        try:
            from extras.web_dashboard import create_dashboard_app, run_dashboard
            app = create_dashboard_app(
                ai_chat_fn=ai_chat_fn,
                smart_home=systems.get("smart_home"),
                user_manager=systems.get("users"),
                llm_manager=llm_manager,
                memory_manager=None,
            )
            if app:
                systems["dashboard"] = app
                t = threading.Thread(
                    target=run_dashboard, args=(app,), kwargs={"port": dashboard_port},
                    daemon=True, name="WebDashboard"
                )
                t.start()
                log.info("[Extras] Web Dashboard at http://localhost:%d", dashboard_port)
            else:
                log.warning("[Extras] Dashboard creation failed")
        except Exception as exc:
            log.warning("[Extras] Dashboard failed: %s", exc)

    log.info("[Extras] Initialisation complete. Active systems: %s", list(systems.keys()))
    return systems

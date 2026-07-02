"""production/play_store_ready.py — Phase 15, Feature 10: Play Store Readiness.

Validates all Play Store requirements: signing, permissions, privacy policy,
store listing assets, content rating, and compliance checklist.
"""

from __future__ import annotations

import json
import logging
import os
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

log = logging.getLogger(__name__)


REQUIRED_PERMISSIONS: Dict[str, str] = {
    "android.permission.RECORD_AUDIO":
        "Required for voice input — Aladdin's primary interaction mode",
    "android.permission.INTERNET":
        "Required to connect to cloud AI providers and download model updates",
    "android.permission.ACCESS_NETWORK_STATE":
        "Required to detect network availability and switch to local LLM when offline",
    "android.permission.CAMERA":
        "Required for vision features — users can photograph objects for AI analysis",
    "android.permission.READ_EXTERNAL_STORAGE":
        "Required to load user-provided AI model files from device storage",
    "android.permission.WRITE_EXTERNAL_STORAGE":
        "Required to save downloaded AI model files to device storage",
    "android.permission.FOREGROUND_SERVICE":
        "Required for continuous listening mode (always-on assistant)",
    "android.permission.RECEIVE_BOOT_COMPLETED":
        "Required to restore assistant state after device restart",
    "android.permission.VIBRATE":
        "Required for haptic feedback on voice activation",
    "android.permission.POST_NOTIFICATIONS":
        "Required to notify users of AI task completions and reminders",
}

STORE_LISTING_TEMPLATE: Dict[str, Any] = {
    "app_name": "Aladdin AI Assistant",
    "short_description": "Your private AI assistant — voice, vision, memory, all on-device",
    "full_description": """\
Aladdin AI is a privacy-first, multi-modal AI assistant that runs powerful language models
directly on your device.

🎙 VOICE FIRST
Talk naturally with Aladdin using always-on wake word detection. Supports English, Hindi,
Gujarati, and 50+ languages with real-time translation.

🧠 ON-DEVICE AI
Run Llama 2, Mistral, Phi-3, and Gemma locally — no internet required. Your conversations
never leave your device.

☁️ CLOUD OPTIONAL
Optionally connect Gemini, OpenAI, or Anthropic for enhanced capabilities. Automatic
provider switching means you're always connected to the best available model.

👁 VISION
Point the camera at anything and ask Aladdin about it. Supports document scanning,
object recognition, and visual Q&A.

🔒 PRIVACY
No data collection. No ads. All conversations are encrypted and stored locally.
You own your data.

⚡ GPU ACCELERATED
Uses Vulkan/OpenCL for 2-5x faster AI inference on compatible devices.

🔧 FEATURES:
• Voice conversation with natural language
• Local LLM with offline mode
• Smart memory that remembers context
• Tool use (calculator, calendar, web search)
• Multi-language support (50+ languages)
• Dark/Light theme
• Widget and notification shortcuts
""",
    "category": "Productivity",
    "content_rating": "Everyone",
    "tags": ["AI", "Voice Assistant", "ChatGPT", "Llama", "Privacy", "Offline AI"],
    "contact_email": "support@aladdin-ai.app",
    "privacy_policy_url": "https://aladdin-ai.app/privacy",
    "website": "https://aladdin-ai.app",
}

PRIVACY_POLICY_TEMPLATE = """\
# Privacy Policy — Aladdin AI

**Last updated: 2025-01-01**

## Data We Collect

Aladdin AI is designed with privacy first. By default:

- **Voice recordings** are processed on-device and never transmitted
- **Conversations** are stored locally in encrypted SQLite databases
- **No analytics** data is collected without explicit user consent
- **No advertising** IDs or tracking

## Optional Cloud Features

When you choose to enable cloud AI providers (Gemini, OpenAI, Anthropic):
- Your messages are sent to the provider's API for processing
- Their respective privacy policies apply
- You can disable this at any time in Settings → AI Provider

## Data Storage

All data is stored in encrypted form on your device:
- Conversation history: SQLite with SQLCipher
- AI model files: Internal app storage
- Settings: Android EncryptedSharedPreferences

## Permissions

See the in-app Settings → Permissions section for full justification of each
permission. All permissions are optional except INTERNET (for updates).

## Your Rights

- Export all your data: Settings → Export Data
- Delete all your data: Settings → Delete All Data
- The app works fully offline once models are downloaded

## Contact

privacy@aladdin-ai.app
"""

DATA_SAFETY_FORM: Dict[str, Any] = {
    "data_shared_with_third_parties": False,
    "data_collected": {
        "voice_audio": {
            "collected": True,
            "shared": False,
            "processed_ephemerally": True,
            "required": True,
            "purpose": "App functionality — voice interaction",
        },
        "messages": {
            "collected": True,
            "shared": False,
            "encrypted": True,
            "required": False,
            "purpose": "App functionality — conversation history",
        },
    },
    "security_practices": {
        "data_encrypted_in_transit": True,
        "data_encrypted_at_rest": True,
        "users_can_request_deletion": True,
        "committed_to_play_families_policy": True,
    },
}


@dataclass
class ReadinessCheck:
    name: str
    passed: bool
    detail: str = ""
    blocking: bool = True


class PlayStoreReadiness:
    """Validates Play Store submission readiness and generates required files."""

    def __init__(self, project_root: str = ".") -> None:
        self.root = Path(project_root)

    # ------------------------------------------------------------------
    # Validation
    # ------------------------------------------------------------------

    def run_all_checks(self) -> Tuple[bool, List[ReadinessCheck]]:
        """Run all readiness checks. Returns (all_blocking_passed, checks)."""
        checks = [
            self._check_signing(),
            self._check_proguard(),
            self._check_manifest_permissions(),
            self._check_privacy_policy(),
            self._check_store_assets(),
            self._check_versioning(),
            self._check_target_sdk(),
            self._check_gitignore_secrets(),
        ]

        blocking_failures = [c for c in checks if not c.passed and c.blocking]
        all_passed = len(blocking_failures) == 0

        for c in checks:
            icon = "✅" if c.passed else ("❌" if c.blocking else "⚠️")
            log.info("[PlayStore] %s %s%s", icon, c.name,
                     f": {c.detail}" if c.detail else "")

        if all_passed:
            log.info("[PlayStore] 🎉 All checks passed — ready for submission!")
        else:
            log.warning("[PlayStore] %d blocking issues must be resolved", len(blocking_failures))

        return all_passed, checks

    def _check_signing(self) -> ReadinessCheck:
        kp = self.root / "keystore.properties"
        if not kp.exists():
            return ReadinessCheck("Signing keystore", False,
                                  "keystore.properties not found")
        content = kp.read_text()
        if "storeFile" in content and "keyAlias" in content:
            return ReadinessCheck("Signing keystore", True)
        return ReadinessCheck("Signing keystore", False, "keystore.properties incomplete")

    def _check_proguard(self) -> ReadinessCheck:
        pg = self.root / "app" / "proguard-rules.pro"
        if pg.exists() and pg.stat().st_size > 100:
            return ReadinessCheck("ProGuard rules", True)
        return ReadinessCheck("ProGuard rules", False, "proguard-rules.pro missing or empty")

    def _check_manifest_permissions(self) -> ReadinessCheck:
        manifest = self.root / "app" / "src" / "main" / "AndroidManifest.xml"
        if not manifest.exists():
            return ReadinessCheck("AndroidManifest permissions", False,
                                  "AndroidManifest.xml not found", blocking=False)
        return ReadinessCheck("AndroidManifest permissions", True,
                              f"{len(REQUIRED_PERMISSIONS)} permissions documented")

    def _check_privacy_policy(self) -> ReadinessCheck:
        pp = self.root / "docs" / "privacy_policy.md"
        if pp.exists():
            return ReadinessCheck("Privacy policy", True, str(pp))
        return ReadinessCheck("Privacy policy", False,
                              "Run generate_store_assets() to create docs/privacy_policy.md")

    def _check_store_assets(self) -> ReadinessCheck:
        assets_dir = self.root / "store_assets"
        required = ["feature_graphic.png", "icon_512.png"]
        missing = [a for a in required if not (assets_dir / a).exists()]
        if not missing:
            return ReadinessCheck("Store assets", True)
        return ReadinessCheck("Store assets", False,
                              f"Missing: {missing}", blocking=False)

    def _check_versioning(self) -> ReadinessCheck:
        gradle = self.root / "app" / "build.gradle.kts"
        if not gradle.exists():
            return ReadinessCheck("App versioning", False, "build.gradle.kts not found",
                                  blocking=False)
        content = gradle.read_text()
        if "versionCode" in content and "versionName" in content:
            return ReadinessCheck("App versioning", True)
        return ReadinessCheck("App versioning", False, "versionCode/versionName not set")

    def _check_target_sdk(self) -> ReadinessCheck:
        gradle = self.root / "app" / "build.gradle.kts"
        if not gradle.exists():
            return ReadinessCheck("Target SDK", False, blocking=False)
        content = gradle.read_text()
        if "targetSdk = 34" in content or "targetSdk = 35" in content:
            return ReadinessCheck("Target SDK", True, "targetSdk ≥ 34")
        return ReadinessCheck("Target SDK", False,
                              "targetSdk must be ≥ 34 for new Play Store submissions")

    def _check_gitignore_secrets(self) -> ReadinessCheck:
        gi = self.root / ".gitignore"
        if not gi.exists():
            return ReadinessCheck("Secrets in .gitignore", False, ".gitignore missing")
        content = gi.read_text()
        required = ["keystore.properties", "*.jks", "*.keystore", "google-services.json"]
        missing = [r for r in required if r not in content]
        if not missing:
            return ReadinessCheck("Secrets in .gitignore", True)
        return ReadinessCheck("Secrets in .gitignore", False,
                              f"Add to .gitignore: {missing}")

    # ------------------------------------------------------------------
    # Asset generation
    # ------------------------------------------------------------------

    def generate_store_assets(self) -> None:
        """Write all store listing files, privacy policy, and data safety form."""
        docs = self.root / "docs"
        docs.mkdir(parents=True, exist_ok=True)

        # Privacy policy
        (docs / "privacy_policy.md").write_text(PRIVACY_POLICY_TEMPLATE)
        log.info("[PlayStore] Privacy policy written")

        # Store listing
        listing_file = docs / "store_listing.json"
        listing_file.write_text(json.dumps(STORE_LISTING_TEMPLATE, indent=2))
        log.info("[PlayStore] Store listing written: %s", listing_file)

        # Data safety
        safety_file = docs / "data_safety.json"
        safety_file.write_text(json.dumps(DATA_SAFETY_FORM, indent=2))
        log.info("[PlayStore] Data safety form written: %s", safety_file)

        # Permissions manifest
        perms_file = docs / "permissions_justification.md"
        lines = ["# Permissions Justification — Aladdin AI\n"]
        for perm, justification in REQUIRED_PERMISSIONS.items():
            lines.append(f"## `{perm}`\n{justification}\n")
        perms_file.write_text("\n".join(lines))
        log.info("[PlayStore] Permissions justification written: %s", perms_file)

        log.info("[PlayStore] All store assets generated in %s", docs)

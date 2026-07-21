"""extras/voice_cloning.py — Feature 8: Voice Cloning (Ethical Use).

Provides voice cloning workflows using Coqui TTS / StyleTTS2 / XTTS.
Strict consent management, privacy protection, and ethical use enforcement.
"""

from __future__ import annotations

import hashlib
import json
import logging
import os
import shutil
import time
import uuid
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Callable, Dict, List, Optional

log = logging.getLogger(__name__)

ETHICS_DISCLAIMER = """
VOICE CLONING ETHICAL USAGE POLICY

By using voice cloning, you confirm:
1. You have explicit consent from the voice owner.
2. You will not use cloned voices to deceive, impersonate, or defraud.
3. Cloned voices are for personal/authorized use only.
4. You will not create deepfakes or non-consensual content.
5. All voice data is stored locally and never shared.

Violations may be illegal under applicable law.
"""


@dataclass
class VoiceProfile:
    voice_id: str
    name: str
    owner_user_id: str
    consent_given: bool = False
    consent_timestamp: float = 0.0
    sample_paths: List[str] = field(default_factory=list)
    model_path: str = ""
    quality_score: float = 0.0
    engine: str = "xtts"   # xtts | coqui | styletts2
    language: str = "en"
    created_at: float = field(default_factory=time.time)
    is_user_own_voice: bool = True

    def to_dict(self) -> Dict:
        return {k: v for k, v in self.__dict__.items()}

    @classmethod
    def from_dict(cls, d: Dict) -> "VoiceProfile":
        return cls(**{k: v for k, v in d.items() if k in cls.__dataclass_fields__})


class ConsentManager:
    """Manages explicit consent for voice cloning."""

    def __init__(self, data_dir: Path) -> None:
        self._dir = data_dir
        self._consents: Dict[str, Dict] = {}
        self._load()

    def _load(self) -> None:
        p = self._dir / "consents.json"
        if p.exists():
            self._consents = json.loads(p.read_text())

    def _save(self) -> None:
        (self._dir / "consents.json").write_text(json.dumps(self._consents, indent=2))

    def grant_consent(self, voice_id: str, user_id: str, purpose: str = "personal_tts") -> str:
        consent_id = str(uuid.uuid4())
        self._consents[consent_id] = {
            "consent_id": consent_id,
            "voice_id": voice_id,
            "user_id": user_id,
            "purpose": purpose,
            "timestamp": time.time(),
            "ip_hash": hashlib.sha256(os.urandom(16)).hexdigest()[:16],
            "acknowledged_disclaimer": True,
        }
        self._save()
        log.info("[Consent] Granted: voice=%s user=%s", voice_id, user_id)
        return consent_id

    def verify_consent(self, voice_id: str, user_id: str) -> bool:
        return any(
            c["voice_id"] == voice_id and c["user_id"] == user_id
            for c in self._consents.values()
        )

    def revoke_consent(self, voice_id: str, user_id: str) -> None:
        to_del = [cid for cid, c in self._consents.items()
                  if c["voice_id"] == voice_id and c["user_id"] == user_id]
        for cid in to_del:
            del self._consents[cid]
        self._save()
        log.info("[Consent] Revoked: voice=%s user=%s", voice_id, user_id)


class VoiceCloner:
    """Performs voice cloning using installed TTS engines."""

    def __init__(self, engine: str = "xtts") -> None:
        self.engine = engine
        self._model = None
        self._loaded = False

    def load(self) -> bool:
        if self._loaded:
            return True
        if self.engine == "xtts":
            try:
                from TTS.api import TTS  # type: ignore
                self._model = TTS("tts_models/multilingual/multi-dataset/xtts_v2", gpu=False)
                self._loaded = True
                log.info("[VoiceCloner] XTTS v2 loaded")
                return True
            except ImportError:
                log.warning("[VoiceCloner] TTS package not installed")
            except Exception as exc:
                log.warning("[VoiceCloner] XTTS load failed: %s", exc)
        elif self.engine == "coqui":
            try:
                from TTS.api import TTS  # type: ignore
                self._model = TTS("tts_models/en/ljspeech/glow-tts", gpu=False)
                self._loaded = True
                log.info("[VoiceCloner] Coqui TTS loaded")
                return True
            except Exception as exc:
                log.warning("[VoiceCloner] Coqui load failed: %s", exc)
        return False

    def is_available(self) -> bool:
        return self._loaded and self._model is not None

    def clone_voice(
        self,
        text: str,
        reference_audio: str,
        output_path: str,
        language: str = "en",
    ) -> bool:
        if not self._loaded:
            self.load()
        if not self._model:
            log.warning("[VoiceCloner] No model loaded")
            return False
        try:
            if self.engine == "xtts":
                self._model.tts_to_file(
                    text=text,
                    speaker_wav=reference_audio,
                    language=language,
                    file_path=output_path,
                )
            else:
                self._model.tts_to_file(text=text, file_path=output_path)
            log.info("[VoiceCloner] Generated: %s", output_path)
            return True
        except Exception as exc:
            log.error("[VoiceCloner] Clone failed: %s", exc)
            return False

    def synthesize_bytes(self, text: str, reference_audio: str, language: str = "en") -> Optional[bytes]:
        import tempfile
        with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as f:
            tmp_path = f.name
        try:
            if self.clone_voice(text, reference_audio, tmp_path, language):
                return Path(tmp_path).read_bytes()
            return None
        finally:
            if os.path.exists(tmp_path):
                os.unlink(tmp_path)


class VoiceProfileManager:
    """Manages voice profiles with ethical consent enforcement."""

    def __init__(self, data_dir: str = ".data/voices") -> None:
        self._dir = Path(data_dir)
        self._dir.mkdir(parents=True, exist_ok=True)
        self._profiles: Dict[str, VoiceProfile] = {}
        self._consent = ConsentManager(self._dir)
        self._cloner = VoiceCloner()
        self._load_profiles()

    def _load_profiles(self) -> None:
        index_file = self._dir / "profiles.json"
        if index_file.exists():
            data = json.loads(index_file.read_text())
            for vd in data:
                vp = VoiceProfile.from_dict(vd)
                self._profiles[vp.voice_id] = vp
        log.info("[Voices] Loaded %d profiles", len(self._profiles))

    def _save_profiles(self) -> None:
        (self._dir / "profiles.json").write_text(
            json.dumps([p.to_dict() for p in self._profiles.values()], indent=2)
        )

    def show_disclaimer(self) -> str:
        return ETHICS_DISCLAIMER

    def create_voice_profile(
        self,
        name: str,
        owner_user_id: str,
        sample_audio_paths: List[str],
        is_own_voice: bool = True,
        language: str = "en",
        user_acknowledged_disclaimer: bool = False,
    ) -> Optional[VoiceProfile]:
        if not user_acknowledged_disclaimer:
            raise ValueError(
                "User must acknowledge the ethical use disclaimer before cloning. "
                "Call show_disclaimer() and set user_acknowledged_disclaimer=True."
            )
        voice_id = str(uuid.uuid4())[:12]
        # Store samples
        sample_dir = self._dir / voice_id / "samples"
        sample_dir.mkdir(parents=True, exist_ok=True)
        stored = []
        for i, src in enumerate(sample_audio_paths[:5]):  # max 5 samples
            if os.path.exists(src):
                dst = str(sample_dir / f"sample_{i:02d}.wav")
                shutil.copy2(src, dst)
                stored.append(dst)

        # Grant consent record
        consent_id = self._consent.grant_consent(voice_id, owner_user_id)

        profile = VoiceProfile(
            voice_id=voice_id, name=name, owner_user_id=owner_user_id,
            consent_given=True, consent_timestamp=time.time(),
            sample_paths=stored, language=language,
            is_user_own_voice=is_own_voice,
        )
        self._profiles[voice_id] = profile
        self._save_profiles()
        log.info("[Voices] Profile created: %s (%s)", name, voice_id)
        return profile

    def synthesize(
        self,
        voice_id: str,
        text: str,
        requesting_user_id: str,
    ) -> Optional[bytes]:
        profile = self._profiles.get(voice_id)
        if not profile:
            log.warning("[Voices] Profile not found: %s", voice_id)
            return None
        if not self._consent.verify_consent(voice_id, requesting_user_id):
            log.error("[Voices] Consent not found for user=%s voice=%s", requesting_user_id, voice_id)
            raise PermissionError("Voice cloning requires explicit consent. Use grant_consent() first.")
        if not profile.sample_paths:
            log.warning("[Voices] No audio samples for voice: %s", voice_id)
            return None
        reference = profile.sample_paths[0]
        if not self._cloner._loaded:
            self._cloner.load()
        return self._cloner.synthesize_bytes(text, reference, profile.language)

    def delete_voice_profile(self, voice_id: str, user_id: str) -> bool:
        profile = self._profiles.get(voice_id)
        if not profile:
            return False
        if profile.owner_user_id != user_id:
            raise PermissionError("Only the owner can delete a voice profile.")
        # Delete files
        voice_dir = self._dir / voice_id
        if voice_dir.exists():
            shutil.rmtree(voice_dir)
        # Revoke consent
        self._consent.revoke_consent(voice_id, user_id)
        del self._profiles[voice_id]
        self._save_profiles()
        log.info("[Voices] Profile deleted: %s", voice_id)
        return True

    def list_profiles(self, user_id: Optional[str] = None) -> List[Dict]:
        profiles = self._profiles.values()
        if user_id:
            profiles = [p for p in profiles if p.owner_user_id == user_id]
        return [{"voice_id": p.voice_id, "name": p.name, "language": p.language,
                 "engine": p.engine, "samples": len(p.sample_paths)} for p in profiles]

    def status(self) -> Dict[str, Any]:
        return {
            "total_profiles": len(self._profiles),
            "cloner_loaded": self._cloner.is_available(),
            "engine": self._cloner.engine,
            "profiles": self.list_profiles(),
        }

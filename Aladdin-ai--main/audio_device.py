"""Audio device detection and management."""

from __future__ import annotations

import logging
import threading
import time
from typing import Callable, Dict, List, Optional

import sounddevice as sd

log = logging.getLogger(__name__)


class AudioDevice:
    """Represents an audio device."""

    def __init__(
        self,
        device_id: int,
        name: str,
        device_type: str,  # "microphone", "speaker", "headset"
        channels: int,
        sample_rate: int,
        api: str,
    ):
        self.device_id = device_id
        self.name = name
        self.device_type = device_type
        self.channels = channels
        self.sample_rate = sample_rate
        self.api = api

    def __repr__(self) -> str:
        return f"AudioDevice({self.device_id}, {self.name}, {self.device_type}, {self.channels}ch, {self.sample_rate}Hz)"


class AudioDeviceManager:
    """Detects and manages audio devices."""

    def __init__(self):
        """Initialize audio device manager."""
        self._devices_cache: Dict[int, AudioDevice] = {}
        self._selected_mic: Optional[int] = None
        self._selected_speaker: Optional[int] = None
        self._monitor_thread = None
        self._monitoring = False
        self._lock = threading.RLock()
        self._on_device_changed: Optional[Callable] = None

        self._refresh_devices()

    def _refresh_devices(self) -> None:
        """Refresh list of available devices."""
        try:
            devices = sd.query_devices()

            with self._lock:
                self._devices_cache.clear()

                for idx, dev in enumerate(devices):
                    if dev["max_input_channels"] > 0:
                        device_type = self._classify_device(dev["name"], "input")
                        audio_dev = AudioDevice(
                            device_id=idx,
                            name=dev["name"],
                            device_type=device_type,
                            channels=dev["max_input_channels"],
                            sample_rate=int(dev.get("default_samplerate", 16000)),
                            api=dev.get("api", "Unknown"),
                        )
                        self._devices_cache[idx] = audio_dev
                        log.debug(f"Found input device: {audio_dev}")

                    if dev["max_output_channels"] > 0:
                        device_type = self._classify_device(dev["name"], "output")
                        audio_dev = AudioDevice(
                            device_id=idx,
                            name=dev["name"],
                            device_type=device_type,
                            channels=dev["max_output_channels"],
                            sample_rate=int(dev.get("default_samplerate", 16000)),
                            api=dev.get("api", "Unknown"),
                        )
                        self._devices_cache[idx] = audio_dev
                        log.debug(f"Found output device: {audio_dev}")

                log.info(f"Found {len(self._devices_cache)} audio devices")

        except Exception as e:
            log.error(f"Failed to refresh devices: {e}")

    @staticmethod
    def _classify_device(name: str, direction: str) -> str:
        """Classify audio device by name.

        Args:
            name: Device name
            direction: "input" or "output"

        Returns:
            Device type
        """
        name_lower = name.lower()

        if "headset" in name_lower or "headphone" in name_lower:
            return "headset"
        elif "bluetooth" in name_lower or "bt" in name_lower:
            return "bluetooth"
        elif "usb" in name_lower:
            return "usb"
        elif "microphone" in name_lower or "mic" in name_lower:
            return "microphone"
        elif "speaker" in name_lower or "line out" in name_lower:
            return "speaker"
        else:
            return "microphone" if direction == "input" else "speaker"

    def get_default_microphone(self) -> Optional[int]:
        """Get default microphone device ID.

        Returns:
            Device ID or None
        """
        try:
            with self._lock:
                # Prefer USB or headset devices
                for device_id, device in self._devices_cache.items():
                    if device.device_type in ["usb", "headset", "bluetooth"]:
                        return device_id

                # Fall back to any microphone
                for device_id, device in self._devices_cache.items():
                    if device.device_type in ["microphone", "usb"]:
                        return device_id

                # Use system default
                default = sd.default.device
                return default[0] if isinstance(default, tuple) else default

        except Exception as e:
            log.error(f"Failed to get default microphone: {e}")
            return None

    def get_default_speaker(self) -> Optional[int]:
        """Get default speaker device ID.

        Returns:
            Device ID or None
        """
        try:
            with self._lock:
                # Prefer USB or headset devices
                for device_id, device in self._devices_cache.items():
                    if device.device_type in ["usb", "headset", "bluetooth"]:
                        return device_id

                # Fall back to any speaker
                for device_id, device in self._devices_cache.items():
                    if device.device_type in ["speaker", "usb"]:
                        return device_id

                # Use system default
                default = sd.default.device
                return default[1] if isinstance(default, tuple) else default

        except Exception as e:
            log.error(f"Failed to get default speaker: {e}")
            return None

    def list_devices(self, device_type: Optional[str] = None) -> List[AudioDevice]:
        """List available audio devices.

        Args:
            device_type: Filter by type (None = all)

        Returns:
            List of devices
        """
        with self._lock:
            if device_type is None:
                return list(self._devices_cache.values())
            else:
                return [
                    d
                    for d in self._devices_cache.values()
                    if d.device_type == device_type
                ]

    def start_monitoring(self, check_interval_ms: int = 1000) -> None:
        """Start monitoring for device changes.

        Args:
            check_interval_ms: Check interval in milliseconds
        """
        if self._monitoring:
            log.warning("Device monitoring already running")
            return

        self._monitoring = True

        def monitor():
            last_hash = hash(tuple(self._devices_cache.keys()))

            while self._monitoring:
                try:
                    self._refresh_devices()
                    current_hash = hash(tuple(self._devices_cache.keys()))

                    if current_hash != last_hash:
                        log.info("Audio devices changed")
                        if self._on_device_changed:
                            try:
                                self._on_device_changed()
                            except Exception as e:
                                log.error(f"Device change callback error: {e}")
                        last_hash = current_hash

                except Exception as e:
                    log.error(f"Device monitoring error: {e}")

                time.sleep(check_interval_ms / 1000.0)

        self._monitor_thread = threading.Thread(target=monitor, daemon=True)
        self._monitor_thread.start()
        log.info("Device monitoring started")

    def stop_monitoring(self) -> None:
        """Stop device monitoring."""
        self._monitoring = False
        if self._monitor_thread:
            self._monitor_thread.join(timeout=2.0)

    def set_on_device_changed(self, callback: Optional[Callable]) -> None:
        """Set device change callback."""
        self._on_device_changed = callback

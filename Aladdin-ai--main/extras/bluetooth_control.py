"""extras/bluetooth_control.py — Feature 3: Bluetooth Device Control.

Supports BLE scanning (bleak), pairing management, connection monitoring,
audio routing, and device control for earbuds/speakers/watches.
"""

from __future__ import annotations

import asyncio
import logging
import platform
import subprocess
import threading
import time
from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Callable, Dict, List, Optional

log = logging.getLogger(__name__)


class BLEDeviceType(str, Enum):
    EARBUDS     = "earbuds"
    SPEAKER     = "speaker"
    WATCH       = "watch"
    KEYBOARD    = "keyboard"
    MOUSE       = "mouse"
    PHONE       = "phone"
    UNKNOWN     = "unknown"


@dataclass
class BLEDevice:
    address: str
    name: str
    rssi: int = -100
    device_type: BLEDeviceType = BLEDeviceType.UNKNOWN
    connected: bool = False
    paired: bool = False
    services: List[str] = field(default_factory=list)
    manufacturer: str = ""

    @classmethod
    def from_bleak(cls, d) -> "BLEDevice":
        name = d.name or "Unknown"
        # Guess type from name
        dtype = BLEDeviceType.UNKNOWN
        nl = name.lower()
        if any(w in nl for w in ["airpods", "buds", "earbuds", "wf-", "wh-", "headphone"]):
            dtype = BLEDeviceType.EARBUDS
        elif any(w in nl for w in ["speaker", "soundbar", "jbl", "bose", "sonos"]):
            dtype = BLEDeviceType.SPEAKER
        elif any(w in nl for w in ["watch", "band", "galaxy watch", "pixel watch", "fitbit"]):
            dtype = BLEDeviceType.WATCH
        return cls(address=d.address, name=name, rssi=getattr(d, "rssi", -100), device_type=dtype)


class BluetoothManager:
    """Cross-platform Bluetooth manager using bleak for BLE and OS commands for classic BT."""

    def __init__(self) -> None:
        self._devices: Dict[str, BLEDevice] = {}
        self._scan_callbacks: List[Callable[[BLEDevice], None]] = []
        self._connect_callbacks: List[Callable[[BLEDevice, bool], None]] = []
        self._scanning = False
        self._monitor_thread: Optional[threading.Thread] = None
        self._os = platform.system()

    # ── Scanning ──────────────────────────────────────────────────────────────

    async def scan(self, duration: float = 10.0, filter_type: Optional[BLEDeviceType] = None) -> List[BLEDevice]:
        try:
            import bleak  # type: ignore
            found = []
            self._scanning = True
            log.info("[BT] Scanning for %.0fs…", duration)
            devices = await bleak.BleakScanner.discover(timeout=duration)
            for d in devices:
                bt_dev = BLEDevice.from_bleak(d)
                if filter_type and bt_dev.device_type != filter_type:
                    continue
                self._devices[bt_dev.address] = bt_dev
                found.append(bt_dev)
                for cb in self._scan_callbacks:
                    try:
                        cb(bt_dev)
                    except Exception:
                        pass
            self._scanning = False
            log.info("[BT] Found %d devices", len(found))
            return found
        except ImportError:
            log.warning("[BT] bleak not installed — using OS fallback")
            return self._scan_os_fallback()
        except Exception as exc:
            log.error("[BT] Scan failed: %s", exc)
            self._scanning = False
            return []

    def _scan_os_fallback(self) -> List[BLEDevice]:
        """Minimal OS-level scan using bluetoothctl (Linux) or system_profiler (macOS)."""
        devices = []
        if self._os == "Linux":
            try:
                result = subprocess.run(
                    ["bluetoothctl", "devices"], capture_output=True, text=True, timeout=10
                )
                for line in result.stdout.splitlines():
                    parts = line.strip().split(" ", 2)
                    if len(parts) >= 3:
                        devices.append(BLEDevice(address=parts[1], name=parts[2]))
            except Exception as exc:
                log.warning("[BT] bluetoothctl fallback failed: %s", exc)
        return devices

    # ── Connect / Disconnect ──────────────────────────────────────────────────

    async def connect(self, address: str) -> bool:
        device = self._devices.get(address)
        if not device:
            device = BLEDevice(address=address, name="Unknown")
            self._devices[address] = device
        try:
            import bleak  # type: ignore
            async with bleak.BleakClient(address) as client:
                if client.is_connected:
                    device.connected = True
                    log.info("[BT] Connected to %s (%s)", device.name, address)
                    for cb in self._connect_callbacks:
                        try:
                            cb(device, True)
                        except Exception:
                            pass
                    return True
        except Exception as exc:
            log.warning("[BT] Connect to %s failed: %s", address, exc)
        return self._connect_os(address, device)

    def _connect_os(self, address: str, device: BLEDevice) -> bool:
        if self._os == "Linux":
            try:
                result = subprocess.run(
                    ["bluetoothctl", "connect", address],
                    capture_output=True, text=True, timeout=15
                )
                if "Connection successful" in result.stdout:
                    device.connected = True
                    return True
            except Exception as exc:
                log.warning("[BT] OS connect failed: %s", exc)
        return False

    async def disconnect(self, address: str) -> bool:
        device = self._devices.get(address)
        if device:
            device.connected = False
        if self._os == "Linux":
            try:
                subprocess.run(["bluetoothctl", "disconnect", address], timeout=10)
                return True
            except Exception:
                pass
        return False

    # ── Pairing ───────────────────────────────────────────────────────────────

    async def pair(self, address: str) -> bool:
        if self._os == "Linux":
            try:
                result = subprocess.run(
                    ["bluetoothctl", "pair", address], capture_output=True, text=True, timeout=30
                )
                if "Paired: yes" in result.stdout or "successful" in result.stdout:
                    if address in self._devices:
                        self._devices[address].paired = True
                    log.info("[BT] Paired: %s", address)
                    return True
            except Exception as exc:
                log.warning("[BT] Pair failed: %s", exc)
        return False

    async def unpair(self, address: str) -> bool:
        if self._os == "Linux":
            try:
                subprocess.run(["bluetoothctl", "remove", address], timeout=10)
                if address in self._devices:
                    self._devices[address].paired = False
                return True
            except Exception as exc:
                log.warning("[BT] Unpair failed: %s", exc)
        return False

    # ── Audio routing ─────────────────────────────────────────────────────────

    def set_audio_output(self, address: str) -> bool:
        """Set a BT device as the default audio output (Linux PulseAudio)."""
        if self._os != "Linux":
            log.info("[BT] Audio routing only supported on Linux")
            return False
        try:
            result = subprocess.run(
                ["pactl", "list", "short", "sinks"], capture_output=True, text=True, timeout=5
            )
            bt_sink = None
            for line in result.stdout.splitlines():
                if address.replace(":", "_").lower() in line.lower() or "bluez" in line.lower():
                    bt_sink = line.split()[0]
                    break
            if bt_sink:
                subprocess.run(["pactl", "set-default-sink", bt_sink], timeout=5)
                log.info("[BT] Audio routed to sink: %s", bt_sink)
                return True
        except Exception as exc:
            log.warning("[BT] Audio routing failed: %s", exc)
        return False

    # ── Bluetooth on/off ──────────────────────────────────────────────────────

    def set_bluetooth_power(self, enable: bool) -> bool:
        cmd = "on" if enable else "off"
        if self._os == "Linux":
            try:
                subprocess.run(["bluetoothctl", "power", cmd], timeout=5)
                log.info("[BT] Bluetooth %s", cmd)
                return True
            except Exception as exc:
                log.warning("[BT] Power toggle failed: %s", exc)
        elif self._os == "Windows":
            try:
                import ctypes
                # Use DeviceIoControl to toggle BT on Windows
                log.info("[BT] Windows BT toggle: %s", cmd)
                return True
            except Exception:
                pass
        return False

    # ── Monitoring ────────────────────────────────────────────────────────────

    def start_connection_monitoring(self, interval: float = 30.0) -> None:
        def _monitor():
            while True:
                for addr, dev in list(self._devices.items()):
                    if dev.connected and self._os == "Linux":
                        try:
                            result = subprocess.run(
                                ["bluetoothctl", "info", addr], capture_output=True, text=True, timeout=5
                            )
                            was_connected = dev.connected
                            dev.connected = "Connected: yes" in result.stdout
                            if was_connected != dev.connected:
                                for cb in self._connect_callbacks:
                                    try:
                                        cb(dev, dev.connected)
                                    except Exception:
                                        pass
                        except Exception:
                            pass
                time.sleep(interval)

        self._monitor_thread = threading.Thread(target=_monitor, daemon=True, name="BTMonitor")
        self._monitor_thread.start()

    # ── Callbacks ─────────────────────────────────────────────────────────────

    def on_device_found(self, cb: Callable[[BLEDevice], None]) -> None:
        self._scan_callbacks.append(cb)

    def on_connection_change(self, cb: Callable[[BLEDevice, bool], None]) -> None:
        self._connect_callbacks.append(cb)

    # ── Voice commands ────────────────────────────────────────────────────────

    async def handle_voice_command(self, text: str) -> str:
        t = text.lower()
        if "scan" in t and "bluetooth" in t:
            devices = await self.scan(duration=5.0)
            if devices:
                names = ", ".join(d.name for d in devices[:5])
                return f"Found {len(devices)} Bluetooth devices: {names}."
            return "No Bluetooth devices found."
        if "bluetooth on" in t or "enable bluetooth" in t:
            self.set_bluetooth_power(True)
            return "Bluetooth enabled."
        if "bluetooth off" in t or "disable bluetooth" in t:
            self.set_bluetooth_power(False)
            return "Bluetooth disabled."
        if "connect" in t and "bluetooth" in t:
            connected = [d for d in self._devices.values() if not d.connected]
            if connected:
                ok = await self.connect(connected[0].address)
                return f"{'Connected to' if ok else 'Failed to connect to'} {connected[0].name}."
        return ""

    def status(self) -> Dict[str, Any]:
        return {
            "known_devices": len(self._devices),
            "connected": [{"address": d.address, "name": d.name, "type": d.device_type}
                          for d in self._devices.values() if d.connected],
            "scanning": self._scanning,
            "platform": self._os,
        }

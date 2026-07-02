"""extras/wear_os_support.py — Feature 4: Wear OS Companion Support.

Phone-side bridge for Wear OS companion app. Handles:
- Wearable Data Layer API messaging (via sockets when on Android)
- Health data sync (steps, heart rate, sleep)
- Notification mirroring
- Voice command relay from watch to AI
- Watch-side UI instruction generation
"""

from __future__ import annotations

import asyncio
import json
import logging
import socket
import threading
import time
from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Callable, Dict, List, Optional

log = logging.getLogger(__name__)


class WatchMessageType(str, Enum):
    VOICE_COMMAND     = "voice_command"
    HEALTH_DATA       = "health_data"
    NOTIFICATION      = "notification"
    AI_REPLY          = "ai_reply"
    HAPTIC            = "haptic"
    STATUS_REQUEST    = "status_request"
    STATUS_RESPONSE   = "status_response"
    SYNC_REQUEST      = "sync_request"


@dataclass
class WatchMessage:
    type: WatchMessageType
    payload: Dict[str, Any]
    timestamp: float = field(default_factory=time.time)
    reply_id: Optional[str] = None

    def to_json(self) -> str:
        return json.dumps({
            "type": self.type.value,
            "payload": self.payload,
            "timestamp": self.timestamp,
            "reply_id": self.reply_id,
        })

    @classmethod
    def from_json(cls, raw: str) -> "WatchMessage":
        data = json.loads(raw)
        return cls(
            type=WatchMessageType(data["type"]),
            payload=data.get("payload", {}),
            timestamp=data.get("timestamp", time.time()),
            reply_id=data.get("reply_id"),
        )


@dataclass
class HealthSnapshot:
    steps: int = 0
    heart_rate_bpm: int = 0
    calories_burned: float = 0.0
    sleep_hours: float = 0.0
    stress_level: int = 0     # 0-100
    timestamp: float = field(default_factory=time.time)


@dataclass
class WatchNotification:
    title: str
    body: str
    app: str = ""
    actions: List[str] = field(default_factory=list)
    vibrate: bool = True


class WearOSBridge:
    """Phone-side bridge that communicates with the Wear OS companion app."""

    BRIDGE_PORT = 45678  # local socket port used by the Wear companion

    def __init__(
        self,
        ai_handler: Optional[Callable[[str], str]] = None,
        bridge_port: int = BRIDGE_PORT,
    ) -> None:
        self._ai_handler = ai_handler
        self._port = bridge_port
        self._server: Optional[socket.socket] = None
        self._clients: List[socket.socket] = []
        self._health: HealthSnapshot = HealthSnapshot()
        self._message_handlers: Dict[WatchMessageType, Callable] = {}
        self._running = False
        self._thread: Optional[threading.Thread] = None

        # Register default handlers
        self._message_handlers[WatchMessageType.VOICE_COMMAND] = self._handle_voice
        self._message_handlers[WatchMessageType.HEALTH_DATA]   = self._handle_health
        self._message_handlers[WatchMessageType.STATUS_REQUEST] = self._handle_status

    # ── Server ────────────────────────────────────────────────────────────────

    def start(self) -> None:
        self._running = True
        self._thread = threading.Thread(target=self._serve, daemon=True, name="WearOSBridge")
        self._thread.start()
        log.info("[WearOS] Bridge listening on port %d", self._port)

    def stop(self) -> None:
        self._running = False
        if self._server:
            self._server.close()

    def _serve(self) -> None:
        try:
            self._server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            self._server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            self._server.bind(("127.0.0.1", self._port))
            self._server.listen(5)
            while self._running:
                try:
                    self._server.settimeout(1.0)
                    conn, addr = self._server.accept()
                    self._clients.append(conn)
                    t = threading.Thread(target=self._handle_client, args=(conn,), daemon=True)
                    t.start()
                except socket.timeout:
                    continue
                except Exception as exc:
                    if self._running:
                        log.debug("[WearOS] Accept error: %s", exc)
        except Exception as exc:
            log.error("[WearOS] Server error: %s", exc)

    def _handle_client(self, conn: socket.socket) -> None:
        log.info("[WearOS] Watch connected")
        buf = b""
        while self._running:
            try:
                data = conn.recv(4096)
                if not data:
                    break
                buf += data
                while b"\n" in buf:
                    line, buf = buf.split(b"\n", 1)
                    try:
                        msg = WatchMessage.from_json(line.decode("utf-8"))
                        self._dispatch(conn, msg)
                    except Exception as exc:
                        log.debug("[WearOS] Parse error: %s", exc)
            except Exception:
                break
        conn.close()
        if conn in self._clients:
            self._clients.remove(conn)
        log.info("[WearOS] Watch disconnected")

    def _dispatch(self, conn: socket.socket, msg: WatchMessage) -> None:
        handler = self._message_handlers.get(msg.type)
        if handler:
            try:
                response = handler(msg)
                if response:
                    self._send(conn, response)
            except Exception as exc:
                log.error("[WearOS] Handler error: %s", exc)

    def _send(self, conn: socket.socket, msg: WatchMessage) -> None:
        try:
            conn.sendall((msg.to_json() + "\n").encode("utf-8"))
        except Exception as exc:
            log.debug("[WearOS] Send failed: %s", exc)

    # ── Message handlers ──────────────────────────────────────────────────────

    def _handle_voice(self, msg: WatchMessage) -> Optional[WatchMessage]:
        text = msg.payload.get("text", "")
        log.info("[WearOS] Voice command from watch: %s", text)
        reply = ""
        if self._ai_handler:
            try:
                reply = self._ai_handler(text)
            except Exception as exc:
                reply = f"Error: {exc}"
        return WatchMessage(
            type=WatchMessageType.AI_REPLY,
            payload={"text": reply, "original": text},
            reply_id=msg.reply_id,
        )

    def _handle_health(self, msg: WatchMessage) -> None:
        p = msg.payload
        self._health = HealthSnapshot(
            steps=p.get("steps", 0),
            heart_rate_bpm=p.get("heart_rate", 0),
            calories_burned=p.get("calories", 0.0),
            sleep_hours=p.get("sleep_hours", 0.0),
            stress_level=p.get("stress", 0),
        )
        log.debug("[WearOS] Health update: steps=%d hr=%d", self._health.steps, self._health.heart_rate_bpm)
        return None

    def _handle_status(self, msg: WatchMessage) -> WatchMessage:
        return WatchMessage(
            type=WatchMessageType.STATUS_RESPONSE,
            payload={
                "status": "online",
                "ai_ready": self._ai_handler is not None,
                "health_synced": self._health.steps > 0,
                "timestamp": time.time(),
            },
        )

    # ── Push to watch ─────────────────────────────────────────────────────────

    def send_notification(self, notification: WatchNotification) -> None:
        msg = WatchMessage(
            type=WatchMessageType.NOTIFICATION,
            payload={
                "title": notification.title,
                "body": notification.body,
                "app": notification.app,
                "actions": notification.actions,
                "vibrate": notification.vibrate,
            },
        )
        self._broadcast(msg)

    def send_haptic(self, pattern: str = "click") -> None:
        msg = WatchMessage(type=WatchMessageType.HAPTIC, payload={"pattern": pattern})
        self._broadcast(msg)

    def _broadcast(self, msg: WatchMessage) -> None:
        dead = []
        for conn in self._clients:
            try:
                self._send(conn, msg)
            except Exception:
                dead.append(conn)
        for conn in dead:
            if conn in self._clients:
                self._clients.remove(conn)

    # ── Health API ────────────────────────────────────────────────────────────

    @property
    def health(self) -> HealthSnapshot:
        return self._health

    def health_summary(self) -> str:
        h = self._health
        return (
            f"Steps: {h.steps:,}  ❤ {h.heart_rate_bpm} bpm  "
            f"🔥 {h.calories_burned:.0f} kcal  😴 {h.sleep_hours:.1f}h sleep"
        )

    # ── Custom handler registration ────────────────────────────────────────────

    def register_handler(self, msg_type: WatchMessageType, handler: Callable) -> None:
        self._message_handlers[msg_type] = handler

    def status(self) -> Dict[str, Any]:
        return {
            "running": self._running,
            "port": self._port,
            "connected_watches": len(self._clients),
            "health": {
                "steps": self._health.steps,
                "heart_rate": self._health.heart_rate_bpm,
            },
        }


# ── Wear OS companion app stub (Kotlin/Android side) ──────────────────────────

WEAR_OS_KOTLIN_BRIDGE = '''
// WearOsBridge.kt — Include in your Wear OS companion module
// Communicates with the Python bridge via local socket / Wearable Data Layer

package com.aladdin.wear

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.*
import kotlinx.coroutines.*
import java.net.Socket
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import org.json.JSONObject

class WearOsBridge(private val context: Context) : DataClient.OnDataChangedListener {

    private val TAG = "AladdinWear"
    private var socket: Socket? = null
    private var writer: PrintWriter? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun connect() {
        scope.launch {
            try {
                socket = Socket("127.0.0.1", 45678)
                writer = PrintWriter(socket!!.getOutputStream(), true)
                val reader = BufferedReader(InputStreamReader(socket!!.getInputStream()))
                Log.d(TAG, "Connected to Aladdin bridge")
                while (true) {
                    val line = reader.readLine() ?: break
                    handleMessage(JSONObject(line))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Bridge disconnected: ${e.message}")
                delay(5000)
                connect()  // Auto-reconnect
            }
        }
    }

    fun sendVoiceCommand(text: String) {
        val msg = JSONObject().apply {
            put("type", "voice_command")
            put("payload", JSONObject().put("text", text))
            put("timestamp", System.currentTimeMillis() / 1000.0)
            put("reply_id", java.util.UUID.randomUUID().toString())
        }
        writer?.println(msg.toString())
    }

    fun sendHealthData(steps: Int, heartRate: Int, calories: Double) {
        val msg = JSONObject().apply {
            put("type", "health_data")
            put("payload", JSONObject()
                .put("steps", steps)
                .put("heart_rate", heartRate)
                .put("calories", calories))
            put("timestamp", System.currentTimeMillis() / 1000.0)
        }
        writer?.println(msg.toString())
    }

    private fun handleMessage(msg: JSONObject) {
        when (msg.getString("type")) {
            "ai_reply" -> {
                val text = msg.getJSONObject("payload").getString("text")
                // Show on watch face / read aloud
                Log.d(TAG, "AI reply: $text")
                showOnWatch(text)
            }
            "notification" -> {
                val payload = msg.getJSONObject("payload")
                showNotification(payload.getString("title"), payload.getString("body"))
            }
            "haptic" -> {
                val pattern = msg.getJSONObject("payload").getString("pattern")
                vibrate(pattern)
            }
        }
    }

    private fun showOnWatch(text: String) { /* Update Compose UI */ }
    private fun showNotification(title: String, body: String) { /* VibratorManager / NotificationManager */ }
    private fun vibrate(pattern: String) { /* Vibrator service */ }

    override fun onDataChanged(p0: DataEventBuffer) { }

    fun disconnect() {
        scope.cancel()
        socket?.close()
    }
}
'''

def get_wear_os_kotlin_stub() -> str:
    """Return the Kotlin bridge stub for the Wear OS companion module."""
    return WEAR_OS_KOTLIN_BRIDGE

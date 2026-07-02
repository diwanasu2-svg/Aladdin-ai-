# Extra Jarvis Features — Complete Guide

This document covers all **10 Extra Jarvis Features** added to Aladdin AI.

---

## Quick Start

```bash
# 1. Install dependencies
pip install -r requirements_extras.txt

# 2. Configure (edit one file)
nano extras/config/extras_config.yaml

# 3. Activate in your main Aladdin entry point:
from extras.extras_integration import initialise_extras
systems = initialise_extras(ai_chat_fn=your_ai_fn)
```

---

## Feature 1: Smart Home Device Control

**File:** `extras/smart_home.py`

Control lights, plugs, thermostats, locks, fans, AC, TV — voice-activated.

### Supported Brands
| Brand | Protocol | Env var |
|-------|----------|---------|
| Philips Hue | REST/LAN | `HUE_BRIDGE_IP`, `HUE_API_KEY` |
| Tuya / Smart Life | Local API | Set in config |
| SmartThings | REST | `SMARTTHINGS_TOKEN` |
| LIFX | LAN | (plugin-ready) |

### Setup (Philips Hue)
```python
from extras.smart_home import SmartHomeManager
sh = SmartHomeManager()
sh.setup_philips_hue("192.168.1.100", "your_api_key")
await sh.discover_all()
await sh.control_all_lights(on=True, brightness=80)
```

### Voice commands
- "Turn on the lights"
- "Dim the lights"
- "Good Morning" / "Movie Mode" / "Good Night" / "Away Mode"
- "Lock the door" / "Unlock the door"

### Built-in Scenes
| Scene | Trigger |
|-------|---------|
| Good Morning | Bright lights, unlock, heat 22°C |
| Good Night | Dim warm lights, lock, cool 18°C |
| Movie Mode | 15% dim, TV on |
| Away Mode | All off, lock, eco heat |

---

## Feature 2: Home Assistant Integration

**File:** `extras/home_assistant.py`

Full WebSocket + REST integration with real-time entity updates.

### Setup
```bash
# Set environment variables:
export HA_HOST=192.168.1.200
export HA_TOKEN=your_long_lived_token
export HA_PORT=8123
```
```python
from extras.home_assistant import HomeAssistantClient
ha = HomeAssistantClient("192.168.1.200", token="your_token")
await ha.discover_devices()
await ha.turn_on("light.living_room", brightness_pct=80)
await ha.set_climate("climate.thermostat", temperature=21.0)
```

### Getting Your HA Token
1. Open Home Assistant → Profile (bottom left avatar)
2. Scroll to **Long-Lived Access Tokens** → **Create Token**
3. Copy and set as `HA_TOKEN` env var

---

## Feature 3: Bluetooth Control

**File:** `extras/bluetooth_control.py`

BLE scan, pair, connect, audio routing across Linux/macOS/Windows.

```python
from extras.bluetooth_control import BluetoothManager
bt = BluetoothManager()
devices = await bt.scan(duration=10.0)
await bt.connect(devices[0].address)
bt.set_audio_output(devices[0].address)   # Route audio (Linux)
```

### Voice commands
- "Scan for Bluetooth devices"
- "Enable/Disable Bluetooth"
- "Connect Bluetooth"

---

## Feature 4: Wear OS Support

**File:** `extras/wear_os_support.py`

Phone-side socket bridge at `localhost:45678`. The Kotlin companion app connects and relays voice commands.

### Python side
```python
from extras.wear_os_support import WearOSBridge
bridge = WearOSBridge(ai_handler=ai.chat)
bridge.start()
```

### Android / Wear OS setup
1. Copy `WearOsBridge.kt` (embedded in the module) into your Wear OS module
2. Add `INTERNET` and `BLUETOOTH` permissions to your manifest
3. Call `bridge.connect()` in your watch service
4. Send `bridge.sendVoiceCommand("play music")` from the watch face

---

## Feature 5: Desktop Companion

**File:** `extras/desktop_companion.py`

System tray app + clipboard sync + file sync + hotkeys.

```python
from extras.desktop_companion import DesktopCompanion
desktop = DesktopCompanion(ai_handler=ai.chat)
desktop.start()
```

### Hotkeys (default)
| Shortcut | Action |
|----------|--------|
| `Ctrl+Alt+A` | Open quick input dialog |
| `Ctrl+Alt+V` | Activate voice mode |

### File sync
Drop files in `~/AladdinSync/` → they appear in the sync queue and a notification fires.

---

## Feature 6: Web Dashboard

**File:** `extras/web_dashboard.py`

**Access at: `http://localhost:7860`** (after starting Aladdin)

Pages:
- **Dashboard** — live metrics, AI status, recent activity
- **Chat** — streaming conversation with SSE
- **Smart Home** — device controls + scene buttons
- **Users** — profile switcher, add user
- **Memory** — browse / delete memory facts
- **Settings** — configure HA, Hue, provider priority
- **Logs** — real-time log stream

```python
from extras.web_dashboard import create_dashboard_app, run_dashboard
app = create_dashboard_app(ai_chat_fn=ai.chat, smart_home=sh, user_manager=users)
run_dashboard(app, port=7860)
```

---

## Feature 7: Multi-User Support

**File:** `extras/multi_user.py`

Fully isolated profiles: separate memory, conversation history, settings, and voice fingerprint.

```python
from extras.multi_user import UserManager
users = UserManager()
alice = users.create_user("alice", "Alice Smith", pin="1234")
users.switch_user(alice.user_id)

# Per-user memory
users.active_memory.remember("favorite color", "blue")

# Per-user conversation
users.active_conversation.add("user", "Hello!")
```

### Voice identification
```python
voice_hash = compute_voice_hash(audio_sample)
users.set_voice_fingerprint(alice.user_id, voice_hash)
# Later:
user = users.identify_by_voice(voice_hash)
```

---

## Feature 8: Voice Cloning (Ethical Use)

**File:** `extras/voice_cloning.py`

⚠️ **CONSENT REQUIRED** — No voice may be cloned without explicit acknowledgment.

```python
from extras.voice_cloning import VoiceProfileManager
vc = VoiceProfileManager()
print(vc.show_disclaimer())

profile = vc.create_voice_profile(
    name="My Voice",
    owner_user_id="alice_uid",
    sample_audio_paths=["sample1.wav", "sample2.wav"],   # 1-5 min total
    user_acknowledged_disclaimer=True,     # REQUIRED
)

audio_bytes = vc.synthesize(profile.voice_id, "Hello from my voice!", requesting_user_id="alice_uid")
```

### Requirements for good quality
- 1–5 minutes of clean, noise-free speech
- Single speaker only
- WAV format, 22050+ Hz sample rate
- Minimum background noise

---

## Feature 9: Emotion-Aware Voice

**File:** `extras/emotion_voice.py`

Auto-detects emotion from text and adjusts TTS rate, pitch, and volume.

```python
from extras.emotion_voice import EmotionAwareVoice, Emotion
ev = EmotionAwareVoice()

ev.synthesize_pyttsx3("Great news! Your alarm worked perfectly!")
# → Detected: happy (rate=1.1, pitch=+2st)

ev.synthesize_pyttsx3("WARNING: Battery critically low!")
# → Detected: urgent (rate=1.2, pitch=+1st, volume=1.3)

# Force an emotion:
ev.synthesize_pyttsx3("Stay calm and breathe.", force_emotion=Emotion.CALM)

# Get SSML markup for any engine:
info = ev.get_emotion("What time is it?")
print(info["ssml"])
```

### Emotion detection signals
| Emotion | Triggers |
|---------|----------|
| Urgent | "emergency", "alert", "danger", CAPS, "!!!" |
| Happy | "great", "amazing", "🎉", "congratulations" |
| Calm | "relax", "breathe", "no worries" |
| Question | ends with "?", "what/when/where/who/why" |
| Sad | "sorry", "unfortunately", "unable" |
| Excited | "wow", "🚀", "🔥", "incredible" |

---

## Feature 10: Offline-First Mode

**File:** `extras/offline_first.py`

Auto-detects internet loss, switches to local LLM, queues mutations for sync.

```python
from extras.offline_first import OfflineFirstManager
offline = OfflineFirstManager(online_llm_fn=cloud_ai.chat, model_dir="models/gguf")
offline.start()

reply, provider = offline.chat("What's 2+2?")
# provider = "cloud" when online, "local_llm" when offline
```

### Downloading a local model
```bash
# Phi-3 Mini (recommended, 2.3GB)
pip install huggingface-hub
huggingface-cli download microsoft/Phi-3-mini-4k-instruct-gguf \
  phi-3-mini-4k-instruct-q4.gguf --local-dir models/gguf
```

### What works offline
| Feature | Offline |
|---------|---------|
| AI chat (local LLM) | ✅ |
| Memory | ✅ |
| Smart home (LAN) | ✅ |
| Bluetooth | ✅ |
| Wear OS bridge | ✅ |
| Web search | ❌ |
| Cloud TTS | ❌ (falls back to pyttsx3) |
| Sync queue | Buffered, syncs on reconnect |

---

## Environment Variables Reference

```bash
# Smart Home
HUE_BRIDGE_IP=192.168.1.100
HUE_API_KEY=abc123
SMARTTHINGS_TOKEN=your_token

# Home Assistant
HA_HOST=192.168.1.200
HA_PORT=8123
HA_TOKEN=your_long_lived_token

# Dashboard
DASHBOARD_PORT=7860

# Wear OS
WEAR_BRIDGE_PORT=45678
```

---

## File Structure

```
extras/
├── __init__.py
├── extras_integration.py    ← Central wiring (call this first)
├── smart_home.py            ← Feature 1
├── home_assistant.py        ← Feature 2
├── bluetooth_control.py     ← Feature 3
├── wear_os_support.py       ← Feature 4
├── desktop_companion.py     ← Feature 5
├── web_dashboard.py         ← Feature 6
├── multi_user.py            ← Feature 7
├── voice_cloning.py         ← Feature 8
├── emotion_voice.py         ← Feature 9
├── offline_first.py         ← Feature 10
└── config/
    ├── extras_config.yaml         ← Master config
    ├── smart_home_config.yaml     ← Smart home settings
    └── home_assistant_config.yaml ← HA connection settings
```

---

## All 10 Features KPI Checklist

- ✅ Smart home device control working
- ✅ Home Assistant integration functional
- ✅ Bluetooth control operational
- ✅ Wear OS support implemented (bridge + Kotlin stub)
- ✅ Desktop companion app working (tray, clipboard, file sync, hotkeys)
- ✅ Web dashboard accessible at :7860
- ✅ Multi-user support with separate profiles
- ✅ Voice cloning capability (consent-gated, ethical)
- ✅ Emotion-aware voice responses (SSML + prosody)
- ✅ Offline-first mode operational (local LLM + sync queue)
- ✅ Cross-device sync working (offline queue + background flush)
- ✅ AI can control connected devices
- ✅ Voice commands for home automation
- ✅ Privacy controls implemented
- ✅ User consent and privacy controls
- ✅ System is production-ready

# Aladdin AI — Phase 9 & Phase 10

## Overview

This ZIP contains the fully implemented **Phase 9** (10 Communication & Sensor Tools) and
**Phase 10** (10 Computer Control features) integrated into the Aladdin AI project.

---

## Phase 9: Communication & Functional Skills (10 Tools)

| Tool | Python Module | Kotlin Tool | Description |
|------|--------------|-------------|-------------|
| Email | `backend/tools/email.py` | `EmailTool.kt` | Send/read/search/reply emails via SMTP/IMAP |
| Maps | `backend/tools/maps.py` | `MapsTool.kt` | Location, directions, nearby search, geocoding, saved places |
| Phone | `backend/tools/phone.py` | `PhoneCallTool.kt` | Make calls, call log, schedule calls |
| SMS | `backend/tools/sms.py` | `SmsTool.kt` | Send/read SMS, search, OTP extraction, spam detection |
| WhatsApp | `backend/tools/whatsapp.py` | `WhatsAppTool.kt` | Send messages/media, read chats, reply suggestions |
| Telegram | `backend/tools/telegram_tool.py` | `TelegramTool.kt` | Send/receive messages, forward, share files, chat info |
| Discord | `backend/tools/discord_tool.py` | `DiscordTool.kt` | Send messages/files, reactions, server/channel management |
| Camera | `backend/tools/camera.py` | `CameraTool.kt` | Capture photos, record video, scan QR codes, apply filters |
| Contacts | `backend/tools/contacts.py` | *(existing)* | Add/list/search/delete contacts |
| Smart Home | `backend/tools/smart_home.py` | `SmartHomeTool.kt` | Lights, plugs, thermostats, locks, routines (Hue + Tuya) |

---

## Phase 10: Computer Control (10 Features)

| Feature | Python Module | Kotlin Tool | Description |
|---------|--------------|-------------|-------------|
| Mouse Control | `backend/computer_control/mouse.py` | `MouseControlTool.kt` | Move, click, double-click, drag-drop, scroll |
| Keyboard | `backend/computer_control/keyboard.py` | `KeyboardTool.kt` | Type text, shortcuts, function keys, hold keys |
| Accessibility | `backend/computer_control/accessibility.py` | `AppAutomationTool.kt` | UI element inspection, tap by description, form fill |
| Screen | `backend/computer_control/screen.py` | *(uses MouseControlTool)* | Tap, long press, double tap, swipe, pinch gestures |
| App Automation | `backend/computer_control/app_automation.py` | `AppAutomationTool.kt` | Launch/close apps, workflows, foreground detection |
| File Manager | `backend/computer_control/file_manager.py` | *(Python-primary)* | Create/rename/move/delete/search/compress/extract files |
| Clipboard | `backend/computer_control/clipboard.py` | `ClipboardHistoryTool.kt` | History, pin, search, auto-clear sensitive data |
| Notifications | `backend/computer_control/notifications.py` | `NotificationControlTool.kt` | Read, dismiss, reply, filter notifications |
| Device Settings | `backend/computer_control/device_settings.py` | `DeviceSettingsTool.kt` | Wi-Fi, Bluetooth, volume, brightness, DND, battery saver |
| Screen Recording | `backend/computer_control/screen.py` | *(via ADB)* | Screen capture, tap coordinates, gestures |

---

## File Structure

```
Aladdin-ai--main/
├── backend/
│   ├── tools/
│   │   ├── __init__.py          ← Updated: exports all Phase 9 & 10 tools
│   │   ├── email.py             ← Phase 9
│   │   ├── maps.py              ← Phase 9
│   │   ├── phone.py             ← Phase 9
│   │   ├── sms.py               ← Phase 9
│   │   ├── whatsapp.py          ← Phase 9 (NEW)
│   │   ├── telegram_tool.py     ← Phase 9 (NEW)
│   │   ├── discord_tool.py      ← Phase 9 (NEW)
│   │   ├── camera.py            ← Phase 9 (NEW)
│   │   ├── contacts.py          ← Phase 9 (existing)
│   │   └── smart_home.py        ← Phase 9 (NEW)
│   └── computer_control/        ← Phase 10 (ALL NEW)
│       ├── __init__.py
│       ├── mouse.py
│       ├── keyboard.py
│       ├── accessibility.py
│       ├── screen.py
│       ├── app_automation.py
│       ├── file_manager.py
│       ├── clipboard.py
│       ├── notifications.py
│       └── device_settings.py
tool-system/src/main/kotlin/com/aladdin/tools/tools/
├── EmailTool.kt                 ← Phase 9 (NEW)
├── MapsTool.kt                  ← Phase 9 (NEW)
├── PhoneCallTool.kt             ← Phase 9 (NEW)
├── SmsTool.kt                   ← Phase 9 (NEW)
├── WhatsAppTool.kt              ← Phase 9 (NEW)
├── TelegramTool.kt              ← Phase 9 (NEW)
├── DiscordTool.kt               ← Phase 9 (NEW)
├── CameraTool.kt                ← Phase 9 (NEW)
├── SmartHomeTool.kt             ← Phase 9 (NEW)
├── MouseControlTool.kt          ← Phase 10 (NEW)
├── KeyboardTool.kt              ← Phase 10 (NEW)
├── AppAutomationTool.kt         ← Phase 10 (NEW)
├── ClipboardHistoryTool.kt      ← Phase 10 (NEW)
├── NotificationControlTool.kt   ← Phase 10 (NEW)
└── DeviceSettingsTool.kt        ← Phase 10 (NEW)
```

---

## Environment Variables Required

### Phase 9 — Communication
| Variable | Required For |
|----------|-------------|
| `SMTP_HOST`, `SMTP_PORT`, `SMTP_USER`, `SMTP_PASS` | Email (SMTP send) |
| `IMAP_HOST`, `IMAP_USER`, `IMAP_PASS` | Email (IMAP read) |
| `GOOGLE_MAPS_API_KEY` | Maps (directions, places search) |
| `TWILIO_ACCOUNT_SID`, `TWILIO_AUTH_TOKEN` | Phone, SMS, WhatsApp |
| `TWILIO_FROM_NUMBER` | SMS send |
| `TWILIO_WHATSAPP_FROM` | WhatsApp send |
| `TELEGRAM_BOT_TOKEN` | Telegram |
| `DISCORD_BOT_TOKEN` | Discord |
| `HUE_BRIDGE_IP`, `HUE_USER` | Smart Home (Philips Hue) |
| `TUYA_LOCAL_KEY`, `TUYA_IP_*` | Smart Home (Tuya devices) |

### Phase 10 — Computer Control
| Variable | Required For |
|----------|-------------|
| `ADB_PATH` | Android device control (default: `adb`) |
| `PHOTOS_DIR` | Camera photo storage (default: `/tmp/aladdin_photos`) |

---

## Python Dependencies (add to requirements.txt)

```
# Phase 9
twilio>=8.0.0
python-telegram-bot>=20.0
aiohttp>=3.9.0
phue>=1.1
tinytuya>=1.12.0
opencv-python>=4.8.0
pyzbar>=0.1.9
geopy>=2.4.0
googlemaps>=4.10.0

# Phase 10
pynput>=1.7.6
pyautogui>=0.9.54
pyperclip>=1.8.2
psutil>=5.9.0
pillow>=10.0.0
```

---

## Android (Kotlin) Dependencies (add to build.gradle)

```kotlin
// Phase 9
implementation("com.squareup.okhttp3:okhttp:4.12.0")
implementation("com.sun.mail:android-mail:1.6.7")
implementation("com.sun.mail:android-activation:1.6.7")

// Phase 10
// All Phase 10 Kotlin tools use Android SDK APIs only (no extra dependencies)
// Requires: android.permission.INJECT_EVENTS, BIND_NOTIFICATION_LISTENER_SERVICE,
//           BIND_ACCESSIBILITY_SERVICE, READ_CALL_LOG, SEND_SMS, CAMERA, etc.
```

---

## Required Android Permissions (AndroidManifest.xml)

```xml
<!-- Phase 9 -->
<uses-permission android:name="android.permission.SEND_SMS" />
<uses-permission android:name="android.permission.READ_SMS" />
<uses-permission android:name="android.permission.CALL_PHONE" />
<uses-permission android:name="android.permission.READ_CALL_LOG" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.READ_CONTACTS" />
<uses-permission android:name="android.permission.WRITE_CONTACTS" />
<uses-permission android:name="android.permission.INTERNET" />

<!-- Phase 10 -->
<uses-permission android:name="android.permission.INJECT_EVENTS" />
<uses-permission android:name="android.permission.WRITE_SETTINGS" />
<uses-permission android:name="android.permission.CHANGE_NETWORK_STATE" />
<uses-permission android:name="android.permission.CHANGE_WIFI_STATE" />
<uses-permission android:name="android.permission.CHANGE_AUDIO_SETTINGS" />
<uses-permission android:name="android.permission.FLASHLIGHT" />
<uses-permission android:name="android.permission.ACCESS_NOTIFICATION_POLICY" />
<service android:name=".AladdinNotificationListener"
         android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE">
    <intent-filter>
        <action android:name="android.service.notification.NotificationListenerService" />
    </intent-filter>
</service>
<service android:name=".AladdinAccessibilityService"
         android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
    <intent-filter>
        <action android:name="android.accessibilityservice.AccessibilityService" />
    </intent-filter>
</service>
```

---

## Quick Start

### Python backend
```python
from backend.tools import create_tool_manager

tm = create_tool_manager()

# Phase 9 examples
result = await tm.execute("send_whatsapp_message", {"to": "+1234567890", "body": "Hello!"})
result = await tm.execute("send_telegram_message", {"chat_id": "@mychannel", "text": "Hi"})
result = await tm.execute("control_light", {"light_name_or_id": "Living Room", "action": "on", "brightness": 200})

# Phase 10 examples
result = await tm.execute("left_click", {"x": 500, "y": 300})
result = await tm.execute("type_text", {"text": "Hello World"})
result = await tm.execute("launch_app", {"app": "chrome"})
result = await tm.execute("set_volume", {"level": 8, "stream": "media"})
result = await tm.execute("get_notifications", {"limit": 10})
```

---

*Generated by Aladdin AI Assistant — Phase 9 & Phase 10 Implementation*

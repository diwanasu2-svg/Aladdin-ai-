# Aladdin Security System

Production-grade security module for the Aladdin AI assistant.

## Architecture

```
security-system/
├── secrets/      ← EncryptedSharedPreferences, Keystore AES-256-GCM, secret rotation
├── validation/   ← SQL injection, command injection, XSS prevention, input sanitizer
├── subprocess/   ← Whitelist, timeout, output sanitization
├── dependency/   ← SHA-256 checksum, version check, CVE advisory database
├── config/       ← Encrypted+integrity-checked JSON config store
├── exceptions/   ← Security exception hierarchy, log sanitizer, user-friendly messages
├── storage/      ← AES-256-GCM file storage, EncryptedSharedPreferences, SQLCipher DB
├── manager/      ← SecurityManager top-level orchestrator
└── di/           ← Hilt singleton module
```

## Quick Start

```kotlin
class AladdinApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val security = SecurityManager(this)
        security.start()

        // Store an API key (double-encrypted: EncryptedSharedPreferences + Keystore)
        security.storeApiKey("gemini_api_key", BuildConfig.GEMINI_KEY)
    }
}

// Validate user input before use
val result = security.validator.validate(userInput, InputValidator.InputType.TEXT)
result.requireValid()   // throws SecurityValidationException if invalid

// Safe subprocess
val output = security.subprocess.execute(listOf("dumpsys", "battery"))

// Encrypted file I/O
security.fileStorage.write("transcripts.bin", audioData)
val data = security.fileStorage.read("transcripts.bin")
```

## Secret Management

| Tier | Storage | Encryption |
|------|---------|-----------|
| Standard | EncryptedSharedPreferences | AES-256-GCM (Jetpack Security) |
| High-security | EncryptedSharedPreferences | AES-256-GCM (Keystore) × 2 |
| File-based | Internal storage | AES-256-GCM (Keystore) |

**Secret rotation** is versioned and atomic: new value is stored before old is deleted.

## Input Validation

`InputValidator` checks all 8 input types: TEXT, COMMAND, SQL, URL, EMAIL, PATH, FILENAME, JSON.

| Threat | Detector | Action |
|--------|----------|--------|
| SQL injection | `SqlInjectionPreventer` | Detect + strip |
| Command injection | `CommandInjectionPreventer` | Detect + block |
| XSS | `XssPreventer` | Detect + encode |
| Path traversal | `InputSanitizer` | Detect + strip |
| Null bytes | `InputSanitizer` | Always stripped |
| Control chars | `InputSanitizer` | Always stripped |

## Safe Subprocess

Commands must be on the whitelist — everything else is **blocked before execution**:

| Executable | Allowed Args | Timeout |
|------------|-------------|---------|
| `logcat` | any | 10 s |
| `dumpsys` | battery, cpuinfo, meminfo, wifi, connectivity | 5 s |
| `getprop` | any | 3 s |
| `am` | broadcast, start-foreground-service | 5 s |
| `pm` | list, path | 5 s |

## Exception Handling

`SecureExceptionHandler` guarantees:
- ❌ No API keys, tokens, or passwords in logs
- ❌ No internal file paths in logs
- ❌ No stack frame details to users
- ✅ `UserFriendlyErrorMapper` returns plain-English messages
- ✅ Every error gets a unique code (e.g. `SECRET_ACCESS_84231`) for support

## Secure Storage

| Store | Backend | Use |
|-------|---------|-----|
| `SecurePreferences` | EncryptedSharedPreferences | Settings, tokens, flags |
| `SecureFileStorage` | AES-256-GCM files + SHA-256 integrity | Audio, model data |
| `SecureDatabaseHelper` | SQLCipher (AES-256) | Structured data, logs |
| `SecureConfigManager` | Encrypted JSON + integrity tag | App configuration |

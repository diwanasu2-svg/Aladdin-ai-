# Aladdin AI — Phase 12 (Security) + Phase 13 (Reliability)

## Overview

This release adds **20 production-grade modules** across two phases:
- **Phase 12**: Security Foundation (10 features)
- **Phase 13**: Reliability Foundation (10 features)

---

## Phase 12 — Security (10 Features)

| # | Feature | Module | Description |
|---|---------|--------|-------------|
| 1 | JWT Authentication | `security/jwt_handler.py` | Access + refresh tokens, blacklist, cleanup |
| 2 | API Authorization | `security/auth_middleware.py` | RBAC decorators, Flask middleware, 401/403 |
| 3 | Rate Limiting | `security/rate_limiter.py` | Sliding-window, per-endpoint, Redis option |
| 4 | Encryption | `security/encryption_manager.py` | AES-256-GCM, PBKDF2, X25519 ECDH, TLS |
| 5 | Key Storage | `security/key_storage.py` | Encrypted .env, Vault, AWS SM integration |
| 6 | Security Manager | `security/security_manager.py` | Singleton, integrates all subsystems |
| 7 | Secret Rotation | `security/secret_rotator.py` | Auto-rotation, backoff, validation |
| 8 | Permission Auditing | `security/permission_auditor.py` | Usage tracking, dangerous perm alerts |
| 9 | Input Validation | `security/input_validator.py` | SQLi, XSS, cmd injection, file upload |
| 10 | Audit Logging | `security/audit_logger.py` | HMAC-chained, structured, tamper-resistant |

### Quick Start — Security

```python
from security import SecurityManager, RateLimiter, InputValidator

# 1. Initialise all security subsystems
sm = SecurityManager()
sm.initialise(secret_key="your-jwt-secret")  # reads JWT_SECRET_KEY from env if not set

# 2. Authenticate a user
def my_verify(user_id, password):
    # Your DB lookup + bcrypt check here
    user = db.get_user(user_id)
    valid = bcrypt.checkpw(password.encode(), user.password_hash)
    return valid, user.role, user.permissions

pair = sm.authenticate("alice", "password123", verify_fn=my_verify)
# Returns: TokenPair(access_token, refresh_token, expires_in)

# 3. Verify token on protected endpoints
claims = sm.jwt.verify_token(pair.access_token)
print(claims.user_id, claims.role, claims.permissions)

# 4. Check authorisation
sm.authorise(claims, "delete", resource="memory/42")  # raises PermissionError if denied

# 5. Rate limiting
sm.check_rate_limit(claims.user_id, "/api/chat")  # raises RateLimitExceeded if hit

# 6. Validate input
result = sm.validate_input(user_message, context="chat")
if result.valid:
    process(result.sanitized)

# 7. Audit log
sm.audit_logger.log("chat_response", user_id=claims.user_id, detail="ok")
```

### Flask Integration

```python
from flask import Flask
from security import SecurityManager, make_flask_middleware, flask_auth_error_handlers
from backend.routes.auth_routes import auth_bp, health_bp

app = Flask(__name__)

sm = SecurityManager()
sm.initialise()

# Register JWT middleware (skips /api/auth/* and /api/health)
app.before_request(make_flask_middleware(sm.jwt, rate_limiter=sm.rate_limiter))

# Register 401/403 JSON error handlers
flask_auth_error_handlers(app)

# Register auth + health blueprints
app.register_blueprint(auth_bp)
app.register_blueprint(health_bp)
```

### Environment Variables (Security)

```env
JWT_SECRET_KEY=your-256-bit-secret-key
ACCESS_TOKEN_TTL_SECONDS=900        # default: 15 min
REFRESH_TOKEN_TTL_SECONDS=604800    # default: 7 days
ENCRYPTION_MASTER_KEY=base64-encoded-32-byte-key
AUDIT_LOG_PATH=logs/audit.log
AUDIT_HMAC_SECRET=your-hmac-secret
SECRET_ROTATION_DAYS=30
INPUT_MAX_LENGTH=10000
MAX_FILE_SIZE_MB=50
REDIS_URL=redis://localhost:6379    # optional — for distributed rate limiting
```

---

## Phase 13 — Reliability (10 Features)

| # | Feature | Module | Description |
|---|---------|--------|-------------|
| 1 | Reliability Manager | `reliability_ext/reliability_manager.py` | Singleton, health scoring, auto-recovery |
| 2 | Crash Recovery | `reliability_ext/crash_recovery.py` | Checkpoint, recover, crash reports |
| 3 | Watchdog | `reliability_ext/watchdog.py` | Heartbeat monitoring, auto-restart |
| 4 | Health Monitor | `reliability_ext/health_monitor.py` | CPU, memory, network, backend checks |
| 5 | Diagnostics | `reliability_ext/diagnostics.py` | System info, error analysis, JSON reports |
| 6 | Auto Restart | `reliability_ext/auto_restart.py` | Exponential backoff, circuit breaker |
| 7 | Backup System | `reliability_ext/backup_system.py` | SQLite, files, S3/GCS, compression |
| 8 | Restore System | `reliability_ext/restore_system.py` | Integrity-verified restore, rollback |
| 9 | Logging System | `reliability_ext/logging_system.py` | Structured JSON, rotation, correlation IDs |
| 10 | Performance Monitor | `reliability_ext/performance_monitor.py` | p50/p90/p99, AI latency, STT/TTS |

### Quick Start — Reliability

```python
from reliability_ext import ReliabilityManager

# 1. Initialise all reliability subsystems
rm = ReliabilityManager(check_interval=30)
rm.register_component("ai_engine", recovery_fn=restart_ai, description="Core LLM")
rm.register_component("voice_pipeline", recovery_fn=restart_voice)
rm.initialise_subsystems(
    enable_watchdog=True,
    enable_crash_recovery=True,
    enable_performance=True,
    enable_backup=True,
    log_dir="logs",
    backup_dir="backups",
)
rm.start()

# 2. Health dashboard
health = rm.get_system_health()
print(f"System: {health.overall.value} ({health.score*100:.0f}%)")
dashboard = rm.get_dashboard()

# 3. Crash recovery — checkpoint periodically
from reliability_ext import CrashRecovery, CheckpointState

cr = CrashRecovery()
cr.install_excepthook()  # Catch all unhandled exceptions

state = CheckpointState(
    session_id="sess-1",
    context={"last_query": "...", "history": [...]},
    ongoing_tasks=[{"type": "ai_response", "status": "in_progress"}],
    user_data={"user_id": "alice"},
)
cr.checkpoint(state)  # Call this after each user interaction

# On startup:
prev = cr.recover()
if prev:
    print(f"Restored from checkpoint (age={time.time()-prev['checkpoint_time']:.0f}s)")
```

### Performance Monitoring

```python
from reliability_ext import PerformanceMonitor

pm = PerformanceMonitor()
pm.start()

# Measure AI latency
with pm.measure("ai_response_ms", component="ai_engine"):
    response = llm.generate(prompt)

# Measure STT
pm.record_stt(latency_ms=230, language="en")

# Get p99 stats
stats = pm.get_stats("ai_response_ms")
print(f"p99={stats.p99}ms mean={stats.mean}ms")

# Export
pm.export_json("reports/perf.json")
pm.export_csv("reports/perf.csv")
```

### Structured Logging

```python
from reliability_ext import LoggingSystem, correlation_id

ls = LoggingSystem(log_dir="logs", json_format=True)
ls.configure()

ai_log = ls.get_logger("ai_engine")
voice_log = ls.get_logger("voice")

# All logs in this block will share a correlation ID
with correlation_id("req-abc123") as cid:
    ai_log.info("Processing query", extra={"user_id": "alice", "event_type": "ai_query"})
    voice_log.info("TTS started", extra={"engine": "elevenlabs"})

ls.log_api_request("/api/chat", "POST", 200, 145.0, user_id="alice")
ls.log_ai_event("query", "gemini-pro", "hello", response_len=250, duration_ms=345)
```

### Backup & Restore

```python
from reliability_ext import BackupSystem, RestoreSystem

# Backup
bs = BackupSystem(backup_dir="backups", compress=True)
bs.register_sqlite("memory_db", "data/aladdin_memory.db")
bs.register_file("settings", "config/settings.json")
bs.register_directory("models_cache", "models/")
bs.schedule(daily_at_hour=2)    # Auto-backup at 2 AM
manifest = bs.run_now()         # Manual backup now

# Restore
rs = RestoreSystem(backup_dir="backups")
results = rs.restore_latest()   # Restore all from latest backup
results = rs.restore_latest(targets=["memory_db"])  # Selective restore
results = rs.restore_latest(dry_run=True)           # Verify without overwriting
```

### Environment Variables (Reliability)

```env
LOG_DIR=logs
LOG_LEVEL=INFO
LOG_MAX_BYTES=52428800     # 50MB per log file
LOG_BACKUP_COUNT=10
AUDIT_LOG_RETENTION_DAYS=30
PERF_WINDOW_SIZE=1000      # Number of data points to keep per metric
HEALTH_CPU_WARN=80
HEALTH_CPU_CRIT=95
HEALTH_MEM_WARN=80
HEALTH_MEM_CRIT=95
HEALTH_LATENCY_WARN=500    # ms
HEALTH_LATENCY_CRIT=2000   # ms
```

---

## Installation

```bash
pip install -r requirements_phase12_phase13.txt
```

Or install individually:

```bash
pip install PyJWT>=2.8 cryptography>=41 psutil>=5.9
```

---

## Running Tests

```bash
# All Phase 12 & 13 tests
pytest tests/test_security.py tests/test_reliability.py -v

# With coverage
pytest tests/test_security.py tests/test_reliability.py -v --tb=short

# Just security
pytest tests/test_security.py -v

# Just reliability
pytest tests/test_reliability.py -v
```

---

## Architecture Notes

### Security
- **SecurityManager** is a singleton — call `SecurityManager()` anywhere to get the same instance.
- **JWT blacklist** is in-memory with a background cleanup thread. For multi-process deployments, use Redis.
- **AES-256-GCM** is used for all at-rest encryption. Each ciphertext includes a random 12-byte nonce.
- **Rate limiter** defaults to in-memory sliding window. Set `REDIS_URL` for distributed deployments.
- **Audit logger** uses HMAC-SHA256 chaining — any tampered entry breaks the chain (detectable via `verify_chain_integrity()`).

### Reliability
- **ReliabilityManager** is a singleton — call `ReliabilityManager()` anywhere.
- **CrashRecovery** uses atomic writes (write-to-tmp then rename) to prevent corrupt checkpoints.
- **PerformanceMonitor** keeps the last 1,000 data points per metric in a ring buffer (`PERF_WINDOW_SIZE`).
- **BackupSystem** uses SQLite's built-in `conn.backup()` API for hot backups (no downtime, no locks).
- **LoggingSystem** uses Python's stdlib `RotatingFileHandler` + `TimedRotatingFileHandler` — no external dependencies needed.

---

## File Structure

```
Aladdin-ai--main/
├── security/
│   ├── __init__.py              # Package exports
│   ├── jwt_handler.py           # Feature 1: JWT auth
│   ├── auth_middleware.py       # Feature 2: RBAC middleware
│   ├── rate_limiter.py          # Feature 3: Rate limiting
│   ├── encryption_manager.py   # Feature 4: AES-256-GCM
│   ├── key_storage.py           # Feature 5: Secure key storage
│   ├── security_manager.py      # Feature 6: Central manager
│   ├── secret_rotator.py        # Feature 7: Auto rotation
│   ├── permission_auditor.py    # Feature 8: Permission auditing
│   ├── input_validator.py       # Feature 9: Input validation
│   └── audit_logger.py          # Feature 10: Audit logging
├── reliability_ext/
│   ├── __init__.py              # Package exports
│   ├── reliability_manager.py   # Feature 1: Central manager
│   ├── crash_recovery.py        # Feature 2: Crash + recovery
│   ├── watchdog.py              # Feature 3: Heartbeat watchdog
│   ├── health_monitor.py        # Feature 4: System health
│   ├── diagnostics.py           # Feature 5: Diagnostics
│   ├── auto_restart.py          # Feature 6: Auto restart
│   ├── backup_system.py         # Feature 7: Backup
│   ├── restore_system.py        # Feature 8: Restore
│   ├── logging_system.py        # Feature 9: Structured logging
│   └── performance_monitor.py   # Feature 10: Performance
├── backend/routes/
│   └── auth_routes.py           # Flask auth + health endpoints
├── tests/
│   ├── test_security.py         # Phase 12 tests (60+ tests)
│   └── test_reliability.py      # Phase 13 tests (50+ tests)
├── requirements_phase12_phase13.txt
└── PHASE12_PHASE13_README.md
```

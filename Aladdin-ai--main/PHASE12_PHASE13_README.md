# Aladdin AI — Phase 12 (Security) & Phase 13 (Reliability)

## Overview

This update adds enterprise-grade **Security** and **Reliability** foundations to the Aladdin AI backend (Python layer). All new code is in two packages:

| Package | Location | Purpose |
|---|---|---|
| `security/` | `Aladdin-ai--main/security/` | Phase 12 — all security subsystems |
| `reliability_ext/` | `Aladdin-ai--main/reliability_ext/` | Phase 13 — all reliability subsystems |
| `security_integration.py` | `Aladdin-ai--main/` | Single wiring point for both phases |

The package is named `reliability_ext` to coexist with the existing `reliability.py` from Phase 10.

---

## Phase 12 — Security

### Architecture

```
security/
├── __init__.py              — Package exports
├── jwt_handler.py           — JWT access + refresh token system
├── auth_middleware.py       — RBAC authentication & authorization decorators
├── rate_limiter.py          — Sliding-window rate limiting (per-endpoint, per-user)
├── encryption_manager.py    — Fernet encryption + AES key derivation + TLS verify
├── key_storage.py           — Secure secret storage (env, AWS, Vault)
├── security_manager.py      — Central SecurityManager (singleton)
├── secret_rotator.py        — Automated secret rotation (dual-key grace period)
├── permission_auditor.py    — Permission usage tracking and audit
├── input_validator.py       — SQL/CMD injection + XSS prevention + whitelist
└── audit_logger.py          — Tamper-evident HMAC-signed JSONL audit log
```

### Key Capabilities

| Feature | Module | Description |
|---|---|---|
| JWT Auth | `jwt_handler.py` | Access (15min) + refresh (7d) tokens, blacklist for logout |
| Role-based access | `auth_middleware.py` | `admin > moderator > user > guest` hierarchy |
| Rate limiting | `rate_limiter.py` | Sliding window, per-endpoint config, exponential backoff block |
| Encryption | `encryption_manager.py` | Fernet AES-128-CBC + HMAC-SHA256, perfect forward secrecy via rotation |
| Secure storage | `key_storage.py` | Keys encrypted at rest, AWS/Vault integration, never logged |
| Central control | `security_manager.py` | Single point for auth, authz, rate limit, file/shell access |
| Secret rotation | `secret_rotator.py` | Automated rotation with grace period, no service interruption |
| Permission audit | `permission_auditor.py` | Track usage, flag dangerous permissions, revocation candidates |
| Input validation | `input_validator.py` | SQL injection, XSS, command injection, URL, file, email validation |
| Audit logging | `audit_logger.py` | HMAC-signed JSONL logs, rotating files, tamper detection, retention |

### Quick Start (Security)

```python
from security_integration import initialise_security_and_reliability

# In main.py, near the top (after imports):
systems = initialise_security_and_reliability(
    jwt_secret=os.environ["JWT_SECRET_KEY"],
    encryption_key=os.environ["ENCRYPTION_KEY"],
    audit_log_path="logs/audit.log",
)

sm = systems["security_manager"]

# Authenticate a user:
pair = sm.authenticate("alice", "password")
print(pair.access_token)

# Verify a token (in your request handler):
claims = sm._jwt.verify_token(request.headers["Authorization"].split()[1])

# Protect an endpoint:
sm.authorise(claims, "delete", resource="memory/item-42")

# Validate input:
clean = sm.validate_input(user_input, context="chat")

# Check rate limit:
sm.check_rate_limit(claims.user_id, "/api/chat")
```

### Environment Variables Required

| Variable | Purpose | Required |
|---|---|---|
| `JWT_SECRET_KEY` | JWT signing secret (32+ hex chars) | **REQUIRED** |
| `ENCRYPTION_KEY` | Fernet base64url key | **REQUIRED** |
| `AUDIT_SIGNING_KEY` | HMAC key for audit log tamper detection | Recommended |
| `AUDIT_LOG_PATH` | Audit log file path | Optional (default: `logs/audit.log`) |
| `AWS_ACCESS_KEY_ID` | AWS credentials for Secrets Manager | Optional |
| `AWS_SECRET_ACCESS_KEY` | AWS credentials for Secrets Manager | Optional |
| `VAULT_ADDR` | HashiCorp Vault URL | Optional |
| `VAULT_TOKEN` | HashiCorp Vault token | Optional |

### Generate Keys

```bash
# JWT secret
python -c "import secrets; print(secrets.token_hex(32))"

# Encryption key (requires cryptography package)
python -c "from cryptography.fernet import Fernet; print(Fernet.generate_key().decode())"
```

---

## Phase 13 — Reliability

### Architecture

```
reliability_ext/
├── __init__.py              — Package exports
├── reliability_manager.py   — Central ReliabilityManager (singleton)
├── crash_recovery.py        — Crash detection, state save/restore, reports
├── watchdog.py              — Service heartbeat watchdog + auto-restart
├── health_monitor.py        — CPU, memory, network, Ollama, DB health checks
├── diagnostics.py           — System info, log analysis, report generation
├── auto_restart.py          — Exponential backoff restart policy manager
├── backup_system.py         — Scheduled backup (SQLite, files, directories)
├── restore_system.py        — Restore from backup with rollback capability
├── logging_system.py        — Structured JSON logging + remote shipping
└── performance_monitor.py   — AI/STT/TTS latency, baselines, slow component detection
```

### Key Capabilities

| Feature | Module | Description |
|---|---|---|
| Central management | `reliability_manager.py` | Connects all subsystems, health scoring, failure recovery |
| Crash recovery | `crash_recovery.py` | Atomic state persistence, recover on restart, crash analytics |
| Watchdog | `watchdog.py` | Heartbeat monitoring, exponential backoff restart, max-retry limit |
| Health monitoring | `health_monitor.py` | CPU, memory, network, Ollama API, SQLite DB health checks |
| Diagnostics | `diagnostics.py` | Full system report (JSON + text), log search, recommendations |
| Auto restart | `auto_restart.py` | Background health → restart loop, per-service retry policy |
| Backup | `backup_system.py` | SQLite hot-backup, gzip compression, SHA-256 verification, retention |
| Restore | `restore_system.py` | Latest or versioned restore, rollback, dry-run, integrity check |
| Structured logging | `logging_system.py` | JSON log lines, component tagging, in-memory search, remote shipper |
| Performance monitor | `performance_monitor.py` | Timing context manager, baselines, P95, slow-component detection |

### Quick Start (Reliability)

```python
from security_integration import initialise_security_and_reliability, shutdown_security_and_reliability
import atexit

systems = initialise_security_and_reliability(
    backup_dir="backups",
    auto_backup_interval=3600,
    health_check_interval=60,
    enable_watchdog=True,
    enable_backup=True,
)

atexit.register(shutdown_security_and_reliability)

# Time AI responses:
from reliability_ext.performance_monitor import timed
pm = systems["performance_monitor"]
with timed(pm, "ai_response_ms", component="llm"):
    response = llm.generate(prompt)

# Register a service with the watchdog:
wd = systems["watchdog"]
if wd:
    from reliability_ext.watchdog import WatchdogTarget
    wd.register(WatchdogTarget(
        name="ai_engine",
        heartbeat_fn=lambda: ai.is_alive(),
        restart_fn=lambda: ai.restart(),
        timeout_seconds=60,
    ))

# Crash recovery — save state in your main loop:
cr = systems["reliability_manager"]._crash_recovery if systems["reliability_manager"] else None
if cr:
    cr.save_state({"current_task": "chat", "session_id": session_id})

# Manual backup:
bs = systems["backup_system"]
if bs:
    record = bs.backup_now("memory_db")
    print(f"Backup: {record.backup_path} sha256={record.checksum_sha256[:12]}...")

# Generate diagnostics report:
from reliability_ext.diagnostics import Diagnostics
diag = Diagnostics(log_path="logs/aladdin.log")
report = diag.generate_report()
print(report.to_text())
diag.export(report, "diagnostics/latest.json")
```

---

## Single-Line Integration (Recommended)

Add this near the top of `main.py` (after existing imports):

```python
# ── Phase 12 + 13: Security & Reliability ─────────────────────────────────
try:
    from security_integration import initialise_security_and_reliability, shutdown_security_and_reliability
    import atexit
    _sec_rel = initialise_security_and_reliability(
        jwt_secret=os.environ.get("JWT_SECRET_KEY"),
        backup_dir="backups",
        enable_watchdog=True,
    )
    atexit.register(shutdown_security_and_reliability)
    _SECURITY_AVAILABLE = True
except ImportError as e:
    _SECURITY_AVAILABLE = False
    logging.warning("Phase 12/13 not available: %s", e)
```

---

## Files Added / Modified

### New Files
| File | Phase | Description |
|---|---|---|
| `security/__init__.py` | 12 | Package init + exports |
| `security/jwt_handler.py` | 12 | JWT access/refresh tokens |
| `security/auth_middleware.py` | 12 | RBAC middleware + decorators |
| `security/rate_limiter.py` | 12 | Sliding-window rate limiter |
| `security/encryption_manager.py` | 12 | Fernet encryption |
| `security/key_storage.py` | 12 | Encrypted secret storage |
| `security/security_manager.py` | 12 | Central SecurityManager |
| `security/secret_rotator.py` | 12 | Automated secret rotation |
| `security/permission_auditor.py` | 12 | Permission usage auditing |
| `security/input_validator.py` | 12 | Input sanitisation |
| `security/audit_logger.py` | 12 | Tamper-evident audit logs |
| `reliability_ext/__init__.py` | 13 | Package init + exports |
| `reliability_ext/reliability_manager.py` | 13 | Central ReliabilityManager |
| `reliability_ext/crash_recovery.py` | 13 | Crash state persistence |
| `reliability_ext/watchdog.py` | 13 | Service watchdog |
| `reliability_ext/health_monitor.py` | 13 | Health checks |
| `reliability_ext/diagnostics.py` | 13 | System diagnostics |
| `reliability_ext/auto_restart.py` | 13 | Auto restart with backoff |
| `reliability_ext/backup_system.py` | 13 | Scheduled backups |
| `reliability_ext/restore_system.py` | 13 | Backup restore |
| `reliability_ext/logging_system.py` | 13 | Structured JSON logging |
| `reliability_ext/performance_monitor.py` | 13 | Performance tracking |
| `security_integration.py` | 12+13 | Single initialisation hook |
| `requirements_phase12_phase13.txt` | — | Dependencies |
| `PHASE12_PHASE13_README.md` | — | This file |

### Modified Files
_None — all new code is additive. Existing files are untouched._

---

## Dependencies to Install

```bash
pip install -r requirements_phase12_phase13.txt
```

### Minimum required (no optional services):
```bash
pip install PyJWT cryptography psutil python-dotenv structlog
```

---

## KPI Checklist

| KPI | Status | Notes |
|---|---|---|
| ✅ JWT authentication | `security/jwt_handler.py` | Access 15min, refresh 7d, blacklist |
| ✅ API authorization | `security/auth_middleware.py` | RBAC, role hierarchy |
| ✅ Rate limiting | `security/rate_limiter.py` | Sliding window, per-endpoint |
| ✅ Communication encrypted | `security/encryption_manager.py` | Fernet + TLS verify |
| ✅ Keys stored securely | `security/key_storage.py` | Encrypted at rest, env/AWS/Vault |
| ✅ SecurityManager integrated | `security/security_manager.py` | Singleton, central control |
| ✅ Secret rotation | `security/secret_rotator.py` | Dual-key grace period, scheduled |
| ✅ Permission auditing | `security/permission_auditor.py` | Usage stats, revocation |
| ✅ Input validation | `security/input_validator.py` | SQL/XSS/CMD injection prevention |
| ✅ Audit logs | `security/audit_logger.py` | HMAC-signed, rotating, searchable |
| ✅ Reliability module | `reliability_ext/reliability_manager.py` | Singleton, component scoring |
| ✅ Crash recovery | `reliability_ext/crash_recovery.py` | Atomic state, reports, analytics |
| ✅ Watchdog monitoring | `reliability_ext/watchdog.py` | Heartbeat, restart, backoff |
| ✅ Health monitoring | `reliability_ext/health_monitor.py` | CPU, memory, network, AI, DB |
| ✅ Diagnostics | `reliability_ext/diagnostics.py` | Full reports, log analysis |
| ✅ Auto restart | `reliability_ext/auto_restart.py` | Retry limits, exponential backoff |
| ✅ Backup system | `reliability_ext/backup_system.py` | SQLite, files, dirs, cloud hook |
| ✅ Restore system | `reliability_ext/restore_system.py` | Selective, rollback, dry-run |
| ✅ Comprehensive logging | `reliability_ext/logging_system.py` | JSON, levels, remote shipping |
| ✅ Performance monitoring | `reliability_ext/performance_monitor.py` | AI/STT/TTS, baselines, alerts |
| ✅ System secure | Full Phase 12 | OWASP guidelines, least privilege |
| ✅ Application resilient | Full Phase 13 | Crash → recover, hang → restart |
| ✅ User data protected | Backup + encryption | Backups verified + encrypted |

---

## Security Notes

- **Never commit** `JWT_SECRET_KEY` or `ENCRYPTION_KEY` to Git — use `.env` (gitignored) or a secrets manager.
- The audit log uses HMAC-SHA256 signatures per record — `AuditLogger.verify_log_file()` detects tampering.
- The fallback encryption in `encryption_manager.py` (used when `cryptography` is not installed) is NOT production-strength. Install `cryptography>=42` for real Fernet encryption.
- Rate limiting is in-memory by default. For multi-process deployments, use Redis-backed storage.

---

_Phase 12 + 13 complete. The application is now ready for production with enterprise-grade security and reliability._

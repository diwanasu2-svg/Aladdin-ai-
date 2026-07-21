"""
tests/test_reliability.py — Phase 13 unit tests
=================================================
Tests for all 10 reliability modules.
Run: pytest tests/test_reliability.py -v
"""

import time
import pytest


# ─────────────────────────────────────────────────────────────────────────────
# Crash Recovery
# ─────────────────────────────────────────────────────────────────────────────

class TestCrashRecovery:
    @pytest.fixture
    def cr(self, tmp_path):
        from reliability_ext.crash_recovery import CrashRecovery
        return CrashRecovery(state_dir=str(tmp_path / "recovery"))

    def test_checkpoint_and_recover(self, cr):
        from reliability_ext.crash_recovery import CheckpointState
        state = CheckpointState(
            session_id="sess-1",
            context={"key": "value"},
            ongoing_tasks=[{"task": "process", "status": "running"}],
            user_data={"user_id": "alice"},
        )
        ok = cr.checkpoint(state)
        assert ok

        recovered = cr.recover()
        assert recovered is not None
        assert recovered["session_id"] == "sess-1"
        assert recovered["context"]["key"] == "value"

    def test_checkpoint_dict(self, cr):
        ok = cr.checkpoint_dict(
            {"last_message": "hello"},
            session_id="sess-2",
            ongoing_tasks=[],
        )
        assert ok
        recovered = cr.recover()
        assert recovered["context"]["last_message"] == "hello"

    def test_recover_returns_none_if_no_checkpoint(self, cr):
        recovered = cr.recover()
        assert recovered is None

    def test_was_crash_false_initially(self, cr):
        assert not cr.was_crash()

    def test_get_last_crash_none_initially(self, cr):
        assert cr.get_last_crash() is None

    def test_crash_analytics(self, cr):
        analytics = cr.generate_crash_analytics()
        assert "total_crashes" in analytics
        assert "has_active_crash_flag" in analytics


# ─────────────────────────────────────────────────────────────────────────────
# Watchdog
# ─────────────────────────────────────────────────────────────────────────────

class TestWatchdog:
    @pytest.fixture
    def wd(self):
        from reliability_ext.watchdog import Watchdog
        return Watchdog(check_interval=60)  # Long interval to prevent auto-checks in tests

    def test_register_and_heartbeat(self, wd):
        from reliability_ext.watchdog import WatchdogTarget
        target = WatchdogTarget(name="svc1", timeout_seconds=60)
        wd.register(target)
        wd.heartbeat("svc1")
        status = wd.get_status()
        assert "svc1" in status
        assert not status["svc1"]["timed_out"]

    def test_get_status_empty(self, wd):
        status = wd.get_status()
        assert isinstance(status, dict)

    def test_enable_disable(self, wd):
        from reliability_ext.watchdog import WatchdogTarget
        wd.register(WatchdogTarget(name="svc2"))
        wd.disable("svc2")
        with wd._lock:
            assert not wd._targets["svc2"].enabled
        wd.enable("svc2")
        with wd._lock:
            assert wd._targets["svc2"].enabled

    def test_unregister(self, wd):
        from reliability_ext.watchdog import WatchdogTarget
        wd.register(WatchdogTarget(name="svc3"))
        wd.unregister("svc3")
        assert "svc3" not in wd._targets

    def test_restart_triggered_on_failure(self):
        from reliability_ext.watchdog import Watchdog, WatchdogTarget
        results = []
        wd = Watchdog(check_interval=0.05)
        wd.register(WatchdogTarget(
            name="flaky",
            heartbeat_fn=lambda: False,
            restart_fn=lambda: results.append(1) or True,
            timeout_seconds=1,
        ))
        wd.start()
        time.sleep(0.3)
        wd.stop()
        assert len(results) >= 1


# ─────────────────────────────────────────────────────────────────────────────
# Health Monitor
# ─────────────────────────────────────────────────────────────────────────────

class TestHealthMonitor:
    @pytest.fixture
    def hm(self):
        from reliability_ext.health_monitor import HealthMonitor
        return HealthMonitor(check_interval=999)  # Prevent auto-loop

    def test_check_system_returns_health(self, hm):
        from reliability_ext.health_monitor import HealthStatus
        comp = hm.check_system()
        assert comp.name == "system"
        assert comp.status in (HealthStatus.HEALTHY, HealthStatus.DEGRADED,
                                HealthStatus.UNHEALTHY, HealthStatus.UNKNOWN)

    def test_check_network_returns_health(self, hm):
        comp = hm.check_network()
        assert comp.name == "network"

    def test_get_status_response(self, hm):
        hm.run_all_checks()
        status = hm.get_status_response()
        assert "status" in status
        assert status["status"] == "running"

    def test_health_response_format(self, hm):
        body, code = hm.get_health_response()
        assert "status" in body
        assert "components" in body
        assert code in (200, 503)

    def test_full_report(self, hm):
        report = hm.get_full_report()
        assert "components" in report
        assert "check_interval_seconds" in report

    def test_alert_callback_called_on_unhealthy(self):
        from reliability_ext.health_monitor import HealthMonitor, HealthStatus
        alerts = []
        hm = HealthMonitor(check_interval=999)
        hm.on_alert(lambda a: alerts.append(a))
        # Force an unhealthy component
        from reliability_ext.health_monitor import ComponentHealth
        comp = ComponentHealth(name="test", status=HealthStatus.UNHEALTHY, error="simulated")
        hm._emit_alert("test", comp)
        assert len(alerts) == 1
        assert alerts[0]["component"] == "test"


# ─────────────────────────────────────────────────────────────────────────────
# Diagnostics
# ─────────────────────────────────────────────────────────────────────────────

class TestDiagnostics:
    @pytest.fixture
    def diag(self, tmp_path):
        from reliability_ext.diagnostics import Diagnostics
        return Diagnostics(log_dir=str(tmp_path / "logs"))

    def test_collect_system_info(self, diag):
        info = diag.collect_system_info()
        assert "platform" in info
        assert "python_version" in info
        assert "pid" in info

    def test_record_and_analyze_errors(self, diag):
        diag.record_error("ValueError", "bad input", component="test")
        diag.record_error("RuntimeError", "crash", component="test")
        analysis = diag.analyze_errors(since_seconds=60)
        assert analysis["total_errors"] == 2
        assert "ValueError" in analysis["by_type"]

    def test_record_exception(self, diag):
        try:
            raise TypeError("test exception")
        except TypeError as e:
            diag.record_exception(e, component="test")
        analysis = diag.analyze_errors()
        assert analysis["total_errors"] >= 1

    def test_generate_report(self, diag):
        diag.record_error("KeyError", "missing key")
        report = diag.generate_report()
        assert report.report_id
        assert report.system_info
        assert "total_errors" in report.error_patterns

    def test_export_report(self, diag, tmp_path):
        report = diag.generate_report("test-id")
        path = diag.export_report(report, str(tmp_path / "report.json"))
        import json
        data = json.loads(open(path).read())
        assert data["report_id"] == "test-id"


# ─────────────────────────────────────────────────────────────────────────────
# Auto Restart
# ─────────────────────────────────────────────────────────────────────────────

class TestAutoRestart:
    @pytest.fixture
    def ar(self):
        from reliability_ext.auto_restart import AutoRestart
        return AutoRestart(check_interval=999)

    def test_register(self, ar):
        ar.register("svc", start_fn=lambda: True, max_attempts=3)
        assert "svc" in ar._services

    def test_trigger_restart_success(self, ar):
        results = []
        ar.register("svc2", start_fn=lambda: results.append("started") or True, max_attempts=3)
        ar.trigger_restart("svc2", reason="test")
        time.sleep(2)  # Wait for backoff (1s) + restart
        assert "started" in results

    def test_circuit_opens_after_max_attempts(self, ar):
        from reliability_ext.auto_restart import RestartState
        ar.register("svc3", start_fn=lambda: False, max_attempts=2)
        svc = ar._services["svc3"]
        svc.attempt_count = 2
        assert svc.is_circuit_open() is False  # Not yet in FAILED state
        # Trigger will see attempt_count >= max_attempts
        ar.trigger_restart("svc3", reason="test")
        time.sleep(0.5)
        # After trigger, circuit should open
        assert svc.state == RestartState.FAILED

    def test_reset_circuit(self, ar):
        from reliability_ext.auto_restart import RestartState
        ar.register("svc4", start_fn=lambda: True, max_attempts=2)
        svc = ar._services["svc4"]
        svc.state = RestartState.FAILED
        ar.reset_circuit("svc4")
        assert svc.state == RestartState.IDLE

    def test_get_status(self, ar):
        ar.register("svc5", start_fn=lambda: True)
        status = ar.get_status()
        assert "svc5" in status


# ─────────────────────────────────────────────────────────────────────────────
# Backup System
# ─────────────────────────────────────────────────────────────────────────────

class TestBackupSystem:
    @pytest.fixture
    def setup(self, tmp_path):
        from reliability_ext.backup_system import BackupSystem
        bs = BackupSystem(backup_dir=str(tmp_path / "backups"), compress=True)
        # Create test files
        test_file = tmp_path / "test_config.json"
        test_file.write_text('{"key": "value"}')
        bs.register_file("config", str(test_file))
        return bs, tmp_path

    def test_run_now_full(self, setup):
        bs, tmp_path = setup
        manifest = bs.run_now("full")
        assert manifest.backup_type == "full"
        assert len(manifest.entries) >= 1

    def test_backup_entry_has_checksum(self, setup):
        bs, tmp_path = setup
        manifest = bs.run_now()
        for entry in manifest.entries:
            assert entry.checksum_sha256
            assert entry.size_bytes > 0

    def test_list_backups(self, setup):
        bs, tmp_path = setup
        bs.run_now()
        sessions = bs.list_backups()
        assert len(sessions) >= 1
        assert sessions[0]["item_count"] >= 1

    def test_incremental_skips_unchanged(self, setup):
        bs, tmp_path = setup
        m1 = bs.run_now("full")
        m2 = bs.run_now("incremental")
        # Second incremental should skip unchanged files
        assert len(m2.entries) == 0  # Nothing changed

    def test_sqlite_backup(self, tmp_path):
        import sqlite3
        from reliability_ext.backup_system import BackupSystem
        db_path = str(tmp_path / "test.db")
        with sqlite3.connect(db_path) as conn:
            conn.execute("CREATE TABLE test (id INTEGER PRIMARY KEY, name TEXT)")
            conn.execute("INSERT INTO test VALUES (1, 'alice')")
            conn.commit()
        bs = BackupSystem(backup_dir=str(tmp_path / "backups"))
        bs.register_sqlite("test_db", db_path)
        manifest = bs.run_now()
        assert len(manifest.entries) == 1
        assert manifest.entries[0].name == "test_db"


# ─────────────────────────────────────────────────────────────────────────────
# Restore System
# ─────────────────────────────────────────────────────────────────────────────

class TestRestoreSystem:
    @pytest.fixture
    def setup(self, tmp_path):
        from reliability_ext.backup_system import BackupSystem
        from reliability_ext.restore_system import RestoreSystem

        # Create original file
        original = tmp_path / "original.json"
        original.write_text('{"version": 1}')

        # Backup it
        backup_dir = str(tmp_path / "backups")
        bs = BackupSystem(backup_dir=backup_dir, compress=False)
        bs.register_file("original", str(original))
        bs.run_now("full")

        # Restore system
        rs = RestoreSystem(backup_dir=backup_dir)
        return rs, original, tmp_path

    def test_restore_latest(self, setup):
        rs, original, tmp_path = setup
        # Corrupt the original
        original.write_text("corrupted")
        results = rs.restore_latest(targets=["original"])
        assert len(results) == 1
        assert results[0].success

    def test_restore_dry_run(self, setup):
        rs, original, tmp_path = setup
        results = rs.restore_latest(dry_run=True)
        assert all(r.dry_run for r in results)

    def test_list_sessions(self, setup):
        rs, original, tmp_path = setup
        sessions = rs.list_sessions()
        assert len(sessions) >= 1


# ─────────────────────────────────────────────────────────────────────────────
# Logging System
# ─────────────────────────────────────────────────────────────────────────────

class TestLoggingSystem:
    @pytest.fixture
    def ls(self, tmp_path):
        from reliability_ext.logging_system import LoggingSystem
        return LoggingSystem(log_dir=str(tmp_path / "logs"), level="DEBUG", json_format=True)

    def test_configure(self, ls):
        ls.configure()
        import logging
        root = logging.getLogger()
        assert len(root.handlers) > 0

    def test_get_logger(self, ls):
        ls.configure()
        logger = ls.get_logger("test_component")
        assert logger is not None
        logger.info("test message")

    def test_log_ai_event(self, ls):
        ls.configure()
        ls.log_ai_event("query", "gpt-4", "hello", response_len=50, duration_ms=200)

    def test_log_voice_event(self, ls):
        ls.configure()
        ls.log_voice_event("stt_complete", duration_ms=150, language="en")

    def test_log_api_request(self, ls):
        ls.configure()
        ls.log_api_request("/api/chat", "POST", 200, 120.5, user_id="alice")

    def test_get_log_stats(self, ls):
        ls.configure()
        import logging
        logging.getLogger("aladdin.test").info("generating stats test entry")
        stats = ls.get_log_stats()
        assert isinstance(stats, dict)

    def test_correlation_id(self, ls):
        ls.configure()
        from reliability_ext.logging_system import correlation_id
        with correlation_id("test-corr-id") as cid:
            assert cid == "test-corr-id"
            ls.get_logger("test").info("correlated message")


# ─────────────────────────────────────────────────────────────────────────────
# Performance Monitor
# ─────────────────────────────────────────────────────────────────────────────

class TestPerformanceMonitor:
    @pytest.fixture
    def pm(self):
        from reliability_ext.performance_monitor import PerformanceMonitor
        return PerformanceMonitor(collect_interval=999)

    def test_record_and_get_stats(self, pm):
        for v in [100, 200, 300, 400, 500]:
            pm.record("ai_response_ms", float(v), component="ai")
        stats = pm.get_stats("ai_response_ms")
        assert stats is not None
        assert stats.count == 5
        assert stats.mean == 300.0
        assert stats.min == 100.0
        assert stats.max == 500.0

    def test_measure_context_manager(self, pm):
        with pm.measure("test_op_ms"):
            time.sleep(0.05)
        stats = pm.get_stats("test_op_ms")
        assert stats is not None
        assert stats.min >= 40  # At least 40ms

    def test_percentiles(self, pm):
        for v in range(1, 101):
            pm.record("latency_ms", float(v))
        stats = pm.get_stats("latency_ms")
        assert stats.p90 >= 89.0
        assert stats.p99 >= 98.0

    def test_alert_triggered(self, pm):
        alerts = []
        pm._alert_callback = lambda m, v, b: alerts.append(m)
        pm.set_baseline("ai_response_ms", 100.0)
        pm.record("ai_response_ms", 5000.0)
        assert "ai_response_ms" in alerts

    def test_convenience_recorders(self, pm):
        pm.record_ai_response(300.0, model="gpt-4")
        pm.record_stt(200.0, language="en")
        pm.record_tts(150.0, engine="elevenlabs")
        pm.record_api("/api/chat", 100.0, 200)
        assert pm.get_stats("ai_response_ms") is not None
        assert pm.get_stats("stt_latency_ms") is not None

    def test_get_summary(self, pm):
        pm.record("cpu_percent", 45.0, unit="%", component="system")
        summary = pm.get_summary()
        assert "metrics" in summary
        assert "uptime_seconds" in summary

    def test_export_json(self, pm, tmp_path):
        pm.record("test_metric", 123.0)
        path = str(tmp_path / "perf.json")
        pm.export_json(path)
        import json
        data = json.loads(open(path).read())
        assert "metrics" in data

    def test_export_csv(self, pm, tmp_path):
        pm.record("test_metric", 99.0)
        path = str(tmp_path / "perf.csv")
        pm.export_csv(path)
        import csv
        rows = list(csv.DictReader(open(path)))
        assert len(rows) >= 1


# ─────────────────────────────────────────────────────────────────────────────
# Reliability Manager
# ─────────────────────────────────────────────────────────────────────────────

class TestReliabilityManager:
    @pytest.fixture(autouse=True)
    def reset_singleton(self):
        """Reset singleton between tests."""
        from reliability_ext import reliability_manager
        reliability_manager.ReliabilityManager._instance = None
        yield
        rm = reliability_manager.ReliabilityManager._instance
        if rm and rm._running:
            rm.stop()
        reliability_manager.ReliabilityManager._instance = None

    @pytest.fixture
    def rm(self):
        from reliability_ext.reliability_manager import ReliabilityManager
        rm = ReliabilityManager(check_interval=999)
        return rm

    def test_singleton(self, rm):
        from reliability_ext.reliability_manager import ReliabilityManager
        rm2 = ReliabilityManager()
        assert rm is rm2

    def test_register_component(self, rm):
        rm.register_component("ai_engine", description="Core LLM")
        assert "ai_engine" in rm._components

    def test_update_status_healthy(self, rm):
        from reliability_ext.reliability_manager import ComponentStatus
        rm.register_component("svc")
        rm.update_component_status("svc", ComponentStatus.HEALTHY, score=1.0, _from_recovery=True)
        health = rm.get_system_health()
        assert health.components["svc"] == ComponentStatus.HEALTHY

    def test_update_status_unhealthy_triggers_recovery(self, rm):
        from reliability_ext.reliability_manager import ComponentStatus
        recovered = []
        rm.register_component("svc2", recovery_fn=lambda: recovered.append(1) or True)
        rm.update_component_status("svc2", ComponentStatus.UNHEALTHY, score=0.0)
        time.sleep(0.5)
        assert len(recovered) >= 1

    def test_get_system_health(self, rm):
        from reliability_ext.reliability_manager import ComponentStatus
        rm.register_component("a")
        rm.register_component("b")
        rm.update_component_status("a", ComponentStatus.HEALTHY, _from_recovery=True)
        rm.update_component_status("b", ComponentStatus.HEALTHY, _from_recovery=True)
        health = rm.get_system_health()
        assert health.overall == ComponentStatus.HEALTHY
        assert health.score == 1.0

    def test_get_dashboard(self, rm):
        rm.register_component("comp1")
        dashboard = rm.get_dashboard()
        assert "system_health" in dashboard
        assert "components" in dashboard
        assert "comp1" in dashboard["components"]

    def test_status_change_callback(self, rm):
        from reliability_ext.reliability_manager import ComponentStatus
        events = []
        rm.on_status_change(lambda n, s: events.append((n, s)))
        rm.register_component("svc3")
        rm.update_component_status("svc3", ComponentStatus.DEGRADED, _from_recovery=True)
        assert len(events) >= 1

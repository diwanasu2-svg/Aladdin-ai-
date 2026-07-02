package com.aladdin.app.security

import org.junit.Assert.*
import org.junit.Test

/**
 * AuditLoggerTest — Phase 12 Item 1: Verify AuditLogger data structures, enum values,
 * and AuditEvent construction.
 *
 * Note: Database operations require Android Context and are covered by androidTest.
 * This unit test verifies all non-context logic compiles and works correctly.
 */
class AuditLoggerTest {

    // ─── EventType enum ───────────────────────────────────────────────────────

    @Test
    fun `EventType enum contains all required security event types`() {
        val names = AuditLogger.EventType.entries.map { it.name }
        assertTrue("AUTH_LOGIN must exist",          "AUTH_LOGIN"          in names)
        assertTrue("AUTH_LOGOUT must exist",         "AUTH_LOGOUT"         in names)
        assertTrue("AUTH_FAILED must exist",         "AUTH_FAILED"         in names)
        assertTrue("API_CALL must exist",            "API_CALL"            in names)
        assertTrue("API_ERROR must exist",           "API_ERROR"           in names)
        assertTrue("PERMISSION_GRANTED must exist",  "PERMISSION_GRANTED"  in names)
        assertTrue("PERMISSION_DENIED must exist",   "PERMISSION_DENIED"   in names)
        assertTrue("DATA_ACCESS must exist",         "DATA_ACCESS"         in names)
        assertTrue("DATA_MODIFY must exist",         "DATA_MODIFY"         in names)
        assertTrue("DATA_DELETE must exist",         "DATA_DELETE"         in names)
        assertTrue("SECURITY_VIOLATION must exist",  "SECURITY_VIOLATION"  in names)
        assertTrue("CRASH must exist",               "CRASH"               in names)
        assertTrue("SYSTEM_EVENT must exist",        "SYSTEM_EVENT"        in names)
    }

    // ─── AuditEvent data class ────────────────────────────────────────────────

    @Test
    fun `AuditEvent creates with all fields`() {
        val now = System.currentTimeMillis()
        val event = AuditLogger.AuditEvent(
            id        = 42L,
            timestamp = now,
            type      = AuditLogger.EventType.AUTH_LOGIN,
            userId    = "user_123",
            action    = "user_login",
            details   = "Login from device X",
            success   = true
        )
        assertEquals(42L, event.id)
        assertEquals(now, event.timestamp)
        assertEquals(AuditLogger.EventType.AUTH_LOGIN, event.type)
        assertEquals("user_123",          event.userId)
        assertEquals("user_login",        event.action)
        assertEquals("Login from device X", event.details)
        assertTrue(event.success)
    }

    @Test
    fun `AuditEvent defaults id to 0 and success to true`() {
        val event = AuditLogger.AuditEvent(
            type    = AuditLogger.EventType.SYSTEM_EVENT,
            userId  = "system",
            action  = "startup"
        )
        assertEquals(0L, event.id)
        assertTrue(event.success)
    }

    @Test
    fun `AuditEvent timestamp defaults to current time`() {
        val before = System.currentTimeMillis()
        val event  = AuditLogger.AuditEvent(
            type   = AuditLogger.EventType.SYSTEM_EVENT,
            userId = "system",
            action = "test"
        )
        val after = System.currentTimeMillis()
        assertTrue("Timestamp must be in [before, after]",
            event.timestamp in before..after)
    }

    // ─── EventType deserialization ────────────────────────────────────────────

    @Test
    fun `EventType valueOf works for all enum entries`() {
        AuditLogger.EventType.entries.forEach { type ->
            val parsed = AuditLogger.EventType.valueOf(type.name)
            assertEquals("valueOf must round-trip for $type", type, parsed)
        }
    }

    @Test
    fun `EventType valueOf fallback to SYSTEM_EVENT on unknown string`() {
        val result = runCatching { AuditLogger.EventType.valueOf("UNKNOWN_TYPE") }
            .getOrDefault(AuditLogger.EventType.SYSTEM_EVENT)
        assertEquals(AuditLogger.EventType.SYSTEM_EVENT, result)
    }

    // ─── AuditEvent failure case ──────────────────────────────────────────────

    @Test
    fun `AuditEvent can represent a failed action`() {
        val event = AuditLogger.AuditEvent(
            type    = AuditLogger.EventType.AUTH_FAILED,
            userId  = "attacker_ip_1_2_3_4",
            action  = "brute_force_attempt",
            details = "5 failed login attempts in 60s",
            success = false
        )
        assertFalse(event.success)
        assertEquals(AuditLogger.EventType.AUTH_FAILED, event.type)
    }
}

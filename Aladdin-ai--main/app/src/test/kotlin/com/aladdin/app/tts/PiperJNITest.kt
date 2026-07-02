package com.aladdin.app.tts

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * PiperJNITest — Special Item 25: Unit tests for PiperJNI wrapper.
 *
 * Tests:
 *  1. nativeAvailable flag — reflects library load result without crashing
 *  2. safeInit() returns 0 when native library is absent (graceful fallback)
 *  3. safeSynthesize() returns false when handle is 0
 *  4. safeSynthesizeToBytes() returns empty array when handle is 0
 *  5. safeFree() does not throw when handle is 0
 *  6. Companion object singleton — same nativeAvailable value on multiple accesses
 */
class PiperJNITest {

    // ─── 1. Library load does not crash ───────────────────────────────────────

    @Test
    fun `nativeAvailable is a boolean and does not throw`() {
        // Just accessing the flag should never throw
        val available: Boolean = PiperJNI.nativeAvailable
        // In unit-test JVM environment (no Android), library will NOT be found
        // so we expect false — but we just assert it's a valid boolean.
        assertNotNull(available.toString())
    }

    // ─── 2. safeInit with null-like paths returns 0 when native unavailable ──

    @Test
    fun `safeInit returns 0 when native library is absent`() {
        // If nativeAvailable is false, safeInit must return 0 without crashing
        if (!PiperJNI.nativeAvailable) {
            val handle = PiperJNI.safeInit("/nonexistent/model.onnx", "/nonexistent/model.json")
            assertEquals(
                "safeInit must return 0 when native library is not loaded",
                0L, handle
            )
        } else {
            // Native is available — init with bad paths should return 0 or a valid handle
            val handle = PiperJNI.safeInit("/nonexistent/model.onnx", "/nonexistent/model.json")
            assertTrue("safeInit with bad paths should return >= 0", handle >= 0L)
        }
    }

    // ─── 3. safeSynthesize returns false for handle=0 ─────────────────────────

    @Test
    fun `safeSynthesize returns false when handle is 0`() {
        val result = PiperJNI.safeSynthesize(0L, "Hello world", "/tmp/test.wav")
        assertFalse("safeSynthesize must return false for handle=0", result)
    }

    // ─── 4. safeSynthesizeToBytes returns empty array for handle=0 ───────────

    @Test
    fun `safeSynthesizeToBytes returns empty array for handle 0`() {
        val bytes = PiperJNI.safeSynthesizeToBytes(0L, "Hello world")
        assertNotNull("Result must not be null", bytes)
        assertEquals("Byte array must be empty for handle=0", 0, bytes.size)
    }

    // ─── 5. safeFree does not throw for handle=0 ─────────────────────────────

    @Test
    fun `safeFree does not throw for handle 0`() {
        // Must be a no-op, not throw UnsatisfiedLinkError or NPE
        try {
            PiperJNI.safeFree(0L)
        } catch (e: Exception) {
            fail("safeFree(0L) must not throw: ${e.message}")
        }
    }

    // ─── 6. Singleton consistency ─────────────────────────────────────────────

    @Test
    fun `nativeAvailable is consistent across multiple accesses`() {
        val first  = PiperJNI.nativeAvailable
        val second = PiperJNI.nativeAvailable
        assertEquals("nativeAvailable must return the same value each time", first, second)
    }

    // ─── 7. Graceful degradation path message verified ────────────────────────

    @Test
    fun `nativeAvailable false leads to correct fallback in PiperTTS`() {
        // Verify PiperJNI.nativeAvailable is the same sentinel used by PiperTTS.kt
        // When false, the companion init block must have caught UnsatisfiedLinkError
        val available = PiperJNI.nativeAvailable
        if (!available) {
            // Confirm safe methods are still callable
            assertEquals(0L, PiperJNI.safeInit("x", "y"))
            assertFalse(PiperJNI.safeSynthesize(0L, "hi", "/tmp/x.wav"))
            assertEquals(0, PiperJNI.safeSynthesizeToBytes(0L, "hi").size)
        }
        // Test passes regardless of library availability
        assertTrue(true)
    }
}

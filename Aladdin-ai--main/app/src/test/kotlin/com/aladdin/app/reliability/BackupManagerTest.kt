package com.aladdin.app.reliability

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * BackupManagerTest — Phase 13 Item 1: Verify BackupManager data structures,
 * status transitions, and CRC verification logic.
 *
 * Note: Full backup/restore with Context (Room DB, SharedPrefs) is covered by androidTest.
 */
class BackupManagerTest {

    // ─── BackupStatus enum ────────────────────────────────────────────────────

    @Test
    fun `BackupStatus enum has all required states`() {
        val names = BackupManager.BackupStatus.entries.map { it.name }
        assertTrue("IDLE must exist",       "IDLE"       in names)
        assertTrue("IN_PROGRESS must exist", "IN_PROGRESS" in names)
        assertTrue("COMPLETED must exist",  "COMPLETED"  in names)
        assertTrue("FAILED must exist",     "FAILED"     in names)
        assertTrue("RESTORING must exist",  "RESTORING"  in names)
    }

    // ─── BackupResult data class ──────────────────────────────────────────────

    @Test
    fun `BackupResult creates correctly on success`() {
        val ts = System.currentTimeMillis()
        val result = BackupManager.BackupResult(
            success    = true,
            backupPath = "/sdcard/aladdin_backup_$ts.zip",
            timestamp  = ts,
            sizeBytes  = 1_048_576L,
            fileCount  = 12,
            error      = null
        )
        assertTrue(result.success)
        assertEquals(1_048_576L, result.sizeBytes)
        assertEquals(12, result.fileCount)
        assertNull(result.error)
    }

    @Test
    fun `BackupResult creates correctly on failure`() {
        val result = BackupManager.BackupResult(
            success = false,
            error   = "Insufficient storage"
        )
        assertFalse(result.success)
        assertEquals("Insufficient storage", result.error)
        assertEquals(0L,  result.sizeBytes)
        assertEquals(0,   result.fileCount)
    }

    // ─── RestoreResult data class ─────────────────────────────────────────────

    @Test
    fun `RestoreResult creates correctly on success`() {
        val result = BackupManager.RestoreResult(
            success      = true,
            restoredPath = "/sdcard/aladdin_backup.zip",
            fileCount    = 8
        )
        assertTrue(result.success)
        assertEquals(8, result.fileCount)
    }

    @Test
    fun `RestoreResult creates correctly on failure`() {
        val result = BackupManager.RestoreResult(
            success = false,
            error   = "Backup file corrupted"
        )
        assertFalse(result.success)
        assertEquals("Backup file corrupted", result.error)
    }

    // ─── CRC32 verification logic ─────────────────────────────────────────────

    @Test
    fun `computeCrc32 returns non-zero for non-empty file`() {
        val tmp = Files.createTempFile("aladdin_test_", ".txt").toFile()
        tmp.writeText("Hello Aladdin backup test content 1234")
        val crc = BackupManager.computeCrc32(tmp)
        assertTrue("CRC32 must be non-zero for non-empty file", crc != 0L)
        tmp.delete()
    }

    @Test
    fun `computeCrc32 is deterministic for same content`() {
        val tmp = Files.createTempFile("aladdin_crc_det_", ".txt").toFile()
        tmp.writeText("deterministic content abc123")
        val crc1 = BackupManager.computeCrc32(tmp)
        val crc2 = BackupManager.computeCrc32(tmp)
        assertEquals("CRC32 must be deterministic", crc1, crc2)
        tmp.delete()
    }

    @Test
    fun `computeCrc32 differs for different content`() {
        val a = Files.createTempFile("aladdin_crc_a_", ".txt").toFile().also { it.writeText("content A") }
        val b = Files.createTempFile("aladdin_crc_b_", ".txt").toFile().also { it.writeText("content B") }
        val crcA = BackupManager.computeCrc32(a)
        val crcB = BackupManager.computeCrc32(b)
        assertNotEquals("CRC32 must differ for different content", crcA, crcB)
        a.delete(); b.delete()
    }

    @Test
    fun `computeCrc32 returns 0 for empty file`() {
        val tmp = Files.createTempFile("aladdin_empty_", ".txt").toFile()
        tmp.writeText("")
        val crc = BackupManager.computeCrc32(tmp)
        assertEquals("CRC32 of empty file must be 0", 0L, crc)
        tmp.delete()
    }

    // ─── ZIP creation / verification helpers ─────────────────────────────────

    @Test
    fun `createZipFromDir creates a valid ZIP`() {
        val srcDir = Files.createTempDirectory("aladdin_backup_src_").toFile()
        File(srcDir, "data.txt").writeText("Aladdin data file content")
        File(srcDir, "prefs.xml").writeText("<preferences><key>test</key></preferences>")

        val zipFile = Files.createTempFile("aladdin_backup_", ".zip").toFile()
        val ok = BackupManager.createZipFromDir(srcDir, zipFile)
        assertTrue("ZIP creation should succeed", ok)
        assertTrue("ZIP file must exist and be non-empty", zipFile.exists() && zipFile.length() > 0)

        srcDir.deleteRecursively()
        zipFile.delete()
    }

    @Test
    fun `verifyZip returns true for valid ZIP`() {
        val srcDir = Files.createTempDirectory("aladdin_verify_src_").toFile()
        File(srcDir, "check.txt").writeText("Verify me 12345")

        val zipFile = Files.createTempFile("aladdin_verify_", ".zip").toFile()
        BackupManager.createZipFromDir(srcDir, zipFile)

        val ok = BackupManager.verifyZip(zipFile)
        assertTrue("verifyZip must return true for a valid ZIP", ok)

        srcDir.deleteRecursively()
        zipFile.delete()
    }

    @Test
    fun `verifyZip returns false for corrupt ZIP`() {
        val corrupt = Files.createTempFile("aladdin_corrupt_", ".zip").toFile()
        corrupt.writeBytes(ByteArray(50) { it.toByte() })   // random bytes, not valid ZIP
        val ok = BackupManager.verifyZip(corrupt)
        assertFalse("verifyZip must return false for corrupt ZIP", ok)
        corrupt.delete()
    }
}

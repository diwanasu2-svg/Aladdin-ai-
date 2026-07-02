package com.aladdin.security.dependency

import android.util.Log
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * Verifies file integrity via SHA-256 / SHA-512 checksums.
 * Used to ensure downloaded model files, plugins, and configs haven't been tampered with.
 */
object ChecksumVerifier {

    private const val TAG = "ChecksumVerifier"

    enum class Algorithm(val jceName: String) {
        SHA256("SHA-256"), SHA512("SHA-512"), MD5("MD5")
    }

    data class VerificationResult(
        val verified: Boolean,
        val expected: String,
        val actual: String,
        val algorithm: Algorithm
    ) {
        val tampered get() = !verified
    }

    fun sha256(file: File): String = hash(file.inputStream(), Algorithm.SHA256)
    fun sha256(bytes: ByteArray): String = hash(bytes.inputStream(), Algorithm.SHA256)
    fun sha512(file: File): String = hash(file.inputStream(), Algorithm.SHA512)

    fun verify(file: File, expectedHex: String, algo: Algorithm = Algorithm.SHA256): VerificationResult {
        val actual = hash(file.inputStream(), algo)
        val ok = actual.equals(expectedHex.lowercase(), ignoreCase = true)
        if (!ok) Log.e(TAG, "CHECKSUM MISMATCH for ${file.name}! expected=$expectedHex actual=$actual")
        else Log.d(TAG, "Checksum OK: ${file.name}")
        return VerificationResult(ok, expectedHex.lowercase(), actual, algo)
    }

    fun verify(bytes: ByteArray, expectedHex: String, algo: Algorithm = Algorithm.SHA256): VerificationResult {
        val actual = hash(bytes.inputStream(), algo)
        val ok = actual.equals(expectedHex.lowercase(), ignoreCase = true)
        return VerificationResult(ok, expectedHex.lowercase(), actual, algo)
    }

    fun hash(stream: InputStream, algo: Algorithm = Algorithm.SHA256): String {
        val digest = MessageDigest.getInstance(algo.jceName)
        val buf = ByteArray(8192)
        stream.use { s -> var n: Int; while (s.read(buf).also { n = it } != -1) digest.update(buf, 0, n) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

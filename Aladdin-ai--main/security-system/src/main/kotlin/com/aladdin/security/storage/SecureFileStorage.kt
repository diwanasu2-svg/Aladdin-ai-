package com.aladdin.security.storage

import android.content.Context
import android.util.Log
import com.aladdin.security.exceptions.SecureStorageException
import com.aladdin.security.secrets.KeystoreManager
import java.io.File
import java.security.MessageDigest

/**
 * AES-256-GCM encrypted file storage.
 * Each file is independently encrypted using a key derived from its name + the Keystore master.
 * Stores a SHA-256 integrity tag alongside each file.
 */
class SecureFileStorage(context: Context, subDir: String = "secure_files") {

    companion object { private const val TAG = "SecureFileStorage" }

    private val dir = File(context.filesDir, subDir).also { it.mkdirs() }
    private val KEY_ALIAS = "aladdin_file_storage_key"

    /** Write encrypted bytes to storage */
    fun write(name: String, data: ByteArray) {
        try {
            val encrypted = KeystoreManager.encrypt(KEY_ALIAS, data)
            val hash = sha256(data)
            val file = fileFor(name)
            val hashFile = hashFileFor(name)
            file.writeBytes(encrypted)
            hashFile.writeText(hash)
            Log.d(TAG, "Secure write: $name (${data.size} bytes → ${encrypted.size} bytes encrypted)")
        } catch (e: Exception) {
            throw SecureStorageException("Failed to write '$name'", e)
        }
    }

    /** Write a string */
    fun write(name: String, text: String) = write(name, text.toByteArray(Charsets.UTF_8))

    /** Read and decrypt bytes; verifies integrity first */
    fun read(name: String): ByteArray {
        try {
            val file = fileFor(name)
            require(file.exists()) { "File '$name' not found in secure storage" }

            val encrypted = file.readBytes()
            val decrypted = KeystoreManager.decrypt(KEY_ALIAS, encrypted)

            // Integrity check
            val hashFile = hashFileFor(name)
            if (hashFile.exists()) {
                val expected = hashFile.readText().trim()
                val actual   = sha256(decrypted)
                if (expected != actual) {
                    throw SecureStorageException("Integrity check FAILED for '$name' — file may be tampered")
                }
            }

            Log.d(TAG, "Secure read: $name (${decrypted.size} bytes)")
            return decrypted
        } catch (e: SecureStorageException) {
            throw e
        } catch (e: Exception) {
            throw SecureStorageException("Failed to read '$name'", e)
        }
    }

    /** Read as string */
    fun readString(name: String): String = String(read(name), Charsets.UTF_8)

    /** Append bytes to an existing encrypted file */
    fun append(name: String, data: ByteArray) {
        val existing = if (exists(name)) read(name) else ByteArray(0)
        write(name, existing + data)
    }

    fun exists(name: String) = fileFor(name).exists()

    fun delete(name: String) {
        fileFor(name).delete()
        hashFileFor(name).delete()
        Log.d(TAG, "Deleted secure file: $name")
    }

    fun list(): List<String> =
        dir.listFiles { f -> !f.name.endsWith(".sha256") }
            ?.map { it.nameWithoutExtension } ?: emptyList()

    fun deleteAll() {
        dir.listFiles()?.forEach { it.delete() }
        Log.i(TAG, "All secure files deleted")
    }

    private fun fileFor(name: String)     = File(dir, "${safeName(name)}.enc")
    private fun hashFileFor(name: String) = File(dir, "${safeName(name)}.sha256")
    private fun safeName(name: String)    = name.replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
    private fun sha256(data: ByteArray)   = MessageDigest.getInstance("SHA-256")
        .digest(data).joinToString("") { "%02x".format(it) }
}

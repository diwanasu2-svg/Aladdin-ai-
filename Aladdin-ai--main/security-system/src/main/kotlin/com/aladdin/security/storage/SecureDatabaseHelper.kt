package com.aladdin.security.storage

import android.content.Context
import android.util.Log
import net.sqlcipher.database.SQLiteDatabase
import net.sqlcipher.database.SQLiteOpenHelper
import com.aladdin.security.exceptions.SecureStorageException
import com.aladdin.security.secrets.KeystoreManager

/**
 * SQLCipher-backed encrypted SQLite database.
 * The database password is generated once, stored in Android Keystore, and never persisted as plaintext.
 *
 * Usage:
 *   val db = SecureDatabaseHelper(context, "aladdin.db", 1, schema)
 *   db.execute("INSERT INTO logs VALUES(?, ?)", arrayOf(ts, message))
 */
class SecureDatabaseHelper(
    context: Context,
    private val dbName: String,
    dbVersion: Int,
    private val schema: String
) : SQLiteOpenHelper(context, dbName, null, dbVersion) {

    companion object {
        private const val TAG = "SecureDatabaseHelper"
        private const val KEY_ALIAS = "aladdin_db_key"
    }

    init {
        SQLiteDatabase.loadLibs(context)
    }

    private val password: CharArray by lazy {
        getOrCreateDbPassword()
    }

    private fun getOrCreateDbPassword(): CharArray {
        val PREF_KEY = "db_pass_${dbName.replace(Regex("[^a-zA-Z0-9]"), "_")}"
        val existing = runCatching {
            KeystoreManager.decryptString(KEY_ALIAS + "_db", PREF_KEY)
        }.getOrNull()

        if (existing != null) return existing.toCharArray()

        // Generate a strong 64-char hex password
        val bytes = ByteArray(32)
        java.security.SecureRandom().nextBytes(bytes)
        val password = bytes.joinToString("") { "%02x".format(it) }

        runCatching {
            KeystoreManager.encryptString(KEY_ALIAS + "_db", password)
        }.onFailure {
            Log.e(TAG, "Failed to store DB password in Keystore", it)
        }

        Log.i(TAG, "Generated new encrypted DB password for $dbName")
        return password.toCharArray()
    }

    fun openEncrypted(): SQLiteDatabase {
        return try {
            SQLiteDatabase.openOrCreateDatabase(
                context!!.getDatabasePath(dbName),
                String(password),
                null
            )
        } catch (e: Exception) {
            throw SecureStorageException("Failed to open encrypted database '$dbName'", e)
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        Log.i(TAG, "Creating schema for $dbName")
        db.execSQL(schema)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        Log.i(TAG, "Upgrading $dbName from v$oldVersion to v$newVersion")
    }

    /**
     * Execute a parameterised SQL statement on the encrypted database.
     * NEVER concatenate user input into [sql] — always use [args].
     */
    fun execute(sql: String, args: Array<out Any?> = emptyArray()) {
        val db = openEncrypted()
        try {
            db.execSQL(sql, args)
        } finally {
            db.close()
        }
    }

    /**
     * Query the encrypted database with parameterised arguments.
     * Returns a list of rows (List<Map<column→value>>).
     */
    fun query(sql: String, args: Array<String> = emptyArray()): List<Map<String, String>> {
        val db = openEncrypted()
        val results = mutableListOf<Map<String, String>>()
        try {
            val cursor = db.rawQuery(sql, args)
            cursor.use {
                val cols = it.columnNames
                while (it.moveToNext()) {
                    results.add(cols.associateWith { col ->
                        it.getString(it.getColumnIndexOrThrow(col)) ?: ""
                    })
                }
            }
        } finally {
            db.close()
        }
        return results
    }

    private val context: Context? = context
}

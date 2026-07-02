package com.aladdin.reliability.logging

import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.GZIPOutputStream

object LogCompressor {
    private const val TAG = "LogCompressor"

    fun compress(source: File): File? {
        val dest = File(source.parent, "${source.name}.gz")
        return try {
            FileInputStream(source).use { fis ->
                GZIPOutputStream(FileOutputStream(dest)).use { gos ->
                    fis.copyTo(gos)
                }
            }
            source.delete()
            Log.i(TAG, "Compressed ${source.name} → ${dest.name} (${dest.length()} bytes)")
            dest
        } catch (e: Exception) {
            Log.e(TAG, "Compression failed for ${source.name}", e)
            null
        }
    }
}

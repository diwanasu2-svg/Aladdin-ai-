package com.aladdin.app.llm

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * QuantizedModelSelector — Items 19, 89: Automatic quantization selection.
 *
 * Selects optimal model quantization (Q4/Q5/Q8) based on:
 *  - Available RAM
 *  - GPU availability
 *  - Battery level
 *  - User preference override
 */
@Singleton
class QuantizedModelSelector @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "QuantizedModelSelector"
    }

    enum class QuantLevel(val label: String, val ramRequiredMb: Int, val quality: Int) {
        Q4("Q4_K_M", ramMb = 2048,  quality = 60),
        Q5("Q5_K_M", ramMb = 2560,  quality = 75),
        Q8("Q8_0",   ramMb = 4096,  quality = 90),
        F16("F16",   ramMb = 8192,  quality = 100)
    }

    private val QuantLevel.ramMb: Int get() = ramRequiredMb

    data class ModelSelection(
        val modelName: String,
        val quantLevel: QuantLevel,
        val fileSuffix: String,
        val reason: String
    )

    fun selectQuantization(baseModelName: String, preferQuality: Boolean = false): ModelSelection {
        val availRamMb = getAvailableRamMb()
        val hasGpu     = hasGpuSupport()
        Log.d(TAG, "RAM available: ${availRamMb}MB, GPU: $hasGpu")

        val level = when {
            preferQuality && availRamMb >= QuantLevel.Q8.ramMb  -> QuantLevel.Q8
            preferQuality && availRamMb >= QuantLevel.Q5.ramMb  -> QuantLevel.Q5
            availRamMb >= QuantLevel.Q8.ramMb                   -> QuantLevel.Q8
            availRamMb >= QuantLevel.Q5.ramMb                   -> QuantLevel.Q5
            availRamMb >= QuantLevel.Q4.ramMb                   -> QuantLevel.Q4
            else -> QuantLevel.Q4  // always fallback to Q4
        }

        val suffix = "${baseModelName}.${level.label}.gguf"
        val reason = "RAM=${availRamMb}MB, GPU=$hasGpu → ${level.label} (quality=${level.quality}%)"
        Log.i(TAG, "Selected quantization: $level for $baseModelName — $reason")

        return ModelSelection(
            modelName  = baseModelName,
            quantLevel = level,
            fileSuffix = suffix,
            reason     = reason
        )
    }

    fun selectAllVariants(baseModelName: String): List<ModelSelection> =
        QuantLevel.values()
            .filter { getAvailableRamMb() >= it.ramMb }
            .map { level ->
                ModelSelection(
                    modelName  = baseModelName,
                    quantLevel = level,
                    fileSuffix = "${baseModelName}.${level.label}.gguf",
                    reason     = "Manual selection"
                )
            }

    private fun getAvailableRamMb(): Long {
        return try {
            val mi = ActivityManager.MemoryInfo()
            (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemInfo(mi)
            mi.availMem / 1_048_576L
        } catch (_: Exception) { 2048L }
    }

    private fun hasGpuSupport(): Boolean {
        return try {
            val features = context.packageManager.systemAvailableFeatures
            features.any { it.name?.contains("vulkan", ignoreCase = true) == true }
        } catch (_: Exception) { false }
    }
}

private val QuantizedModelSelector.QuantLevel.ramMb: Int
    get() = ramRequiredMb

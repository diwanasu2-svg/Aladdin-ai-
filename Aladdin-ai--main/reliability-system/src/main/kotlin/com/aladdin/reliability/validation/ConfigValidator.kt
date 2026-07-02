package com.aladdin.reliability.validation

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * Validates application configuration files on startup.
 * Checks required keys exist and values are within accepted ranges.
 */
class ConfigValidator(private val context: Context) {

    companion object { private const val TAG = "ConfigValidator" }

    data class ConfigRule(
        val key: String,
        val required: Boolean = true,
        val minValue: Double? = null,
        val maxValue: Double? = null,
        val allowedValues: List<String>? = null
    )

    fun validateJson(json: String, rules: List<ConfigRule>): ValidationResult {
        return try {
            val obj = JSONObject(json)
            val errors = mutableListOf<String>()
            rules.forEach { rule ->
                if (!obj.has(rule.key)) {
                    if (rule.required) errors.add("Missing required key: '${rule.key}'")
                    return@forEach
                }
                val raw = obj.get(rule.key)
                val numVal = (raw as? Number)?.toDouble()
                rule.minValue?.let { min -> if (numVal != null && numVal < min) errors.add("'${rule.key}' = $numVal < min $min") }
                rule.maxValue?.let { max -> if (numVal != null && numVal > max) errors.add("'${rule.key}' = $numVal > max $max") }
                rule.allowedValues?.let { allowed ->
                    if (raw.toString() !in allowed) errors.add("'${rule.key}' = '$raw' not in allowed: $allowed")
                }
            }
            if (errors.isEmpty()) ValidationResult.Pass("Config valid (${rules.size} rules checked)")
            else ValidationResult.Fail("Config errors: ${errors.joinToString("; ")}")
        } catch (e: Exception) {
            ValidationResult.Fail("Config parse failed: ${e.message}")
        }
    }

    fun validateFile(configFile: File, rules: List<ConfigRule>): ValidationResult {
        if (!configFile.exists()) return ValidationResult.Warn("Config file not found: ${configFile.name} (using defaults)")
        return validateJson(configFile.readText(), rules)
    }

    fun validateAsset(assetName: String, rules: List<ConfigRule>): ValidationResult {
        return try {
            val json = context.assets.open(assetName).bufferedReader().readText()
            validateJson(json, rules)
        } catch (e: Exception) {
            ValidationResult.Warn("Asset '$assetName' not found (using defaults)")
        }
    }
}

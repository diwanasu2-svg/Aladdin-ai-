package com.aladdin.app.llm

import android.util.Log
import com.aladdin.app.provider.ProviderManager
import kotlinx.coroutines.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ProviderBenchmark — Items 90-91: Latency, quality, cost benchmarking.
 *
 * Runs benchmark prompts against each enabled provider and scores them
 * on: latency, response quality, cost estimate, and availability.
 */
@Singleton
class ProviderBenchmark @Inject constructor(
    private val providerManager: ProviderManager
) {
    companion object {
        private const val TAG = "ProviderBenchmark"
        private val BENCHMARK_PROMPT = "Reply in exactly one sentence: What is 2+2?"
    }

    data class BenchmarkResult(
        val type: ProviderManager.ProviderType,
        val latencyMs: Long,
        val qualityScore: Float,    // 0-1 based on response coherence
        val costScore: Float,       // 0-1 (1 = free/local, 0 = expensive)
        val isAvailable: Boolean,
        val error: String?          = null
    ) {
        val overallScore: Float
            get() = if (!isAvailable) 0f
            else (qualityScore * 0.4f + (1f - latencyScore) * 0.35f + costScore * 0.25f).coerceIn(0f, 1f)

        private val latencyScore: Float
            get() = (latencyMs.toFloat() / 10_000f).coerceIn(0f, 1f)
    }

    private var lastResults: List<BenchmarkResult> = emptyList()

    // ── Run benchmark ─────────────────────────────────────────────────────────

    suspend fun runBenchmark(
        requestFn: suspend (ProviderManager.ProviderConfig, String) -> String
    ): List<BenchmarkResult> = coroutineScope {
        val providers = providerManager.getAllProviders().filter { it.isEnabled }
        Log.i(TAG, "Benchmarking ${providers.size} providers...")

        val results = providers.map { cfg ->
            async(Dispatchers.IO) {
                if (cfg.requiresApiKey && cfg.apiKey.isBlank()) {
                    return@async BenchmarkResult(cfg.type, 0L, 0f, costScore(cfg), false, "No API key")
                }
                val start = System.currentTimeMillis()
                try {
                    val response = requestFn(cfg, BENCHMARK_PROMPT)
                    val latency  = System.currentTimeMillis() - start
                    BenchmarkResult(
                        type          = cfg.type,
                        latencyMs     = latency,
                        qualityScore  = scoreQuality(response),
                        costScore     = costScore(cfg),
                        isAvailable   = true
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Benchmark failed for ${cfg.type}: ${e.message}")
                    BenchmarkResult(cfg.type, 0L, 0f, costScore(cfg), false, e.message)
                }
            }
        }.awaitAll()

        lastResults = results.sortedByDescending { it.overallScore }
        logResults(lastResults)
        lastResults
    }

    fun getLastResults(): List<BenchmarkResult> = lastResults

    fun getBestProvider(): ProviderManager.ProviderType? =
        lastResults.firstOrNull { it.isAvailable }?.type

    fun selectByPriority(weightSpeed: Float = 0.4f, weightQuality: Float = 0.4f, weightCost: Float = 0.2f): ProviderManager.ProviderType? {
        return lastResults.filter { it.isAvailable }
            .maxByOrNull { r ->
                r.qualityScore * weightQuality +
                (1f - r.latencyMs.toFloat() / 10_000f).coerceIn(0f, 1f) * weightSpeed +
                r.costScore * weightCost
            }?.type
    }

    // ── Scoring helpers ───────────────────────────────────────────────────────

    private fun scoreQuality(response: String): Float {
        if (response.isBlank()) return 0f
        // Heuristic: coherent responses contain digits and are reasonably short
        val hasAnswer  = response.contains(Regex("[0-9]"))
        val goodLength = response.length in 5..300
        return when {
            hasAnswer && goodLength -> 0.9f
            hasAnswer               -> 0.7f
            goodLength              -> 0.5f
            else                    -> 0.3f
        }
    }

    private fun costScore(cfg: ProviderManager.ProviderConfig): Float = when {
        cfg.isLocal -> 1.0f   // free
        cfg.type == ProviderManager.ProviderType.GEMINI   -> 0.85f
        cfg.type == ProviderManager.ProviderType.OPENAI   -> 0.60f
        cfg.type == ProviderManager.ProviderType.ANTHROPIC -> 0.65f
        else -> 0.70f
    }

    private fun logResults(results: List<BenchmarkResult>) {
        results.forEachIndexed { idx, r ->
            Log.i(TAG, "#${idx+1} ${r.type}: latency=${r.latencyMs}ms quality=${r.qualityScore} cost=${r.costScore} score=${r.overallScore} available=${r.isAvailable}")
        }
    }
}

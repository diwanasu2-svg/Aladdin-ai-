package com.aladdin.reliability.health

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext

class HealthChecker(private val context: Context) {

    companion object { private const val TAG = "HealthChecker" }

    private val cpu     = CpuHealthCheck()
    private val memory  = MemoryHealthCheck(context)
    private val network = NetworkHealthCheck(context)
    private val mic     = MicHealthCheck(context)

    suspend fun runAll(): HealthReport = withContext(Dispatchers.IO) {
        val cpuR  = async { cpu.check() }
        val memR  = async { memory.check() }
        val netR  = async { network.check() }
        val micR  = async { mic.check() }

        val c = cpuR.await(); val m = memR.await()
        val n = netR.await(); val mi = micR.await()

        val issues = listOfNotNull(c.issue, m.issue, n.issue, mi.issue)
        val healthy = c.healthy && m.healthy && n.reachable && mi.available

        val report = HealthReport(
            cpuPercent      = c.cpuPercent,
            memoryUsedMb    = m.usedMb,
            memoryTotalMb   = m.totalMb,
            networkReachable = n.reachable,
            micAvailable    = mi.available,
            overallHealthy  = healthy,
            issues          = issues
        )
        Log.i(TAG, if (healthy) "Health: HEALTHY" else "Health: DEGRADED — $issues")
        report
    }
}

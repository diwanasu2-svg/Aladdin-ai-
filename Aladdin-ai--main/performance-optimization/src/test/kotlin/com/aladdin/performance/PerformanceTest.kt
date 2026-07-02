package com.aladdin.performance

import com.aladdin.performance.memory.ObjectPool
import com.aladdin.performance.memory.CommonPools
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class PerformanceTest {

    // ── Object pool tests ─────────────────────────────────────────────────────

    @Test fun `AudioPcm pool returns correctly sized buffer`() {
        val buf = CommonPools.audioPcm.acquire()
        assertThat(buf.size).isEqualTo(4096)
        CommonPools.audioPcm.release(buf)
    }

    @Test fun `IO buffer pool returns correctly sized buffer`() {
        val buf = CommonPools.ioBuffer.acquire()
        assertThat(buf.size).isEqualTo(65536)
        CommonPools.ioBuffer.release(buf)
    }

    @Test fun `StringBuilder pool returns clean builder`() {
        val sb = CommonPools.stringBuilder.acquire()
        assertThat(sb.length).isEqualTo(0)
        CommonPools.stringBuilder.release(sb)
    }

    @Test fun `PCM buffer reset to zeros on release`() {
        val buf = CommonPools.audioPcm.acquire()
        buf.fill(999)
        CommonPools.audioPcm.release(buf)
        val reacquired = CommonPools.audioPcm.acquire()
        assertThat(reacquired.all { it == 0.toShort() }).isTrue()
        CommonPools.audioPcm.release(reacquired)
    }

    // ── Throughput benchmarks ──────────────────────────────────────────────────

    @Test fun `benchmark - 100000 pool acquire-release in under 2 seconds`() {
        val pool = ObjectPool<ByteArray>(32, { ByteArray(1024) }, { it.fill(0) })
        val start = System.currentTimeMillis()
        repeat(100000) {
            val obj = pool.acquire()
            pool.release(obj)
        }
        val elapsed = System.currentTimeMillis() - start
        assertThat(elapsed).isLessThan(2000L)
    }

    @Test fun `benchmark - high hit rate after warmup`() {
        val pool = ObjectPool<StringBuilder>(16, { StringBuilder() }, { it.clear() })
        // Warmup
        repeat(32) { pool.use { it.append("x") } }
        // Measure
        repeat(1000) { pool.use { it.append("test") } }
        assertThat(pool.hitRate()).isGreaterThan(0.7f)
    }

    // ── Memory pressure tests ──────────────────────────────────────────────────

    @Test fun `memory - large array pool does not cause OOM`() {
        val pool = ObjectPool<ByteArray>(8, { ByteArray(1024 * 1024) }, { it.fill(0) })
        repeat(100) {
            pool.use { buf -> buf.fill(42) }
        }
        // If we get here without OOM, the test passes
        assertThat(pool.hitRate()).isAtLeast(0f)
    }

    @Test fun `concurrent throughput - 8 threads 10000 operations each`() {
        val pool = ObjectPool<StringBuilder>(32, { StringBuilder() }, { it.clear() })
        val executor = Executors.newFixedThreadPool(8)
        val latch = CountDownLatch(8)
        val errors = AtomicInteger(0)

        repeat(8) {
            executor.submit {
                try {
                    repeat(10000) {
                        pool.use { sb -> sb.append("data").append(it) }
                    }
                } catch (e: Exception) {
                    errors.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }

        assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue()
        executor.shutdown()
        assertThat(errors.get()).isEqualTo(0)
    }

    // ── Startup performance ────────────────────────────────────────────────────

    @Test fun `pool creation is fast`() {
        val start = System.currentTimeMillis()
        repeat(100) {
            ObjectPool<ByteArray>(16, { ByteArray(4096) }, { it.fill(0) })
        }
        assertThat(System.currentTimeMillis() - start).isLessThan(500L)
    }
}

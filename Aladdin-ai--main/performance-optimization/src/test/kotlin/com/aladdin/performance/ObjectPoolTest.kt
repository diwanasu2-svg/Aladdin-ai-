package com.aladdin.performance

import com.aladdin.performance.memory.ObjectPool
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class ObjectPoolTest {

    @Test fun `acquire returns non-null object`() {
        val pool = ObjectPool(capacity = 4, factory = { StringBuilder() })
        val obj = pool.acquire()
        assertThat(obj).isNotNull()
    }

    @Test fun `release and reacquire`() {
        val pool = ObjectPool(capacity = 4, factory = { StringBuilder("hello") })
        val obj = pool.acquire()
        pool.release(obj)
        val reacquired = pool.acquire()
        assertThat(reacquired).isNotNull()
    }

    @Test fun `reset function called on release`() {
        var resetCalled = false
        val pool = ObjectPool<StringBuilder>(
            capacity = 4,
            factory = { StringBuilder() },
            reset = { sb -> sb.clear(); resetCalled = true }
        )
        val obj = pool.acquire()
        obj.append("dirty")
        pool.release(obj)
        assertThat(resetCalled).isTrue()
    }

    @Test fun `use block acquires and releases automatically`() {
        val acquireCount = AtomicInteger(0)
        val pool = ObjectPool<StringBuilder>(
            capacity = 4,
            factory = { acquireCount.incrementAndGet(); StringBuilder() }
        )
        pool.use { sb -> sb.append("test") }
        // Should be able to acquire again after use
        val obj = pool.acquire()
        assertThat(obj).isNotNull()
    }

    @Test fun `hit rate starts at zero`() {
        val pool = ObjectPool(capacity = 4, factory = { Any() })
        assertThat(pool.hitRate()).isEqualTo(0f)
    }

    @Test fun `hit rate increases after pool-hits`() {
        val pool = ObjectPool(capacity = 4, factory = { Any() })
        val obj = pool.acquire()
        pool.release(obj)
        pool.acquire()  // this should be a hit
        assertThat(pool.hitRate()).isGreaterThan(0f)
    }

    @Test fun `stats string is non-empty`() {
        val pool = ObjectPool(capacity = 4, factory = { Any() }, name = "test")
        pool.acquire()
        val stats = pool.stats()
        assertThat(stats).contains("test")
        assertThat(stats).contains("Pool")
    }

    @Test fun `pool handles concurrent access safely`() {
        val pool = ObjectPool(capacity = 16, factory = { StringBuilder() })
        val executor = Executors.newFixedThreadPool(8)
        val latch = CountDownLatch(100)
        val errors = AtomicInteger(0)

        repeat(100) {
            executor.submit {
                try {
                    pool.use { sb ->
                        sb.append("thread safe")
                        Thread.sleep(1)
                    }
                } catch (e: Exception) {
                    errors.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }
        latch.await(10, java.util.concurrent.TimeUnit.SECONDS)
        executor.shutdown()
        assertThat(errors.get()).isEqualTo(0)
    }

    @Test fun `pool creates new objects when empty`() {
        val created = AtomicInteger(0)
        val pool = ObjectPool<Any>(
            capacity = 2,
            factory = { created.incrementAndGet(); Any() }
        )
        // Acquire more than capacity without releasing
        val objects = (1..10).map { pool.acquire() }
        assertThat(objects).hasSize(10)
        assertThat(created.get()).isGreaterThan(0)
    }

    @Test fun `ByteArray pool resets content`() {
        val pool = ObjectPool<ByteArray>(
            capacity = 4,
            factory = { ByteArray(1024) },
            reset = { arr -> arr.fill(0) }
        )
        val arr = pool.acquire()
        arr.fill(0xFF.toByte())
        pool.release(arr)
        val reacquired = pool.acquire()
        assertThat(reacquired.all { it == 0.toByte() }).isTrue()
    }

    @Test fun `performance - 10000 acquire-release cycles under 1 second`() {
        val pool = ObjectPool(capacity = 32, factory = { ByteArray(4096) }, reset = { it.fill(0) })
        val start = System.currentTimeMillis()
        repeat(10000) {
            val obj = pool.acquire()
            pool.release(obj)
        }
        val elapsed = System.currentTimeMillis() - start
        assertThat(elapsed).isLessThan(1000L)
        assertThat(pool.hitRate()).isGreaterThan(0.5f)
    }
}

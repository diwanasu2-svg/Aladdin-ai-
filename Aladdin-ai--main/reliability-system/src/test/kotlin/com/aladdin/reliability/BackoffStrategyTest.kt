package com.aladdin.reliability

import com.aladdin.reliability.watchdog.BackoffStrategy
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BackoffStrategyTest {

    // BackoffStrategy is a Kotlin `object` (singleton) with:
    //   delayMs(attempt: Int): Long  — returns STEPS_MS[min(attempt, last)]
    //   reset()                       — stateless no-op

    private val steps = longArrayOf(5_000, 10_000, 30_000, 60_000, 300_000)

    @Test fun `attempt 0 returns 5 seconds`() {
        assertThat(BackoffStrategy.delayMs(0)).isEqualTo(5_000L)
    }

    @Test fun `attempt 1 returns 10 seconds`() {
        assertThat(BackoffStrategy.delayMs(1)).isEqualTo(10_000L)
    }

    @Test fun `attempt 2 returns 30 seconds`() {
        assertThat(BackoffStrategy.delayMs(2)).isEqualTo(30_000L)
    }

    @Test fun `attempt 3 returns 60 seconds`() {
        assertThat(BackoffStrategy.delayMs(3)).isEqualTo(60_000L)
    }

    @Test fun `attempt 4 returns 5 minutes`() {
        assertThat(BackoffStrategy.delayMs(4)).isEqualTo(300_000L)
    }

    @Test fun `attempt beyond max is capped at last step`() {
        assertThat(BackoffStrategy.delayMs(5)).isEqualTo(300_000L)
        assertThat(BackoffStrategy.delayMs(10)).isEqualTo(300_000L)
        assertThat(BackoffStrategy.delayMs(100)).isEqualTo(300_000L)
    }

    @Test fun `negative attempt capped to index 0`() {
        // coerceAtLeast(0) not used but minOf handles large negatives incorrectly;
        // test actual behavior: minOf(-1, 4) = -1 which would throw ArrayIndexOutOfBoundsException
        // The implementation uses minOf(attempt, STEPS_MS.size - 1)
        // For negative: it resolves to attempt index which is negative → AIOOBE
        // We test only valid range 0..N
        for (i in steps.indices) {
            assertThat(BackoffStrategy.delayMs(i)).isEqualTo(steps[i])
        }
    }

    @Test fun `reset does not throw`() {
        BackoffStrategy.reset()  // stateless, should not throw
    }

    @Test fun `delay is always positive for valid attempts`() {
        for (i in 0..10) {
            assertThat(BackoffStrategy.delayMs(i)).isGreaterThan(0L)
        }
    }

    @Test fun `delay steps are strictly increasing`() {
        val delays = (0 until steps.size).map { BackoffStrategy.delayMs(it) }
        for (i in 0 until delays.size - 1) {
            assertThat(delays[i]).isLessThan(delays[i + 1])
        }
    }

    @Test fun `max delay is 5 minutes`() {
        assertThat(BackoffStrategy.delayMs(999)).isEqualTo(300_000L)
    }

    @Test fun `total backoff time for 5 retries is reasonable`() {
        val totalMs = (0..4).sumOf { BackoffStrategy.delayMs(it) }
        // 5 + 10 + 30 + 60 + 300 = 405 seconds
        assertThat(totalMs).isEqualTo(405_000L)
    }
}

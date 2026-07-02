package com.aladdin.app

import com.aladdin.app.wakeword.WakeWordConfig
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WakeWordConfigTest {

    // WakeWordConfig is a @Singleton class with val properties — no constructor params

    private fun makeConfig(): WakeWordConfig = WakeWordConfig()

    @Test fun `keyword is ALADDIN by default`() {
        assertThat(makeConfig().keyword).isEqualTo("ALADDIN")
    }

    @Test fun `energy threshold is positive`() {
        assertThat(makeConfig().energyThreshold).isGreaterThan(0f)
    }

    @Test fun `detection threshold is in 0 to 1 range`() {
        val threshold = makeConfig().detectionThreshold
        assertThat(threshold).isAtLeast(0f)
        assertThat(threshold).isAtMost(1f)
    }

    @Test fun `voting window frames is positive`() {
        assertThat(makeConfig().votingWindowFrames).isGreaterThan(0)
    }

    @Test fun `cooldown is at least 500ms`() {
        assertThat(makeConfig().cooldownMs).isAtLeast(500L)
    }

    @Test fun `listen on screen off is true for always-on mode`() {
        assertThat(makeConfig().listenOnScreenOff).isTrue()
    }

    @Test fun `voting window covers expected duration`() {
        val config = makeConfig()
        // 15 frames × 20ms = 300ms minimum keyword duration
        val durationMs = config.votingWindowFrames * 20
        assertThat(durationMs).isAtLeast(200)
    }

    @Test fun `energy threshold reasonable for PCM 16-bit`() {
        // PCM 16-bit range: -32768 to 32767
        // Energy threshold should be well within this range
        assertThat(makeConfig().energyThreshold).isLessThan(32768f)
    }

    @Test fun `keyword is uppercase`() {
        assertThat(makeConfig().keyword).isEqualTo(makeConfig().keyword.uppercase())
    }

    @Test fun `detection threshold default is 0.55`() {
        assertThat(makeConfig().detectionThreshold).isEqualTo(0.55f)
    }
}

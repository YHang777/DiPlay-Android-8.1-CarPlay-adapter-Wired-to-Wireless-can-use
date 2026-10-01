package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioLatencyGuardTest {
    @Test fun depthUsesTheUnsignedRtpSampleSpan() {
        assertEquals(0, AudioLatencyGuard.depthMs(1_000, 1_000, 48_000))
        assertEquals(500, AudioLatencyGuard.depthMs(0, 24_000, 48_000))
        // u32 wrap: newest has gone past zero while oldest is still near the top.
        assertEquals(250, AudioLatencyGuard.depthMs(-12_000, 0, 48_000))
        assertEquals(1_000, AudioLatencyGuard.depthMs(-48_000, 0, 48_000))
        assertEquals(0, AudioLatencyGuard.depthMs(0, 0, 0))
    }

    @Test fun mediaCapFollowsTheUserBufferPreset() {
        assertEquals(300, AudioLatencyGuard(true, 300).capMs)
        assertEquals(500, AudioLatencyGuard(true, 500).capMs)
        assertEquals(1_000, AudioLatencyGuard(true, 1_000).capMs)
        // Unknown presets fall back the same way MediaAudioBuffer sanitizes them.
        assertEquals(300, AudioLatencyGuard(true, 42).capMs)
        assertEquals(200, AudioLatencyGuard(false, 1_000).capMs)
        assertEquals(3_000, AudioLatencyGuard(true, 500).hardCapMs)
        assertEquals(500, AudioLatencyGuard(false, 500).hardCapMs)
    }

    @Test fun shrinkingBacklogIsNeverTrimmed() {
        var now = 0L
        val guard = AudioLatencyGuard(true, 500, nowNs = { now })
        // A Wi-Fi gap dumps 2 s of audio and then drains it.
        var depth = 2_000
        repeat(30) {
            assertEquals(AudioLatencyGuard.Action.NONE, guard.observe(depth))
            now += 100_000_000L
            depth -= 70 // clearly draining
        }
    }

    /** CarPlay Opus is one 20 ms frame per packet: the drain exemption must fire at that rate. */
    @Test fun opusSizedDrainIsNeverTrimmed() {
        var now = 0L
        val guard = AudioLatencyGuard(true, 500, nowNs = { now })
        var depth = 2_000
        // 100 steps of 20 ms at a 10 ms worker cadence — well past the 1.5 s hold and the
        // 250 ms hard-cap hold — must never skip, because every step is one packet leaving.
        repeat(100) {
            assertEquals(AudioLatencyGuard.Action.NONE, guard.observe(depth))
            now += 10_000_000L
            depth -= 20
        }
    }

    @Test fun flatBacklogTrimsOnceAfterTheHold() {
        var now = 0L
        val guard = AudioLatencyGuard(true, 500, nowNs = { now })
        assertEquals(AudioLatencyGuard.Action.NONE, guard.observe(800))
        now = 1_400_000_000L
        assertEquals(AudioLatencyGuard.Action.NONE, guard.observe(800))
        now = 1_500_000_000L
        assertEquals(AudioLatencyGuard.Action.TRIM, guard.observe(800))
        // Still disarmed until the backlog is well under the cap: one trim per episode.
        now = 3_000_000_000L
        assertEquals(AudioLatencyGuard.Action.NONE, guard.observe(700))
        assertEquals(AudioLatencyGuard.Action.NONE, guard.observe(240))
        assertEquals(AudioLatencyGuard.Action.NONE, guard.observe(800))
        now = 4_500_000_000L
        assertEquals(AudioLatencyGuard.Action.TRIM, guard.observe(800))
    }

    @Test fun hardCapTrimsAfterTheShortHoldNotOnOneObservation() {
        var now = 0L
        val guard = AudioLatencyGuard(true, 500, nowNs = { now })
        // Starts the gate; a single sample of extreme lag must not skip on its own.
        assertEquals(AudioLatencyGuard.Action.NONE, guard.observe(3_001))
        now = 249_999_999L
        assertEquals(AudioLatencyGuard.Action.NONE, guard.observe(3_001))
        now = 250_000_000L
        assertEquals(AudioLatencyGuard.Action.TRIM, guard.observe(3_001))
        assertEquals(AudioLatencyGuard.Action.NONE, guard.observe(2_000))
    }

    @Test fun underCapObservationsAreQuiet() {
        var now = 0L
        val guard = AudioLatencyGuard(true, 500, nowNs = { now })
        repeat(20) {
            assertEquals(AudioLatencyGuard.Action.NONE, guard.observe(400))
            now += 500_000_000L
        }
    }
}

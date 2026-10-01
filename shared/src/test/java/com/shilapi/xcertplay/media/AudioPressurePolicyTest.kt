package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioPressurePolicyTest {
    @Test fun aBareStutterOnlyRaisesElevated() {
        // A Wi-Fi hole drops audio without asking video for anything; dropping most of the
        // picture for two seconds because of it made the whole session look broken.
        val policy = AudioPressurePolicy()
        assertEquals(AudioPressure.ELEVATED, policy.evaluate(AudioPressurePolicy.Sample(underrunDelta = 1)))
        val rebuffer = AudioPressurePolicy()
        assertEquals(AudioPressure.ELEVATED, rebuffer.evaluate(AudioPressurePolicy.Sample(rebufferDelta = 1)))
    }

    @Test fun aStutterWhileThePipelineIsStarvedIsCritical() {
        // When the decoder is also behind, video stepping aside is exactly what audio needs.
        val deferred = AudioPressurePolicy()
        assertEquals(
            AudioPressure.CRITICAL,
            deferred.evaluate(
                AudioPressurePolicy.Sample(underrunDelta = 1, deferredMs = 1_001),
            ),
        )
        val overHardCap = AudioPressurePolicy()
        assertEquals(
            AudioPressure.CRITICAL,
            overHardCap.evaluate(
                AudioPressurePolicy.Sample(rebufferDelta = 1, queueOverHardCap = true),
            ),
        )
    }

    @Test fun deferredDecoderAgeClimbsThroughTheThresholds() {
        val calm = AudioPressurePolicy()
        assertEquals(AudioPressure.CALM, calm.evaluate(AudioPressurePolicy.Sample(deferredMs = 200)))
        val elevated = AudioPressurePolicy()
        assertEquals(AudioPressure.ELEVATED, elevated.evaluate(AudioPressurePolicy.Sample(deferredMs = 201)))
        val critical = AudioPressurePolicy()
        assertEquals(AudioPressure.CRITICAL, critical.evaluate(AudioPressurePolicy.Sample(deferredMs = 1_001)))
    }

    @Test fun queueDepthRaisesPressureWithoutASample() {
        val elevated = AudioPressurePolicy()
        assertEquals(AudioPressure.ELEVATED, elevated.evaluate(AudioPressurePolicy.Sample(queueOverCap = true)))
        val critical = AudioPressurePolicy()
        assertEquals(AudioPressure.CRITICAL, critical.evaluate(AudioPressurePolicy.Sample(queueOverHardCap = true)))
    }

    @Test fun levelHoldsForTwoSecondsAfterTheLastRaisingSample() {
        var now = 0L
        val policy = AudioPressurePolicy(nowNs = { now })
        assertEquals(AudioPressure.CRITICAL, policy.evaluate(AudioPressurePolicy.Sample(queueOverHardCap = true)))
        // A quiet sample right away must not flap video back to the full budget.
        now = 100_000_000L
        assertEquals(AudioPressure.CRITICAL, policy.evaluate(AudioPressurePolicy.Sample()))
        now = 1_999_999_999L
        assertEquals(AudioPressure.CRITICAL, policy.evaluate(AudioPressurePolicy.Sample()))
        now = 2_000_000_000L
        assertEquals(AudioPressure.CALM, policy.evaluate(AudioPressurePolicy.Sample()))
    }

    @Test fun aStillSufferingSampleExtendsTheHold() {
        var now = 0L
        val policy = AudioPressurePolicy(nowNs = { now })
        assertEquals(AudioPressure.ELEVATED, policy.evaluate(AudioPressurePolicy.Sample(queueOverCap = true)))
        now = 1_800_000_000L
        assertEquals(AudioPressure.ELEVATED, policy.evaluate(AudioPressurePolicy.Sample(queueOverCap = true)))
        now = 3_600_000_000L
        assertEquals(AudioPressure.ELEVATED, policy.evaluate(AudioPressurePolicy.Sample()))
    }

    @Test fun signalTakesTheMaxAcrossLiveSourcesAndForgetsWithThem() {
        val signal = AudioPressureSignal()
        assertEquals(AudioPressure.CALM, signal.level())
        signal.publish("media", AudioPressure.ELEVATED)
        signal.publish("nav", AudioPressure.CRITICAL)
        assertEquals(AudioPressure.CRITICAL, signal.level())
        signal.clear("nav")
        assertEquals(AudioPressure.ELEVATED, signal.level())
        signal.clear("media")
        assertEquals(AudioPressure.CALM, signal.level())
    }
}

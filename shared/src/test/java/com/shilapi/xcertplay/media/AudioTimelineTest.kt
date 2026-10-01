package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioTimelineTest {
    private val rate = 48_000

    @Test fun aContinuousStreamPadsNothing() {
        val timeline = AudioTimeline()
        assertEquals(0, timeline.concealBeforeSample(1024, 1024, rate))
        assertEquals(0, timeline.concealBeforeSample(2048, 1024, rate))
        assertEquals(0, timeline.concealBeforeSample(3072, 1024, rate))
    }

    @Test fun aLostRunIsPaddedExactly() {
        // The head unit measured this shape: three RTP packets gone inside a radio hole, so
        // the next packet arrives 4096 samples past the previous one instead of 1024.
        val timeline = AudioTimeline()
        assertEquals(0, timeline.concealBeforeSample(0, 1024, rate))
        assertEquals(0, timeline.concealBeforeSample(1024, 1024, rate))
        // 1024 + 1024 = 2048 was expected; 5120 arrived, so 3072 samples (64 ms) are gone.
        assertEquals(3072, timeline.concealBeforeSample(5120, 1024, rate))
        // The hole is behind us: the stream is continuous again.
        assertEquals(0, timeline.concealBeforeSample(6144, 1024, rate))
    }

    @Test fun theFirstPacketPadsNothing() {
        // Starting mid-song must not synthesise a silence before the first note.
        val timeline = AudioTimeline()
        assertEquals(0, timeline.concealBeforeSample(7_800_832, 1024, rate))
    }

    @Test fun aRestartIsNotPadded() {
        // A seek or a new stream lands anywhere. Padding that would add a long silence on
        // top of a jump the listener already heard as a jump.
        val timeline = AudioTimeline()
        assertEquals(0, timeline.concealBeforeSample(0, 1024, rate))
        assertEquals(0, timeline.concealBeforeSample(2_000_000, 1024, rate))
    }

    @Test fun aHoleAcrossTheSampleCounterWrapIsStillPadded() {
        // The RTP sample counter is u32 and wraps mid-song; the hole has to survive the wrap.
        val timeline = AudioTimeline()
        assertEquals(0, timeline.concealBeforeSample(0xffff_f000.toInt(), 1024, rate))
        // Continuous across the wrap would be 0xffff_f400. 0 is 3072 samples further on.
        assertEquals(3072, timeline.concealBeforeSample(0, 1024, rate))
    }

    @Test fun decodePairingUsesSubmissionOrder() {
        val timeline = AudioTimeline()
        timeline.submitted(0)
        timeline.submitted(1024)
        timeline.submitted(4096) // one packet lost between the second and third
        assertEquals(0, timeline.concealBefore(1024, rate))
        assertEquals(0, timeline.concealBefore(1024, rate))
        assertEquals(2048, timeline.concealBefore(1024, rate))
    }

    @Test fun anUnpairedOutputPadsNothingRatherThanGuessing() {
        val timeline = AudioTimeline()
        assertEquals(0, timeline.concealBefore(1024, rate))
    }

    @Test fun resetForgetsHistoryAcrossATrackRebuild() {
        val timeline = AudioTimeline()
        assertEquals(0, timeline.concealBeforeSample(0, 1024, rate))
        timeline.reset()
        // A new stream starts anywhere; it must not be padded into the old one's timeline.
        assertEquals(0, timeline.concealBeforeSample(500_000, 1024, rate))
    }

    @Test fun aCodecFarBehindDropsMarkersRatherThanSound() {
        // Capacity 3: the fourth submission forgets the oldest marker, not the newest packet.
        val timeline = AudioTimeline(capacity = 3)
        timeline.submitted(0)
        timeline.submitted(1024)
        timeline.submitted(2048)
        timeline.submitted(3072)
        // The marker for sample 0 is gone; what remains still pairs in order.
        assertEquals(0, timeline.concealBefore(1024, rate))
        assertEquals(0, timeline.concealBefore(1024, rate))
        assertEquals(0, timeline.concealBefore(1024, rate))
    }
}

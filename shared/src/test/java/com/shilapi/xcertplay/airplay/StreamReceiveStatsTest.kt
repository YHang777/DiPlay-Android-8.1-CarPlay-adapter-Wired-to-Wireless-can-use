package com.shilapi.xcertplay.airplay

import org.junit.Assert.*
import org.junit.Test

class StreamReceiveStatsTest {
    @Test fun separatesSocketWaitFromLocalProcessingAndTracksSequenceWrap() {
        var clock = 0L
        val output = mutableListOf<String>()
        val stats = StreamReceiveStats("audio", output::add) { clock }
        fun packet(sequence: Int) {
            stats.reading()
            clock += 400_000_000L
            stats.received(100, sequence)
            clock += 200_000L
            stats.processed()
        }
        packet(65535)
        packet(0)
        packet(3) // two missing, then one late packet arrives
        packet(2)
        packet(4)
        stats.flush(ended = true)
        assertEquals(1, output.size)
        assertTrue(output.single().contains("readMaxMs=400 processMaxUs=200 seqForwardGaps=2 lateOrDuplicate=1"))
        assertTrue(output.single().contains("packets=5 bytes=500"))
    }

    @Test fun reportResetsWindowButRetainsSequenceContinuity() {
        var clock = 0L
        val output = mutableListOf<String>()
        val stats = StreamReceiveStats("audio", output::add) { clock }
        stats.reading()
        clock = 5_000_000_000L
        stats.received(10, 1)
        stats.processed()
        stats.reading()
        stats.received(20, 3)
        stats.processed()
        stats.flush(true)
        assertEquals(2, output.size)
        assertTrue(output.last().contains("packets=1 bytes=20 readMaxMs=0"))
        assertTrue(output.last().contains("seqForwardGaps=1"))
    }

    /** Datagrams without an RTP header must not be counted as late/duplicate, and must not box. */
    @Test fun packetsWithoutSequenceAreNotLateOrDuplicate() {
        var clock = 0L
        val output = mutableListOf<String>()
        val stats = StreamReceiveStats("video", output::add) { clock }
        repeat(4) {
            stats.reading()
            clock += 1_000_000L
            stats.received(200) // no sequence, no timestamp
            clock += 1_000L
            stats.processed()
        }
        stats.flush(ended = true)
        assertTrue(output.single().contains("packets=4 bytes=800"))
        assertTrue(output.single().contains("seqForwardGaps=0 lateOrDuplicate=0"))
    }

    @Test fun sentinelsCarryThroughToTheGapReport() {
        var clock = 0L
        val output = mutableListOf<String>()
        val stats = StreamReceiveStats("audio", output::add) { clock }
        stats.reading()
        clock += 1_000_000L
        stats.received(10, sequence = 5, timestamp = StreamReceiveStats.NO_TIMESTAMP)
        clock += 1_000L
        stats.processed()
        stats.reading()
        clock += 1_000_000L
        stats.received(10, sequence = 9, timestamp = 42L)
        stats.processed()
        stats.flush(ended = true)
        // First packet established the baseline with no timestamp of its own, so the gap report
        // must say "unknown" rather than crashing or printing a sentinel.
        assertTrue(output.single().contains("previousRtpTs=unknown"))
        assertTrue(output.single().contains("receivedRtpTs=42"))
    }
}

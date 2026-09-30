package com.shilapi.xcertplay.orchestration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamStallWatchdogTest {
    private val frames = mutableMapOf<Int, Long>()
    private var now = 0L
    private val stalled = mutableListOf<Int>()
    private val watchdog = StreamStallWatchdog(
        stallNanos = 20_000_000_000L,
        nowNanos = { now },
        lastFrameNanos = { frames[it] },
        onStalled = { stalled += it },
    )

    @Test
    fun anActiveStreamWithFreshFramesIsQuiet() {
        watchdog.onStreamActive(110, true)
        frames[110] = now
        now += 10_000_000_000L

        assertTrue(watchdog.check().isEmpty())
        assertTrue(stalled.isEmpty())
    }

    @Test
    fun anActiveStreamThatGoesQuietIsReported() {
        watchdog.onStreamActive(110, true)
        frames[110] = now
        now += 21_000_000_000L

        assertEquals(listOf(110), watchdog.check())
        assertEquals(listOf(110), stalled)
    }

    @Test
    fun aStreamThatNeverProducedAFrameIsReported() {
        watchdog.onStreamActive(110, true)
        now += 21_000_000_000L

        assertEquals(listOf(110), watchdog.check())
    }

    @Test
    fun anIdleScreenIsNeverTreatedAsStalled() {
        // The CarPlay home screen can sit still indefinitely; only an expected stream counts.
        assertFalse(watchdog.isActive(110))
        assertTrue(watchdog.check().isEmpty())

        watchdog.onStreamActive(110, true)
        watchdog.onStreamActive(110, false)
        now += 60_000_000_000L
        assertTrue(watchdog.check().isEmpty())
    }

    @Test
    fun aNewFrameClearsTheStall() {
        watchdog.onStreamActive(110, true)
        frames[110] = now
        now += 21_000_000_000L
        assertEquals(listOf(110), watchdog.check())

        frames[110] = now
        now += 1_000_000_000L
        assertTrue(watchdog.check().isEmpty())
        assertEquals(listOf(110), stalled)
    }

    @Test
    fun onlyStalledStreamsAreReported() {
        watchdog.onStreamActive(110, true)
        watchdog.onStreamActive(111, true)
        frames[110] = now
        frames[111] = now - 30_000_000_000L

        assertEquals(listOf(111), watchdog.check())
    }
}

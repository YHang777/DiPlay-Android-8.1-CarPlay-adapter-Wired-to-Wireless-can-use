package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.VideoCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoDecodeQueuePressureTest {
    @Test fun calmOverflowStillEmitsResync() {
        val queue = VideoDecodeQueue(maxFrames = 2)
        queue.offer(VideoJob.Frame(byteArrayOf(1)), pressureLevel = { AudioPressure.CALM })
        queue.offer(VideoJob.Frame(byteArrayOf(2)), pressureLevel = { AudioPressure.CALM })
        queue.offer(VideoJob.Frame(byteArrayOf(3)), pressureLevel = { AudioPressure.CALM })
        assertEquals(VideoJob.Resync, queue.poll(0))
        assertArrayEquals(byteArrayOf(3), (queue.poll(0) as VideoJob.Frame).nalus)
    }

    @Test fun elevatedAndCriticalOverflowSoftSkipInsteadOfResync() {
        for (level in listOf(AudioPressure.ELEVATED, AudioPressure.CRITICAL)) {
            val queue = VideoDecodeQueue(maxFrames = 2)
            queue.offer(VideoJob.Frame(byteArrayOf(1)), pressureLevel = { level })
            queue.offer(VideoJob.Frame(byteArrayOf(2)), pressureLevel = { level })
            queue.offer(VideoJob.Frame(byteArrayOf(3)), pressureLevel = { level })
            assertEquals(VideoJob.SoftSkip, queue.poll(0))
            assertArrayEquals(byteArrayOf(3), (queue.poll(0) as VideoJob.Frame).nalus)
        }
    }

    @Test fun pressureScalesTheFrameAndByteLimitsDown() {
        val queue = VideoDecodeQueue()
        // CALM allows 60 frames; CRITICAL allows 15, so the 16th overflows.
        repeat(15) { queue.offer(VideoJob.Frame(byteArrayOf(1)), pressureLevel = { AudioPressure.CRITICAL }) }
        queue.offer(VideoJob.Frame(byteArrayOf(2)), pressureLevel = { AudioPressure.CRITICAL })
        assertEquals(VideoJob.SoftSkip, queue.poll(0))
        assertArrayEquals(byteArrayOf(2), (queue.poll(0) as VideoJob.Frame).nalus)
    }

    @Test fun configurationAndSurfaceChangesSurviveOverflow() {
        val queue = VideoDecodeQueue(maxFrames = 1)
        val config = VideoJob.Config(VideoCodec.H264, byteArrayOf(1))
        val surface = VideoJob.SurfaceChanged(null)
        queue.offer(config, pressureLevel = { AudioPressure.CRITICAL })
        queue.offer(VideoJob.Frame(byteArrayOf(1)), pressureLevel = { AudioPressure.CRITICAL })
        queue.offer(surface, pressureLevel = { AudioPressure.CRITICAL })
        queue.offer(VideoJob.Frame(byteArrayOf(2)), pressureLevel = { AudioPressure.CRITICAL })
        assertEquals(config, queue.poll(0))
        assertEquals(surface, queue.poll(0))
        assertEquals(VideoJob.SoftSkip, queue.poll(0))
        assertEquals(2, (queue.poll(0) as VideoJob.Frame).nalus.single().toInt())
    }

    @Test fun discardFramesAlsoClearsSoftSkip() {
        val queue = VideoDecodeQueue(maxFrames = 1)
        queue.offer(VideoJob.Frame(byteArrayOf(1)), pressureLevel = { AudioPressure.CRITICAL })
        queue.offer(VideoJob.Frame(byteArrayOf(2)), pressureLevel = { AudioPressure.CRITICAL })
        assertEquals(VideoJob.SoftSkip, queue.poll(0))
        queue.offer(VideoJob.SoftSkip, pressureLevel = { AudioPressure.CALM })
        queue.discardFrames()
        assertNull(queue.poll(0))
    }
}

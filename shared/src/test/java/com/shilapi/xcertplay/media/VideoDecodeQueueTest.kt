package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.VideoCodec
import org.junit.Assert.*
import org.junit.Test

class VideoDecodeQueueTest {
    @Test fun lostReferenceChainWaitsForSuccessfullyQueuedKeyframe() {
        val chain = VideoReferenceChain()
        val predicted = byteArrayOf(0, 0, 0, 1, 0x41, 1)
        val idr = byteArrayOf(0, 0, 0, 1, 0x65, 1)
        assertFalse(chain.accepts(predicted, VideoCodec.H264))
        assertTrue(chain.accepts(idr, VideoCodec.H264))
        assertTrue(chain.needsKeyFrame) // Receiving it is insufficient if the codec is still busy.
        chain.onQueued()
        assertTrue(chain.accepts(predicted, VideoCodec.H264))
        chain.reset()
        assertFalse(chain.accepts(predicted, VideoCodec.H264))
    }

    /**
     * Regression: dropping an interframe from an intact chain leaves a hole that the next
     * P-frame can be decoded against. Pressure easing mid-GOP then feeds a frame whose
     * reference never decoded — visible corruption on the weak decoders this path targets.
     *
     * Every pressure drop has to go through this path (decode thread). A drop on the RTP
     * thread cannot see an IDR that has been offered but not yet queued, so it will discard
     * an interframe that would have decoded *after* that IDR — the same hole, reopened.
     */
    @Test fun droppedInterframeBreaksTheChainSoDependentsWaitForAnIdr() {
        val chain = VideoReferenceChain()
        val idr = byteArrayOf(0, 0, 0, 1, 0x65, 1)
        val predicted = byteArrayOf(0, 0, 0, 1, 0x41, 1)
        chain.onQueued() // a previous IDR made the chain healthy
        assertFalse(chain.needsKeyFrame)

        // feed() drops a P-frame under pressure and must break the chain...
        chain.reset()
        // ...so that the next P-frame (which references the dropped one) is refused,
        assertFalse(chain.accepts(predicted, VideoCodec.H264))
        // and only a real IDR restores decoding.
        assertTrue(chain.accepts(idr, VideoCodec.H264))
        chain.onQueued()
        assertTrue(chain.accepts(predicted, VideoCodec.H264))
    }

    @Test fun overflowPreservesConfigurationAndResetsBeforeNewReferenceChain() {
        val queue = VideoDecodeQueue(maxFrames = 2)
        val config = VideoJob.Config(VideoCodec.H264, byteArrayOf(1))
        val surface = VideoJob.SurfaceChanged(null)
        queue.offer(config)
        queue.offer(VideoJob.Frame(byteArrayOf(1)))
        queue.offer(surface)
        queue.offer(VideoJob.Frame(byteArrayOf(2)))
        queue.offer(VideoJob.Frame(byteArrayOf(3)))
        assertSame(config, queue.poll(0))
        assertSame(surface, queue.poll(0))
        assertEquals(VideoJob.Resync, queue.poll(0))
        assertArrayEquals(byteArrayOf(3), (queue.poll(0) as VideoJob.Frame).nalus)
        assertNull(queue.poll(0))
    }

    @Test fun byteBudgetAlsoTriggersRecoveryAndRejectsOversizedFrame() {
        val queue = VideoDecodeQueue(maxFrames = 8, maxBytes = 5)
        queue.offer(VideoJob.Frame(ByteArray(3)))
        queue.offer(VideoJob.Frame(ByteArray(3)))
        assertEquals(VideoJob.Resync, queue.poll(0))
        assertEquals(3, (queue.poll(0) as VideoJob.Frame).nalus.size)
        queue.offer(VideoJob.Frame(ByteArray(6)))
        assertEquals(VideoJob.Resync, queue.poll(0))
        assertNull(queue.poll(0))
    }

    @Test fun fullOutputMustBeDrainedWhileRetryingTheSameInput() {
        var heldOutputs = 2
        var dequeues = 0
        val submitted = mutableListOf<Int>()
        for (frame in 1..3) {
            val index = VideoInputPump.acquire(
                running = { true },
                drain = { if (heldOutputs > 0) heldOutputs-- },
                dequeue = { dequeues++; if (heldOutputs > 0) -1 else 0 },
            )
            assertEquals(0, index)
            submitted.add(frame)
            heldOutputs = 2
        }
        assertEquals(listOf(1, 2, 3), submitted)
        assertEquals(6, dequeues)
    }

    @Test fun polledFramesFreeTheirSlotsForNewOffers() {
        val queue = VideoDecodeQueue(maxFrames = 2, maxBytes = 100)
        queue.offer(VideoJob.Frame(byteArrayOf(1, 2, 3)))
        queue.offer(VideoJob.Frame(byteArrayOf(4, 5)))
        // Both slots are full: the third offer discards the backlog and marks recovery.
        queue.offer(VideoJob.Frame(byteArrayOf(6)))
        assertEquals(VideoJob.Resync, queue.poll(0))
        assertEquals(1, (queue.poll(0) as VideoJob.Frame).nalus.size)
        assertNull(queue.poll(0))
        // Counters must have dropped with the polls: two fresh frames fit without recovery.
        queue.offer(VideoJob.Frame(byteArrayOf(7, 8, 9)))
        queue.offer(VideoJob.Frame(byteArrayOf(10, 11)))
        assertEquals(3, (queue.poll(0) as VideoJob.Frame).nalus.size)
        assertEquals(2, (queue.poll(0) as VideoJob.Frame).nalus.size)
        assertNull(queue.poll(0))
    }

    @Test fun blockedPollWakesWhenAnotherThreadOffers() {
        val queue = VideoDecodeQueue()
        val frame = VideoJob.Frame(byteArrayOf(9))
        val poller = Thread {
            val got = queue.poll(2_000)
            assertSame(frame, got)
        }
        poller.start()
        // Give the poller time to enter awaitNanos before the offer signals it.
        Thread.sleep(50)
        queue.offer(frame)
        poller.join(2_000)
        assertFalse("poller did not observe the offer", poller.isAlive)
    }

    @Test fun stalledDecoderHasFiniteWaitAndShutdownCancelsImmediately() {
        var time = 0L
        var attempts = 0
        assertEquals(-1, VideoInputPump.acquire(
            running = { true }, drain = {}, dequeue = { attempts++; -1 },
            nanoTime = { time.also { time += 10 } }, timeoutNs = 30,
        ))
        assertEquals(3, attempts)
        assertEquals(-1, VideoInputPump.acquire(running = { false }, drain = { fail() }, dequeue = { fail(); 0 }))
    }
}

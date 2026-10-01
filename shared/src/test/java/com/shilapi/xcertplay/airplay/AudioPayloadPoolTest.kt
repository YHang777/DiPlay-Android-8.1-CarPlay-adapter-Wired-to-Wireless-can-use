package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioPayloadPoolTest {
    // Odd sizes so parallel test classes cannot collide with a common AAC/Opus payload length.
    private val size = 1013

    @Test fun releasedBufferIsReusedByTheNextAcquireOfThatSize() {
        val payload = AudioPayloadPool.acquire(size)
        assertEquals(size, payload.size)
        AudioPayloadPool.release(payload)
        val next = AudioPayloadPool.acquire(size)
        assertSame(payload, next)
    }

    @Test fun acquireReturnsExactSizeEvenWhenThePoolHoldsOtherLengths() {
        val other = AudioPayloadPool.acquire(size + 2)
        AudioPayloadPool.release(other)
        val exact = AudioPayloadPool.acquire(size)
        assertEquals(size, exact.size)
        assertNotSame(other, exact)
        AudioPayloadPool.release(exact)
    }

    @Test fun neverReleasedBuffersAreSimplyNotReused() {
        val leaked = AudioPayloadPool.acquire(size + 4)
        val fresh = AudioPayloadPool.acquire(size + 4)
        assertNotSame(leaked, fresh)
        assertEquals(size + 4, fresh.size)
        AudioPayloadPool.release(fresh)
    }

    @Test fun idleInventoryStaysBounded() {
        // Hold all buffers at once: releasing them one by one into an acquire loop would
        // recycle a single array and never grow the idle list.
        val held = ArrayList<ByteArray>(300)
        repeat(300) { held.add(AudioPayloadPool.acquire(size + 6)) }
        held.forEach { AudioPayloadPool.release(it) }
        // ByteArray equality is referential, so this set is an identity set.
        val releasedSet = HashSet<ByteArray>(held)
        var reused = 0
        repeat(300) {
            val next = AudioPayloadPool.acquire(size + 6)
            if (releasedSet.contains(next)) reused++
        }
        // MAX_IDLE (256) caps retention: at most 256 of the 300 can come back.
        assertTrue("pool returned $reused of 300 buffers; MAX_IDLE not enforced", reused <= 256)
        // And the pool must actually be reusing, not just allocating.
        assertTrue("pool reused only $reused of 300 buffers", reused >= 200)
        // Drain: take back whatever is idle so later runs start from a clean pool.
        repeat(300) { AudioPayloadPool.acquire(size + 6) }
    }
}

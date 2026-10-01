package com.shilapi.xcertplay.media

import android.view.Surface
import com.shilapi.xcertplay.airplay.VideoCodec
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

internal sealed interface VideoJob {
    data class Config(val codec: VideoCodec, val codecData: ByteArray) : VideoJob
    data class Frame(val nalus: ByteArray, val receivedNs: Long = System.nanoTime()) : VideoJob
    data class SurfaceChanged(val surface: Surface?) : VideoJob
    data object Resync : VideoJob
    /** Drop queued frames and wait for a keyframe without releasing the decoder. */
    data object SoftSkip : VideoJob
}

/**
 * Do not resume dependent pictures after losing a reference frame.
 *
 * Touched only from the decode thread: every drop decision and every [onQueued] happen in
 * feed(), so the RTP thread never observes this and the flag needs no volatile.
 */
internal class VideoReferenceChain {
    var needsKeyFrame = true
        private set

    fun reset() { needsKeyFrame = true }
    fun accepts(bytes: ByteArray, codec: VideoCodec): Boolean =
        !needsKeyFrame || MediaCodecSupport.isRandomAccess(bytes, codec)
    fun onQueued() { needsKeyFrame = false }
}

/**
 * Decode budget for one audio-pressure level. Level 0 keeps today's behaviour; higher levels
 * shrink the queue and prefer [VideoJob.SoftSkip] (keep the codec) over [VideoJob.Resync]
 * (release + reconfigure), which is expensive churn on weak vendor decoders.
 */
internal data class VideoYieldBudget(
    val maxFrames: Int,
    val maxBytes: Int,
    val maxFrameAgeNs: Long,
    val dropNonKeyFrames: Boolean,
    val softOverflow: Boolean,
) {
    companion object {
        val CALM = VideoYieldBudget(
            maxFrames = 60,
            maxBytes = 8 * 1024 * 1024,
            maxFrameAgeNs = 250_000_000L,
            dropNonKeyFrames = false,
            softOverflow = false,
        )
        val ELEVATED = VideoYieldBudget(
            maxFrames = 30,
            maxBytes = 4 * 1024 * 1024,
            maxFrameAgeNs = 150_000_000L,
            dropNonKeyFrames = false,
            softOverflow = true,
        )
        val CRITICAL = VideoYieldBudget(
            maxFrames = 15,
            maxBytes = 2 * 1024 * 1024,
            maxFrameAgeNs = 80_000_000L,
            dropNonKeyFrames = true,
            softOverflow = true,
        )

        fun forLevel(level: Int): VideoYieldBudget = when {
            level >= AudioPressure.CRITICAL -> CRITICAL
            level >= AudioPressure.ELEVATED -> ELEVATED
            else -> CALM
        }
    }
}

/**
 * Limit latency and memory without ever dropping a reference frame silently.
 *
 * Backed by a plain deque under one short-held lock instead of a [java.util.concurrent.LinkedBlockingQueue]:
 * the old overflow check ran `filterIsInstance` + `sumOf` over the whole queue on every offer
 * (O(frames) with an iterator, a list and boxed longs per video frame). Frame count and byte
 * totals are maintained incrementally, so an offer is O(1) and allocation-free.
 */
internal class VideoDecodeQueue(
    // Wi-Fi delivers frames in bursts after a radio gap; the decoder's age check bounds latency.
    private val maxFrames: Int = 60,
    private val maxBytes: Int = 8 * 1024 * 1024,
) {
    private val lock = ReentrantLock()
    private val jobAvailable = lock.newCondition()
    private val jobs = ArrayDeque<VideoJob>()
    private var queuedFrames = 0
    private var queuedFrameBytes = 0

    /**
     * [pressureLevel] is read at offer time so a rising audio pressure shrinks the backlog
     * immediately. The constructor limits stay the ceiling: tests pin small values there.
     */
    fun offer(job: VideoJob, pressureLevel: () -> Int = { AudioPressure.CALM }) {
        lock.lock()
        try {
            if (job is VideoJob.Frame) {
                val budget = VideoYieldBudget.forLevel(pressureLevel())
                val frameLimit = minOf(maxFrames, budget.maxFrames)
                val byteLimit = minOf(maxBytes, budget.maxBytes)
                if (queuedFrames >= frameLimit || queuedFrameBytes + job.nalus.size > byteLimit) {
                    discardFramesLocked()
                    jobs.addLast(if (budget.softOverflow) VideoJob.SoftSkip else VideoJob.Resync)
                }
                // A single oversized frame is also a lost reference chain.
                if (job.nalus.size > byteLimit) return
                queuedFrames++
                queuedFrameBytes += job.nalus.size
            }
            jobs.addLast(job)
            jobAvailable.signal()
        } finally {
            lock.unlock()
        }
    }

    fun discardFrames() {
        lock.lock()
        try {
            discardFramesLocked()
        } finally {
            lock.unlock()
        }
    }

    fun poll(timeoutMillis: Long): VideoJob? {
        lock.lock()
        try {
            if (jobs.isEmpty() && timeoutMillis > 0) {
                var remainingNs = TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
                while (jobs.isEmpty() && remainingNs > 0L) {
                    remainingNs = jobAvailable.awaitNanos(remainingNs)
                }
            }
            val job = jobs.pollFirst() ?: return null
            if (job is VideoJob.Frame) {
                queuedFrames--
                queuedFrameBytes -= job.nalus.size
            }
            return job
        } finally {
            lock.unlock()
        }
    }

    private fun discardFramesLocked() {
        val iterator = jobs.iterator()
        while (iterator.hasNext()) {
            val job = iterator.next()
            if (job is VideoJob.Frame || job is VideoJob.Resync || job is VideoJob.SoftSkip) {
                if (job is VideoJob.Frame) {
                    queuedFrames--
                    queuedFrameBytes -= job.nalus.size
                }
                iterator.remove()
            }
        }
    }
}

/** Drain output while waiting for input: full output buffers can otherwise starve input forever. */
internal object VideoInputPump {
    /** Inline so the per-frame lambdas (each captures the decoder) compile away entirely. */
    inline fun acquire(
        running: () -> Boolean,
        drain: () -> Unit,
        dequeue: () -> Int,
        nanoTime: () -> Long = { System.nanoTime() },
        timeoutNs: Long = TimeUnit.MILLISECONDS.toNanos(500),
    ): Int {
        val start = nanoTime()
        while (running()) {
            drain()
            val index = dequeue()
            if (index >= 0) return index
            if (nanoTime() - start >= timeoutNs) break
        }
        return -1
    }
}

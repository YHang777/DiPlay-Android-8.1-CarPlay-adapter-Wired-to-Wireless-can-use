package com.shilapi.xcertplay.media

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** How much the audio pipeline is struggling. Video yields to audio as this rises. */
object AudioPressure {
    const val CALM = 0
    const val ELEVATED = 1
    const val CRITICAL = 2

    fun label(level: Int): String = when {
        level >= CRITICAL -> "critical"
        level >= ELEVATED -> "elevated"
        else -> "calm"
    }
}

/**
 * Pure state machine: turns audio-suffering samples into a pressure level with hysteresis.
 *
 * How long the decoder has been deferring work and how far behind live the queue sits decide the
 * level. A stutter (underrun or rebuffer) is evidence audio glitched, not evidence video is at
 * fault, so it costs [AudioPressure.ELEVATED] on its own and [AudioPressure.CRITICAL] only when
 * the pipeline is starved as well. Once raised, the level is held for [holdNs] after the last
 * non-lowering sample so video does not thrash between decode budgets every few hundred ms.
 */
internal class AudioPressurePolicy(
    private val holdNs: Long = DEFAULT_HOLD_NS,
    private val elevatedDeferredMs: Int = ELEVATED_DEFERRED_MS,
    private val criticalDeferredMs: Int = CRITICAL_DEFERRED_MS,
    private val nowNs: () -> Long = System::nanoTime,
) {
    data class Sample(
        val deferredMs: Int = 0,
        val underrunDelta: Int = 0,
        val rebufferDelta: Int = 0,
        val queueOverCap: Boolean = false,
        val queueOverHardCap: Boolean = false,
    )

    private var level = AudioPressure.CALM
    private var holdUntilNs = 0L

    fun evaluate(sample: Sample): Int {
        val now = nowNs()
        val instant = instantLevel(sample)
        if (instant >= level) {
            level = instant
            holdUntilNs = now + holdNs
        } else if (now >= holdUntilNs) {
            level = instant
        }
        return level
    }

    fun current(): Int = level

    private fun instantLevel(sample: Sample): Int {
        // A stutter alone is usually upstream jitter: wireless CarPlay delivers audio in
        // clumps with a ~400 ms hole, and no amount of video yielding brings those packets
        // back. Demoting video to the emergency budget on every one of those holes dropped
        // most of the picture for two seconds at a time while audio was already healthy.
        // So a bare stutter is ELEVATED — video tightens its backlog budget without losing
        // pictures — and only becomes CRITICAL when our own pipeline is also behind, which
        // is the case video can actually help by stepping aside.
        val pipelineStarved = sample.deferredMs > criticalDeferredMs || sample.queueOverHardCap
        val stuttered = sample.underrunDelta > 0 || sample.rebufferDelta > 0
        if (stuttered && pipelineStarved) return AudioPressure.CRITICAL
        if (sample.queueOverHardCap) return AudioPressure.CRITICAL
        if (sample.deferredMs > criticalDeferredMs) return AudioPressure.CRITICAL
        if (stuttered) return AudioPressure.ELEVATED
        if (sample.deferredMs > elevatedDeferredMs) return AudioPressure.ELEVATED
        if (sample.queueOverCap) return AudioPressure.ELEVATED
        return AudioPressure.CALM
    }

    companion object {
        const val DEFAULT_HOLD_NS = 2_000_000_000L
        const val ELEVATED_DEFERRED_MS = 200
        const val CRITICAL_DEFERRED_MS = 1_000
    }
}

/**
 * Cross-thread pressure signal. Each audio renderer publishes under its own source key so a
 * closed stream cannot pin the level; video workers read [level] as a single cached max.
 */
internal class AudioPressureSignal {
    private val levels = ConcurrentHashMap<String, Int>()
    private val cached = AtomicInteger(AudioPressure.CALM)

    fun publish(source: String, level: Int) {
        levels[source] = level
        recompute()
    }

    fun clear(source: String) {
        levels.remove(source)
        recompute()
    }

    fun level(): Int = cached.get()

    private fun recompute() {
        var max = AudioPressure.CALM
        for (value in levels.values) if (value > max) max = value
        cached.set(max)
    }
}

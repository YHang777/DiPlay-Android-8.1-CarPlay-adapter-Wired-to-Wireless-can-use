package com.shilapi.xcertplay.media

/**
 * Bounds how far behind live the compressed audio queue may play.
 *
 * The 192-packet FIFO exists so a Wi-Fi gap burst is held instead of dropped, but it also lets a
 * slow decoder accumulate seconds of A/V desync — sound behind the screen. This guard trims the
 * oldest packets only when the backlog is persistent and not draining, or already past a hard cap.
 * A burst that is actively shrinking is exempt: it is on its way back to live on its own.
 */
internal class AudioLatencyGuard(
    private val isMedia: Boolean,
    private val mediaBufferMillis: Int,
    private val holdNs: Long = DEFAULT_HOLD_NS,
    private val hardHoldNs: Long = DEFAULT_HARD_HOLD_NS,
    private val nowNs: () -> Long = System::nanoTime,
) {
    enum class Action { NONE, TRIM }

    /** Soft ceiling: the user's own music-buffer preset for media, a tiny window otherwise. */
    val capMs: Int = if (isMedia) MediaAudioBuffer.sanitize(mediaBufferMillis) else NON_MEDIA_CAP_MS

    /** Past this much queued audio, the short [hardHoldNs] gate replaces the ordinary hold. */
    val hardCapMs: Int = if (isMedia) MEDIA_HARD_CAP_MS else NON_MEDIA_HARD_CAP_MS

    private var overSinceNs: Long? = null
    private var lastDepthMs = 0
    private var firstObservation = true
    private var armed = true

    /**
     * Decides whether [depthMs] of queued audio must be trimmed back under [capMs].
     *
     * TRIM targets [capMs]: the point of the hatch is to jump toward live, not merely to get
     * under the hard cap. Both the soft and the hard path wait for persistence first, so a
     * burst that is already leaving never costs a skip.
     */
    fun observe(depthMs: Int): Action {
        val now = nowNs()
        if (!armed) {
            // One trim per episode: wait until the backlog is well under the cap before re-arming.
            if (depthMs <= capMs / 2) {
                armed = true
                overSinceNs = null
                lastDepthMs = depthMs
                firstObservation = false
            }
            return Action.NONE
        }
        // A Wi-Fi gap dumps a whole backlog at once and then drains it. Growing or flat depth
        // means the decoder cannot keep up; shrinking means the burst is already leaving.
        // One Opus packet is 20 ms of queue depth per worker iteration, so the threshold must
        // fire on that: a strict `> 20` never arms for the format CarPlay mostly uses.
        val decreasing = lastDepthMs - depthMs
        val shrinking = !firstObservation && decreasing >= MIN_SHRINK_MS
        lastDepthMs = depthMs
        firstObservation = false
        if (depthMs <= capMs + SLOP_MS || shrinking) {
            overSinceNs = null
            return Action.NONE
        }
        val since = overSinceNs
        if (since == null) {
            overSinceNs = now
            return Action.NONE
        }
        // Extreme lag gets a much shorter persistence than the ordinary over-cap hold: still
        // gated, so a one-observation spike cannot skip music, but a backlog that is already
        // seconds wrong does not sit there for another 1.5 s either.
        val hold = if (depthMs > hardCapMs) hardHoldNs else holdNs
        if (now - since >= hold) {
            armed = false
            return Action.TRIM
        }
        return Action.NONE
    }

    companion object {
        const val NON_MEDIA_CAP_MS = 200
        const val MEDIA_HARD_CAP_MS = 3_000
        const val NON_MEDIA_HARD_CAP_MS = 500
        const val DEFAULT_HOLD_NS = 1_500_000_000L
        const val DEFAULT_HARD_HOLD_NS = 250_000_000L
        const val SLOP_MS = 20
        /** Depth shed per observation that counts as "the burst is leaving". One 10 ms chunk. */
        const val MIN_SHRINK_MS = 10

        /**
         * Queue content duration from the RTP sample span. The sample counters are u32 and wrap,
         * so the span is computed the same unsigned way `AudioBufferProgress` tracks the head.
         */
        fun depthMs(oldestSample: Int, newestSample: Int, sampleRate: Int): Int {
            if (sampleRate <= 0) return 0
            val oldest = oldestSample.toLong() and 0xffff_ffffL
            val newest = newestSample.toLong() and 0xffff_ffffL
            val span = (newest - oldest) and 0xffff_ffffL
            return (span * 1000L / sampleRate).toInt().coerceAtLeast(0)
        }
    }
}

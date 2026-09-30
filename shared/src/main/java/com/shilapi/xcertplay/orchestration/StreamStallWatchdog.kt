package com.shilapi.xcertplay.orchestration

/**
 * Notices a screen that has stopped updating and reports it as a lost transport.
 *
 * TCP keepalive deliberately does not treat an idle CarPlay screen as a failure, so a session whose
 * socket is still open but whose video has stopped is invisible to the existing reconnect path: the
 * picture freezes and nothing recovers. This watchdog fills that gap. It only looks at streams that
 * are actually active, so an idle home screen never fires — a stall is "a stream we expect frames
 * from has gone quiet", not "nothing happened for a while".
 *
 * [lastFrameNanos] is polled rather than pushed so the video path stays lock-free and the watchdog
 * can be driven by a fake clock in tests. It returns null when the stream has never produced a
 * frame — timestamps come from `System.nanoTime()`, which has an arbitrary origin and may be 0, so
 * the absence of a frame must not be encoded as a number.
 */
class StreamStallWatchdog(
    private val stallNanos: Long = DEFAULT_STALL_NANOS,
    private val nowNanos: () -> Long = System::nanoTime,
    private val lastFrameNanos: (type: Int) -> Long?,
    private val onStalled: (type: Int) -> Unit,
) {
    private val activeTypes = mutableSetOf<Int>()

    /** Tracks which screen streams should be producing frames. */
    @Synchronized
    fun onStreamActive(type: Int, active: Boolean) {
        if (active) activeTypes.add(type) else activeTypes.remove(type)
    }

    /** True when [type] is expected to be producing frames. */
    @Synchronized
    fun isActive(type: Int): Boolean = type in activeTypes

    /**
     * Reports every active stream whose newest frame is older than the stall threshold.
     *
     * A stream that has never produced a frame counts as stalled from the moment it becomes active,
     * so a stream that fails to start is caught too.
     */
    @Synchronized
    fun check(): List<Int> {
        val now = nowNanos()
        return activeTypes.filter { type ->
            val last = lastFrameNanos(type)
            last == null || now - last >= stallNanos
        }.onEach(onStalled)
    }

    companion object {
        /** Long enough that a dropped frame or a slow decoder does not look like a dead link. */
        const val DEFAULT_STALL_NANOS = 20_000_000_000L
    }
}

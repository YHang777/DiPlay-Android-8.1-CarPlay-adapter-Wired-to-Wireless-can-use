package com.shilapi.xcertplay.airplay

/** Receive-thread timing only: no payloads, endpoint addresses, or route data. */
internal class StreamReceiveStats(
    private val label: String,
    private val report: (String) -> Unit,
    private val nowNs: () -> Long = System::nanoTime,
) {
    private var windowStart = nowNs()
    private var readStart = windowStart
    private var processingStart = windowStart
    private var packets = 0
    private var bytes = 0L
    private var maxReadNs = 0L
    private var lastReceivedNs = 0L
    private var maxInterArrivalNs = 0L
    private var maxProcessNs = 0L
    private var lastTimestamp: Long = NO_TIMESTAMP
    private var nextSequence: Int = NO_SEQUENCE
    private var forwardGapPackets = 0
    private var sequenceGapEvents = 0
    private var maxSequenceGap = 0
    private var lastSequenceGap = "none"
    private var lastSequenceGapAtMs = -1L
    private var lateOrDuplicate = 0

    fun reading() { readStart = nowNs() }

    /**
     * Records one received datagram. [sequence] and [timestamp] are primitive sentinels rather
     * than `Int?` so the receive loop boxes nothing per packet — this runs on every audio and
     * video datagram on a weak SoC. [timestamp] is the RTP timestamp zero-extended to a Long, so
     * every u32 is representable and [NO_TIMESTAMP] cannot collide with a real tick.
     */
    fun received(size: Int, sequence: Int = NO_SEQUENCE, timestamp: Long = NO_TIMESTAMP) {
        processingStart = nowNs()
        maxReadNs = maxOf(maxReadNs, processingStart - readStart)
        if (lastReceivedNs != 0L) {
            maxInterArrivalNs = maxOf(maxInterArrivalNs, processingStart - lastReceivedNs)
        }
        lastReceivedNs = processingStart
        packets++
        bytes += size
        if (sequence != NO_SEQUENCE) {
            val expected = nextSequence
            val delta = if (expected == NO_SEQUENCE) 0 else (sequence - expected) and 0xffff
            if (delta < 0x8000) {
                forwardGapPackets += delta
                if (delta > 0) {
                    sequenceGapEvents++
                    maxSequenceGap = maxOf(maxSequenceGap, delta)
                    lastSequenceGap = "expected=$expected received=$sequence missing=$delta " +
                        "previousRtpTs=${if (lastTimestamp == NO_TIMESTAMP) "unknown" else lastTimestamp} " +
                        "receivedRtpTs=${if (timestamp == NO_TIMESTAMP) "unknown" else timestamp}"
                    lastSequenceGapAtMs = (processingStart - windowStart) / 1_000_000L
                }
                nextSequence = (sequence + 1) and 0xffff
            } else lateOrDuplicate++
        }
        if (timestamp != NO_TIMESTAMP) lastTimestamp = timestamp
    }

    fun processed() {
        maxProcessNs = maxOf(maxProcessNs, nowNs() - processingStart)
        flush()
    }

    fun flush(ended: Boolean = false) {
        val now = nowNs()
        if (!ended && now - windowStart < 5_000_000_000L) return
        runCatching { report("Receive: $label packets=$packets bytes=$bytes readMaxMs=${maxReadNs / 1_000_000} " +
            "processMaxUs=${maxProcessNs / 1000} seqForwardGaps=$forwardGapPackets " +
            "lateOrDuplicate=$lateOrDuplicate interArrivalMaxMs=${maxInterArrivalNs / 1_000_000} " +
            "seqGapEvents=$sequenceGapEvents seqGapMax=$maxSequenceGap " +
            "seqGapLast=[$lastSequenceGap] seqGapAtMs=$lastSequenceGapAtMs ended=$ended") }
        windowStart = now
        packets = 0
        bytes = 0
        maxReadNs = 0
        maxInterArrivalNs = 0
        maxProcessNs = 0
        forwardGapPackets = 0
        sequenceGapEvents = 0
        maxSequenceGap = 0
        lastSequenceGap = "none"
        lastSequenceGapAtMs = -1L
        lateOrDuplicate = 0
    }

    internal companion object {
        /** "No sequence on this datagram" sentinel; a real RTP sequence is always 0..0xffff. */
        const val NO_SEQUENCE = -1

        /** "No RTP timestamp on this datagram" sentinel; real values are zero-extended u32. */
        const val NO_TIMESTAMP = -1L
    }
}

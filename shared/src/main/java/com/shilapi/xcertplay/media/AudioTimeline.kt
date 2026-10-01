package com.shilapi.xcertplay.media

/**
 * Keeps played PCM on the content timeline when the wire drops RTP packets.
 *
 * Wireless CarPlay loses whole packets inside a radio hole. Decoding only what arrives and
 * writing it back-to-back makes the song jump forward over the missing audio — the "it speeds
 * up after the cutout" glitch — and the playback buffer then bleeds away by exactly the lost
 * duration until the track underruns. Padding the hole with silence keeps the timeline
 * continuous: the ear hears a gap where the packets were gone (they are gone; nothing can put
 * them back) but the music does not skip, and the buffer stops draining toward an underrun.
 *
 * Pairing is a small ring rather than a map: audio decode is in-order and roughly one output
 * per access unit, so submission order is decode order and the ring only has to cover the
 * handful of frames the codec is holding. The render thread drives both ends, so it needs no
 * locking and allocates nothing after construction.
 */
internal class AudioTimeline(private val capacity: Int = 64) {
    private val inFlight = IntArray(capacity)
    private var head = 0
    private var count = 0
    private var nextExpected: Long = UNSET

    /** Records a packet handed to the decoder. Only call this when the decoder took it. */
    fun submitted(sample: Int) {
        if (count == capacity) {
            // The codec is further behind than the ring can track. Forget the oldest marker
            // rather than any sound: concealment just gets imprecise for one packet.
            head = (head + 1) % capacity
            count--
        }
        inFlight[(head + count) % capacity] = sample
        count++
    }

    /**
     * Silence samples to play before the [frames] just decoded, which belong to the oldest
     * submitted packet. Zero when the stream is continuous or the history is unknown.
     */
    fun concealBefore(frames: Int, sampleRate: Int): Int {
        if (count == 0) return 0
        val sample = inFlight[head]
        head = (head + 1) % capacity
        count--
        return concealBeforeSample(sample, frames, sampleRate)
    }

    /**
     * Same as [concealBefore] for a stream with no decode stage to pair against (LPCM is
     * written straight to the track), so the caller names the packet's sample itself.
     */
    fun concealBeforeSample(sample: Int, frames: Int, sampleRate: Int): Int {
        if (frames <= 0) return 0
        val current = sample.toLong() and 0xffff_ffffL
        val expected = nextExpected
        nextExpected = current + frames
        if (expected == UNSET) return 0
        // Both counters are u32 and wrap. Masking the difference the unsigned way makes a
        // hole that straddles the wrap read as the small gap it is instead of a near-2^32 one.
        val missing = (current - expected) and 0xffff_ffffL
        if (missing == 0L) return 0
        // A restart or a wildly late packet shows up as a huge unsigned delta. Conceal a hole
        // a listener would otherwise hear as a skip; at a second or more the stream is not
        // continuing any more and a long silence on top of that helps nobody.
        if (missing >= sampleRate) return 0
        return missing.toInt()
    }

    /** Forgets history across a track rebuild so one stream is never padded into another. */
    fun reset() {
        head = 0
        count = 0
        nextExpected = UNSET
    }

    private companion object {
        const val UNSET = Long.MIN_VALUE
    }
}

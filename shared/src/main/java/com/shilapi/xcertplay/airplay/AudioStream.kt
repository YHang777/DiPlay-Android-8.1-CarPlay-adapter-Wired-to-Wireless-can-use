package com.shilapi.xcertplay.airplay

import java.io.Closeable
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

enum class AudioCodecKind { AAC_LC, OPUS, LPCM }

data class AudioFormat(
    val codec: AudioCodecKind,
    val sampleRate: Int,
    val channels: Int,
    val payloadType: Int,
    val audioType: String = "media",
)

/**
 * Binds the RTP data and RTCP control UDP ports for one CarPlay audio stream.
 *
 * Wire layout follows LIVI `livi_audio_stream`: one RTP packet per datagram, a 12-byte header,
 * ciphertext, a 16-byte tag, then an 8-byte little-endian nonce. The header's last eight bytes
 * (timestamp + SSRC) are the AEAD associated data.
 */
class AudioStream(
    private val key: ByteArray,
    private val streamType: Int = -1,
    private val onDiagnostic: (String) -> Unit = {},
    /**
     * Copies each datagram and re-assembles RTP only for [Listener.onPacket]. Diagnostic capture
     * needs those bytes; the decode path does not, so normal playback skips both copies.
     */
    private val retainWire: Boolean = false,
) : Closeable {
    interface Listener {
        fun onStarted(firstSample: Int) {}
        /**
         * Decrypted access unit (no RTP header) plus its u32 sample timestamp.
         *
         * [payload] comes from [AudioPayloadPool]. A consumer that has finished with it —
         * the bytes are copied into the decoder or AudioTrack — must hand it back with
         * [AudioPayloadPool.release] so the next packet can reuse the allocation. A sink
         * that ignores the buffer only loses the reuse, never correctness.
         */
        fun onRtp(payload: ByteArray, sample: Int) {}
        fun onPacket(
            wire: ByteArray,
            rtp: ByteArray?,
            sample: Int?,
            error: Throwable?,
        ) {}
    }

    private val closed = AtomicBoolean(false)
    private val receivedPackets = AtomicInteger()
    private val decryptedPackets = AtomicInteger()
    private val authenticationFailures = AtomicInteger()
    private var dataSocket: DatagramSocket? = null
    private var controlSocket: DatagramSocket? = null
    private var dataThread: Thread? = null
    private var controlThread: Thread? = null
    private var started = false

    fun listen(listener: Listener): Pair<Int, Int> {
        val data = bindAnyPort()
        // Keep short Wi-Fi bursts in the kernel while decrypting or scheduling pauses
        // the receive thread. The platform may cap this request; log the actual size.
        val originalBufferBytes = runCatching { data.receiveBufferSize }.getOrDefault(0)
        if (originalBufferBytes < AUDIO_RECEIVE_BUFFER_BYTES) {
            runCatching { data.receiveBufferSize = AUDIO_RECEIVE_BUFFER_BYTES }
        }
        onDiagnostic("Audio UDP receive buffer type=$streamType original=$originalBufferBytes requested=$AUDIO_RECEIVE_BUFFER_BYTES actual=${runCatching { data.receiveBufferSize }.getOrDefault(0)}")
        val control = bindAnyPort()
        dataSocket = data
        controlSocket = control
        dataThread = Thread({ runData(data, listener) }, "airplay-audio-rx").apply {
            isDaemon = true
            start()
        }
        controlThread = Thread({ runControl(control) }, "airplay-rtcp-rx").apply {
            isDaemon = true
            start()
        }
        return data.localPort to control.localPort
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        dataSocket?.close()
        controlSocket?.close()
        dataThread?.interrupt()
        controlThread?.interrupt()
    }

    private fun runData(socket: DatagramSocket, listener: Listener) {
        // Decryption runs here; if this thread loses the CPU to video decode the kernel UDP buffer
        // overflows and the datagrams are gone before the app ever sees them.
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
        } catch (_: Exception) {
            // Keep the default priority if the platform refuses.
        }
        val stats = StreamReceiveStats("audio type=$streamType", onDiagnostic)
        val buffer = ByteArray(DATAGRAM_BYTES)
        // Thread-confined scratch: one less short-lived allocation per packet on a weak SoC.
        val aadScratch = ByteArray(8)
        val nonceScratch = ByteArray(12)
        // Reused for the life of the stream: receive() rewrites length and address, so the
        // capacity is reset before every call instead of allocating a DatagramPacket each time.
        val datagram = DatagramPacket(buffer, buffer.size)
        try {
            while (!closed.get()) {
                try {
                    stats.reading()
                    datagram.length = buffer.size
                    socket.receive(datagram)
                } catch (_: Exception) {
                    if (closed.get()) return else continue
                }
                val length = datagram.length
                val hasRtpHeader = length >= RTP_HEADER_LEN
                val sample = if (hasRtpHeader) readU32Be(buffer, 4) else 0
                stats.received(
                    size = length,
                    sequence = if (hasRtpHeader) {
                        ((buffer[2].toInt() and 0xff) shl 8) or (buffer[3].toInt() and 0xff)
                    } else StreamReceiveStats.NO_SEQUENCE,
                    timestamp = if (hasRtpHeader) sample.toLong() and 0xffff_ffffL
                    else StreamReceiveStats.NO_TIMESTAMP,
                )
                val packetNumber = receivedPackets.incrementAndGet()
                if (length < RTP_HEADER_LEN + TAIL_LEN) {
                    if (packetNumber == 1) {
                        android.util.Log.w(
                            TAG,
                            "audio stream type=$streamType short packet bytes=$length",
                        )
                    }
                    // Only diagnostics want the bytes back; the decoder never sees this packet.
                    if (retainWire) {
                        listener.onPacket(
                            buffer.copyOf(length),
                            null,
                            null,
                            IOException("audio packet shorter than RTP header plus tail"),
                        )
                    }
                    stats.processed()
                    continue
                }

                // Header bytes 4..11 (timestamp + SSRC) are the AEAD associated data; the last
                // eight wire bytes are the nonce counter that sits above four zero bytes. Decrypt
                // straight out of the datagram buffer — no `wire` copy on the playback path.
                System.arraycopy(buffer, 4, aadScratch, 0, RTP_HEADER_LEN - 4)
                val sealedEnd = length - NONCE_LEN
                System.arraycopy(buffer, sealedEnd, nonceScratch, 4, NONCE_LEN)
                // Pooled destination: a steady-state packet allocates nothing here. The buffer is
                // handed to the sink with onRtp and returns via AudioPayloadPool.release once the
                // renderer has consumed it; a decrypt failure or a sink exception returns it here.
                val payload = AudioPayloadPool.acquire(sealedEnd - RTP_HEADER_LEN - TAG_LEN)
                // Once onRtp returns, the sink owns the payload and will release it; every other
                // path (decrypt failure, listener exception, early return) must release it here.
                var handedToSink = false
                try {
                    try {
                        AirPlayCrypto.chachaOpenInto(
                            key,
                            nonceScratch,
                            buffer,
                            RTP_HEADER_LEN,
                            sealedEnd - RTP_HEADER_LEN,
                            aadScratch,
                            payload,
                            0,
                        )
                    } catch (error: Exception) {
                        val failureNumber = authenticationFailures.incrementAndGet()
                        if (failureNumber == 1) {
                            android.util.Log.w(
                                TAG,
                                "audio stream type=$streamType first decrypt failure " +
                                    "wire=${buffer.copyOf(length).toHexString()}",
                                error,
                            )
                        }
                        if (retainWire) {
                            try {
                                listener.onPacket(buffer.copyOf(length), null, sample, error)
                            } catch (_: Exception) {
                                // Diagnostics must never take the receive thread down.
                            }
                        }
                        stats.processed()
                        continue
                    }
                    val decryptedNumber = decryptedPackets.incrementAndGet()
                    if (decryptedNumber <= FIRST_PACKET_LOG_COUNT) {
                        android.util.Log.i(
                            TAG,
                            "audio stream type=$streamType packet=$decryptedNumber sample=$sample " +
                                "wireBytes=$length payloadBytes=${payload.size} " +
                                "payloadHead=${payload.copyOf(minOf(payload.size, 16)).toHexString()}",
                        )
                    } else if (decryptedNumber % PACKET_LOG_INTERVAL == 0) {
                        android.util.Log.i(
                            TAG,
                            "audio stream type=$streamType decrypted=$decryptedNumber " +
                                "authFailures=${authenticationFailures.get()}",
                        )
                    }
                    if (retainWire) {
                        val wire = buffer.copyOf(length)
                        val rtp = ByteArray(RTP_HEADER_LEN + payload.size)
                        System.arraycopy(wire, 0, rtp, 0, RTP_HEADER_LEN)
                        System.arraycopy(payload, 0, rtp, RTP_HEADER_LEN, payload.size)
                        try {
                            listener.onPacket(wire, rtp, sample, null)
                        } catch (_: Exception) {
                            // Diagnostics must never take the receive thread down.
                        }
                    }
                    if (!started) {
                        started = true
                        listener.onStarted(sample)
                    }
                    // Ownership transfers at the call, not at the return: submit() may have
                    // queued the payload before throwing, and a release here would hand the
                    // same buffer to two consumers. A throw before submit() leaks one slot —
                    // the pool's contract allows that; it never allows a double-release.
                    handedToSink = true
                    listener.onRtp(payload, sample)
                    stats.processed()
                } catch (error: Exception) {
                    // An uncaught exception on `airplay-audio-rx` kills the Android process. A
                    // sink/listener failure costs one packet, not the whole CarPlay session.
                    if (!closed.get()) {
                        android.util.Log.w(
                            TAG,
                            "audio stream type=$streamType packet handler failed; dropping packet",
                            error,
                        )
                    }
                    stats.processed()
                } finally {
                    if (!handedToSink) {
                        AudioPayloadPool.release(payload)
                    }
                }
                // Decrypt, log, forward and account exactly once per datagram. A second
                // forward here (a leftover from the pooled-payload refactor) enqueued every
                // packet twice: the FIFO held seconds of stale audio, both AudioPackets
                // shared one pooled buffer so the first release recycled memory still being
                // decoded, and the queue overflow showed up as constant drop/trim holes.
            }
        } finally { stats.flush(ended = true) }
    }

    private fun runControl(socket: DatagramSocket) {
        val buffer = ByteArray(DATAGRAM_BYTES)
        while (!closed.get()) {
            try {
                socket.receive(DatagramPacket(buffer, buffer.size))
            } catch (_: Exception) {
                if (closed.get()) return
            }
        }
    }

    private fun bindAnyPort(): DatagramSocket {
        val socket = DatagramSocket(null)
        socket.reuseAddress = true
        // A Wi-Fi gap delivers a whole backlog of RTP in one burst. The default socket buffer is
        // too small to hold it while this thread decrypts, which shows up as silent packet loss.
        // Set before bind so the kernel allocates it up front; listen() logs what it actually got.
        socket.receiveBufferSize = AUDIO_RECEIVE_BUFFER_BYTES
        socket.bind(InetSocketAddress(InetAddress.getByName("::"), 0))
        return socket
    }

    private fun readU32Be(source: ByteArray, offset: Int): Int =
        ((source[offset].toInt() and 0xff) shl 24) or
            ((source[offset + 1].toInt() and 0xff) shl 16) or
            ((source[offset + 2].toInt() and 0xff) shl 8) or
            (source[offset + 3].toInt() and 0xff)

    private fun ByteArray.toHexString(): String =
        joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private companion object {
        const val TAG = "xcertplay-usb"
        const val DATAGRAM_BYTES = 4_096
        const val AUDIO_RECEIVE_BUFFER_BYTES = 512 * 1024
        const val RTP_HEADER_LEN = 12
        const val TAG_LEN = 16
        const val NONCE_LEN = 8
        const val TAIL_LEN = TAG_LEN + NONCE_LEN
        const val FIRST_PACKET_LOG_COUNT = 3
        const val PACKET_LOG_INTERVAL = 100
    }
}

/**
 * Size-keyed free list for decrypted audio payloads.
 *
 * The receive thread acquires a buffer before decrypting; the renderer returns it once the bytes
 * are copied into the decoder or AudioTrack. Without the pool every packet allocates and then
 * abandons its plaintext array (~50/s per stream), which is steady young-generation churn on the
 * weak SoC this receiver targets.
 *
 * Buffers handed to a consumer that never returns them are simply not reused — the pool saves
 * allocations, it never revives a buffer in place. [release] must not be called on an array that
 * is still in use, and never twice for the same buffer.
 */
internal object AudioPayloadPool {
    /** Bounds retained RAM; a burst deeper than this falls back to plain allocation. */
    private const val MAX_IDLE = 256
    private val idle = HashMap<Int, ArrayList<ByteArray>>()
    private var idleTotal = 0

    @Synchronized
    fun acquire(length: Int): ByteArray {
        val free = idle[length]
        if (free != null && free.isNotEmpty()) {
            idleTotal--
            return free.removeAt(free.size - 1)
        }
        return ByteArray(length)
    }

    @Synchronized
    fun release(payload: ByteArray) {
        if (idleTotal >= MAX_IDLE) return
        var free = idle[payload.size]
        if (free == null) {
            free = ArrayList()
            idle[payload.size] = free
        }
        free.add(payload)
        idleTotal++
    }
}

/** Maps the phone's negotiated audioFormat bits to a decode/render format. */
object AudioStreamCodec {
    fun fromFormatBits(bits: Long, payloadType: Int, audioType: String = "media"): AudioFormat {
        val isAacLc = (bits and (AAC_LC_44K_STEREO or AAC_LC_48K_STEREO)) != 0L
        val isOpus = (bits and OPUS_MONO) != 0L
        val pcm = PCM_FORMAT[bits]
        return when {
            isOpus -> AudioFormat(AudioCodecKind.OPUS, 48_000, 1, payloadType, audioType)
            isAacLc -> AudioFormat(
                AudioCodecKind.AAC_LC,
                if ((bits and AAC_LC_48K_STEREO) != 0L) 48_000 else 44_100,
                2,
                payloadType,
                audioType,
            )
            pcm != null -> AudioFormat(AudioCodecKind.LPCM, pcm.first, pcm.second, payloadType, audioType)
            else -> AudioFormat(AudioCodecKind.LPCM, 44_100, 2, payloadType, audioType)
        }
    }

    private const val AAC_LC_44K_STEREO = 0x400000L
    private const val AAC_LC_48K_STEREO = 0x800000L
    private const val OPUS_MONO = 0x10000000L or 0x20000000L or 0x40000000L

    private val PCM_FORMAT = mapOf(
        0x4L to (8_000 to 1),
        0x8L to (8_000 to 2),
        0x10L to (16_000 to 1),
        0x20L to (16_000 to 2),
        0x40L to (24_000 to 1),
        0x80L to (24_000 to 2),
        0x100L to (32_000 to 1),
        0x200L to (32_000 to 2),
        0x400L to (44_100 to 1),
        0x800L to (44_100 to 2),
        0x4000L to (48_000 to 1),
        0x8000L to (48_000 to 2),
    )
}

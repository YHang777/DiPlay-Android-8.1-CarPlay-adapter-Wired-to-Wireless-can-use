package com.shilapi.xcertplay.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat as AndroidAudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import com.shilapi.xcertplay.airplay.AudioPayloadPool
import com.shilapi.xcertplay.airplay.AudioStreamId
import com.shilapi.xcertplay.airplay.MediaSink
import com.shilapi.xcertplay.airplay.MicrophoneConfig
import com.shilapi.xcertplay.airplay.VideoCodec
import com.shilapi.xcertplay.airplay.toHexString
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.TimeUnit
import java.util.function.LongBinaryOperator

/** Owns one focus request for all eligible tracks in a CarPlay sink. */
internal class AudioFocusCoordinator(
    context: Context?,
    private val enabled: Boolean,
    private val report: (String) -> Unit = {},
) {
    private data class Entry(val channel: AudioChannel, val attributes: AudioAttributes)

    private val manager = context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val active = LinkedHashMap<AudioTrack, Entry>()
    private var request: AudioFocusRequest? = null
    private var requestedChannel: AudioChannel? = null
    private val listener = AudioManager.OnAudioFocusChangeListener { change ->
        synchronized(this) {
            runCatching { report("Audio: focus change=$change activeTracks=${active.size}") }
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> setVolume(DUCKED_VOLUME)
                AudioManager.AUDIOFOCUS_GAIN -> setVolume(FULL_VOLUME)
                // Keep CarPlay audio running on permanent or transient loss. Some head units
                // do not send a later gain callback after taking focus back.
            }
        }
    }

    @Synchronized
    fun acquire(track: AudioTrack, channel: AudioChannel, attributes: AudioAttributes) {
        if (!enabled || manager == null || channel == AudioChannel.NAVIGATION) return
        active[track] = Entry(channel, attributes)
        refreshRequest()
    }

    @Synchronized
    fun release(track: AudioTrack) {
        if (active.remove(track) != null) refreshRequest()
    }

    private fun refreshRequest() {
        val primary = active.values.maxByOrNull { it.channel.focusPriority() }
        if (primary == null) {
            request?.let { manager?.abandonAudioFocusRequest(it) }
            request = null
            requestedChannel = null
            return
        }
        if (request != null && requestedChannel == primary.channel) return
        request?.let { manager?.abandonAudioFocusRequest(it) }
        val gain = when (primary.channel) {
            AudioChannel.MEDIA -> AudioManager.AUDIOFOCUS_GAIN
            AudioChannel.PHONE -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            AudioChannel.ASSISTANT -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            AudioChannel.NAVIGATION -> return
        }
        val next = AudioFocusRequest.Builder(gain)
            .setAudioAttributes(primary.attributes)
            .setOnAudioFocusChangeListener(listener, Handler(Looper.getMainLooper()))
            .build()
        request = next
        requestedChannel = primary.channel
        val result = manager?.requestAudioFocus(next)
        val line = "Audio: focus requested channel=${primary.channel} gain=$gain granted=$result activeTracks=${active.size}"
        Log.i(TAG, line)
        runCatching { report(line) }
    }

    private fun setVolume(volume: Float) {
        active.keys.forEach { track -> runCatching { track.setStereoVolume(volume, volume) } }
    }

    private fun AudioChannel.focusPriority(): Int = when (this) {
        AudioChannel.MEDIA -> 3
        AudioChannel.PHONE -> 2
        AudioChannel.ASSISTANT -> 1
        AudioChannel.NAVIGATION -> 0
    }

    private companion object {
        const val TAG = "DiPlay-AudioFocus"
        const val FULL_VOLUME = 1f
        const val DUCKED_VOLUME = 0.2f
    }
}

/**
 * Android rendering backend for the CarPlay media engine. Video frames are
 * decoded with MediaCodec onto a Surface; audio streams are decoded to PCM and
 * played through AudioTrack. Each audio stream keeps its own track and usage so
 * media and navigation guidance stay independently routable. Call [close]
 * when the session tears down.
 */
class AndroidMediaSink(
    surface: Surface? = null,
    private val videoWidth: Int = 1280,
    private val videoHeight: Int = 720,
    private val preferSoftwareHevcDecoder: Boolean = false,
    private val advancedAudioChannelMapping: Boolean = false,
    private val audioFocusEnabled: Boolean = false,
    private val mediaChannel: Int = 0,
    private val navigationChannel: Int = 0,
    context: Context? = null,
    private val navigationStreamType: Int = AudioChannelMapper.DEFAULT_NAVIGATION_STREAM_TYPE,
    onScreenStreamActiveChanged: ((Int, Boolean) -> Unit)? = null,
    private val mediaBufferMillis: Int = MediaAudioBuffer.DEFAULT_MILLIS,
    private val onAudioDiagnostic: (String) -> Unit = {},
    /** True while any music ("media") audio stream is running; called from media threads. */
    private val onMediaAudioChanged: (Boolean) -> Unit = {},
) : MediaSink {
    private val appContext = context?.applicationContext
    private val audioFocusCoordinator = AudioFocusCoordinator(
        appContext,
        audioFocusEnabled,
        onAudioDiagnostic,
    )
    private val screenStateLock = Any()
    private val activeScreenTypes = mutableSetOf<Int>()
    private val defaultSurface = surface
    @Volatile private var screenStreamActiveChanged = onScreenStreamActiveChanged
    private val surfaces = ConcurrentHashMap<Int, Surface>()
    private val videoDecoders = ConcurrentHashMap<Int, VideoDecoder>()
    private val mediaAudioTypes = mutableSetOf<AudioStreamId>()
    private val audioRenderers = ConcurrentHashMap<AudioStreamId, AudioRenderer>()
    // Hot path for onAudioRtp: one identity check per packet instead of a synchronized map
    // lookup. Written before cachedRendererId so a reader that sees the id also sees the value.
    @Volatile private var cachedRendererId: AudioStreamId? = null
    @Volatile private var cachedRenderer: AudioRenderer? = null
    private val microphoneUplinks = ConcurrentHashMap<AudioStreamId, MicrophoneUplink>()
    private val pendingVideoCodec = ConcurrentHashMap<Int, VideoCodec>()
    private val videoRecoveryHandlers = ConcurrentHashMap<Int, () -> Unit>()
    private val videoDiagnosticHandlers = ConcurrentHashMap<Int, (String) -> Unit>()
    // AtomicLong rather than Long: boxing the timestamp on every frame was one garbage object
    // per video frame on the receive path.
    private val lastVideoFrameNanosByType = ConcurrentHashMap<Int, AtomicLong>()
    private val audioPressure = AudioPressureSignal()
    private val recoveryPending = AtomicBoolean(false)
    private val recoveryExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "carplay-video-recovery").apply { isDaemon = true }
    }

    override fun setVideoRecoveryHandler(type: Int, handler: () -> Unit) {
        videoRecoveryHandlers[type] = handler
    }

    override fun setVideoDiagnosticHandler(type: Int, handler: (String) -> Unit) {
        videoDiagnosticHandlers[type] = handler
    }

    private fun requestVideoRecovery(type: Int) {
        if (!recoveryPending.compareAndSet(false, true)) return
        try {
            recoveryExecutor.execute {
                try { videoRecoveryHandlers[type]?.invoke() }
                catch (error: Exception) { Log.w("xcertplay-usb", "Video keyframe request failed", error) }
                finally { recoveryPending.set(false) }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) { recoveryPending.set(false) }
    }

    fun setSurface(type: Int, surface: Surface) {
        surfaces[type] = surface
        videoDecoders[type]?.setSurface(surface)
    }

    fun clearSurface(type: Int, surface: Surface) {
        if (surfaces.remove(type, surface)) videoDecoders[type]?.setSurface(null)
    }

    fun setScreenStreamActiveChangedListener(listener: ((Int, Boolean) -> Unit)?) {
        synchronized(screenStateLock) {
            screenStreamActiveChanged = listener
            activeScreenTypes.forEach { listener?.invoke(it, true) }
        }
    }

    override fun onVideoCodec(type: Int, codec: VideoCodec) {
        pendingVideoCodec[type] = codec
    }

    override fun onVideoConfig(type: Int, codecData: ByteArray) {
        val codec = pendingVideoCodec[type] ?: VideoCodec.H264
        videoDecoder(type).configure(codec, codecData)
    }

    override fun onVideoFrame(type: Int, naluBytes: ByteArray) {
        // Written on the RX path, never on the decode path: pressure-driven frame skips must not
        // look like a stream stall to the 20 s watchdog.
        lastVideoFrameNanosByType.getOrPut(type) { AtomicLong() }.set(System.nanoTime())
        videoDecoder(type).submit(naluBytes)
    }

    /**
     * Nanoseconds of the newest video frame seen on [type], or null when none has arrived. Read by
     * the stream-stall watchdog; written on the video path, so it stays a lock-free map write.
     * Null rather than 0 keeps "no frame yet" distinct from a real `nanoTime()` reading.
     */
    fun lastVideoFrameNanos(type: Int): Long? = lastVideoFrameNanosByType[type]?.get()

    override fun onScreenStreamActive(type: Int, active: Boolean) {
        if (!active) {
            videoRecoveryHandlers.remove(type)
            videoDiagnosticHandlers.remove(type)
            videoDecoders.remove(type)?.close()
            pendingVideoCodec.remove(type)
        }
        synchronized(screenStateLock) {
            if (active) activeScreenTypes.add(type) else activeScreenTypes.remove(type)
            screenStreamActiveChanged?.invoke(type, active)
        }
    }

    override fun onAudioStarted(id: AudioStreamId, format: AudioFormat, firstSample: Int) {
        audioRenderer(id, format).start()
        if (format.audioType == "media") updateMediaAudio(id, true)
    }

    override fun onAudioRtp(id: AudioStreamId, format: AudioFormat, payload: ByteArray, sample: Int) {
        audioRenderer(id, format).submit(payload, sample)
    }

    override fun onAudioStopped(id: AudioStreamId) {
        audioRenderers.remove(id)?.close()
        if (cachedRendererId === id) {
            cachedRendererId = null
            cachedRenderer = null
        }
        updateMediaAudio(id, false)
    }

    private fun updateMediaAudio(id: AudioStreamId, active: Boolean) {
        val (before, after) = synchronized(mediaAudioTypes) {
            val before = mediaAudioTypes.isNotEmpty()
            if (active) mediaAudioTypes.add(id) else mediaAudioTypes.remove(id)
            before to mediaAudioTypes.isNotEmpty()
        }
        if (before != after) onMediaAudioChanged(after)
    }

    override fun onMicrophoneStarted(id: AudioStreamId, config: MicrophoneConfig) {
        val uplink = microphoneUplinks.computeIfAbsent(id) { MicrophoneUplink(config) }
        if (!uplink.start()) microphoneUplinks.remove(id, uplink)
    }

    override fun onMicrophoneStopped(id: AudioStreamId) {
        microphoneUplinks.remove(id)?.close()
    }

    fun close() {
        synchronized(screenStateLock) {
            activeScreenTypes.forEach { screenStreamActiveChanged?.invoke(it, false) }
            activeScreenTypes.clear()
            screenStreamActiveChanged = null
        }
        videoDecoders.values.forEach(VideoDecoder::close)
        videoDecoders.clear()
        videoRecoveryHandlers.clear()
        videoDiagnosticHandlers.clear()
        recoveryExecutor.shutdownNow()
        audioRenderers.values.forEach(AudioRenderer::close)
        audioRenderers.clear()
        cachedRendererId = null
        cachedRenderer = null
        val hadMedia = synchronized(mediaAudioTypes) { mediaAudioTypes.isNotEmpty().also { mediaAudioTypes.clear() } }
        if (hadMedia) onMediaAudioChanged(false)
        microphoneUplinks.values.forEach(MicrophoneUplink::close)
        microphoneUplinks.clear()
    }

    private fun videoDecoder(type: Int): VideoDecoder {
        // Plain get first: computeIfAbsent would build a capturing lambda on every video frame.
        val existing = videoDecoders[type]
        if (existing != null) return existing
        return videoDecoders.computeIfAbsent(type) {
            VideoDecoder(
                type,
                surfaces[type] ?: defaultSurface,
                videoWidth,
                videoHeight,
                preferSoftwareHevcDecoder,
                audioPressure,
                requestKeyFrame = { requestVideoRecovery(type) },
                report = { videoDiagnosticHandlers[type]?.invoke(it) },
            )
        }
    }

    private fun audioRenderer(id: AudioStreamId, format: AudioFormat): AudioRenderer {
        // Fast path: the engine reuses the id and format instances for the life of a stream,
        // so steady-state RTP costs two volatile reads and no monitor.
        if (cachedRendererId === id) {
            val cached = cachedRenderer
            if (cached != null && cached.format == format) return cached
        }
        return audioRendererLocked(id, format)
    }

    @Synchronized
    private fun audioRendererLocked(id: AudioStreamId, format: AudioFormat): AudioRenderer {
        val existing = audioRenderers[id]
        if (existing?.format == format) {
            cachedRenderer = existing
            cachedRendererId = id
            return existing
        }
        existing?.close()
        return AudioRenderer(
            format,
            pressureSignal = audioPressure,
            advancedAudioChannelMapping = advancedAudioChannelMapping,
            audioFocusEnabled = audioFocusEnabled,
            mediaChannel = mediaChannel,
            navigationChannel = navigationChannel,
            audioFocusCoordinator = audioFocusCoordinator,
            navigationStreamType = navigationStreamType,
            mediaBufferMillis = mediaBufferMillis,
            report = onAudioDiagnostic,
        ).also {
            audioRenderers[id] = it
            cachedRenderer = it
            cachedRendererId = id
        }
    }
}

/** Serial MediaCodec video decoder: one worker owns configure and frame feeding. */
private class VideoDecoder(
    streamType: Int,
    surface: Surface?,
    private val width: Int,
    private val height: Int,
    private val preferSoftwareHevcDecoder: Boolean,
    private val pressureSignal: AudioPressureSignal,
    private val requestKeyFrame: () -> Unit,
    private val report: (String) -> Unit,
) : Closeable {
    private val queue = VideoDecodeQueue()
    @Volatile private var running = true
    @Volatile private var decoder: MediaCodec? = null
    private var outputSurface: Surface? = surface
    private var lastConfig: VideoJob.Config? = null
    private var renderedFrameLogged = false
    private var submittedFrameLogged = false
    private var duplicateConfigLogged = false
    private val referenceChain = VideoReferenceChain()
    private var lastKeyFrameRequestNs = 0L
    private var lastKnownPressure = AudioPressure.CALM
    private var threadDemoted = false
    // One supplier instance for every offer: `pressureSignal::level` per job allocated a bound
    // reference on the receive thread each frame.
    private val pressureLevel: () -> Int = { pressureSignal.level() }
    // Reused across the whole decode loop instead of one BufferInfo allocation per drain.
    private val outputInfo = MediaCodec.BufferInfo()
    // The main screen keeps the historical log format; other screens are labelled.
    private val stats = VideoStats(if (streamType == 110) "" else " stream=$streamType")
    private val thread = Thread(::run, "carplay-video").apply { isDaemon = true; start() }

    fun configure(codec: VideoCodec, codecData: ByteArray) {
        queue.offer(VideoJob.Config(codec, codecData), pressureLevel)
    }

    fun submit(nalus: ByteArray) {
        stats.onReceived(nalus.size)
        // Every pressure drop happens in feed(), on the decode thread, where it is ordered with
        // referenceChain.onQueued(). Dropping here would race that: needsKeyFrame is still true
        // while a just-offered IDR waits in the queue, so an interframe that would have decoded
        // *after* that IDR gets discarded, and the next P-frame is then decoded against a
        // reference that never existed. Detecting that safely means parsing every frame on the
        // RTP thread (an allocation per frame for AVCC input), which is what this path just spent
        // its budget removing. The queue is already capped at the pressure budget and the video
        // worker is demoted below audio, so the wakeups we give up here are work video should be
        // losing to audio anyway.
        queue.offer(VideoJob.Frame(nalus), pressureLevel)
    }

    fun setSurface(surface: Surface?) {
        queue.offer(VideoJob.SurfaceChanged(surface), pressureLevel)
    }

    override fun close() {
        running = false
        thread.interrupt()
    }

    private fun run() {
        try {
            while (running) {
                val level = pressureSignal.level()
                if (level != lastKnownPressure) {
                    lastKnownPressure = level
                    applyThreadPriority(level)
                }
                val job = queue.poll(5)
                try {
                    when (job) {
                        is VideoJob.Config -> configureDecoder(job)
                        is VideoJob.Frame -> {
                            val budget = VideoYieldBudget.forLevel(pressureSignal.level())
                            if (System.nanoTime() - job.receivedNs > budget.maxFrameAgeNs) {
                                softSkip("video backlog exceeded ${budget.maxFrameAgeNs / 1_000_000} ms")
                            } else feed(job.nalus)
                        }
                        is VideoJob.SurfaceChanged -> changeSurface(job.surface)
                        is VideoJob.SoftSkip -> softSkip("video queue overflow")
                        is VideoJob.Resync -> recover("video queue overflow")
                        null -> Unit
                    }
                    val draining = decoder
                    if (draining != null) drainOutput(draining)
                    stats.logIfDue(pressureSignal.level())?.let(report)
                    if (referenceChain.needsKeyFrame && lastConfig != null && outputSurface != null) requestKeyFrameIfDue()
                } catch (error: Exception) {
                    if (running) Log.e(TAG, "video decoder job failed: ${job?.javaClass?.simpleName}", error)
                    if (running) report("decoder error ${error.javaClass.simpleName}; waiting for keyframe")
                    releaseDecoder()
                    referenceChain.reset()
                    requestKeyFrameIfDue()
                }
            }
        } catch (_: InterruptedException) {
            // Worker shut down.
        } finally {
            releaseDecoder()
        }
    }

    /**
     * Cheaper than [recover]: drop the queued backlog and wait for a keyframe while the decoder
     * keeps running. Releasing and reconfiguring MediaCodec is the expensive part on weak SoCs.
     */
    private fun softSkip(reason: String) {
        Log.i(TAG, "Video soft-skip: $reason; requesting keyframe")
        stats.onSoftSkip()
        report("soft-skip: $reason; requesting keyframe")
        queue.discardFrames()
        referenceChain.reset()
        requestKeyFrameIfDue()
    }

    /** At CRITICAL, video steps aside so the urgent-audio workers get the CPU. */
    private fun applyThreadPriority(level: Int) {
        val demote = level >= AudioPressure.CRITICAL
        if (demote == threadDemoted) return
        threadDemoted = demote
        try {
            android.os.Process.setThreadPriority(
                android.os.Process.myTid(),
                if (demote) android.os.Process.THREAD_PRIORITY_DEFAULT + 4
                else android.os.Process.THREAD_PRIORITY_DEFAULT,
            )
        } catch (_: Exception) {
            // Best effort; the frame budget already yields work without this.
        }
    }

    private fun configureDecoder(config: VideoJob.Config) {
        val previous = lastConfig
        if (
            decoder != null &&
            previous?.codec == config.codec &&
            previous.codecData.contentEquals(config.codecData)
        ) {
            if (!duplicateConfigLogged) {
                duplicateConfigLogged = true
                Log.i(TAG, "video decoder config unchanged; keeping existing decoder")
            }
            return
        }
        lastConfig = config
        duplicateConfigLogged = false
        releaseDecoder()
        referenceChain.reset()
        val surface = outputSurface ?: return
        val codec = config.codec
        val codecData = config.codecData
        val mime = if (codec == VideoCodec.H265) MediaFormat.MIMETYPE_VIDEO_HEVC
        else MediaFormat.MIMETYPE_VIDEO_AVC
        val csd = if (codec == VideoCodec.H265) {
            MediaCodecSupport.hevcCodecSpecificData(codecData).takeIf { it.isNotEmpty() }
                ?.let { listOf(it) } ?: emptyList()
        } else {
            val (sps, pps) = MediaCodecSupport.avcParameterSets(codecData)
            listOfNotNull(
                sps.takeIf { it.isNotEmpty() }?.let { START_CODE + it },
                pps.takeIf { it.isNotEmpty() }?.let { START_CODE + it },
            )
        }
        // Some vendor decoders (e.g. MediaTek c2.mtk.avc.decoder) reject the tuned
        // parameters with BAD_VALUE. Fall back to a minimal format, then to software.
        val attempts = listOf(
            DecoderAttempt(codecName = null, tuned = true),
            DecoderAttempt(codecName = null, tuned = false),
        ) + softwareDecoderName(mime)?.let { listOf(DecoderAttempt(it, tuned = false)) }.orEmpty()
        var next: MediaCodec? = null
        for (attempt in attempts) {
            next = tryConfigure(mime, csd, surface, attempt)
            if (next != null) break
        }
        if (next == null) {
            report("decoder configuration failed mime=$mime size=${width}x$height")
        }
        decoder = next
        renderedFrameLogged = false
        submittedFrameLogged = false
        if (next != null) {
            report("decoder=${next.name} mime=$mime size=${width}x$height")
            Log.i(
                TAG,
                "video decoder configured name=${next.name} mime=$mime size=${width}x$height",
            )
        }
    }

    private data class DecoderAttempt(val codecName: String?, val tuned: Boolean)

    private fun buildFormat(mime: String, csd: List<ByteArray>, tuned: Boolean): MediaFormat =
        MediaFormat.createVideoFormat(mime, width, height).apply {
            if (tuned) {
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_SIZE)
                setInteger(MediaFormat.KEY_PRIORITY, 0)
            }
            csd.forEachIndexed { index, bytes -> setByteBuffer("csd-$index", ByteBuffer.wrap(bytes)) }
        }

    private fun tryConfigure(
        mime: String,
        csd: List<ByteArray>,
        surface: Surface,
        attempt: DecoderAttempt,
    ): MediaCodec? {
        var candidate: MediaCodec? = null
        return try {
            val format = buildFormat(mime, csd, attempt.tuned)
            val codec = attempt.codecName?.let { MediaCodec.createByCodecName(it) } ?: createDecoder(mime)
            candidate = codec
            if (attempt.tuned && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                codec.codecInfo.getCapabilitiesForType(mime).isFeatureSupported("low-latency")) {
                format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            codec.configure(format, surface, null, 0)
            codec.start()
            codec
        } catch (error: Exception) {
            runCatching { candidate?.release() }
            Log.w(
                TAG,
                "video decoder configure failed name=${attempt.codecName ?: "default"} " +
                    "tuned=${attempt.tuned} mime=$mime size=${width}x$height",
                error,
            )
            null
        }
    }

    private fun softwareDecoderName(mime: String): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull {
            !it.isEncoder && it.isSoftwareOnly && mime in it.supportedTypes
        }?.name
    }

    private fun createDecoder(mime: String): MediaCodec {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            mime == MediaFormat.MIMETYPE_VIDEO_HEVC &&
            preferSoftwareHevcDecoder
        ) {
            val software = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull {
                !it.isEncoder && it.isSoftwareOnly && mime in it.supportedTypes
            }
            if (software != null) {
                try {
                    return MediaCodec.createByCodecName(software.name)
                } catch (error: Exception) {
                    Log.w(TAG, "software HEVC decoder unavailable name=${software.name}", error)
                }
            }
        }
        return MediaCodec.createDecoderByType(mime)
    }

    private fun changeSurface(surface: Surface?) {
        if (outputSurface === surface) return
        outputSurface = surface
        if (surface == null) {
            releaseDecoder()
            Log.i(TAG, "video decoder detached from surface")
            return
        }
        val codec = decoder
        if (codec != null) {
            try {
                codec.setOutputSurface(surface)
                Log.i(TAG, "video decoder output surface updated")
                return
            } catch (error: Exception) {
                Log.w(TAG, "video decoder output surface update failed; reconfiguring", error)
            }
        }
        releaseDecoder()
        lastConfig?.let(::configureDecoder)
    }

    private fun feed(nalus: ByteArray) {
        val annexB = MediaCodecSupport.toAnnexB(nalus)
        val config = lastConfig ?: return
        if (outputSurface == null) return
        if (annexB.isEmpty()) { recover("invalid video access unit"); return }
        if (!referenceChain.accepts(annexB, config.codec)) {
            requestKeyFrameIfDue()
            return
        }
        val budget = VideoYieldBudget.forLevel(pressureSignal.level())
        if (budget.dropNonKeyFrames && !MediaCodecSupport.isRandomAccess(annexB, config.codec)) {
            // Skipping an interframe costs no decode time and no decoder churn — but it leaves a
            // hole in the reference chain, because the next P-frame depends on the one dropped
            // here. Break the chain so those dependents are rejected until a real IDR is queued;
            // without this, pressure easing mid-GOP feeds a P-frame whose reference never decoded.
            stats.onPressureDrop()
            referenceChain.reset()
            requestKeyFrameIfDue()
            return
        }
        if (decoder == null) configureDecoder(config)
        val codec = decoder ?: return
        if (!submittedFrameLogged) {
            submittedFrameLogged = true
            Log.i(
                TAG,
                "video decoder first input avcc=${nalus.size} annexB=${annexB.size} " +
                    "head=${annexB.take(16).joinToString("") { "%02x".format(it.toInt() and 0xff) }}",
            )
        }
        val index = VideoInputPump.acquire(
            running = { running }, drain = { drainOutput(codec) },
            dequeue = { codec.dequeueInputBuffer(INPUT_TIMEOUT_US) },
        )
        if (index < 0) { recover("video decoder input stalled"); return }
        val input = checkNotNull(codec.getInputBuffer(index)) { "Decoder input buffer unavailable" }
        input.clear()
        if (annexB.size <= input.remaining()) {
            input.put(annexB)
            codec.queueInputBuffer(index, 0, annexB.size, System.nanoTime() / 1000, 0)
            referenceChain.onQueued()
        } else {
            recover("video frame exceeded codec input capacity")
            return
        }
        drainOutput(codec)
    }

    private fun recover(reason: String) {
        Log.w(TAG, "Video recovery: $reason; waiting for keyframe")
        stats.onRecovery()
        report("recovery: $reason; waiting for keyframe")
        // Recreate with codec-specific data: flush can discard CSD before the first output.
        releaseDecoder()
        referenceChain.reset()
        requestKeyFrameIfDue()
    }

    private fun requestKeyFrameIfDue() {
        val now = System.nanoTime()
        if (lastKeyFrameRequestNs != 0L && now - lastKeyFrameRequestNs < 1_000_000_000L) return
        lastKeyFrameRequestNs = now
        requestKeyFrame()
    }

    private fun drainOutput(codec: MediaCodec) {
        val info = outputInfo
        while (running) {
            val index = codec.dequeueOutputBuffer(info, 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> logOutputFormat(codec.outputFormat)
                index >= 0 -> {
                    val render = outputSurface != null
                    codec.releaseOutputBuffer(index, render)
                    if (render) stats.onRendered()
                    if (render && !renderedFrameLogged) {
                        renderedFrameLogged = true
                        report("first frame rendered")
                        Log.i(TAG, "video decoder rendered first frame bytes=${info.size}")
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
                else -> return
            }
        }
    }

    private fun logOutputFormat(format: MediaFormat) {
        report("output format requested=${width}x${height} " +
            "coded=${format.intOrNull(MediaFormat.KEY_WIDTH)}x${format.intOrNull(MediaFormat.KEY_HEIGHT)} " +
            "crop=${format.intOrNull("crop-left")},${format.intOrNull("crop-top")}," +
            "${format.intOrNull("crop-right")},${format.intOrNull("crop-bottom")} " +
            "stride=${format.intOrNull(MediaFormat.KEY_STRIDE)} slice=${format.intOrNull(MediaFormat.KEY_SLICE_HEIGHT)} " +
            "color=${format.intOrNull(MediaFormat.KEY_COLOR_STANDARD)}/${format.intOrNull(MediaFormat.KEY_COLOR_RANGE)}/${format.intOrNull(MediaFormat.KEY_COLOR_TRANSFER)}")
        Log.i(
            TAG,
            "video decoder output format " +
                "size=${format.intOrNull(MediaFormat.KEY_WIDTH)}x" +
                "${format.intOrNull(MediaFormat.KEY_HEIGHT)} " +
                "stride=${format.intOrNull(MediaFormat.KEY_STRIDE)} " +
                "slice=${format.intOrNull(MediaFormat.KEY_SLICE_HEIGHT)} " +
                "standard=${format.intOrNull(MediaFormat.KEY_COLOR_STANDARD)} " +
                "range=${format.intOrNull(MediaFormat.KEY_COLOR_RANGE)} " +
                "transfer=${format.intOrNull(MediaFormat.KEY_COLOR_TRANSFER)}",
        )
    }

    @Synchronized
    private fun releaseDecoder() {
        val codec = decoder
        decoder = null
        if (codec != null) {
            try {
                codec.stop()
            } catch (_: Exception) {
                // Best effort.
            }
            try {
                codec.release()
            } catch (_: Exception) {
                // Best effort.
            }
        }
    }

    private companion object {
        const val TAG = "xcertplay-usb"
        const val MAX_INPUT_SIZE = 8 * 1024 * 1024
        const val INPUT_TIMEOUT_US = 10_000L
        val START_CODE = byteArrayOf(0x00, 0x00, 0x00, 0x01)
    }
}

private fun MediaFormat.intOrNull(key: String): Int? =
    if (!containsKey(key)) {
        null
    } else {
        try {
            getInteger(key)
        } catch (_: Exception) {
            null
        }
    }

/** Decodes AAC-LC/Opus to PCM and plays it, or plays wired LPCM directly. */
private class AudioRenderer(
    val format: AudioFormat,
    private val pressureSignal: AudioPressureSignal,
    private val advancedAudioChannelMapping: Boolean,
    private val audioFocusEnabled: Boolean,
    private val mediaChannel: Int,
    private val navigationChannel: Int,
    private val audioFocusCoordinator: AudioFocusCoordinator,
    private val navigationStreamType: Int,
    private val mediaBufferMillis: Int,
    private val report: (String) -> Unit,
) : Closeable {
    /** Decrypted access unit (no RTP header) plus its u32 sample timestamp. */
    private data class AudioPacket(val payload: ByteArray, val sample: Int)

    // Unique per instance: a replaced renderer must not clear its successor's pressure.
    private val pressureSource = "${format.audioType}#${System.identityHashCode(this)}"

    private var trackAttributes: AudioAttributes? = null
    private var mappedChannel: AudioChannel? = null
    // ArrayBlockingQueue allocates nothing per offer; LinkedBlockingQueue makes a Node each time.
    private val queue = ArrayBlockingQueue<AudioPacket>(MAX_QUEUED_PACKETS)
    @Volatile private var running = true
    @Volatile private var started = false
    // Set first thing in release(): the RX thread can still call submit() while the worker
    // tears down, and a packet offered after the drain would strand a pooled buffer in a queue
    // nobody reads again.
    @Volatile private var released = false
    private var codec: MediaCodec? = null
    private var track: AudioTrack? = null
    // Reused for every drain instead of one BufferInfo allocation per worker iteration.
    private val codecOutputInfo = MediaCodec.BufferInfo()
    // Held back when the decoder has no input buffer yet. Dropping it would skip music — the
    // "pause, then the song jumps ahead" glitch — so it is retried until the decoder accepts it.
    private var pending: AudioPacket? = null
    private var pendingSinceNs = 0L
    private var adtsScratch = ByteArray(0)
    // Reused decode-output scratch: MediaCodec buffers are copied here before AudioTrack.
    private var pcm = ByteArray(64 * 1024)
    private var playbackStarted = false
    private var prebufferBytes = 0
    private var startThresholdBytes = 0
    private var rebufferStartThresholdBytes = 0
    // Pads the content timeline over RTP packets the wire lost; see AudioTimeline.
    private val timeline = AudioTimeline()
    // Reused zero buffer: a hole arrives every few seconds and a fresh array per hole would
    // put back exactly the GC churn we took off the crypto path.
    private var silence = ByteArray(0)
    private var concealEvents = 0
    private var concealEventsTotal = 0
    private var concealedSamplesThisWindow = 0L
    private var fadeApplied = false
    private var droppedPacketsLogged = false
    private var firstAacPayloadLogged = false
    private var firstOpusShortPacketLogged = false
    private var firstInputQueuedLogged = false
    private var inputQueued = 0
    private var inputDropped = 0
    private var inputDeferred = 0
    private var outputBuffers = 0
    private var firstPcmLogged = false
    private val packetsReceived = AtomicInteger()
    private val packetsDropped = AtomicInteger()
    private val lastArrivalNs = AtomicLong()
    private val maxArrivalGapMs = AtomicLong()
    private val frameBytes = if (format.channels >= 2) 4 else 2
    private var totalWrittenFrames = 0L
    private var writtenFramesThisWindow = 0L
    private var writeErrorsThisWindow = 0
    private var lastWriteErrorCode: Int? = null
    private var zeroWritesThisWindow = 0
    private var partialWritesThisWindow = 0
    private var lastPlaybackHeadFrames: Long? = null
    private var maxWriteMs = 0L
    private var statsWindowStartNs = 0L
    private var statsLastUnderruns = 0
    private var bytesPerSecond = 0
    private val bufferProgress = AudioBufferProgress(if (format.channels >= 2) 4 else 2)
    private var underrunsAtPlaybackStart = 0
    private var lastPcmWriteNs = 0L
    private var rebufferCount = 0
    private val pressurePolicy = AudioPressurePolicy()
    private val latencyGuard = AudioLatencyGuard(
        isMedia = format.audioType == "media",
        mediaBufferMillis = mediaBufferMillis,
    )
    // Newest retained sample (written on submit, read by the worker): measures how far behind
    // live the queued content sits, not how far behind the phone is sending.
    @Volatile private var newestSample = 0
    private var lastPressureSampleNs = 0L
    private var lastPressureUnderruns = 0
    private var lastPressureRebuffers = 0
    private var latencyDrops = 0
    private var latencyDropsTotal = 0
    private var lastQueueLatencyMs = 0
    private val thread = Thread(::run, "carplay-audio").apply { isDaemon = true }

    fun start() {
        if (started) return
        started = true
        thread.start()
    }

    fun submit(payload: ByteArray, sample: Int) {
        if (released) {
            // Teardown already drained; returning it here is what keeps the pool's contract
            // ("leaks cost only reuse") from quietly becoming a per-stream allocation spike.
            AudioPayloadPool.release(payload)
            return
        }
        if (started) {
            packetsReceived.incrementAndGet()
            val now = System.nanoTime()
            val previous = lastArrivalNs.getAndSet(now)
            if (previous != 0L) {
                maxArrivalGapMs.accumulateAndGet((now - previous) / 1_000_000L, MAX_GAP)
            }
        }
        if (!started || !queue.offer(AudioPacket(payload, sample))) {
            // Never enqueued: hand the pooled buffer straight back so a full queue
            // still recycles instead of leaking the slot to the GC.
            AudioPayloadPool.release(payload)
            if (started) packetsDropped.incrementAndGet()
            if (started && !droppedPacketsLogged) {
                droppedPacketsLogged = true
                Log.w(TAG, "audio queue full; dropping newest packets to bound latency")
                report("Audio: queue full audioType=${format.audioType}")
            }
        } else if (started) {
            newestSample = sample
        }
    }

    override fun close() {
        running = false
        thread.interrupt()
    }

    private fun run() {
        // Audio must win the CPU against video decode on weak SoCs. At the default priority the
        // video decoder starves this worker, the AudioTrack underruns, and the stream sounds cut up.
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
        } catch (_: Exception) {
            // Keep the JVM priority if the platform refuses; playback still works.
        }
        try {
            when (format.codec) {
                AudioCodecKind.AAC_LC -> configureCodec(MediaFormat.MIMETYPE_AUDIO_AAC)
                AudioCodecKind.OPUS -> configureCodec(MediaFormat.MIMETYPE_AUDIO_OPUS)
                AudioCodecKind.LPCM -> Unit
            }
            createTrack()
            requestAudioFocus()
            while (running) {
                val packet = pending ?: queue.poll(AUDIO_POLL_MILLIS, TimeUnit.MILLISECONDS)
                if (packet != null) {
                    // A packet the decoder will not take yet stays pending instead of being lost.
                    if (pending == null) pendingSinceNs = System.nanoTime()
                    if (handle(packet)) {
                        AudioPayloadPool.release(packet.payload)
                        pending = null
                        pendingSinceNs = 0L
                    } else {
                        pending = packet
                    }
                }
                // Output becomes ready asynchronously, including after the last packet of a burst.
                // Waiting for the next UDP packet can strand decoded sound for hundreds of ms.
                val draining = codec
                if (draining != null) drainCodec(draining)
                maintainPlaybackBuffer()
                maybeTrimLatency()
                samplePressure()
                logStatsIfDue()
            }
        } catch (_: InterruptedException) {
            // Worker shut down.
        } catch (error: Throwable) {
            // Throwable, not Exception: a vendor framework missing a method throws
            // NoSuchMethodError, which is an Error and would otherwise kill the process.
            if (running) {
                Log.e(TAG, "audio renderer worker failed", error)
                report("Audio: renderer failed audioType=${format.audioType} error=${error.javaClass.simpleName}")
            }
        } finally {
            runCatching { logStatsIfDue(force = true) }
            // Clear before release(): a throw from teardown must not leave this source pinned
            // at CRITICAL and freeze video on the emergency budget until process restart.
            pressureSignal.clear(pressureSource)
            runCatching { release() }
        }
    }

    private fun queueLatencyMs(): Int {
        val oldest = pending?.sample ?: queue.peek()?.sample ?: return 0
        return AudioLatencyGuard.depthMs(oldest, newestSample, format.sampleRate)
    }

    /**
     * The FIFO holds a Wi-Fi gap burst without dropping sound, but a slow decoder can turn that
     * into seconds of A/V desync. The guard trims only a persistent, non-draining backlog.
     */
    private fun maybeTrimLatency() {
        val depthMs = queueLatencyMs().also { lastQueueLatencyMs = it }
        if (latencyGuard.observe(depthMs) != AudioLatencyGuard.Action.TRIM) return
        var dropped = 0
        // The held packet is the stalest thing we own: it is next up and already oldest.
        if (pending != null) {
            AudioPayloadPool.release(pending!!.payload)
            pending = null
            pendingSinceNs = 0L
            dropped++
        }
        while (true) {
            val oldest = queue.peek()?.sample ?: break
            if (AudioLatencyGuard.depthMs(oldest, newestSample, format.sampleRate) <= latencyGuard.capMs) break
            val victim = queue.poll() ?: break
            AudioPayloadPool.release(victim.payload)
            dropped++
        }
        if (dropped == 0) return
        latencyDrops += dropped
        latencyDropsTotal += dropped
        // One TRIM per episode (the guard re-arms only after the backlog drains), so this is
        // the episode log — not once per session and not once per dropped packet.
        Log.w(
            TAG,
            "audio latency trim audioType=${format.audioType} dropped=$dropped " +
                "capMs=${latencyGuard.capMs} depthMs=$depthMs",
        )
        report(
            "Audio: latency trim audioType=${format.audioType} dropped=$dropped " +
                "capMs=${latencyGuard.capMs}",
        )
    }

    private fun samplePressure() {
        val now = System.nanoTime()
        if (lastPressureSampleNs != 0L && now - lastPressureSampleNs < PRESSURE_SAMPLE_NS) return
        lastPressureSampleNs = now
        val underruns = safeUnderruns(track)
        val underrunDelta = (underruns - lastPressureUnderruns).coerceAtLeast(0)
        lastPressureUnderruns = underruns
        val rebufferDelta = rebufferCount - lastPressureRebuffers
        lastPressureRebuffers = rebufferCount
        val deferredMs = if (pending != null && pendingSinceNs != 0L) {
            ((now - pendingSinceNs) / 1_000_000L).toInt().coerceAtLeast(0)
        } else {
            0
        }
        val depthMs = queueLatencyMs()
        val level = pressurePolicy.evaluate(
            AudioPressurePolicy.Sample(
                deferredMs = deferredMs,
                underrunDelta = underrunDelta,
                rebufferDelta = rebufferDelta,
                queueOverCap = depthMs > latencyGuard.capMs + AudioLatencyGuard.SLOP_MS,
                queueOverHardCap = depthMs > latencyGuard.hardCapMs,
            ),
        )
        pressureSignal.publish(pressureSource, level)
    }

    private fun configureCodec(mime: String) {
        if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) {
            Log.i(
                TAG,
                "audio AAC config rate=${format.sampleRate} channels=${format.channels} " +
                    "csd0=${aacAudioSpecificConfig().toHexString()}",
            )
        }
        // Some vendor decoders reject the tuned parameters with BAD_VALUE. Fall back to a
        // minimal format rather than losing the stream.
        val attempts = listOf(true, false)
        var next: MediaCodec? = null
        for (tuned in attempts) {
            next = tryConfigureAudio(mime, tuned)
            if (next != null) break
        }
        codec = next
        if (next == null) Log.e(TAG, "audio decoder configuration failed mime=$mime")
    }

    private fun tryConfigureAudio(mime: String, tuned: Boolean): MediaCodec? {
        var candidate: MediaCodec? = null
        return try {
            val mediaFormat = buildAudioFormat(mime, tuned)
            val codec = MediaCodec.createDecoderByType(mime)
            candidate = codec
            codec.configure(mediaFormat, null, null, 0)
            codec.start()
            Log.i(TAG, "audio decoder configured mime=$mime name=${codec.name} tuned=$tuned")
            codec
        } catch (error: Exception) {
            runCatching { candidate?.release() }
            Log.w(TAG, "audio decoder configure failed mime=$mime tuned=$tuned", error)
            null
        }
    }

    private fun buildAudioFormat(mime: String, tuned: Boolean): MediaFormat = MediaFormat().apply {
        setString(MediaFormat.KEY_MIME, mime)
        setInteger(MediaFormat.KEY_SAMPLE_RATE, format.sampleRate)
        setInteger(MediaFormat.KEY_CHANNEL_COUNT, format.channels)
        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
        // KEY_PRIORITY / KEY_OPERATING_RATE are deliberately not set. Vendor audio decoders
        // on head units have been observed to native-crash on those keys within the first
        // seconds of a CarPlay session. The "tuned" attempt is now the same minimal format
        // that shipped before the optimization pass.
        if (tuned) {
            // Kept as a no-op so the tuned/untuned configure attempts stay distinguishable
            // in logs; the keys that made them different are gone on purpose.
        }
        if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) {
            setInteger(MediaFormat.KEY_IS_ADTS, 1)
            setByteBuffer("csd-0", ByteBuffer.wrap(aacAudioSpecificConfig()))
        } else {
            setByteBuffer("csd-0", ByteBuffer.wrap(opusHead()))
            setByteBuffer("csd-1", ByteBuffer.wrap(opusCodecDelay()))
            setByteBuffer("csd-2", ByteBuffer.wrap(opusSeekPreRoll()))
        }
    }

    private fun createTrack() {
        val encoding = AndroidAudioFormat.ENCODING_PCM_16BIT
        val channelMask = if (format.channels >= 2) AndroidAudioFormat.CHANNEL_OUT_STEREO
        else AndroidAudioFormat.CHANNEL_OUT_MONO
        val minBuffer = AudioTrack.getMinBufferSize(format.sampleRate, channelMask, encoding)
        if (minBuffer <= 0) {
            Log.e(TAG, "AudioTrack buffer size unavailable rate=${format.sampleRate} channels=${format.channels}")
            return
        }
        val selection = mappedSelection()
        mappedChannel = selection.channel
        val streamOverride = channelOverride(selection.channel)
        val attributes = audioAttributesFor(selection, streamOverride)
        trackAttributes = attributes
        val plan = MediaAudioBuffer.plan(selection.channel == AudioChannel.MEDIA,
            format.sampleRate, format.channels, minBuffer, mediaBufferMillis)
        bytesPerSecond = format.sampleRate * frameBytes
        val built: AudioTrack
        var routeLabel: String
        if (streamOverride == 0) {
            val attributes = audioAttributesFor(selection)
            routeLabel = "usage"
            built = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(pcmFormat(encoding, channelMask))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(plan.trackBufferBytes)
                .build()
        } else {
            val streamType = streamOverride
            routeLabel = "streamType=$streamType"
            built = LegacyAudioFallback.build(
                createLegacy = {
                    AudioTrack(streamType, format.sampleRate, channelMask, encoding,
                        plan.trackBufferBytes, AudioTrack.MODE_STREAM)
                },
                isInitialized = { it.state == AudioTrack.STATE_INITIALIZED },
                release = { it.release() },
                createFallback = {
                    routeLabel = "streamType=$streamType(fallback=usage)"
                    Log.w(TAG, "streamType=$streamType rejected by this ROM; falling back to usage-based track")
                    AudioTrack.Builder()
                        .setAudioAttributes(audioAttributesFor(selection))
                        .setAudioFormat(pcmFormat(encoding, channelMask))
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .setBufferSizeInBytes(plan.trackBufferBytes)
                        .build()
                },
            )
        }
        track = built
        // Not read back from the track. AudioTrack.getAudioAttributes() is missing from the
        // framework.jar on some head units (8227L), where it throws NoSuchMethodError the
        // moment audio starts and takes the whole process down. We already hold the exact
        // attributes we built the track with — see trackAttributes above.
        val bufferFrames = safeBufferFrames(built)
        val capacityBytes = if (bufferFrames > 0) bufferFrames * frameBytes else plan.trackBufferBytes
        startThresholdBytes = MediaAudioBuffer.startBytesFor(plan.startBytes, capacityBytes, PREBUFFER_WRITE_CHUNK_BYTES)
        // A mid-stream underrun re-arms at a fraction of the start level: see REBUFFER_START_MILLIS.
        rebufferStartThresholdBytes = MediaAudioBuffer.startBytesFor(
            (bytesPerSecond.toLong() * REBUFFER_START_MILLIS / 1000L).toInt(),
            capacityBytes,
            PREBUFFER_WRITE_CHUNK_BYTES,
        )
        timeline.reset()
        report("Audio: ready audioType=${format.audioType} codec=${format.codec} " +
            "rate=${format.sampleRate} channels=${format.channels} " +
            "route=$routeLabel " +
            "bufferMs=${capacityBytes * 1000L / bytesPerSecond} startMs=${startThresholdBytes * 1000L / bytesPerSecond}")
        Log.i(
            TAG,
            "audio track prepared type=${format.payloadType} audioType=${format.audioType} " +
                "codec=${format.codec} " +
                "rate=${format.sampleRate} channels=${format.channels} " +
                "route=$routeLabel " +
                "buffer=${capacityBytes * 1000L / bytesPerSecond}ms start=${startThresholdBytes * 1000L / bytesPerSecond}",
        )
        Log.i(
            TAG,
            "audio route type=${format.payloadType} audioType=${format.audioType} " +
                "mode=${if (advancedAudioChannelMapping) AudioChannelMappingMode.AUTOMOTIVE_BUS else AudioChannelMappingMode.MOBILE_COMPATIBLE} " +
                "channel=${selection.channel} usage=${usageFor(selection.channel)} " +
                "contentType=${contentTypeFor(selection.contentType)} " +
                "streamOverride=$streamOverride " +
                "focus=${if (audioFocusEnabled) "on" else "off"}",
        )
    }

    /** 0 keeps usage routing; 1-10 selects an Android legacy stream ID. */
    private fun channelOverride(channel: AudioChannel): Int = when (channel) {
        AudioChannel.MEDIA -> mediaChannel
        AudioChannel.NAVIGATION -> navigationChannel
        else -> 0
    }

    private fun audioAttributesFor(
        selection: AudioChannelSelection,
        streamOverride: Int,
    ): AudioAttributes {
        if (streamOverride in AudioManager.STREAM_SYSTEM..AudioManager.STREAM_ACCESSIBILITY) {
            // Android accepts only its defined legacy stream IDs here. BYD audio policy can
            // map these standard streams to vehicle outputs; arbitrary channel numbers are
            // not valid AudioAttributes legacy stream types.
            try {
                return AudioAttributes.Builder().setLegacyStreamType(streamOverride).build()
            } catch (error: Exception) {
                Log.w(TAG, "legacy audio stream $streamOverride rejected; keeping usage routing", error)
            }
        }
        return AudioAttributes.Builder()
            .setUsage(usageFor(selection.channel))
            .setContentType(contentTypeFor(selection.contentType))
            .build()
    }

    private fun mappedSelection(): AudioChannelSelection {
        val mode = if (advancedAudioChannelMapping) {
            AudioChannelMappingMode.AUTOMOTIVE_BUS
        } else {
            AudioChannelMappingMode.MOBILE_COMPATIBLE
        }
        return AudioChannelMapper.map(
            audioType = format.audioType,
            payloadType = format.payloadType,
            mode = mode,
        )
    }

    private fun audioAttributesFor(selection: AudioChannelSelection): AudioAttributes =
        AudioAttributes.Builder()
            .setUsage(usageFor(selection.channel))
            .setContentType(contentTypeFor(selection.contentType))
            .build()

    /**
     * Shares a sink-level focus request across all active non-navigation renderers.
     * Navigation guidance intentionally takes no focus: it overlays media without ducking it.
     */
    private fun requestAudioFocus() {
        val channel = mappedChannel ?: return
        val attributes = trackAttributes ?: return
        if (channel == AudioChannel.NAVIGATION) {
            Log.i(TAG, "audio focus skipped channel=NAVIGATION; overlays without ducking")
            return
        }
        track?.let { audioFocusCoordinator.acquire(it, channel, attributes) }
    }

    private fun abandonAudioFocus() {
        track?.let(audioFocusCoordinator::release)
    }

    private fun pcmFormat(encoding: Int, channelMask: Int) = AndroidAudioFormat.Builder()
        .setSampleRate(format.sampleRate)
        .setChannelMask(channelMask)
        .setEncoding(encoding)
        .build()

    private fun streamType(): Int {
        val mode = if (advancedAudioChannelMapping) {
            AudioChannelMappingMode.AUTOMOTIVE_BUS
        } else {
            AudioChannelMappingMode.MOBILE_COMPATIBLE
        }
        return AudioChannelMapper.map(
            audioType = format.audioType,
            payloadType = format.payloadType,
            mode = mode,
            navigationStreamType = navigationStreamType,
        ).streamType
    }

    private fun aacAudioSpecificConfig(): ByteArray {
        val frequencyIndex = MediaCodecSupport.aacFrequencyIndex(format.sampleRate)
        val value = (AAC_OBJECT_TYPE_LC shl 11) or
            (frequencyIndex shl 7) or
            (format.channels.coerceIn(1, 7) shl 3)
        return byteArrayOf((value ushr 8).toByte(), value.toByte())
    }

    private fun usageFor(channel: AudioChannel): Int = when (channel) {
        AudioChannel.MEDIA -> AudioAttributes.USAGE_MEDIA
        AudioChannel.PHONE -> AudioAttributes.USAGE_VOICE_COMMUNICATION
        AudioChannel.ASSISTANT -> AudioAttributes.USAGE_ASSISTANT
        AudioChannel.NAVIGATION -> AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
    }

    private fun contentTypeFor(contentType: AudioContentType): Int = when (contentType) {
        AudioContentType.MUSIC -> AudioAttributes.CONTENT_TYPE_MUSIC
        AudioContentType.SPEECH -> AudioAttributes.CONTENT_TYPE_SPEECH
    }

    /** Minimal OpusHead CSD for the mono 48 kHz stream CarPlay negotiates. */
    private fun opusHead(): ByteArray {
        val head = ByteArray(19)
        "OpusHead".toByteArray(Charsets.US_ASCII).copyInto(head, 0)
        head[8] = 1
        head[9] = format.channels.toByte()
        head[10] = 0x38
        head[11] = 0x01
        head[12] = format.sampleRate.toByte()
        head[13] = (format.sampleRate ushr 8).toByte()
        head[14] = (format.sampleRate ushr 16).toByte()
        head[15] = (format.sampleRate ushr 24).toByte()
        return head
    }

    private fun opusCodecDelay(): ByteArray =
        java.nio.ByteBuffer.allocate(8)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putLong(OPUS_CODEC_DELAY_NANOS)
            .array()

    private fun opusSeekPreRoll(): ByteArray =
        java.nio.ByteBuffer.allocate(8)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putLong(OPUS_SEEK_PRE_ROLL_NANOS)
            .array()

    /** True when the packet has been consumed; false when the decoder wants it retried later. */
    private fun handle(packet: AudioPacket): Boolean {
        val payload = packet.payload
        if (payload.isEmpty()) return true
        val timestampUs = sampleTimestampUs(packet.sample)
        return when (format.codec) {
            AudioCodecKind.LPCM -> {
                byteSwapS16(payload, 0)
                // No decode stage to pair against, so the packet names its own sample.
                concealGap(timeline.concealBeforeSample(packet.sample, payload.size / frameBytes, format.sampleRate))
                writePcm(payload, 0, payload.size)
                true
            }
            AudioCodecKind.AAC_LC -> {
                if (!firstAacPayloadLogged) {
                    firstAacPayloadLogged = true
                    Log.i(
                        TAG,
                        "audio AAC access unit bytes=${payload.size} " +
                            "head=${payload.copyOfRange(0, minOf(payload.size, 16)).toHexString()}",
                    )
                }
                val accepted = feedCodec(adtsFrame(payload), 0, ADTS_HEADER_BYTES + payload.size, timestampUs)
                if (accepted) timeline.submitted(packet.sample)
                accepted
            }
            AudioCodecKind.OPUS -> {
                if (payload.size < MIN_OPUS_PACKET_BYTES) {
                    if (!firstOpusShortPacketLogged) {
                        firstOpusShortPacketLogged = true
                        Log.i(
                            TAG,
                            "audio Opus skipping short packet bytes=${payload.size} " +
                                "head=${payload.toHexString()}",
                        )
                    }
                    return true
                }
                val accepted = feedCodec(payload, 0, payload.size, timestampUs)
                if (accepted) timeline.submitted(packet.sample)
                accepted
            }
        }
    }

    private fun sampleTimestampUs(sample: Int): Long =
        (sample.toLong() and 0xffff_ffffL) * 1_000_000L / format.sampleRate

    /** Frames the AAC access unit with an ADTS header in a reused buffer — one allocation-free path. */
    private fun adtsFrame(accessUnit: ByteArray): ByteArray {
        val needed = ADTS_HEADER_BYTES + accessUnit.size
        if (adtsScratch.size < needed) adtsScratch = ByteArray(needed)
        MediaCodecSupport.writeAdtsHeader(adtsScratch, accessUnit.size, format.sampleRate, format.channels)
        System.arraycopy(accessUnit, 0, adtsScratch, ADTS_HEADER_BYTES, accessUnit.size)
        return adtsScratch
    }

    /** False when the decoder has no input buffer yet: the caller keeps the packet and retries. */
    private fun feedCodec(payload: ByteArray, offset: Int, size: Int, presentationTimeUs: Long): Boolean {
        val codec = codec ?: return true
        val index = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
        if (index < 0) {
            // Not dropped: a slow decoder on a weak SoC must cost latency, never sound.
            inputDeferred++
            if (inputDeferred == 1) {
                Log.w(
                    TAG,
                    "audio decoder input unavailable codec=${format.codec} " +
                        "queued=$inputQueued deferrals=$inputDeferred",
                )
            }
            return false
        }
        val input = codec.getInputBuffer(index) ?: run {
            // Never leak a dequeued slot: release it empty and drop this packet rather than
            // retry forever with the buffer still held.
            codec.queueInputBuffer(index, 0, 0, 0, 0)
            inputDropped++
            return true
        }
        input.clear()
        return if (size <= input.remaining()) {
            input.put(payload, offset, size)
            codec.queueInputBuffer(index, 0, size, presentationTimeUs, 0)
            inputQueued++
            if (!firstInputQueuedLogged) {
                firstInputQueuedLogged = true
                Log.i(
                    TAG,
                    "audio decoder first input codec=${format.codec} bytes=$size " +
                        "head=${payload.copyOfRange(offset, minOf(payload.size, offset + 16)).toHexString()}",
                )
            }
            true
        } else {
            // Cannot ever fit; queue an empty buffer to keep the decoder fed and count the loss.
            codec.queueInputBuffer(index, 0, 0, 0, 0)
            inputDropped++
            true
        }
    }

    private fun drainCodec(codec: MediaCodec) {
        val info = codecOutputInfo
        while (running) {
            val index = codec.dequeueOutputBuffer(info, 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                index >= 0 -> {
                    val size = info.size
                    if (size > 0) {
                        outputBuffers++
                        if (outputBuffers == 1 || outputBuffers % DECODED_BUFFER_LOG_INTERVAL == 0) {
                            Log.i(
                                TAG,
                                "audio decoder output codec=${format.codec} " +
                                    "buffers=$outputBuffers bytes=$size " +
                                    "queued=$inputQueued dropped=$inputDropped",
                            )
                        }
                    }
                    if (size > 0) {
                        val output = codec.getOutputBuffer(index)
                        if (output != null) {
                            // Copy out of the codec buffer first. Writing a MediaCodec ByteBuffer
                            // straight into AudioTrack has been seen to native-crash vendor HALs
                            // (foreign position/limit, direct buffers the HAL rejects). The
                            // intermediate `pcm` scratch is the path that shipped before.
                            if (pcm.size < size) pcm = ByteArray(size)
                            output.position(info.offset)
                            output.get(pcm, 0, size)
                            concealGap(timeline.concealBefore(size / frameBytes, format.sampleRate))
                            writePcm(pcm, 0, size)
                        }
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
                else -> return
            }
        }
    }

    /**
     * Plays the silence that stands in for RTP packets the wire lost, before the PCM that
     * follows the hole. The music then continues at the right place in the song instead of
     * jumping the gap, which is the "it speeds up after the cutout" glitch.
     */
    private fun concealGap(silenceSamples: Int) {
        if (silenceSamples <= 0) return
        concealEvents++
        concealEventsTotal++
        concealedSamplesThisWindow += silenceSamples
        if (concealEventsTotal == 1 || concealEventsTotal % CONCEAL_LOG_INTERVAL == 0) {
            Log.w(
                TAG,
                "audio conceal gap audioType=${format.audioType} events=$concealEventsTotal " +
                    "thisMs=${silenceSamples * 1000L / format.sampleRate} " +
                    "windowMs=${concealedSamplesThisWindow * 1000L / format.sampleRate}",
            )
        }
        writeSilence(silenceSamples * frameBytes)
    }

    private fun writeSilence(bytes: Int) {
        if (bytes <= 0) return
        if (silence.size < bytes) silence = ByteArray(bytes)
        writePcm(silence, 0, bytes, isSilence = true)
    }

    private fun writePcm(data: ByteArray, offset: Int = 0, length: Int = data.size, isSilence: Boolean = false) {
        val track = track ?: return
        if (!firstPcmLogged && length > 0 && !isSilence) {
            firstPcmLogged = true
            val end = minOf(data.size, offset + minOf(length, 16))
            Log.i(
                TAG,
                "audio first PCM type=${format.payloadType} bytes=$length " +
                    "head=${data.copyOfRange(offset, end).toHexString()}",
            )
        }
        if (!fadeApplied && !isSilence) {
            applyFadeIn(data, offset, length)
            fadeApplied = true
        }
        var written = 0
        while (written < length && running) {
            val writeLength = if (playbackStarted) {
                length - written
            } else {
                minOf(length - written, PREBUFFER_WRITE_CHUNK_BYTES)
            }
            val writeStarted = System.nanoTime()
            val count = track.write(data, offset + written, writeLength, AudioTrack.WRITE_BLOCKING)
            maxWriteMs = maxOf(maxWriteMs, (System.nanoTime() - writeStarted) / 1_000_000L)
            if (count < 0) {
                writeErrorsThisWindow++
                lastWriteErrorCode = count
                break
            }
            if (count == 0) {
                zeroWritesThisWindow++
                break
            }
            if (count < writeLength) partialWritesThisWindow++
            written += count
            val framesWritten = count / frameBytes
            totalWrittenFrames += framesWritten
            writtenFramesThisWindow += framesWritten
            bufferProgress.written(count)
            lastPcmWriteNs = System.nanoTime()
            if (!playbackStarted) {
                prebufferBytes += count
                if (prebufferBytes >= startThresholdBytes) {
                    startPlayback(track)
                    Log.i(TAG, "audio playback started type=${format.payloadType}")
                }
            }
        }
    }

    private fun startPlayback(track: AudioTrack) {
        underrunsAtPlaybackStart = safeUnderruns(track)
        track.play()
        playbackStarted = true
    }

    /**
     * Reads back from [AudioTrack] go through these, never straight off the object.
     *
     * Head-unit frameworks are not the AOSP the SDK compiles against: the 8227L throws
     * NoSuchMethodError from `getAudioAttributes`, and its siblings (`getUnderrunCount`,
     * `getRoutedDevice`, `getBufferSizeInFrames`) are equally suspect. A missing accessor
     * must cost a default, not the process.
     */
    private fun safeUnderruns(track: AudioTrack?): Int =
        try {
            track?.underrunCount ?: 0
        } catch (_: Throwable) {
            0
        }

    private fun safePlaybackHead(track: AudioTrack): Int =
        try {
            track.playbackHeadPosition
        } catch (_: Throwable) {
            -1
        }

    private fun safeBufferFrames(track: AudioTrack): Int =
        try {
            track.bufferSizeInFrames
        } catch (_: Throwable) {
            -1
        }

    private fun safeRoutedType(track: AudioTrack?): Int =
        try {
            track?.routedDevice?.type ?: -1
        } catch (_: Throwable) {
            -1
        }

    private fun safeTrackSampleRate(track: AudioTrack?): Int =
        try {
            track?.sampleRate ?: format.sampleRate
        } catch (_: Throwable) {
            format.sampleRate
        }

    private fun maintainPlaybackBuffer() {
        val track = track ?: return
        if (bufferProgress.shouldRebuffer(mappedChannel == AudioChannel.MEDIA, playbackStarted,
                safeUnderruns(track) > underrunsAtPlaybackStart, queue.isEmpty(), safePlaybackHead(track))) {
            // The hardware buffer has actually drained. Pause without flushing or discarding
            // PCM, then resume on a small cushion — not the full start threshold, which made
            // every underrun cost a second of silence and left us a second behind live.
            track.pause()
            playbackStarted = false
            prebufferBytes = 0
            startThresholdBytes = rebufferStartThresholdBytes
            rebufferCount++
        }
        // A short final burst may never reach the start threshold. Play it after a bounded wait.
        if (!playbackStarted && prebufferBytes > 0 && queue.isEmpty() &&
            System.nanoTime() - lastPcmWriteNs >= BUFFER_TAIL_WAIT_NS) {
            startPlayback(track)
        }
    }

    // Persist counters even during packet starvation, and flush before disconnect releases the track.
    private fun logStatsIfDue(force: Boolean = false) {
        val now = System.nanoTime()
        if (statsWindowStartNs == 0L) statsWindowStartNs = now
        if (!force && now - statsWindowStartNs < STATS_WINDOW_NS) return
        val underruns = safeUnderruns(track)
        val lastRx = lastArrivalNs.get()
        val currentTrack = track
        val headFramesRaw = currentTrack?.let { safePlaybackHead(it) }?.takeIf { it >= 0 }
        val playbackHeadFrames = headFramesRaw?.toLong()?.and(0xffff_ffffL)
        val playbackAdvanceFrames = playbackHeadFrames?.let { current ->
            val previous = lastPlaybackHeadFrames
            lastPlaybackHeadFrames = current
            previous?.let { (current - it) and 0xffff_ffffL }
        }
        val queuedFrames = playbackHeadFrames?.let { (totalWrittenFrames - it).coerceAtLeast(0L) }
        val line = "audio stats audioType=${format.audioType} channel=$mappedChannel " +
            "routeType=${safeRoutedType(currentTrack)} codec=${format.codec} " +
            "trackState=${currentTrack?.state ?: -1} playState=${currentTrack?.playState ?: -1} " +
            "sampleRate=${safeTrackSampleRate(currentTrack)} " +
            "trackBufferFrames=${currentTrack?.let { safeBufferFrames(it) } ?: -1} " +
            "rx=${packetsReceived.getAndSet(0)} " +
            "dropped=${packetsDropped.getAndSet(0)} underruns=+${underruns - statsLastUnderruns} queue=${queue.size} " +
            "playing=$playbackStarted maxGapMs=${maxArrivalGapMs.getAndSet(0)} " +
            "sinceRxMs=${if (lastRx == 0L) -1 else (now - lastRx) / 1_000_000L} maxWriteMs=$maxWriteMs " +
            "writtenFrames=$writtenFramesThisWindow totalWrittenFrames=$totalWrittenFrames " +
            "playbackHeadFrames=${playbackHeadFrames ?: -1} playbackAdvanceFrames=${playbackAdvanceFrames ?: -1} " +
            "estimatedQueuedFrames=${queuedFrames ?: -1} writeErrors=$writeErrorsThisWindow " +
            "lastWriteError=${lastWriteErrorCode ?: "none"} zeroWrites=$zeroWritesThisWindow " +
            "partialWrites=$partialWritesThisWindow " +
            "decoderDroppedTotal=$inputDropped decoderDeferralsTotal=$inputDeferred " +
            "outputBuffersTotal=$outputBuffers rebuffers=$rebufferCount " +
            "queueLatencyMs=$lastQueueLatencyMs latencyDrops=$latencyDrops " +
            "latencyDropsTotal=$latencyDropsTotal " +
            "concealEvents=$concealEvents concealEventsTotal=$concealEventsTotal " +
            "concealMs=${concealedSamplesThisWindow * 1000L / format.sampleRate} " +
            "pressure=${AudioPressure.label(pressurePolicy.current())} ended=$force"
        Log.i(STATS_TAG, line)
        report(line)
        statsLastUnderruns = underruns
        maxWriteMs = 0L
        latencyDrops = 0
        concealEvents = 0
        concealedSamplesThisWindow = 0L
        writtenFramesThisWindow = 0L
        writeErrorsThisWindow = 0
        lastWriteErrorCode = null
        zeroWritesThisWindow = 0
        partialWritesThisWindow = 0
        statsWindowStartNs = now
    }

    private fun applyFadeIn(data: ByteArray, offset: Int, length: Int) {
        val samples = (length - length % 2) / 2
        val fadeSamples = minOf(samples, maxOf(1, format.sampleRate / 100))
        for (index in 0 until fadeSamples) {
            val position = offset + index * 2
            val sample = (data[position].toInt() and 0xff) or (data[position + 1].toInt() shl 8)
            val scaled = (sample.toLong() * (index + 1) / fadeSamples).toInt()
            data[position] = scaled.toByte()
            data[position + 1] = (scaled shr 8).toByte()
        }
    }

    private fun byteSwapS16(source: ByteArray, offset: Int = 0) {
        // In place on the RTP buffer the renderer already owns: LPCM arrives big-endian and a
        // per-packet copy just to swap bytes is pure GC pressure on a weak SoC.
        for (index in offset until source.size - 1 step 2) {
            val tmp = source[index]
            source[index] = source[index + 1]
            source[index + 1] = tmp
        }
    }

    @Synchronized
    private fun release() {
        // Before anything else: from here on submit() returns its own buffers instead of
        // enqueuing into a queue that is about to be drained and never read again.
        released = true
        abandonAudioFocus()
        // Return every held payload to the pool before teardown. This can still race a submit()
        // that passed its released check a moment ago; that costs at most one buffer's reuse
        // (the renderer is unreachable after onAudioStopped), never a double-release.
        pending?.let { AudioPayloadPool.release(it.payload) }
        pending = null
        while (true) {
            val packet = queue.poll() ?: break
            AudioPayloadPool.release(packet.payload)
        }
        val codec = codec
        this.codec = null
        if (codec != null) {
            try {
                codec.stop()
            } catch (_: Exception) {
                // Best effort.
            }
            try {
                codec.release()
            } catch (_: Exception) {
                // Best effort.
            }
        }
        val track = track
        this.track = null
        if (track != null) {
            try {
                track.pause()
            } catch (_: Exception) {
                // Best effort.
            }
            try {
                track.flush()
            } catch (_: Exception) {
                // Best effort.
            }
            try {
                track.release()
            } catch (_: Exception) {
                // Best effort.
            }
        }
    }

    private companion object {
        const val TAG = "xcertplay-usb"
        const val AAC_OBJECT_TYPE_LC = 2
        const val MIN_OPUS_PACKET_BYTES = 4
        const val ADTS_HEADER_BYTES = MediaCodecSupport.ADTS_HEADER_BYTES
        const val OPUS_CODEC_DELAY_NANOS = 6_500_000L
        const val OPUS_SEEK_PRE_ROLL_NANOS = 80_000_000L
        const val INPUT_TIMEOUT_US = 10_000L
        const val AUDIO_POLL_MILLIS = 10L
        const val BUFFER_TAIL_WAIT_NS = 500_000_000L
        /**
         * PCM to queue again before play() after a mid-stream underrun. The full start
         * threshold is right for the first note of a session — it is what outlasts the first
         * radio hole — but re-paying it after every underrun turned a hundred-millisecond
         * hole into a second of silence and left us playing a second behind live.
         */
        const val REBUFFER_START_MILLIS = 200
        // Holds a burst after a Wi-Fi gap (~4 s of AAC) instead of dropping it.
        const val MAX_QUEUED_PACKETS = 192
        const val PREBUFFER_WRITE_CHUNK_BYTES = 2 * 1024
        const val PRESSURE_SAMPLE_NS = 250_000_000L
        const val STATS_TAG = "DiPlay-AudioStats"
        const val STATS_WINDOW_NS = 5_000_000_000L
        const val DECODED_BUFFER_LOG_INTERVAL = 50
        const val CONCEAL_LOG_INTERVAL = 25
        // One instance: `::maxOf` per packet boxed two longs and a bound reference.
        val MAX_GAP = LongBinaryOperator { a, b -> if (a > b) a else b }
    }
}

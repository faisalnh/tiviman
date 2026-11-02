package faisalnh.tiviman

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import java.nio.ByteBuffer

/**
 * Session-wide positive audio offset (audio plays later than video). Negative offsets are not supported.
 *
 * The value is read when the audio sink is configured (to decide whether the processor is active) and again on
 * every sink flush (to size the silence). Changing it only affects playback after the sink is reconfigured:
 * use [setDelayAndReprepare].
 */
@androidx.annotation.OptIn(UnstableApi::class)
object AudioDelay {
    const val MIN_MS = 0
    const val MAX_MS = 2000

    @Volatile
    var delayMs: Int = 0
        private set

    /**
     * Bounded, immutable runtime counters aggregated across every [AudioDelayProcessor] instance (the sink may
     * create/reconfigure several). They prove only that the processor ran and how much silence it armed/emitted;
     * they say nothing about audible lip-sync. Frames are PCM frames at the last configured format.
     */
    data class Stats(
        val configureCount: Int = 0,
        val activeConfigureCount: Int = 0,
        val flushCount: Int = 0,
        val armedFlushCount: Int = 0,
        val lastDelayMs: Int = -1,
        val lastSampleRate: Int = 0,
        val lastChannelCount: Int = 0,
        val lastEncoding: Int = 0,
        val lastFrameBytes: Int = 0,
        val lastArmedFrames: Long = 0,
        val totalArmedFrames: Long = 0,
        val insertedFrames: Long = 0,
        val insertedFlushCount: Int = 0,
    )

    private val statsLock = Any()
    private var stats = Stats()

    fun statsSnapshot(): Stats = synchronized(statsLock) { stats }

    fun resetStats() = synchronized(statsLock) { stats = Stats() }

    internal fun recordConfigure(format: AudioFormat, delayMs: Int, active: Boolean) = synchronized(statsLock) {
        stats = stats.copy(
            configureCount = stats.configureCount + 1,
            activeConfigureCount = stats.activeConfigureCount + if (active) 1 else 0,
            lastDelayMs = delayMs,
            lastSampleRate = format.sampleRate,
            lastChannelCount = format.channelCount,
            lastEncoding = format.encoding,
            lastFrameBytes = format.bytesPerFrame,
        )
    }

    internal fun recordFlush(armedFrames: Long) = synchronized(statsLock) {
        stats = stats.copy(
            flushCount = stats.flushCount + 1,
            armedFlushCount = stats.armedFlushCount + if (armedFrames > 0) 1 else 0,
            lastArmedFrames = armedFrames,
            totalArmedFrames = stats.totalArmedFrames + armedFrames,
        )
    }

    internal fun recordInserted(frames: Long) = synchronized(statsLock) {
        stats = stats.copy(insertedFrames = stats.insertedFrames + frames, insertedFlushCount = stats.insertedFlushCount + 1)
    }

    /** Clamps to [MIN_MS]..[MAX_MS], stores it and returns the stored value. Does not touch any player. */
    fun setDelayMs(ms: Int): Int {
        val clamped = ms.coerceIn(MIN_MS, MAX_MS)
        delayMs = clamped
        return clamped
    }

    /**
     * Stores [ms] and re-prepares [player] so the audio sink is disabled, reset and reconfigured with the new
     * delay. stop() keeps the playlist and position; prepare() restarts loading (live streams rejoin the edge).
     * Must be called on the player's application thread.
     */
    fun setDelayAndReprepare(player: Player, ms: Int): Int {
        val previous = delayMs
        val applied = setDelayMs(ms)
        if (applied != previous && player.mediaItemCount > 0) {
            player.stop()
            player.prepare()
        }
        return applied
    }
}

/**
 * Inserts [AudioDelay.delayMs] of PCM silence at the start of the stream after every flush, before source audio.
 *
 * Why this shifts audio relative to video (Media3 1.5.1 DefaultAudioSink, from bytecode): the reported position is
 * checkpoint.mediaTimeUs + (audioTrackPositionUs - checkpoint.audioTrackPositionUs), where the track position counts
 * every frame written to the AudioTrack, silence included. `getDurationAfterProcessorApplied` is never called by the
 * sink (only Sonic/Trimming/SpeedChanging implement it), and the expected-position check uses submitted *input*
 * frames, so the silence is neither subtracted nor flagged as a discontinuity. The clock therefore advances through
 * the silence while video renders, and the first source sample plays [AudioDelay.delayMs] after its timestamp.
 * Do not add a position-compensating sink on top of this: it would cancel or double the shift.
 *
 * Caveat: the sink re-flushes its processors on a playback-speed change and on a timestamp resync, which
 * inserts the silence again at that point (a gap of the same length, not a drifting offset).
 * Active only when the delay is greater than zero at configure time. Supports every linear PCM encoding
 * (8/16/24/32 bit, float, big-endian variants); compressed/passthrough formats are rejected.
 */
@UnstableApi
class AudioDelayProcessor(
    private val delayMsProvider: () -> Int = { AudioDelay.delayMs },
) : BaseAudioProcessor() {

    private var pendingSilenceBytes = 0
    private var silenceChunk = ByteArray(SILENCE_CHUNK_BYTES)

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (!Util.isEncodingLinearPcm(inputAudioFormat.encoding) ||
            inputAudioFormat.sampleRate <= 0 || inputAudioFormat.channelCount <= 0
        ) {
            throw UnhandledAudioFormatException(inputAudioFormat)
        }
        val delay = clampedDelay()
        if (delay == 0) {
            AudioDelay.recordConfigure(inputAudioFormat, 0, false)
            return AudioFormat.NOT_SET
        }
        AudioDelay.recordConfigure(inputAudioFormat, delay, true)
        // Unsigned 8-bit PCM is centred on 0x80; every other linear PCM encoding is silent at all-zero bytes.
        val fill = if (inputAudioFormat.encoding == C.ENCODING_PCM_8BIT) 0x80.toByte() else 0
        silenceChunk.fill(fill)
        return inputAudioFormat
    }

    override fun onFlush() {
        pendingSilenceBytes = if (isActive) silenceBytes(inputAudioFormat) else 0
        AudioDelay.recordFlush(if (pendingSilenceBytes > 0) pendingSilenceBytes.toLong() / inputAudioFormat.bytesPerFrame else 0L)
    }

    override fun onReset() {
        pendingSilenceBytes = 0
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val inputBytes = inputBuffer.remaining()
        if (inputBytes == 0) return
        val out = replaceOutputBuffer(pendingSilenceBytes + inputBytes)
        putSilence(out, pendingSilenceBytes)
        recordEmitted()
        out.put(inputBuffer)
        out.flip()
    }

    override fun onQueueEndOfStream() {
        // No source audio arrived after the flush: still emit the delay so output length is delay + source.
        if (pendingSilenceBytes == 0) return
        val out = replaceOutputBuffer(pendingSilenceBytes)
        putSilence(out, pendingSilenceBytes)
        recordEmitted()
        out.flip()
    }

    private fun recordEmitted() {
        if (pendingSilenceBytes > 0) AudioDelay.recordInserted(pendingSilenceBytes.toLong() / inputAudioFormat.bytesPerFrame)
        pendingSilenceBytes = 0
    }

    private fun clampedDelay(): Int = delayMsProvider().coerceIn(AudioDelay.MIN_MS, AudioDelay.MAX_MS)

    private fun silenceBytes(format: AudioFormat): Int {
        val frames = format.sampleRate.toLong() * clampedDelay() / 1000
        return (frames * Util.getPcmFrameSize(format.encoding, format.channelCount)).toInt()
    }

    private fun putSilence(out: ByteBuffer, bytes: Int) {
        var left = bytes
        while (left > 0) {
            val n = minOf(left, silenceChunk.size)
            out.put(silenceChunk, 0, n)
            left -= n
        }
    }

    private companion object {
        const val SILENCE_CHUNK_BYTES = 4096
    }
}

/**
 * Renderers factory whose audio sink runs a fresh [AudioDelayProcessor]. Pass to
 * `ExoPlayer.Builder(context, AudioDelayRenderersFactory(context))`.
 */
@UnstableApi
class AudioDelayRenderersFactory(context: Context) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink =
        DefaultAudioSink.Builder(context)
            .setAudioProcessors(arrayOf(AudioDelayProcessor()))
            .setEnableFloatOutput(false)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .build()
}

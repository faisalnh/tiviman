package faisalnh.tiviman

import android.app.Instrumentation
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * Isolated runtime checks for [AudioDelayRenderersFactory]/[AudioDelayProcessor]. Standalone ExoPlayer on a generated
 * local PCM WAV: no Activity, no providers/URLs, no private data. Wire from the runner with the `audioDelayTools` flag:
 * `if (audioDelayTools) { AudioDelayChecks(this).run(); return }`.
 *
 * These are runtime assertions about the processor (configured, armed, inserted frames, playhead advancing, pause
 * intent kept across a re-prepare). They are NOT audible or lip-sync proof.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class AudioDelayChecks(private val runner: Instrumentation) {
    private var player: ExoPlayer? = null
    private val passed = mutableListOf<String>()
    private val digestLines = mutableListOf<String>()

    private fun <T> main(block: () -> T): T {
        var value: T? = null
        runner.runOnMainSync { value = block() }
        @Suppress("UNCHECKED_CAST") return value as T
    }

    private fun await(what: String, timeoutMs: Long = 15000, test: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (!main(test) && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
        check(main(test)) { "Timed out waiting for $what" }
    }

    private fun check(name: String, ok: Boolean, detail: () -> String = { "" }) {
        if (!ok) throw AssertionError("$name ${detail()}".trim())
        passed.add(name)
    }

    private fun p() = player!!

    private fun writeWav(file: File) {
        val seconds = 6
        val frames = SAMPLE_RATE * seconds
        val data = frames * CHANNELS * 2
        val buffer = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(36 + data).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(CHANNELS.toShort()).putInt(SAMPLE_RATE)
            .putInt(SAMPLE_RATE * CHANNELS * 2).putShort((CHANNELS * 2).toShort()).putShort(16)
            .put("data".toByteArray()).putInt(data)
        repeat(frames) { i ->
            val s = (kotlin.math.sin(i * 2 * Math.PI * 440 / SAMPLE_RATE) * 3000).toInt().toShort()
            repeat(CHANNELS) { buffer.putShort(s) }
        }
        file.writeBytes(buffer.array())
    }

    private fun startPlayer(item: MediaItem, playWhenReady: Boolean) {
        main {
            val context = runner.targetContext
            player = ExoPlayer.Builder(context, AudioDelayRenderersFactory(context)).build().apply {
                volume = 0f
                setMediaItem(item)
                this.playWhenReady = playWhenReady
                prepare()
            }
        }
    }

    private fun describe(s: AudioDelay.Stats) =
        "cfg=${s.configureCount}/${s.activeConfigureCount} flush=${s.flushCount}/${s.armedFlushCount} " +
            "delay=${s.lastDelayMs} fmt=${s.lastSampleRate}x${s.lastChannelCount}e${s.lastEncoding}b${s.lastFrameBytes} " +
            "armed=${s.totalArmedFrames} last=${s.lastArmedFrames} inserted=${s.insertedFrames}/${s.insertedFlushCount}"

    /** Position must advance by at least [minMs] while playing. */
    private fun assertAdvances(name: String, minMs: Long) {
        val start = main { p().currentPosition }
        await("$name playhead +${minMs}ms") { p().currentPosition - start >= minMs }
        check(name, main { p().isPlaying })
    }

    /** Every armed flush must have armed exactly rate*delay/1000 frames == rate*channels*size*delay/1000 bytes. */
    private fun assertExactPerFlush(label: String, delayMs: Int) {
        val s = AudioDelay.statsSnapshot()
        val frames = SAMPLE_RATE.toLong() * delayMs / 1000
        val bytes = SAMPLE_RATE.toLong() * CHANNELS * 2 * delayMs / 1000
        check("$label format seen by processor",
            s.lastSampleRate == SAMPLE_RATE && s.lastChannelCount == CHANNELS && s.lastEncoding == C.ENCODING_PCM_16BIT) { describe(s) }
        check("$label lastDelayMs", s.lastDelayMs == delayMs) { describe(s) }
        check("$label armed frames per flush", s.armedFlushCount >= 1 &&
            s.lastArmedFrames == frames && s.totalArmedFrames == s.armedFlushCount * frames) { describe(s) }
        check("$label armed bytes per flush", s.lastArmedFrames * s.lastFrameBytes == bytes) { describe(s) }
        check("$label inserted frames == flushes inserted * rate*delay", s.insertedFlushCount >= 1 &&
            s.insertedFrames == s.insertedFlushCount * frames) { describe(s) }
    }

    fun run() {
        val report = Bundle()
        var stage = "setup"
        val wav = File(runner.targetContext.cacheDir, "audio-delay-synthetic.wav")
        fun cleanup() {
            AudioDelay.setDelayMs(0)
            runCatching { main { player?.release(); player = null } }
            wav.delete()
        }
        try {
            writeWav(wav)
            val item = MediaItem.fromUri(Uri.fromFile(wav))

            stage = "delay 0"
            AudioDelay.setDelayMs(0)
            AudioDelay.resetStats()
            startPlayer(item, true)
            await("ready at delay 0") { p().playbackState == Player.STATE_READY && p().isPlaying }
            assertAdvances("delay 0 playhead advances", 400)
            val audioFormat = main { p().audioFormat }
            check("delay 0 audio track decoded/selected", audioFormat != null && audioFormat.sampleRate == SAMPLE_RATE &&
                main { p().currentTracks.isTypeSelected(C.TRACK_TYPE_AUDIO) }) { "format=$audioFormat" }
            val zero = AudioDelay.statsSnapshot()
            check("delay 0 processor inactive and silent", zero.activeConfigureCount == 0 &&
                zero.armedFlushCount == 0 && zero.insertedFrames == 0L) { describe(zero) }
            digestLines.add("delay0 ${describe(zero)} decoderCounters=${main { p().audioDecoderCounters?.let { "init=${it.decoderInitCount} queued=${it.queuedInputBufferCount} rendered=${it.renderedOutputBufferCount}" } }}")

            stage = "delay 500"
            AudioDelay.resetStats()
            main { AudioDelay.setDelayAndReprepare(p(), 500) }
            await("silence inserted at 500 ms") { AudioDelay.statsSnapshot().insertedFlushCount >= 1 && p().isPlaying }
            assertAdvances("delay 500 playhead advances", 400)
            check("delay 500 audio track still selected", main { p().currentTracks.isTypeSelected(C.TRACK_TYPE_AUDIO) })
            assertExactPerFlush("delay 500", 500)
            digestLines.add("delay500 ${describe(AudioDelay.statsSnapshot())}")

            stage = "reprepare 250 while paused"
            main { p().playWhenReady = false }
            await("paused") { !p().isPlaying }
            AudioDelay.resetStats()
            main { AudioDelay.setDelayAndReprepare(p(), 250) }
            await("ready paused at 250 ms") {
                p().playbackState == Player.STATE_READY && AudioDelay.statsSnapshot().lastDelayMs == 250
            }
            check("pause intent stable across reprepare", main { !p().playWhenReady && !p().isPlaying })
            val pausedAt = main { p().currentPosition }
            SystemClock.sleep(700)
            check("paused position does not advance", kotlin.math.abs(main { p().currentPosition } - pausedAt) < 150)
            check("pause intent still stable", main { !p().playWhenReady })
            main { p().playWhenReady = true }
            await("playing after resume at 250 ms") { p().isPlaying && AudioDelay.statsSnapshot().insertedFlushCount >= 1 }
            assertAdvances("delay 250 playhead advances", 400)
            assertExactPerFlush("delay 250", 250)
            digestLines.add("delay250 ${describe(AudioDelay.statsSnapshot())}")

            report.putString("checks", passed.joinToString("\n"))
            report.putString("summary", "PASS: ${passed.size} audio-delay runtime assertions on a local synthetic PCM WAV. " +
                "Proves the processor was configured, armed and inserted exact silence frames per flush, the playhead advanced and " +
                "pause intent survived re-prepare. Does NOT prove audible output or lip-sync.")
            saveDigest("PASS")
            cleanup()
            runner.finish(-1, report)
        } catch (error: Throwable) {
            report.putString("passed", passed.joinToString("\n"))
            report.putString("failure", "Audio delay check failed at $stage: ${error.message ?: error.javaClass.simpleName}")
            report.putString("location", error.stackTrace.firstOrNull { it.className == javaClass.name }?.lineNumber?.toString() ?: "none")
            runCatching { saveDigest("FAIL at $stage") }
            cleanup()
            runner.finish(0, report)
        } finally {
            cleanup()
        }
    }

    /** Numeric digest only (no URLs, titles or provider data) under isolated cache storage, directories created recursively. */
    private fun saveDigest(result: String) {
        val dir = File(runner.targetContext.cacheDir, "audio-delay-checks/latest")
        check(dir.isDirectory || dir.mkdirs()) { "Cannot create digest directory" }
        val body = buildString {
            appendLine("result=$result")
            passed.forEach { appendLine("passed=$it") }
            digestLines.forEach { appendLine(it) }
        }
        val sha = MessageDigest.getInstance("SHA-256").digest(body.toByteArray()).joinToString("") { "%02x".format(it) }
        File(dir, "digest.txt").writeText(body + "sha256=$sha\n")
    }

    private companion object {
        const val SAMPLE_RATE = 44100
        const val CHANNELS = 2
    }
}

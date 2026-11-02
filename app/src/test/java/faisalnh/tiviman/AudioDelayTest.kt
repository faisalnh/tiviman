package faisalnh.tiviman

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

@UnstableApi
class AudioDelayTest {
    private fun processor(ms: Int) = AudioDelayProcessor { ms }

    private fun direct(bytes: ByteArray): ByteBuffer =
        ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes).flip() as ByteBuffer

    private fun ByteBuffer.drainTo(sink: ByteArrayOutputStream) {
        val bytes = ByteArray(remaining())
        get(bytes)
        sink.write(bytes)
    }

    private fun start(p: AudioDelayProcessor, format: AudioFormat) {
        p.configure(format)
        p.flush()
    }

    @Test fun zeroDelayIsInactive() {
        val p = processor(0)
        p.configure(AudioFormat(48000, 2, C.ENCODING_PCM_16BIT))
        p.flush()
        assertFalse(p.isActive)
    }

    @Test fun positiveDelayIsActiveAndKeepsFormat() {
        val p = processor(100)
        val format = AudioFormat(48000, 2, C.ENCODING_PCM_16BIT)
        assertEquals(format, p.configure(format))
        assertTrue(p.isActive)
    }

    @Test fun compressedFormatIsRejected() {
        try {
            processor(100).configure(AudioFormat(48000, 2, C.ENCODING_AC3))
            fail("expected UnhandledAudioFormatException")
        } catch (_: UnhandledAudioFormatException) {
        }
    }

    @Test fun silencePrecedesSourceAndLengthIsDelayPlusSource() {
        val p = processor(100)
        start(p, AudioFormat(48000, 2, C.ENCODING_PCM_16BIT))
        val source = ByteArray(1000) { (it % 120 + 1).toByte() }
        p.queueInput(direct(source))
        val out = ByteArrayOutputStream()
        p.getOutput().drainTo(out)
        p.queueEndOfStream()
        p.getOutput().drainTo(out)
        val silenceBytes = 4800 * 4
        val bytes = out.toByteArray()
        assertEquals(silenceBytes + source.size, bytes.size)
        assertTrue(bytes.take(silenceBytes).all { it == 0.toByte() })
        assertArrayEquals(source, bytes.copyOfRange(silenceBytes, bytes.size))
        assertTrue(p.isEnded)
    }

    @Test fun delayIsOnlyInsertedOncePerFlush() {
        val p = processor(50)
        start(p, AudioFormat(8000, 1, C.ENCODING_PCM_16BIT))
        val delayBytes = 400 * 2
        p.queueInput(direct(ByteArray(64) { 1 }))
        assertEquals(delayBytes + 64, p.getOutput().remaining())
        p.getOutput()
        p.queueInput(direct(ByteArray(64) { 1 }))
        assertEquals(64, p.getOutput().remaining())
        p.flush()
        p.queueInput(direct(ByteArray(64) { 1 }))
        assertEquals(delayBytes + 64, p.getOutput().remaining())
    }

    @Test fun endOfStreamWithoutInputStillEmitsDelay() {
        val p = processor(20)
        start(p, AudioFormat(1000, 2, C.ENCODING_PCM_16BIT))
        p.queueEndOfStream()
        val out = p.getOutput()
        assertEquals(20 * 4, out.remaining())
        while (out.hasRemaining()) assertEquals(0.toByte(), out.get())
        assertTrue(p.isEnded)
    }

    @Test fun emptyInputProducesNothingAndKeepsPendingDelay() {
        val p = processor(10)
        start(p, AudioFormat(1000, 1, C.ENCODING_PCM_16BIT))
        p.queueInput(ByteBuffer.allocateDirect(0))
        assertFalse(p.getOutput().hasRemaining())
        p.queueInput(direct(ByteArray(8) { 1 }))
        assertEquals(10 * 2 + 8, p.getOutput().remaining())
    }

    @Test fun supportedPcmEncodingsGetFrameAlignedSilence() {
        val cases = listOf(
            C.ENCODING_PCM_8BIT to 1, C.ENCODING_PCM_16BIT to 2, C.ENCODING_PCM_24BIT to 3,
            C.ENCODING_PCM_32BIT to 4, C.ENCODING_PCM_FLOAT to 4,
        )
        for ((encoding, width) in cases) {
            val p = processor(10)
            start(p, AudioFormat(1000, 2, encoding))
            p.queueEndOfStream()
            val out = p.getOutput()
            assertEquals("encoding $encoding", 10 * 2 * width, out.remaining())
            val expected = if (encoding == C.ENCODING_PCM_8BIT) 0x80.toByte() else 0.toByte()
            while (out.hasRemaining()) assertEquals("encoding $encoding", expected, out.get())
        }
    }

    @Test fun maximumDelayAndReusedOutputBuffer() {
        val p = processor(5000) // above the bound: clamped to 2000 ms
        start(p, AudioFormat(48000, 2, C.ENCODING_PCM_16BIT))
        p.queueInput(direct(ByteArray(16)))
        val first = p.getOutput()
        assertEquals(96000 * 4 + 16, first.remaining())
        first.position(first.limit())
        p.queueInput(direct(ByteArray(16)))
        val second = p.getOutput()
        assertEquals(16, second.remaining())
        assertSame(first, second)
    }

    @Test fun processorDoesNotHideInsertedSilenceFromTheSinkClock() {
        // DefaultAudioSink 1.5.1 never subtracts processor-added time; if a processor overrode this to remove the
        // silence from the reported position, the audio/video offset would vanish.
        val p = processor(100)
        start(p, AudioFormat(48000, 2, C.ENCODING_PCM_16BIT))
        assertEquals(123_456L, p.getDurationAfterProcessorApplied(123_456L))
    }

    @Test fun sessionDelayIsClampedAndDefaultsToZero() {
        val original = AudioDelay.delayMs
        try {
            assertEquals(2000, AudioDelay.setDelayMs(9999))
            assertEquals(0, AudioDelay.setDelayMs(-5))
            assertEquals(750, AudioDelay.setDelayMs(750))
            assertEquals(750, AudioDelay.delayMs)
            val p = AudioDelayProcessor()
            p.configure(AudioFormat(1000, 1, C.ENCODING_PCM_16BIT))
            assertTrue(p.isActive)
        } finally {
            AudioDelay.setDelayMs(original)
        }
    }
}

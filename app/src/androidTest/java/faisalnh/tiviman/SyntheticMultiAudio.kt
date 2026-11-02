package faisalnh.tiviman

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/** Test-only generated AAC tones plus shipped synthetic video; no downloaded media. */
object SyntheticMultiAudio {
    private data class Sample(val bytes: ByteArray, val time: Long, val flags: Int)
    fun create(context: android.content.Context, output: File) {
        val codec = MediaCodec.createEncoderByType("audio/mp4a-latm")
        val samples = mutableListOf<Sample>()
        var audioFormat: MediaFormat? = null
        try {
            codec.configure(MediaFormat.createAudioFormat("audio/mp4a-latm", 44100, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 64000)
            }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var frame = 0
            val total = 44100 * 5
            var sentEnd = false
            val deadline = android.os.SystemClock.elapsedRealtime() + 15000
            while (true) {
                check(android.os.SystemClock.elapsedRealtime() < deadline)
                if (!sentEnd) {
                    val input = codec.dequeueInputBuffer(1000)
                    if (input >= 0) {
                        val buffer = codec.getInputBuffer(input)!!.apply { clear(); order(java.nio.ByteOrder.LITTLE_ENDIAN) }
                        val count = minOf(buffer.remaining() / 2, total - frame, 1024)
                        repeat(count) { index -> buffer.putShort((kotlin.math.sin((frame + index) * 2 * Math.PI * 440 / 44100) * 2000).toInt().toShort()) }
                        val time = frame * 1000000L / 44100
                        frame += count
                        sentEnd = count == 0
                        codec.queueInputBuffer(input, 0, count * 2, time, if (sentEnd) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                    }
                }
                val index = codec.dequeueOutputBuffer(info, 1000)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) audioFormat = codec.outputFormat
                if (index >= 0) {
                    if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        val data = codec.getOutputBuffer(index)!!.duplicate().apply { position(info.offset); limit(info.offset + info.size) }
                        samples.add(Sample(ByteArray(info.size).also { data.get(it) }, info.presentationTimeUs.coerceAtLeast(0), 0))
                    }
                    val end = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(index, false)
                    if (end) break
                }
            }
        } finally { codec.release() }
        check(samples.isNotEmpty())
        val extractor = MediaExtractor()
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            context.assets.openFd("fixture.mp4").use { extractor.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
            val source = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("video/") }
            extractor.selectTrack(source)
            val video = muxer.addTrack(extractor.getTrackFormat(source))
            val english = muxer.addTrack(audioFormat!!.apply { setString(MediaFormat.KEY_LANGUAGE, "eng") })
            val spanish = muxer.addTrack(audioFormat!!.apply { setString(MediaFormat.KEY_LANGUAGE, "spa") })
            muxer.start()
            val buffer = ByteBuffer.allocate(1024 * 1024)
            val info = MediaCodec.BufferInfo()
            while (true) {
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                info.set(0, size, extractor.sampleTime, extractor.sampleFlags)
                muxer.writeSampleData(video, buffer, info)
                extractor.advance()
            }
            for (track in listOf(english, spanish)) for (sample in samples) {
                info.set(0, sample.bytes.size, sample.time, sample.flags)
                muxer.writeSampleData(track, ByteBuffer.wrap(sample.bytes), info)
            }
            muxer.stop()
        } finally { extractor.release(); muxer.release() }
    }
}

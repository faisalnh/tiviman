package faisalnh.tiviman

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.SystemClock
import java.io.File

/** Solid, visibly distinct local video resolutions for output-buffer regression tests. */
object SyntheticVideo {
    fun create(output: File, width: Int, height: Int, luma: Int) {
        val codec = MediaCodec.createEncoderByType("video/avc")
        val muxer = MediaMuxer(output.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxing = false
        try {
            val colors = codec.codecInfo.getCapabilitiesForType("video/avc").colorFormats
            val color = listOf(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible).first { it in colors }
            codec.configure(MediaFormat.createVideoFormat("video/avc", width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, color)
                setInteger(MediaFormat.KEY_BIT_RATE, 500000)
                setInteger(MediaFormat.KEY_FRAME_RATE, 15)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val bytes = ByteArray(width * height * 3 / 2) { if (it < width * height) luma.toByte() else 128.toByte() }
            val info = MediaCodec.BufferInfo()
            var frame = 0
            var track = -1
            val deadline = SystemClock.elapsedRealtime() + 20000
            while (true) {
                check(SystemClock.elapsedRealtime() < deadline)
                if (frame <= 30) {
                    val index = codec.dequeueInputBuffer(1000)
                    if (index >= 0) {
                        val end = frame == 30
                        if (!end) codec.getInputBuffer(index)!!.apply { clear(); put(bytes) }
                        codec.queueInputBuffer(index, 0, if (end) 0 else bytes.size,
                            frame * 1000000L / 15, if (end) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                        frame++
                    }
                }
                val index = codec.dequeueOutputBuffer(info, 1000)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = muxer.addTrack(codec.outputFormat); muxer.start(); muxing = true
                }
                if (index >= 0) {
                    if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        muxer.writeSampleData(track, codec.getOutputBuffer(index)!!, info)
                    }
                    val end = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(index, false)
                    if (end) break
                }
            }
        } finally {
            codec.release()
            if (muxing) muxer.stop()
            muxer.release()
        }
    }
}

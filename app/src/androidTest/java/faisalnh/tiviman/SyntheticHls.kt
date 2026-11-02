package faisalnh.tiviman

import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteBuffer

/** AAC extracted from generated test MP4, framed as ADTS for local HLS. */
object SyntheticHls {
    fun create(multi: File, directory: File): File {
        check(directory.isDirectory)
        val extractor = MediaExtractor()
        val segment = File(directory, "tone.aac")
        try {
            extractor.setDataSource(multi.absolutePath)
            val track = (0 until extractor.trackCount).first {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == "audio/mp4a-latm"
            }
            val format = extractor.getTrackFormat(track)
            check(format.getInteger(MediaFormat.KEY_SAMPLE_RATE) == 44100 && format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) == 1)
            extractor.selectTrack(track)
            segment.outputStream().use { output ->
                val buffer = ByteBuffer.allocate(65536)
                while (true) {
                    buffer.clear()
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    val length = size + 7
                    // MPEG-4 AAC LC, 44.1 kHz, mono, no CRC.
                    output.write(byteArrayOf(0xff.toByte(), 0xf1.toByte(), 0x50, (0x40 or (length shr 11)).toByte(),
                        (length shr 3).toByte(), (((length and 7) shl 5) or 0x1f).toByte(), 0xfc.toByte()))
                    val bytes = ByteArray(size)
                    buffer.position(0); buffer.get(bytes); output.write(bytes)
                    extractor.advance()
                }
            }
        } finally { extractor.release() }
        for (name in listOf("low.m3u8", "high.m3u8")) File(directory, name).writeText(
            "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:6\n#EXT-X-MEDIA-SEQUENCE:0\n#EXTINF:5.1,\ntone.aac\n#EXT-X-ENDLIST\n")
        return File(directory, "master.m3u8").apply { writeText(
            "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=64000,CODECS=\"mp4a.40.2\"\nlow.m3u8\n" +
                "#EXT-X-STREAM-INF:BANDWIDTH=128000,CODECS=\"mp4a.40.2\"\nhigh.m3u8\n") }
    }
}

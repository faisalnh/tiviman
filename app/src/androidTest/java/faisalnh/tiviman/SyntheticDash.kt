package faisalnh.tiviman

import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteBuffer

/** Minimal test-only fragmented AAC MP4, derived from our Android-generated MP4. */
object SyntheticDash {
    private data class Box(val type: String, val data: ByteArray)
    private fun ints(vararg values: Int) = ByteBuffer.allocate(values.size * 4).apply { values.forEach { putInt(it) } }.array()
    private fun box(type: String, data: ByteArray) = ints(data.size + 8) + type.toByteArray(Charsets.US_ASCII) + data
    private fun boxes(bytes: ByteArray): List<Box> {
        val result = mutableListOf<Box>()
        var offset = 0
        while (offset < bytes.size) {
            check(offset + 8 <= bytes.size)
            val shortSize = ByteBuffer.wrap(bytes, offset, 4).int
            val header = if (shortSize == 1) 16 else 8
            check(offset + header <= bytes.size)
            val longSize = when (shortSize) {
                0 -> (bytes.size - offset).toLong()
                1 -> ByteBuffer.wrap(bytes, offset + 8, 8).long
                else -> shortSize.toLong()
            }
            check(longSize >= header && longSize <= bytes.size - offset)
            val size = longSize.toInt()
            result.add(Box(String(bytes, offset + 4, 4, Charsets.US_ASCII), bytes.copyOfRange(offset + header, offset + size)))
            offset += size
        }
        return result
    }
    private fun rebuild(bytes: ByteArray): ByteArray = boxes(bytes).fold(byteArrayOf()) { output, child ->
        val data = when (child.type) {
            "mdia", "minf", "stbl" -> rebuild(child.data)
            "stts", "stsc", "stco", "co64", "ctts", "stss" -> ints(0, 0)
            "stsz" -> ints(0, 0, 0)
            else -> child.data
        }
        output + box(child.type, data)
    }
    fun create(source: File, directory: File): File {
        val top = boxes(source.readBytes())
        val movie = boxes(top.first { it.type == "moov" }.data)
        val audio = movie.filter { it.type == "trak" }.first { track ->
            val mdia = boxes(track.data).first { it.type == "mdia" }
            val handler = boxes(mdia.data).first { it.type == "hdlr" }.data
            String(handler, 8, 4, Charsets.US_ASCII) == "soun"
        }
        val trackHeader = boxes(audio.data).first { it.type == "tkhd" }.data
        check(trackHeader[0].toInt() == 0)
        val trackId = ByteBuffer.wrap(trackHeader, 12, 4).int
        val mediaHeader = boxes(boxes(audio.data).first { it.type == "mdia" }.data).first { it.type == "mdhd" }.data
        check(mediaHeader[0].toInt() == 0)
        val timescale = ByteBuffer.wrap(mediaHeader, 12, 4).int
        val init = box("ftyp", top.first { it.type == "ftyp" }.data) + box("moov",
            box("mvhd", movie.first { it.type == "mvhd" }.data) + box("trak", rebuild(audio.data)) +
                box("mvex", box("trex", ints(0, trackId, 1, 1024, 0, 0))))
        val samples = mutableListOf<ByteArray>()
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(source.absolutePath)
            val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == "audio/mp4a-latm" }
            extractor.selectTrack(track)
            val buffer = ByteBuffer.allocate(65536)
            while (true) {
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                samples.add(ByteArray(size).also { buffer.position(0); buffer.get(it) })
                extractor.advance()
            }
        } finally { extractor.release() }
        check(samples.isNotEmpty() && timescale == 44100)
        fun fragment(offset: Int): ByteArray {
            val entries = samples.fold(byteArrayOf()) { bytes, sample -> bytes + ints(1024, sample.size) }
            return box("moof", box("mfhd", ints(0, 1)) + box("traf",
                box("tfhd", ints(0x020000, trackId)) + box("tfdt", ints(0, 0)) +
                    box("trun", ints(0x000301, samples.size, offset) + entries)))
        }
        val moof = fragment(0)
        File(directory, "init.mp4").writeBytes(init)
        File(directory, "segment.m4s").outputStream().use { output ->
            output.write(fragment(moof.size + 8))
            output.write(ints(samples.sumOf { it.size } + 8)); output.write("mdat".toByteArray())
            samples.forEach { output.write(it) }
        }
        return File(directory, "manifest.mpd").apply { writeText("""
            <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static" mediaPresentationDuration="PT5S" minBufferTime="PT0.1S" profiles="urn:mpeg:dash:profile:isoff-on-demand:2011">
              <Period duration="PT5S"><AdaptationSet mimeType="audio/mp4" codecs="mp4a.40.2" segmentAlignment="true">
                <SegmentList timescale="44100" duration="${samples.size * 1024}"><Initialization sourceURL="init.mp4"/><SegmentURL media="segment.m4s"/></SegmentList>
                <Representation id="low" bandwidth="64000" audioSamplingRate="44100"/>
                <Representation id="high" bandwidth="128000" audioSamplingRate="44100"/>
              </AdaptationSet></Period>
            </MPD>
        """.trimIndent()) }
    }
}

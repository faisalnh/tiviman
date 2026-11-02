package faisalnh.tiviman

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks

/** Selections are channel-scoped; hashes avoid storing provider track labels/IDs in preferences. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object TrackControls {
    val types = mapOf(5 to C.TRACK_TYPE_VIDEO, 6 to C.TRACK_TYPE_AUDIO, 8 to C.TRACK_TYPE_TEXT)
    data class Option(val label: String, val key: String, val group: Tracks.Group? = null,
        val index: Int = 0, val supported: Boolean = true)

    fun identity(format: Format): String {
        val value = listOf(format.id, format.sampleMimeType, format.codecs, format.language,
            format.label, format.width, format.height, format.bitrate, format.channelCount,
            format.sampleRate, format.roleFlags).joinToString("|")
        return java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    fun options(tracks: Tracks, type: Int): List<Option> = buildList {
        add(Option("Auto", "auto"))
        if (type != C.TRACK_TYPE_VIDEO) add(Option("Off", "off"))
        tracks.groups.filter { it.type == type }.forEach { group ->
            repeat(group.length) { index ->
                val f = group.getTrackFormat(index)
                val details = buildList {
                    if (type == C.TRACK_TYPE_VIDEO && f.width > 0 && f.height > 0) add("${f.width} × ${f.height}")
                    if (type != C.TRACK_TYPE_VIDEO) add(f.language?.takeUnless { it == "und" } ?: "Unknown language")
                    if (f.channelCount > 0) add(if (f.channelCount == 2) "Stereo" else "${f.channelCount} ch")
                    if (type == C.TRACK_TYPE_VIDEO && f.bitrate > 0) add("${f.bitrate / 1000} kbps")
                    f.sampleMimeType?.let { add(it) }
                }.joinToString(" • ")
                val supported = group.isTrackSupported(index)
                add(Option("Track ${size + 1}: ${details.ifBlank { "Unknown format" }}${if (supported) "" else " • Unsupported"}",
                    identity(f), group, index, supported))
            }
        }
    }

    fun apply(player: Player, type: Int, key: String) {
        val builder = player.trackSelectionParameters.buildUpon().clearOverridesOfType(type)
            .setTrackTypeDisabled(type, key == "off")
        if (key != "auto" && key != "off") {
            val option = options(player.currentTracks, type).firstOrNull { it.key == key && it.supported }
            option?.group?.let { builder.setOverrideForType(TrackSelectionOverride(it.mediaTrackGroup, option.index)) }
        }
        val next = builder.build()
        if (next != player.trackSelectionParameters) player.trackSelectionParameters = next
    }
}

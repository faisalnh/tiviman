package faisalnh.tiviman

/** Local playback preferences only: never edits the catalog or another channel. */
object ChannelOptions {
    fun resetTracks(choices: Map<String, String>, channelId: Int): Map<String, String> =
        choices.filterKeys { !it.startsWith("$channelId:") }

    fun resetDisplay(choices: Map<String, String>, channelId: Int): Map<String, String> =
        choices - channelId.toString()
}

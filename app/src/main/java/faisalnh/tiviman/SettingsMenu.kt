package faisalnh.tiviman

/** Navigation only; no new global preferences or implied backend capabilities. */
object SettingsMenu {
    data class Entry(val label: String, val available: Boolean = true)
    val entries = listOf(
        Entry("Playlists • switch or add a saved playlist"),
        Entry("Video tracks • this channel"),
        Entry("Audio tracks • this channel"),
        Entry("Closed captions • this channel"),
        Entry("Display mode • this channel"),
        Entry("Sleep timer • this process session"),
        Entry("Channel options • this channel"),
        Entry("Audio offset • experimental PCM delay, session"),
        Entry("Picture-in-picture • Unavailable on this device", false),
        Entry("Recordings • direct streams, foreground only"),
        Entry("Multiview • two simultaneous streams"),
        Entry("EPG • import, browse, mapping and opt-in refresh")
    )

    fun forDevice(pictureInPicture: Boolean) = entries.mapIndexed { index, entry ->
        if (index == 8) Entry(if (pictureInPicture) "Picture-in-picture • this playback session"
            else "Picture-in-picture • Unavailable on this device", pictureInPicture) else entry
    }
}

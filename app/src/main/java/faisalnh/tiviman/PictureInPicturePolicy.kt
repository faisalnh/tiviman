package faisalnh.tiviman

/** Explicit entry only: Home never silently starts background playback. */
object PictureInPicturePolicy {
    fun available(api: Int, feature: Boolean) = api >= 26 && feature

    fun canEnter(supported: Boolean, started: Boolean, loading: Boolean,
        hasPlayer: Boolean, failed: Boolean, expired: Boolean) =
        supported && started && !loading && hasPlayer && !failed && !expired
}

package faisalnh.tiviman

import android.app.Instrumentation
import android.os.Bundle
import java.io.File
import java.security.MessageDigest

/** User-authorized addition only; keeps existing active playlist and all its files. */
class AddAlternativePlaylist(private val runner: Instrumentation) {
    fun run() {
        val result = Bundle()
        fun legacyDigests() = runner.targetContext.noBackupFilesDir.listFiles().orEmpty()
            .filter { it.isFile && it.name != "playlists.enc" }
            .associate { it.name to MessageDigest.getInstance("SHA-256").digest(it.readBytes()).toList() }
        val before = legacyDigests()
        try {
            val store = PlaylistStore(runner.targetContext)
            val active = store.playlistId
            val label = "Curated M3U"
            check(store.playlists().none { it.name == label }) // Do not silently add duplicates on retry.
            val cache = File(runner.targetContext.cacheDir, "alternative-epg")
            val text = File(cache, "playlist.download").readText()
            val channels = Playlist.parse(text)
            val guide = java.util.zip.GZIPInputStream(File(cache, "epg.download").inputStream()).use { Epg.parseStream(it, channels) }
            val matched = Epg.project(channels, guide, Epg.minute()).count { c -> c.programmes.any { it.title != "No information" } }
            check(matched > 0)
            val id = store.add(label, text, guide = guide, activate = false)
            check(PlaylistStore(runner.targetContext).playlistId == active)
            check(PlaylistStore(runner.targetContext, id).loadEpg(PlaylistStore(runner.targetContext, id).snapshot().revision)?.events == guide.events)
            check(before == legacyDigests())
            result.putInt("channels", channels.size); result.putInt("matched_channels", matched)
            result.putString("summary", "PASS: Curated M3U added with encrypted EPG; original active playlist and original saved files unchanged; no network or playback")
            runner.finish(-1, result)
        } catch (error: Exception) {
            result.putString("failure", error.javaClass.simpleName)
            result.putBoolean("original_files_unchanged", before == legacyDigests())
            runner.finish(0, result)
        }
    }
}

package faisalnh.tiviman

import android.app.Instrumentation
import android.content.ContextWrapper
import android.os.Bundle
import java.io.File
import java.security.MessageDigest

/** Explicit opt-in, supplied local downloads only. No player and no saved-account writes. */
class AlternativeEpgCheck(private val runner: Instrumentation) {
    fun run() {
        val report = Bundle()
        fun digests() = runner.targetContext.noBackupFilesDir.listFiles().orEmpty().filter { it.isFile }
            .associate { it.name to MessageDigest.getInstance("SHA-256").digest(it.readBytes()).toList() }
        val before = digests()
        var stage = "parse_playlist"
        try {
            val root = File(runner.targetContext.cacheDir, "alternative-epg").apply { mkdirs() }
            val text = File(root, "playlist.download").readText()
            val catalog = Playlist.parse(text)
            stage = "parse_gzip_xmltv"
            val guide = java.util.zip.GZIPInputStream(File(root, "epg.download").inputStream()).use { Epg.parseStream(it, catalog) }
            stage = "project_and_save_isolated"
            val projected = Epg.project(catalog, guide, Epg.minute())
            val matched = projected.count { c -> c.programmes.any { it.title != "No information" } }
            check(matched > 0)
            val isolated = object : ContextWrapper(runner.targetContext) {
                override fun getNoBackupFilesDir() = File(root, "store").apply { mkdirs() }
            }
            val store = PlaylistStore(isolated)
            store.save(text)
            val revision = store.snapshot().revision
            store.saveEpg(revision, guide)
            check(store.loadEpg(revision)?.events == guide.events)
            check(store.snapshot().channels?.size == catalog.size)
            check(digests() == before)
            report.putInt("channels", catalog.size)
            report.putInt("matched_channels", matched)
            report.putInt("retained_events", guide.events.size)
            report.putBoolean("saved_files_unchanged", true)
            report.putString("summary", "PASS: supplied M3U/gzip XMLTV parsed, matched, projected and encrypted/reloaded in isolated cache; no playback")
            runner.finish(-1, report)
        } catch (error: Exception) {
            report.putString("failed_stage", stage)
            report.putString("error_category", error.javaClass.simpleName)
            report.putString("location", error.stackTrace.firstOrNull { it.className.startsWith("faisalnh.tiviman.") }?.let { "${it.fileName}:${it.lineNumber}" })
            report.putString("validation", error.message?.takeIf { it in setOf("Invalid XMLTV time", "XML declarations unsupported", "EPG too large", "Too many matched channels", "Invalid XMLTV", "No XMLTV programmes") } ?: "other")
            report.putBoolean("saved_files_unchanged", digests() == before)
            runner.finish(0, report)
        }
    }
}

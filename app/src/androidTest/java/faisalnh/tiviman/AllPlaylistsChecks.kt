package faisalnh.tiviman

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View

import androidx.media3.common.C
import androidx.media3.ui.AspectRatioFrameLayout
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/** Isolated app storage for the All playlists guide: a distinct directory under the cache. Never the real files. */
internal fun isolatedAllPlaylistsRoot(base: Context): File = File(base.cacheDir, "isolated-all-playlists")

internal fun isolatedAllPlaylistsContext(base: Context): Context {
    val root = isolatedAllPlaylistsRoot(base)
    return object : ContextWrapper(base) {
        override fun getNoBackupFilesDir(): File = File(root, "nobackup").apply { mkdirs() }
        override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
    }
}

/** Test-only substitution, installed by the runner's newActivity. Same root as [AllPlaylistsChecks]. */
class IsolatedAllPlaylistsActivity : AllPlaylistsActivity() {
    private val isolated by lazy { isolatedAllPlaylistsContext(baseContext) }
    override fun storageContext(): Context = isolated
}

/**
 * All playlists guide instrumentation. Two synthetic playlists with overlapping channel ids live in an isolated
 * store; the only network is a 127.0.0.1 server serving the packaged fixture.mp4. No catalog/provider network.
 * Real app files are digested before/after. Nothing prints addresses, ports or file names.
 *
 * Parent wiring: gate on a flag (allPlaylistsTools), route newActivity through [substitute], then call [run].
 * Persisted EPG manual mappings are not stored by PlaylistStore.saveEpg, so they are not seeded here; each
 * playlist's guide is separated by its own tvg-ids and its own encrypted epg.enc.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class AllPlaylistsChecks(private val runner: Instrumentation) {
    companion object {
        /** For the parent's newActivity: returns the isolated subclass only for this Activity, else null. */
        fun substitute(className: String): Activity? =
            if (className == AllPlaylistsActivity::class.java.name) IsolatedAllPlaylistsActivity() else null
        private const val FILLER_A = 150
        private const val FILLER_B = 150
    }

    private class FixtureServer(private val media: ByteArray) {
        val socket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val paths = CopyOnWriteArrayList<String>()
        private val workers = Executors.newCachedThreadPool()
        init {
            Thread {
                try { while (!socket.isClosed) { val client = socket.accept(); workers.execute { handle(client) } } } catch (_: Exception) { }
            }.apply { isDaemon = true }.start()
        }
        fun url(path: String) = "http://127.0.0.1:${socket.localPort}$path"
        private fun handle(client: java.net.Socket) {
            try {
                client.use {
                    it.soTimeout = 10000
                    val reader = it.getInputStream().bufferedReader()
                    val path = reader.readLine()?.split(' ')?.getOrNull(1) ?: return
                    var start = 0
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        if (line.startsWith("Range:", true)) start = Regex("bytes=(\\d+)-").find(line)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    }
                    paths.add(path)
                    val out = it.getOutputStream()
                    if (path != "/a.mp4" && path != "/b.mp4") {
                        out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); out.flush(); return
                    }
                    val from = start.coerceIn(0, media.size)
                    val head = (if (from > 0) "HTTP/1.1 206 Partial Content\r\nContent-Range: bytes $from-${media.size - 1}/${media.size}\r\n" else "HTTP/1.1 200 OK\r\n") +
                        "Content-Type: video/mp4\r\nContent-Length: ${media.size - from}\r\nAccept-Ranges: bytes\r\nConnection: close\r\n\r\n"
                    out.write(head.toByteArray()); out.write(media, from, media.size - from); out.flush()
                }
            } catch (_: Exception) { }
        }
        fun close() { socket.close(); workers.shutdownNow() }
    }

    private lateinit var activity: AllPlaylistsActivity
    private var server: FixtureServer? = null

    private fun <T> main(block: () -> T): T {
        var value: T? = null
        runner.runOnMainSync { value = block() }
        @Suppress("UNCHECKED_CAST") return value as T
    }
    private fun await(label: String, timeout: Long = 20000, test: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeout
        while (!main(test) && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
        check(main(test)) { "Timed out: $label" }
        runner.waitForIdleSync()
    }
    private fun need(ok: Boolean, label: String) { if (!ok) throw AssertionError(label) }

    private val isolatedRoot get() = isolatedAllPlaylistsRoot(runner.targetContext)

    /** Content digest of everything in the real app-private dirs outside the isolated root. */
    private fun realDigest(): String {
        val sha = MessageDigest.getInstance("SHA-256")
        fun walk(dir: File) {
            dir.listFiles()?.sortedBy { it.name }?.forEach { file ->
                if (file == isolatedRoot) return@forEach
                sha.update(file.name.toByteArray())
                if (file.isDirectory) walk(file)
                else file.inputStream().use { s -> val buf = ByteArray(8192); while (true) { val n = s.read(buf); if (n < 0) break; sha.update(buf, 0, n) } }
            }
        }
        walk(runner.targetContext.noBackupFilesDir); walk(runner.targetContext.filesDir)
        return sha.digest().joinToString("") { "%02x".format(it) }
    }

    private fun bringToFront() {
        runner.targetContext.startActivity(Intent(runner.targetContext, AllPlaylistsActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        await("front again") { activity.window.decorView.hasWindowFocus() }
    }

    private fun launch() {
        val started = runner.startActivitySync(Intent(runner.targetContext, AllPlaylistsActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        // Fail closed: the isolated subclass must have been substituted by newActivity.
        if (started !is IsolatedAllPlaylistsActivity) {
            (started as? Activity)?.let { a -> runner.runOnMainSync { a.finish() } }
            throw AssertionError("Isolated Activity was not substituted")
        }
        activity = started
        await("window focus") { activity.window.decorView.hasWindowFocus() }
    }

    private fun close() {
        val a = activity
        main { a.finish() }
        await("destroyed") { a.isDestroyed }
    }

    private fun search(query: String, expect: (List<AllPlaylistsActivity.RowInfo>) -> Boolean, label: String) {
        main { activity.searchBox.setText(query) }
        await(label) { activity.isLoaded() && activity.searchBox.text.toString() == query && expect(activity.rowInfos()) }
    }

    fun run() {
        val result = Bundle()
        var stage = "setup"
        var before = ""
        try {
            val media = runner.targetContext.assets.open("fixture.mp4").use { it.readBytes() }
            before = realDigest()
            isolatedRoot.deleteRecursively()
            val srv = FixtureServer(media).also { server = it }
            stage = "seed two isolated playlists with overlapping ids"
            val context = isolatedAllPlaylistsContext(runner.targetContext)
            val store = PlaylistStore(context)
            need(store.playlists().isEmpty(), "Isolated store was not empty")
            fun m3u(tag: String, tvg: String, stream: String, filler: Int) = "#EXTM3U\n" +
                "#EXTINF:-1 tvg-id=\"$tvg\" group-title=\"Main\",$tag One\n${srv.url(stream)}\n" +
                (1..filler).joinToString("") { "#EXTINF:-1 group-title=\"Filler\",$tag Filler $it\n${srv.url("/filler/${tag.lowercase()}/$it")}\n" }
            val idA = store.add("Alpha list", m3u("Alpha", "alpha0", "/a.mp4", FILLER_A), activate = false)
            val idB = store.add("Beta list", m3u("Beta", "beta0", "/b.mp4", FILLER_B), activate = false)
            val now = Epg.minute()
            val a = PlaylistStore(context, idA); val b = PlaylistStore(context, idB)
            val ra = a.snapshot().revision; val rb = b.snapshot().revision
            a.saveEpg(ra, EpgData(listOf(EpgEvent("alpha0", "Alpha Now", now - 10, now + 30, "alpha description"),
                EpgEvent("alpha0", "Alpha Next", now + 30, now + 90, "")), emptyMap(), System.currentTimeMillis()))
            b.saveEpg(rb, EpgData(listOf(EpgEvent("beta0", "Beta Now", now - 5, now + 10, "beta description"),
                EpgEvent("beta0", "Beta Later", now + 40, now + 90, "")), emptyMap(), System.currentTimeMillis()))
            // Same channel id 0 in both, different saved choices: A mutes audio + zoom, B disables captions + full.
            a.saveTracks(ra, mapOf("0:${C.TRACK_TYPE_AUDIO}" to "off")); a.saveDisplay(ra, mapOf("0" to DisplayMode.ZOOM.name))
            b.saveTracks(rb, mapOf("0:${C.TRACK_TYPE_TEXT}" to "off")); b.saveDisplay(rb, mapOf("0" to DisplayMode.FULL.name))
            val catalogA = requireNotNull(a.snapshot().channels); val catalogB = requireNotNull(b.snapshot().channels)
            need(catalogA.size == FILLER_A + 1 && catalogB.size == FILLER_B + 1, "Seeded catalog size")
            need(catalogA[0].id == 0 && catalogB[0].id == 0, "Ids do not overlap")
            need((catalogA + catalogB).all { it.stream.startsWith("http://127.0.0.1:") }, "Seed stream is not loopback")
            // Registry intentionally left with no 'active' playlist change by this screen: remember it.
            val registryBefore = File(File(isolatedRoot, "nobackup"), "playlists.enc").readBytes().toList()
            val digestSeeded = isolatedRoot.walkTopDown().filter { it.isFile }
                .associate { it.relativeTo(isolatedRoot).path to MessageDigest.getInstance("SHA-256").digest(it.readBytes()).toList() }

            stage = "open: loads off main, combined fair cap, no stream requests"
            launch()
            await("loaded") { activity.isLoaded() }
            var rows = main { activity.rowInfos() }
            need(rows.size == 200, "Cap not 200: ${rows.size}")
            need(rows.count { it.playlistId == idA } == 100 && rows.count { it.playlistId == idB } == 100, "Cap not fair across playlists")
            need(rows.all { it.playlist == "Alpha list" || it.playlist == "Beta list" }, "Row missing playlist label")
            need(rows.any { it.playlistId == idA && it.channelId == 0 } && rows.any { it.playlistId == idB && it.channelId == 0 }, "Overlapping ids lost")
            need(main { activity.player == null && activity.playingInfo() == null }, "Player present before explicit play")
            need(srv.paths.isEmpty(), "Stream requested on open")

            stage = "search is scoped and never plays"
            search("beta", { r -> r.isNotEmpty() && r.all { it.playlistId == idB } }, "beta only")
            need(main { activity.rowInfos().size } == FILLER_B + 1, "Playlist-name search lost rows")
            search("alpha one", { r -> r.size == 1 && r[0].playlistId == idA && r[0].now.contains("Alpha Now") && r[0].next.contains("Alpha Next") }, "alpha one")
            search("beta one", { r -> r.size == 1 && r[0].playlistId == idB && r[0].now.contains("Beta Now") && r[0].next.contains("No information") }, "beta one with neutral gap")
            search("zzz-no-match", { it.isEmpty() }, "empty")
            need(main { activity.selectedInfo() == null && !activity.playButton.isEnabled }, "Selection after empty search")
            need(main { activity.player } == null && srv.paths.isEmpty(), "Search played or fetched a stream")

            stage = "selected focus matches the row"
            search("one", { r -> r.size == 2 }, "both One rows")
            main { activity.list.requestFocus(); activity.list.setSelection(1) }
            await("selection follows list") { activity.selectedInfo() == activity.rowInfos().getOrNull(activity.list.selectedItemPosition) && activity.list.selectedItemPosition == 1 }
            main { activity.list.setSelection(0) }
            await("selection row 0") { activity.list.selectedItemPosition == 0 && activity.selectedInfo() == activity.rowInfos()[0] }
            need(srv.paths.isEmpty(), "Moving selection fetched a stream")

            stage = "explicit click: chosen stream, tracks and display (playlist A)"
            val first = main { activity.rowInfos()[0] }
            val aFirst = if (first.playlistId == idA) 0 else 1
            main { activity.list.performItemClick(null, aFirst, 0) }
            await("player A") { activity.player != null && activity.playingInfo()?.playlistId == idA }
            await("A requested /a.mp4") { "/a.mp4" in srv.paths }
            need("/b.mp4" !in srv.paths, "Wrong stream requested")
            await("A tracks") { activity.player?.trackSelectionParameters?.disabledTrackTypes?.let { C.TRACK_TYPE_AUDIO in it && C.TRACK_TYPE_TEXT !in it } == true }
            await("A display zoom") { activity.video.resizeMode == AspectRatioFrameLayout.RESIZE_MODE_ZOOM }
            need(main { activity.playerHostVisible() }, "Fullscreen player not shown")

            stage = "pause, stop, then B has its own choices"
            main { activity.pauseButton.performClick() }
            await("paused") { activity.player?.playWhenReady == false }
            main { activity.stopButton.performClick() }
            await("released") { activity.player == null && activity.playingInfo() == null }
            main { activity.list.performItemClick(null, 1 - aFirst, 0) }
            await("player B") { activity.player != null && activity.playingInfo()?.playlistId == idB }
            await("B requested /b.mp4") { "/b.mp4" in srv.paths }
            await("B tracks") { activity.player?.trackSelectionParameters?.disabledTrackTypes?.let { C.TRACK_TYPE_TEXT in it && C.TRACK_TYPE_AUDIO !in it } == true }
            await("B display full") { activity.video.resizeMode == AspectRatioFrameLayout.RESIZE_MODE_FILL }

            stage = "onStop releases; no automatic resume"
            val requests = srv.paths.size
            runner.sendKeyDownUpSync(KeyEvent.KEYCODE_HOME)
            await("released on stop") { activity.player == null && activity.playingInfo() == null }
            bringToFront()
            SystemClock.sleep(1500)
            need(main { activity.player == null && activity.playingInfo() == null && activity.video.player == null }, "Playback resumed automatically")
            need(srv.paths.size == requests, "Stream reopened without a click")
            need(main { activity.rowInfos().isNotEmpty() && activity.playButton.visibility == View.VISIBLE }, "Guide not shown after return")
            close()

            stage = "private files and isolated catalog unchanged"
            need(realDigest() == before, "Real app files changed")
            need(File(File(isolatedRoot, "nobackup"), "playlists.enc").readBytes().toList() == registryBefore, "Registry written")
            val digestAfter = isolatedRoot.walkTopDown().filter { it.isFile }
                .associate { it.relativeTo(isolatedRoot).path to MessageDigest.getInstance("SHA-256").digest(it.readBytes()).toList() }
            need(digestAfter == digestSeeded, "Isolated playlist files were mutated by the guide")
            result.putString("summary", "PASS: two isolated playlists with overlapping ids; 200-row fair cap, scoped search, neutral gaps, selection focus, " +
                "no stream before explicit click, per-playlist tracks/display, pause/stop, onStop release without resume; real and isolated files unchanged")
            runner.finish(-1, result)
        } catch (error: Throwable) {
            result.putString("failure", error.javaClass.simpleName)
            result.putString("stage", stage)
            result.putString("message", error.message?.take(120))
            result.putString("location", error.stackTrace.firstOrNull { it.className.startsWith("faisalnh.tiviman") }?.let { "${it.fileName}:${it.lineNumber}" })
            result.putBoolean("real_files_unchanged", before.isNotEmpty() && before == realDigest())
            if (::activity.isInitialized) runCatching { runner.runOnMainSync { activity.finish() } }
            runner.finish(0, result)
        } finally {
            server?.close()
            isolatedRoot.deleteRecursively()
        }
    }
}

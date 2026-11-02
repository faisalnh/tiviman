package faisalnh.tiviman

import android.app.Activity
import android.app.AlertDialog
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/** Isolated app storage for advanced tools: a distinct directory under the cache. Never the real files. */
internal fun isolatedAdvancedRoot(base: Context): File = File(base.cacheDir, "isolated-advanced")

internal fun isolatedAdvancedContext(base: Context): Context {
    val root = isolatedAdvancedRoot(base)
    return object : ContextWrapper(base) {
        override fun getNoBackupFilesDir(): File = File(root, "nobackup").apply { mkdirs() }
        override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
    }
}

/** Test-only substitution, installed by the runner's newActivity. Same root as [AdvancedPlaybackChecks]. */
class IsolatedAdvancedActivity : AdvancedPlaybackActivity() {
    private val isolated by lazy { isolatedAdvancedContext(baseContext) }
    override fun storageContext(): Context = isolated
}

/**
 * Advanced tools instrumentation. Network is a 127.0.0.1 server seeded with the packaged fixture.mp4;
 * the catalog is a synthetic M3U saved into an isolated store. Real app files are digested before/after.
 * Nothing prints addresses, ports or file names.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class AdvancedPlaybackChecks(private val runner: Instrumentation) {
    companion object {
        /** For the parent's newActivity: returns the isolated subclass only for this Activity, else null. */
        fun substitute(className: String): Activity? =
            if (className == AdvancedPlaybackActivity::class.java.name) IsolatedAdvancedActivity() else null
    }

    private class FixtureServer(private val media: ByteArray) {
        val socket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val paths = CopyOnWriteArrayList<String>()
        private val workers = Executors.newCachedThreadPool()
        private val manifest = "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:6\n#EXTINF:5,\nseg.ts\n#EXT-X-ENDLIST\n".toByteArray()
        init {
            Thread {
                try {
                    while (!socket.isClosed) {
                        val client = socket.accept()
                        workers.execute { handle(client) }
                    }
                } catch (_: Exception) { }
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
                    val (type, body) = when (path) {
                        "/a.mp4", "/b.mp4" -> "video/mp4" to media
                        "/live.m3u8" -> "application/vnd.apple.mpegurl" to manifest
                        "/disguised.ts" -> "application/octet-stream" to manifest
                        else -> { it.getOutputStream().apply { write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); flush() }; return }
                    }
                    val from = start.coerceIn(0, body.size)
                    val partial = from > 0
                    val head = (if (partial) "HTTP/1.1 206 Partial Content\r\nContent-Range: bytes $from-${body.size - 1}/${body.size}\r\n" else "HTTP/1.1 200 OK\r\n") +
                        "Content-Type: $type\r\nContent-Length: ${body.size - from}\r\nAccept-Ranges: bytes\r\nConnection: close\r\n\r\n"
                    val out = it.getOutputStream()
                    out.write(head.toByteArray()); out.write(body, from, body.size - from); out.flush()
                }
            } catch (_: Exception) { }
        }
        fun close() { socket.close(); workers.shutdownNow() }
    }

    private lateinit var activity: AdvancedPlaybackActivity
    private lateinit var server: FixtureServer
    private lateinit var media: ByteArray

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
    private fun field(name: String): Any? = AdvancedPlaybackActivity::class.java.getDeclaredField(name).let { it.isAccessible = true; it.get(activity) }
    private fun descendants(view: View): List<View> =
        listOf(view) + ((view as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { descendants(g.getChildAt(it)) } } ?: emptyList())
    private fun views() = descendants(activity.window.decorView)
    private fun button(text: String) = views().filterIsInstance<Button>().first { it.text.toString() == text }
    private fun statusText() = views().filterIsInstance<TextView>().filter { it !is Button }.joinToString(" | ") { it.text }
    private fun players() = field("players") as Array<*>
    private fun player(i: Int) = players()[i] as ExoPlayer
    private fun dialog() = field("dialog") as AlertDialog?

    private val isolatedRoot get() = isolatedAdvancedRoot(runner.targetContext)
    private val recordings get() = File(File(isolatedRoot, "files"), "recordings")
    private fun recordingFiles(ext: String) = recordings.listFiles()?.filter { it.name.endsWith(ext) }.orEmpty()

    /** Content digest of everything in the real app-private dirs outside the isolated root. */
    private fun realDigest(): String {
        val sha = MessageDigest.getInstance("SHA-256")
        fun walk(dir: File) {
            dir.listFiles()?.sortedBy { it.name }?.forEach { file ->
                if (file == isolatedRoot) return@forEach
                if (file.isDirectory) { sha.update(file.name.toByteArray()); walk(file) }
                else { sha.update(file.name.toByteArray()); file.inputStream().use { s -> val buf = ByteArray(8192); while (true) { val n = s.read(buf); if (n < 0) break; sha.update(buf, 0, n) } } }
            }
        }
        walk(runner.targetContext.noBackupFilesDir); walk(runner.targetContext.filesDir)
        return sha.digest().joinToString("") { "%02x".format(it) }
    }

    private fun launch(mode: String, playlistId: String, channelId: Int) {
        val started = runner.startActivitySync(AdvancedPlaybackActivity.intent(runner.targetContext, mode, playlistId, channelId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        // Fail closed: the isolated subclass must have been substituted by newActivity.
        if (started !is IsolatedAdvancedActivity) { (started as? Activity)?.let { a -> runner.runOnMainSync { a.finish() } }; throw AssertionError("Isolated Activity was not substituted") }
        activity = started
        await("window focus") { activity.window.decorView.hasWindowFocus() }
    }

    private fun close() {
        val a = activity
        main { a.finish() }
        await("destroyed") { a.isDestroyed }
    }

    fun run() {
        val result = Bundle()
        val passed = mutableListOf<String>()
        var stage = "setup"
        var before = ""
        try {
            media = runner.targetContext.assets.open("fixture.mp4").use { it.readBytes() }
            before = realDigest()
            isolatedRoot.deleteRecursively()
            server = FixtureServer(media)
            stage = "isolated catalog"
            val store = PlaylistStore(isolatedAdvancedContext(runner.targetContext))
            need(store.playlists().isEmpty(), "Isolated store was not empty")
            val m3u = "#EXTM3U\n" + listOf("Synthetic Alpha" to "/a.mp4", "Synthetic Beta" to "/b.mp4",
                "Synthetic Manifest" to "/live.m3u8", "Synthetic Disguised" to "/disguised.ts")
                .joinToString("") { "#EXTINF:-1 group-title=\"Synthetic\",${it.first}\n${server.url(it.second)}\n" }
            val id = store.add("Synthetic advanced", m3u)
            passed.add(stage)

            stage = "recordings open: no player, no network, secure"
            launch(AdvancedPlaybackActivity.MODE_RECORDINGS, id, 0)
            await("catalog loaded") { button("Record current channel").isEnabled }
            need(main { field("recordingPlayer") == null }, "Recordings opened with a player")
            need(server.paths.isEmpty(), "Recordings opened a connection")
            need(recordingFiles(".rec").isEmpty() && recordingFiles(".part").isEmpty(), "Unexpected files")
            need(main { activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0 }, "Window is not secure")
            passed.add(stage)

            stage = "explicit record captures loopback progressive"
            main { button("Record current channel").performClick() }
            await("confirm shown") { dialog()?.isShowing == true }
            need(main { dialog()!!.window!!.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0 }, "Dialog is not secure")
            need(server.paths.isEmpty(), "Recording started before confirmation")
            main { dialog()!!.getButton(AlertDialog.BUTTON_POSITIVE).performClick() }
            await("recording saved", 30000) { recordingFiles(".rec").size == 1 && recordingFiles(".part").isEmpty() }
            need(recordingFiles(".rec").single().readBytes().contentEquals(media), "Capture differs from fixture")
            need(AdvancedRules.isSafeRecordingName(recordingFiles(".rec").single().name), "Unsafe file name")
            await("list shows it") { (field("savedList") as ListView).adapter.count == 1 }
            passed.add(stage)

            stage = "saved recording plays and decodes"
            val list = main { field("savedList") as ListView }
            main { list.performItemClick(list.adapter.getView(0, null, list), 0, 0) }
            await("choose dialog") { dialog()?.isShowing == true }
            main { val d = dialog()!!; d.listView.performItemClick(d.listView.adapter.getView(0, null, d.listView), 0, 0) }
            await("player created") { field("recordingPlayer") != null }
            await("decoded video", 30000) {
                val p = field("recordingPlayer") as ExoPlayer
                p.playbackState == Player.STATE_READY && p.videoSize.width > 0 && p.playerError == null
            }
            main { activity.onBackPressed() }
            await("player closed") { field("recordingPlayer") == null }
            passed.add(stage)

            stage = "onStop stops playback and nothing resumes"
            main { list.performItemClick(list.adapter.getView(0, null, list), 0, 0) }
            await("choose dialog again") { dialog()?.isShowing == true }
            main { val d = dialog()!!; d.listView.performItemClick(d.listView.adapter.getView(0, null, d.listView), 0, 0) }
            await("player again") { field("recordingPlayer") != null }
            runner.sendKeyDownUpSync(KeyEvent.KEYCODE_HOME)
            await("released on stop") { field("recordingPlayer") == null }
            runner.targetContext.startActivity(AdvancedPlaybackActivity.intent(runner.targetContext, AdvancedPlaybackActivity.MODE_RECORDINGS, id, 0)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            await("front again") { activity.window.decorView.hasWindowFocus() }
            SystemClock.sleep(1500)
            need(main { field("recordingPlayer") == null }, "Playback resumed by itself")
            passed.add(stage)

            stage = "HLS manifest rejected, existing recording stays"
            close()
            val existing = recordingFiles(".rec").single().name
            launch(AdvancedPlaybackActivity.MODE_RECORDINGS, id, 2)
            await("manifest channel not recordable") { !button("Record current channel").isEnabled && statusText().contains("HLS/DASH") }
            need("/live.m3u8" !in server.paths, "Manifest channel was fetched")
            close()
            launch(AdvancedPlaybackActivity.MODE_RECORDINGS, id, 3)
            await("disguised channel enabled") { button("Record current channel").isEnabled }
            main { button("Record current channel").performClick() }
            await("confirm shown") { dialog()?.isShowing == true }
            main { dialog()!!.getButton(AlertDialog.BUTTON_POSITIVE).performClick() }
            await("rejected as unsupported", 30000) { statusText().contains("not a direct progressive") }
            need(recordingFiles(".part").isEmpty() && recordingFiles(".rec").map { it.name } == listOf(existing), "Rejection changed saved files")
            passed.add(stage)

            stage = "delete needs confirmation, cleanup"
            val list2 = main { field("savedList") as ListView }
            await("saved listed") { list2.adapter.count == 1 }
            main { list2.performItemClick(list2.adapter.getView(0, null, list2), 0, 0) }
            await("choose dialog") { dialog()?.isShowing == true }
            main { val d = dialog()!!; d.listView.performItemClick(d.listView.adapter.getView(1, null, d.listView), 1, 1) }
            await("confirm delete") { dialog()?.isShowing == true }
            main { dialog()!!.getButton(AlertDialog.BUTTON_NEGATIVE).performClick() }
            runner.waitForIdleSync(); SystemClock.sleep(300)
            need(recordingFiles(".rec").size == 1, "Cancel deleted the recording")
            main { list2.performItemClick(list2.adapter.getView(0, null, list2), 0, 0) }
            await("choose dialog") { dialog()?.isShowing == true }
            main { val d = dialog()!!; d.listView.performItemClick(d.listView.adapter.getView(1, null, d.listView), 1, 1) }
            await("confirm delete") { dialog()?.isShowing == true }
            main { dialog()!!.getButton(AlertDialog.BUTTON_POSITIVE).performClick() }
            await("deleted") { recordingFiles(".rec").isEmpty() && recordingFiles(".part").isEmpty() }
            close()
            passed.add(stage)

            stage = "multiview: explicit start, two streams, mute/switch"
            val before2 = server.paths.size
            launch(AdvancedPlaybackActivity.MODE_MULTIVIEW, id, 0)
            await("picker ready") { (field("pickerRows") as List<*>).size >= 2 }
            need(server.paths.size == before2, "Multiview opened a connection before start")
            need(!button("Start multiview").isEnabled, "Start enabled with one channel")
            val rows = main { (field("pickerRows") as List<*>).map { (it as Channel).name } }
            val betaRow = rows.indexOf("Synthetic Beta"); need(betaRow >= 0, "Second channel missing from picker")
            val picker = main { views().filterIsInstance<ListView>().first() }
            main { picker.performItemClick(picker.adapter.getView(betaRow, null, picker), betaRow, 0) }
            await("start enabled") { button("Start multiview").isEnabled }
            main { button("Start multiview").performClick() }
            await("confirm shown") { dialog()?.isShowing == true }
            need(main { players().all { it == null } } && server.paths.size == before2, "Streams began before confirmation")
            main { dialog()!!.getButton(AlertDialog.BUTTON_POSITIVE).performClick() }
            await("both decode", 40000) {
                (0..1).all { i -> (players()[i] as? ExoPlayer)?.let { it.playbackState == Player.STATE_READY && it.videoSize.width > 0 && it.playerError == null } == true }
            }
            need(main { player(0).volume == 1f && player(1).volume == 0f }, "Secondary is not muted")
            main { views().filterIsInstance<Button>().first { it.text.startsWith("Audio:") }.performClick() }
            need(main { player(0).volume == 0f && player(1).volume == 1f }, "Audio did not switch")
            passed.add(stage)

            stage = "multiview home releases, picker shown, no auto resume"
            runner.sendKeyDownUpSync(KeyEvent.KEYCODE_HOME)
            await("both released") { players().all { it == null } }
            need(main { (field("selectionView") as View).visibility == View.VISIBLE && (field("viewingView") as View).visibility == View.GONE }, "Dead screen: picker hidden")
            runner.targetContext.startActivity(AdvancedPlaybackActivity.intent(runner.targetContext, AdvancedPlaybackActivity.MODE_MULTIVIEW, id, 0)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            await("front again") { activity.window.decorView.hasWindowFocus() }
            val seen = server.paths.size
            SystemClock.sleep(1500)
            need(main { players().all { it == null } } && server.paths.size == seen, "Multiview resumed by itself")
            need(main { (field("selectionView") as View).visibility == View.VISIBLE }, "Picker not visible after return")
            passed.add(stage)

            stage = "multiview stop button releases, then close"
            await("start enabled") { button("Start multiview").isEnabled }
            main { button("Start multiview").performClick() }
            await("confirm shown") { dialog()?.isShowing == true }
            main { dialog()!!.getButton(AlertDialog.BUTTON_POSITIVE).performClick() }
            await("players again", 40000) { players().all { it != null } }
            main { button("Stop multiview").performClick() }
            await("released by stop") { players().all { it == null } }
            need(main { (field("selectionView") as View).visibility == View.VISIBLE }, "Picker not shown after stop")
            close()
            passed.add(stage)

            stage = "real app files unchanged"
            need(realDigest() == before, "Real app-private files changed")
            passed.add(stage)
            result.putString("checks", passed.joinToString("\n"))
            result.putString("summary", "PASS: ${passed.size} advanced playback checks")
            cleanup()
            runner.finish(PrototypeChecks.RESULT_OK, result)
        } catch (error: Throwable) {
            // Stage names only: failure details never include addresses or file names.
            result.putString("passed", passed.joinToString("\n"))
            result.putString("failure", "Stage '$stage' failed: ${error.javaClass.simpleName}: ${error.message?.take(120)}")
            runCatching { if (this::activity.isInitialized) runner.runOnMainSync { activity.finish() } }
            cleanup()
            runner.finish(PrototypeChecks.RESULT_CANCELED, result)
        }
    }

    private fun cleanup() {
        runCatching { server.close() }
        isolatedRoot.deleteRecursively()
    }
}

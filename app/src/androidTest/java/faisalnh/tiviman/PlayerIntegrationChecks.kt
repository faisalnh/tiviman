package faisalnh.tiviman

import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import java.io.File

/** Test-only Activity substitution: all app storage goes to a distinct cache directory. */
class IsolatedToolsActivity : MainActivity() {
    override fun getApplicationContext(): Context = object : ContextWrapper(super.getApplicationContext()) {
        override fun getNoBackupFilesDir(): File = File(baseContext.cacheDir, "isolated-player-tools").apply { mkdirs() }
    }
}

class IsolatedEpgActivity : EpgActivity() {
    override fun getApplicationContext(): Context = object : ContextWrapper(super.getApplicationContext()) {
        override fun getNoBackupFilesDir(): File = File(baseContext.cacheDir, "isolated-player-tools").apply { mkdirs() }
    }
}

/** Full Activity/remote tests. No imports or private streams; storage isolation verified before launch. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlayerIntegrationChecks(private val runner: Instrumentation) {
    private lateinit var activity: MainActivity
    private fun field(name: String) = MainActivity::class.java.getDeclaredField(name).let {
        it.isAccessible = true; it.get(activity)
    }
    private fun <T> main(block: () -> T): T {
        var value: T? = null
        runner.runOnMainSync { value = block() }
        @Suppress("UNCHECKED_CAST") return value as T
    }
    private fun player() = field("player") as ExoPlayer
    private fun state() = field("state") as GuideState
    private fun await(test: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15000
        while (!main(test) && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
        check(main(test))
        runner.waitForIdleSync()
    }
    private fun key(code: Int) {
        runner.sendKeyDownUpSync(code); runner.waitForIdleSync(); SystemClock.sleep(120)
    }
    private fun toolbar(index: Int) {
        // Dialog dismissal animations can retain the input window after onDismiss.
        await { activity.window.decorView.hasWindowFocus() }
        main {
            MainActivity::class.java.getDeclaredField("state").apply { isAccessible = true }
                .set(activity, state().copy(pane = Pane.FULLSCREEN, overlay = false, toolbar = false))
            MainActivity::class.java.getDeclaredMethod("render").apply { isAccessible = true }.invoke(activity)
        }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        repeat(14) {
            val current = main { state().toolbarAction }
            if (current < index) key(KeyEvent.KEYCODE_DPAD_RIGHT)
            if (current > index) key(KeyEvent.KEYCODE_DPAD_LEFT)
        }
        check(main { state().toolbar && state().toolbarAction == index }) {
            main { "Toolbar target=$index actual=${state().toolbarAction} pane=${state().pane} overlay=${state().overlay} toolbar=${state().toolbar}" }
        }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
    }
    private fun choose(index: Int, field: String) {
        val list = main { (this.field(field) as android.app.AlertDialog).listView }
        main { list.setSelection(index); list.requestFocus() }
        runner.waitForIdleSync()
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        await { this.field(field) == null }
    }
    private fun recreate() {
        val monitor = runner.addMonitor(IsolatedToolsActivity::class.java.name, null, false)
        main { activity.recreate() }
        activity = runner.waitForMonitorWithTimeout(monitor, 10000) as? MainActivity ?: error("Recreation failed")
        runner.removeMonitor(monitor)
        await { !(field("loading") as Boolean) && field("player") != null }
    }
    fun run() {
        val result = Bundle()
        var stage = "storage isolation"
        val privateFiles = listOf("playlist.enc", "navigation.enc", "tracks.enc", "display.enc", "history.enc", "favorites.enc", "epg.enc")
            .associateWith { File(runner.targetContext.noBackupFilesDir, it) }
        fun digests() = privateFiles.mapValues { (_, file) -> if (file.exists())
            java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes()).toList() else emptyList() }
        val before = digests()
        try {
            val dir = File(runner.targetContext.cacheDir, "isolated-player-tools").apply { mkdirs() }
            check(dir.canonicalFile != runner.targetContext.noBackupFilesDir.canonicalFile)
            // Refuse unexpected residue rather than silently deleting it.
            val stores = setOf("playlist.enc", "navigation.enc", "tracks.enc", "display.enc", "history.enc", "favorites.enc", "epg.enc")
            val allowed = stores + stores.map { "$it.new" } + stores.map { "$it.bak" } + setOf("retry.mp4", "multi.mp4", "large.mp4", "small.mp4")
            check(dir.listFiles().orEmpty().all { it.isFile && it.name in allowed })
            // Only this mode's known synthetic files may be replaced on retry.
            dir.listFiles().orEmpty().forEach { check(it.delete()) }
            PlaybackSleepTimer.timer.setMinutes(0, SystemClock.elapsedRealtime())
            PlaybackSleepTimer.timer.allowPlayback()
            activity = runner.startActivitySync(Intent(runner.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
            check(activity is IsolatedToolsActivity)
            await { !(field("loading") as Boolean) && field("player") != null }
            check(main { activity.applicationContext.noBackupFilesDir.canonicalFile == dir.canonicalFile })
            check(main { state().catalog == Fixtures.channels })
            await { player().playbackState == Player.STATE_READY }
            stage = "preview tune closes opaque shutter before first replacement frame"
            fun shutter() = (field("video") as androidx.media3.ui.PlayerView)
                .findViewById<android.view.View>(androidx.media3.ui.R.id.exo_shutter)
            await { shutter().visibility != android.view.View.VISIBLE }
            main {
                check((shutter().background as android.graphics.drawable.ColorDrawable).color == android.graphics.Color.BLACK)
                val exo = player()
                MainActivity::class.java.getDeclaredField("state").apply { isAccessible = true }
                    .set(activity, state().copy(pane = Pane.GUIDE, channelId = 1, tunedId = 1))
                MainActivity::class.java.getDeclaredMethod("prepareTuned", java.lang.Long.TYPE)
                    .apply { isAccessible = true }.invoke(activity, 0L)
                check(shutter().visibility == android.view.View.VISIBLE && player() === exo)
                MainActivity::class.java.getDeclaredMethod("render").apply { isAccessible = true }.invoke(activity)
            }
            await { player().isPlaying && shutter().visibility != android.view.View.VISIBLE }
            check(main { (field("videoViewport") as android.view.View).width == 576 })
            main {
                MainActivity::class.java.getDeclaredField("state").apply { isAccessible = true }.set(activity, GuideState())
                MainActivity::class.java.getDeclaredMethod("prepareTuned", java.lang.Long.TYPE)
                    .apply { isAccessible = true }.invoke(activity, 0L)
            }
            await { player().isPlaying && shutter().visibility != android.view.View.VISIBLE }
            stage = "different-resolution preview output replacement and pixel coverage"
            val largeVideo = File(dir, "large.mp4")
            val small = File(dir, "small.mp4")
            SyntheticVideo.create(largeVideo, 1280, 720, 200)
            SyntheticVideo.create(small, 640, 360, 40)
            val outputPlayer = main { player() }
            val initialCatalog = main { state().catalog }
            for ((iteration, sample) in listOf(Triple(largeVideo, 1280, true), Triple(small, 640, false),
                Triple(largeVideo, 1280, true), Triple(small, 640, false)).withIndex()) {
                val (media, resolution, light) = sample
                val oldSurface = main { (field("video") as androidx.media3.ui.PlayerView).videoSurfaceView }
                main {
                    val catalog = initialCatalog.map { if (it.id == 0) it.copy(stream = media.toURI().toString()) else it }
                    MainActivity::class.java.getDeclaredField("state").apply { isAccessible = true }
                        .set(activity, GuideState(catalog = catalog).copy(pane = if (iteration < 2) Pane.GUIDE else Pane.FULLSCREEN))
                    MainActivity::class.java.getDeclaredMethod("render").apply { isAccessible = true }.invoke(activity)
                    MainActivity::class.java.getDeclaredMethod("prepareTuned", java.lang.Long.TYPE)
                        .apply { isAccessible = true }.invoke(activity, 0L)
                    check((field("video") as androidx.media3.ui.PlayerView).videoSurfaceView !== oldSurface)
                    val viewport = field("videoViewport") as android.view.ViewGroup
                    check(viewport.childCount == 2 && viewport.getChildAt(0) === field("video") &&
                        viewport.getChildAt(1) === field("bufferingIndicator"))
                }
                await { player().isPlaying && player().videoSize.width == resolution && shutter().visibility != android.view.View.VISIBLE }
                val bitmap = android.graphics.Bitmap.createBitmap(640, 360, android.graphics.Bitmap.Config.ARGB_8888)
                val copyDeadline = SystemClock.uptimeMillis() + 5000
                var copyResult: Int
                var attempts = 0
                do {
                    val copied = java.util.concurrent.CountDownLatch(1)
                    var resultCode = -1
                    main {
                        android.view.PixelCopy.request((field("video") as androidx.media3.ui.PlayerView).videoSurfaceView as android.view.SurfaceView,
                            bitmap, { resultCode = it; copied.countDown() }, android.os.Handler(android.os.Looper.getMainLooper()))
                    }
                    check(copied.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                        "PixelCopy callback timed out: iteration=$iteration attempt=${attempts + 1}"
                    }
                    copyResult = resultCode
                    attempts++
                    // A freshly attached surface can be ready before its first copyable buffer arrives.
                    if (copyResult == android.view.PixelCopy.ERROR_SOURCE_NO_DATA) SystemClock.sleep(50)
                } while (copyResult == android.view.PixelCopy.ERROR_SOURCE_NO_DATA && SystemClock.uptimeMillis() < copyDeadline)
                check(copyResult == android.view.PixelCopy.SUCCESS) {
                    "PixelCopy failed: result=$copyResult iteration=$iteration attempts=$attempts"
                }
                result.putString("pixelCopy$iteration", "result=$copyResult attempts=$attempts")
                for (x in listOf(10, 320, 629)) for (y in listOf(10, 180, 349)) {
                    val value = android.graphics.Color.red(bitmap.getPixel(x, y))
                    check(if (light) value > 160 else value < 80)
                }
                bitmap.recycle()
                check(main { player() === outputPlayer && player().mediaItemCount == 1 })
            }
            main {
                MainActivity::class.java.getDeclaredField("state").apply { isAccessible = true }.set(activity, GuideState())
                MainActivity::class.java.getDeclaredMethod("prepareTuned", java.lang.Long.TYPE)
                    .apply { isAccessible = true }.invoke(activity, 0L)
            }
            await { player().isPlaying }
            stage = "real buffering indicator with delayed loopback media"
            val server = java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
            val release = java.util.concurrent.CountDownLatch(1)
            val bytes = runner.targetContext.assets.open("fixture.mp4").use { it.readBytes() }
            val serving = Thread {
                try {
                    while (!server.isClosed) server.accept().use { socket ->
                        socket.soTimeout = 10000
                        val reader = socket.getInputStream().bufferedReader()
                        var offset = 0
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                            if (line.startsWith("Range:", ignoreCase = true)) {
                                offset = line.substringAfter("bytes=").substringBefore('-').toIntOrNull() ?: 0
                            }
                        }
                        val output = socket.getOutputStream()
                        val range = if (offset > 0) "Content-Range: bytes $offset-${bytes.lastIndex}/${bytes.size}\r\n" else ""
                        output.write("HTTP/1.1 ${if (offset > 0) "206 Partial Content" else "200 OK"}\r\nContent-Type: video/mp4\r\nAccept-Ranges: bytes\r\n${range}Content-Length: ${bytes.size - offset}\r\nConnection: close\r\n\r\n".toByteArray())
                        output.flush()
                        if (release.await(15, java.util.concurrent.TimeUnit.SECONDS)) {
                            output.write(bytes, offset, bytes.size - offset); output.flush()
                        }
                    }
                } catch (_: Exception) { /* Test reports state assertions, never source diagnostics. */ }
            }.apply { isDaemon = true; start() }
            fun spinner() = field("bufferingIndicator") as android.view.View
            try {
                main {
                    player().setMediaItem(MediaItem.fromUri("http://127.0.0.1:${server.localPort}/fixture.mp4"))
                    player().prepare(); player().play()
                }
                stage = "buffering spinner visible"
                await { player().playbackState == Player.STATE_BUFFERING && spinner().visibility == android.view.View.VISIBLE }
                runner.waitForIdleSync()
                check(main {
                    val viewport = field("videoViewport") as android.view.ViewGroup
                    val indicator = spinner()
                    indicator.parent === viewport && !indicator.isFocusable &&
                        indicator.contentDescription == "Buffering" &&
                        kotlin.math.abs(indicator.left + indicator.width / 2 - viewport.width / 2) <= 1 &&
                        kotlin.math.abs(indicator.top + indicator.height / 2 - viewport.height / 2) <= 1 &&
                        viewport.indexOfChild(indicator) > viewport.indexOfChild(field("video") as android.view.View)
                }) { "Buffering indicator must be centered over the video viewport" }
                release.countDown()
                stage = "buffering spinner hidden after delayed media ready"
                await { player().isPlaying && spinner().visibility != android.view.View.VISIBLE }
                main { player().pause() }
                check(main { spinner().visibility != android.view.View.VISIBLE })
                main { player().stop() }
                check(main { spinner().visibility != android.view.View.VISIBLE })
                main { player().setMediaItem(MediaItem.fromUri("asset:///fixture.mp4")); player().prepare(); player().play() }
                await { player().isPlaying }
            } finally {
                release.countDown(); server.close(); serving.join(2000)
            }
            val original = main { player() }
            val item = main { player().currentMediaItem }
            stage = "playing display and audio selection"
            toolbar(9); choose(DisplayMode.RATIO_4_3.ordinal, "displayDialog")
            check(main { (field("video") as androidx.media3.ui.PlayerView).width == 1440 })
            toolbar(6); choose(1, "trackDialog") // Off, even on this video-only fixture.
            check(main { player().trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_AUDIO) })
            check(main { player() === original && player().currentMediaItem == item && player().playWhenReady })
            stage = "fresh Activity encrypted preference restoration"
            main { activity.finish() }
            await { !(field("started") as Boolean) }
            activity = runner.startActivitySync(Intent(runner.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            await { !(field("loading") as Boolean) && field("player") != null }
            check(main { (field("displayChoices") as Map<*, *>)["0"] == "RATIO_4_3" })
            check(main { player().trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_AUDIO) })
            stage = "timer set, cancel, recreation and expiry"
            toolbar(10); choose(1, "sleepDialog")
            val deadline = PlaybackSleepTimer.timer.deadline
            check(deadline > SystemClock.elapsedRealtime())
            recreate()
            check(PlaybackSleepTimer.timer.deadline == deadline)
            toolbar(10); choose(0, "sleepDialog")
            check(PlaybackSleepTimer.timer.deadline == 0L)
            // Keep production durations unchanged; advance only the test deadline via reflection.
            main {
                SleepTimer::class.java.getDeclaredField("deadline").apply { isAccessible = true }
                    .setLong(PlaybackSleepTimer.timer, SystemClock.elapsedRealtime() - 1)
                MainActivity::class.java.getDeclaredMethod("checkSleepTimer").apply { isAccessible = true }.invoke(activity)
            }
            check(main { !player().playWhenReady && player().playbackState == Player.STATE_IDLE })
            recreate()
            check(main { player().mediaItemCount == 0 && !player().playWhenReady })
            key(KeyEvent.KEYCODE_MEDIA_PLAY)
            await { player().isPlaying }
            stage = "background overdue timer blocks automatic stream preparation"
            toolbar(10); choose(1, "sleepDialog")
            val backgroundDeadline = PlaybackSleepTimer.timer.deadline
            key(KeyEvent.KEYCODE_HOME)
            await { !(field("started") as Boolean) && field("player") == null }
            check(PlaybackSleepTimer.timer.deadline == backgroundDeadline)
            main {
                SleepTimer::class.java.getDeclaredField("deadline").apply { isAccessible = true }
                    .setLong(PlaybackSleepTimer.timer, SystemClock.elapsedRealtime() - 1)
            }
            runner.targetContext.startActivity(Intent(runner.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            await { field("started") as Boolean && field("player") != null }
            check(main { player().mediaItemCount == 0 && !player().playWhenReady })
            key(KeyEvent.KEYCODE_MEDIA_PLAY)
            await { player().isPlaying }
            stage = "Activity error-only Retry recovery"
            val retry = File(dir, "retry.mp4")
            val retryCatalog = main { state().catalog }
            val retryPlayer = main { player() }
            val retrySurface = main { (field("video") as androidx.media3.ui.PlayerView).videoSurfaceView }
            main {
                MainActivity::class.java.getDeclaredField("state").apply { isAccessible = true }.set(activity,
                    state().copy(catalog = retryCatalog.map { if (it.id == state().tunedId)
                        it.copy(stream = retry.toURI().toString()) else it }))
                MainActivity::class.java.getDeclaredMethod("prepareTuned", java.lang.Long.TYPE)
                    .apply { isAccessible = true }.invoke(activity, 0L)
            }
            await { player().playerError != null }
            runner.targetContext.assets.open("fixture.mp4").use { source -> retry.outputStream().use { source.copyTo(it) } }
            main {
                MainActivity::class.java.getDeclaredField("state").apply { isAccessible = true }
                    .set(activity, state().copy(pane = Pane.FULLSCREEN, overlay = false, toolbar = false))
            }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            repeat(3) { key(KeyEvent.KEYCODE_DPAD_RIGHT) }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await { player().playerError == null && player().isPlaying }
            check(main { player() === retryPlayer && player().mediaItemCount == 1 &&
                (field("video") as androidx.media3.ui.PlayerView).videoSurfaceView !== retrySurface })
            main {
                MainActivity::class.java.getDeclaredField("state").apply { isAccessible = true }
                    .set(activity, state().copy(catalog = retryCatalog))
            }
            stage = "channel list focus versus tune and encrypted history"
            toolbar(1)
            val channelDialog = main { field("channelDialog") as android.app.AlertDialog }
            main { channelDialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).performClick() }
            runner.waitForIdleSync(); SystemClock.sleep(200)
            check(main { field("channelSurface") == "groups" && field("channelDialog") != null })
            val groupsList = main { (field("channelDialog") as android.app.AlertDialog).listView }
            main { groupsList.setSelection(2); groupsList.requestFocus() }
            runner.waitForIdleSync()
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            // Selecting a group opens a new list, rather than tuning the first channel.
            await { field("channelSurface") == "channels" && field("channelDialog") != null }
            check(main { field("browseGroup") == 2 })
            check(main { (field("channelDialog") as android.app.AlertDialog).listView.adapter.count ==
                state().catalog.count { it.group == state().groups[2] } })
            check(main { state().tunedId == 0 })
            key(KeyEvent.KEYCODE_BACK)
            toolbar(1)
            main { (field("channelDialog") as android.app.AlertDialog).listView.apply { requestFocus(); setSelection(4) } }
            runner.waitForIdleSync()
            stage = "browser row selected before recreation"
            await { (field("channelDialog") as android.app.AlertDialog).listView.selectedItemPosition == 4 }
            recreate()
            await { field("channelDialog") != null && field("channelSurface") == "channels" }
            stage = "browser row restored after recreation"
            await { (field("channelDialog") as android.app.AlertDialog).listView.selectedItemPosition == 4 }
            stage = "browser explicit row selection after focus restore"
            val tuneBefore = main { state().tunedId }
            val historyBefore = main { field("recentChannels") as List<*> }
            val list = main { (field("channelDialog") as android.app.AlertDialog).listView }
            main { list.requestFocus(); list.setSelection(1) }
            runner.waitForIdleSync()
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            check(main { state().tunedId == tuneBefore && field("recentChannels") == historyBefore })
            main { list.requestFocus(); list.setSelection(1) }
            runner.waitForIdleSync()
            await { list.selectedItemPosition == 1 }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await { field("channelDialog") == null && state().tunedId == 1 }
            check(main { (field("recentChannels") as List<*>).first() == 1 })
            recreate()
            check(main { (field("recentChannels") as List<*>).first() == 1 })
            // History is reachable from the programme panel and Back restores that focus.
            main { MainActivity::class.java.getDeclaredField("state").apply { isAccessible = true }
                .set(activity, state().copy(pane = Pane.FULLSCREEN, overlay = false, toolbar = false)) }
            key(KeyEvent.KEYCODE_DPAD_CENTER); key(KeyEvent.KEYCODE_DPAD_RIGHT); key(KeyEvent.KEYCODE_DPAD_CENTER)
            check(main { field("channelSurface") == "history" })
            key(KeyEvent.KEYCODE_BACK)
            check(main { state().overlay && state().action == 1 && activity.currentFocus?.contentDescription == "History" })
            stage = "favorites toggle, encrypted restore and browser"
            val current = main { state().tunedId }
            toolbar(11)
            check(main { current in (field("favoriteChannels") as Set<*>) })
            check(main { activity.currentFocus?.contentDescription == "Remove from Favorites" })
            recreate()
            check(main { current in (field("favoriteChannels") as Set<*>) })
            stage = "favorites opening channel browser after recreation"
            toolbar(1)
            main { (field("channelDialog") as android.app.AlertDialog).getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick() }
            await { field("channelSurface") == "favorites" }
            await { (field("channelDialog") as android.app.AlertDialog).window!!.decorView.hasWindowFocus() }
            check(main { (field("channelDialog") as android.app.AlertDialog).listView.adapter.count == 1 })
            key(KeyEvent.KEYCODE_BACK)
            stage = "favorites remove after browser Back"
            toolbar(11)
            check(main { (field("favoriteChannels") as Set<*>).isEmpty() })
            stage = "search results focus, recreation and explicit tuning"
            toolbar(0)
            fun descendants(view: android.view.View): List<android.view.View> = listOf(view) +
                if (view is android.view.ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
            fun searchViews() = descendants((field("searchDialog") as android.app.AlertDialog).window!!.decorView)
            main { searchViews().filterIsInstance<android.widget.EditText>().single().setText("no-match-fixture-xyz") }
            stage = "search no-match feedback"
            await { searchViews().filterIsInstance<android.widget.TextView>().any { it.text.toString() == "0 matches" } }
            check(main { state().tunedId == current })
            main { searchViews().filterIsInstance<android.widget.EditText>().single().setText(" ") }
            await { searchViews().filterIsInstance<android.widget.ListView>().single().adapter.count == 0 }
            key(KeyEvent.KEYCODE_BACK)
            // An open platform keyboard consumes the first Back before the dialog.
            if (main { field("searchDialog") != null }) key(KeyEvent.KEYCODE_BACK)
            stage = "search Back focus"
            await { field("searchDialog") == null }
            check(main { state().toolbar && activity.currentFocus?.contentDescription == "Search" })
            toolbar(0)
            stage = "search matching and recreation"
            main { searchViews().filterIsInstance<android.widget.EditText>().single().setText("HORIZON") }
            await { searchViews().filterIsInstance<android.widget.ListView>().single().adapter.count == 1 }
            check(main { state().tunedId == current })
            recreate()
            stage = "search query/results restored after recreation"
            await { field("searchDialog") != null && searchViews().filterIsInstance<android.widget.ListView>().single().adapter?.count == 1 }
            check(main { (field("favoriteChannels") as Set<*>).isEmpty() })
            val resultsList = main { searchViews().filterIsInstance<android.widget.ListView>().single() }
            await { (field("searchDialog") as android.app.AlertDialog).window!!.decorView.hasWindowFocus() }
            main { resultsList.setSelection(0); resultsList.requestFocus() }
            await { resultsList.hasFocus() && resultsList.selectedItemPosition == 0 }
            runner.waitForIdleSync()
            main { resultsList.performItemClick(resultsList.selectedView, 0, resultsList.adapter.getItemId(0)) }
            stage = "search native result activation tunes after recreation (not remote Center coverage)"
            await { field("searchDialog") == null && state().tunedId == 0 }
            stage = "large synthetic browser adapter recycling"
            val large = (0 until 100000).map {
                Channel(it, "Synthetic $it", "Group ${it % 10}", Fixtures.channels[0].programmes)
            }
            main {
                val synthetic = GuideState(catalog = large)
                val rows = synthetic.browserChannels("channels", 0, emptyList(), emptySet())
                check(rows === large)
                val adapter = ChannelBrowserAdapter(activity, rows, 99999)
                check(adapter.count == 100000 && adapter.hasStableIds())
                val parent = android.widget.ListView(activity)
                val first = adapter.getView(0, null, parent)
                val last = adapter.getView(99999, first, parent)
                check(last === first)
                check((last as android.widget.CheckedTextView).text.toString() == "▶ 100000  Synthetic 99999")
                check(adapter.getItemId(99999) == 99999L)
                check(synthetic.browserChannels("channels", 1, emptyList(), emptySet()).size == 10000)
            }
            main { activity.finish() }
            await { !(field("started") as Boolean) }
            stage = "isolated Activity multi-audio catalog setup"
            val multi = File(dir, "multi.mp4")
            SyntheticMultiAudio.create(runner.targetContext, multi)
            val tasksType = Class.forName("faisalnh.tiviman.CatalogTasks")
            val taskInstance = tasksType.getDeclaredField("INSTANCE").apply { isAccessible = true }.get(null)
            val executor = tasksType.getDeclaredMethod("getWorker").apply { isAccessible = true }
                .invoke(taskInstance) as java.util.concurrent.ExecutorService
            val saved = java.util.concurrent.CountDownLatch(1)
            var saveError = false
            executor.execute {
                try {
                    val isolated = object : ContextWrapper(runner.targetContext) {
                        override fun getNoBackupFilesDir() = dir
                    }
                    PlaylistStore(isolated).saveProvider(ProviderConfig(SourceType.M3U, "https://synthetic.invalid"),
                        listOf(Channel(0, "Synthetic dual audio", "Synthetic", Fixtures.channels[0].programmes,
                            android.net.Uri.fromFile(multi).toString()),
                            Channel(1, "Synthetic video", "Synthetic", Fixtures.channels[0].programmes),
                            Channel(2, "Synthetic alternate audio", "Synthetic", Fixtures.channels[0].programmes,
                                android.net.Uri.fromFile(multi).toString())))
                } catch (_: Exception) { saveError = true } finally { saved.countDown() }
            }
            check(saved.await(10, java.util.concurrent.TimeUnit.SECONDS) && !saveError)
            activity = runner.startActivitySync(Intent(runner.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            await { !(field("loading") as Boolean) && field("player") != null && player().isPlaying }
            stage = "remote multi-audio choice and encrypted Activity restoration"
            val options = main { TrackControls.options(player().currentTracks, C.TRACK_TYPE_AUDIO) }
            val target = options.last { it.group != null && it.supported }
            check(options.count { it.group != null && it.supported } == 2)
            val samePlayer = main { player() }
            val sameItem = main { player().currentMediaItem }
            toolbar(6); choose(options.indexOf(target), "trackDialog")
            fun selectedAudio() = player().currentTracks.groups.any { group -> group.type == C.TRACK_TYPE_AUDIO &&
                (0 until group.length).any { group.isTrackSelected(it) && TrackControls.identity(group.getTrackFormat(it)) == target.key } }
            await { selectedAudio() }
            check(main { player() === samePlayer && player().currentMediaItem == sameItem && player().playWhenReady })
            recreate()
            await { selectedAudio() && player().isPlaying }
            check(main { (field("trackChoices") as Map<*, *>)["0:${C.TRACK_TYPE_AUDIO}"] == target.key })
            stage = "sequential Activity channel-scoped display/audio/history/favorites"
            toolbar(9); choose(DisplayMode.RATIO_4_3.ordinal, "displayDialog")
            toolbar(11)
            fun tuneRow(row: Int) {
                val sequentialPlayer = main { player() }
                toolbar(1)
                val rows = main { (field("channelDialog") as android.app.AlertDialog).listView }
                main { rows.requestFocus(); rows.setSelection(row) }
                runner.waitForIdleSync()
                await { rows.selectedItemPosition == row }
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                await { state().tunedId == row && field("channelDialog") == null && player().isPlaying }
                check(main { player() === sequentialPlayer && player().mediaItemCount == 1 && player().playerError == null })
            }
            tuneRow(1)
            check(main { (field("displayChoices") as Map<*, *>)["1"] == null })
            check(main { player().trackSelectionParameters.overrides.isEmpty() })
            toolbar(9); choose(DisplayMode.REDUCED.ordinal, "displayDialog")
            tuneRow(2)
            val alternate = main { TrackControls.options(player().currentTracks, C.TRACK_TYPE_AUDIO).first { it.group != null && it.supported } }
            val alternateIndex = main { TrackControls.options(player().currentTracks, C.TRACK_TYPE_AUDIO).indexOfFirst { it.key == alternate.key } }
            toolbar(6); choose(alternateIndex, "trackDialog")
            await { player().currentTracks.groups.any { group -> group.type == C.TRACK_TYPE_AUDIO &&
                (0 until group.length).any { group.isTrackSelected(it) && TrackControls.identity(group.getTrackFormat(it)) == alternate.key } } }
            tuneRow(0)
            await { selectedAudio() }
            check(main { (field("displayChoices") as Map<*, *>)["0"] == "RATIO_4_3" &&
                (field("video") as androidx.media3.ui.PlayerView).width == 1440 })
            check(main { field("recentChannels") == listOf(0, 2, 1) && field("favoriteChannels") == setOf(0) })
            stage = "same-channel versus another-channel sleep-expiry behavior"
            main {
                SleepTimer::class.java.getDeclaredField("deadline").apply { isAccessible = true }
                    .setLong(PlaybackSleepTimer.timer, SystemClock.elapsedRealtime() - 1)
                MainActivity::class.java.getDeclaredMethod("checkSleepTimer").apply { isAccessible = true }.invoke(activity)
            }
            toolbar(1)
            val sameRows = main { (field("channelDialog") as android.app.AlertDialog).listView }
            main { sameRows.requestFocus(); sameRows.setSelection(0) }
            runner.waitForIdleSync()
            await { sameRows.selectedItemPosition == 0 }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            check(main { PlaybackSleepTimer.timer.expired && !player().playWhenReady && player().playbackState == Player.STATE_IDLE })
            tuneRow(1)
            check(!PlaybackSleepTimer.timer.expired)
            check(main { (field("video") as androidx.media3.ui.PlayerView).width == DisplayMode.REDUCED.size(1920, 1080).width })
            recreate()
            await { player().isPlaying }
            check(main { field("recentChannels") == listOf(1, 0, 2) && field("favoriteChannels") == setOf(0) })
            check(main { (field("trackChoices") as Map<*, *>)["0:${C.TRACK_TYPE_AUDIO}"] == target.key &&
                (field("trackChoices") as Map<*, *>)["2:${C.TRACK_TYPE_AUDIO}"] == alternate.key })
            stage = "channel options summary, Back, recreation, favorite and scoped reset"
            tuneRow(0)
            toolbar(8); choose(1, "trackDialog")
            val optionsPlayer = main { player() }
            val optionsItem = main { player().currentMediaItem }
            val historyBeforeReset = main { field("recentChannels") }
            val catalogBeforeReset = main { state().catalog }
            toolbar(12)
            fun optionsDialog() = field("optionsDialog") as android.app.AlertDialog
            fun summary() = optionsDialog().findViewById<android.widget.TextView>(android.R.id.message).text.toString()
            check(main { summary().contains("Prototype controls") && summary().contains("Display: 4:3") &&
                summary().contains("Captions: Off") && summary().contains(target.label) && summary().contains("Favorite: Yes") })
            key(KeyEvent.KEYCODE_BACK)
            check(main { field("optionsDialog") == null && state().toolbarAction == 12 &&
                activity.currentFocus?.contentDescription == "Channel options" })
            toolbar(12)
            recreate()
            await { field("optionsDialog") != null && selectedAudio() }
            check(main { summary().contains("Display: 4:3") && summary().contains("Captions: Off") })
            val recreatedOptionsPlayer = main { player() }
            val recreatedOptionsItem = main { player().currentMediaItem }
            fun optionButton(which: Int) {
                main { optionsDialog().getButton(which).requestFocus() }
                key(KeyEvent.KEYCODE_DPAD_CENTER)
            }
            optionButton(android.app.AlertDialog.BUTTON_NEUTRAL)
            check(main { field("favoriteChannels") == emptySet<Int>() && summary().contains("Favorite: No") })
            optionButton(android.app.AlertDialog.BUTTON_NEUTRAL)
            check(main { field("favoriteChannels") == setOf(0) })
            optionButton(android.app.AlertDialog.BUTTON_POSITIVE)
            await { player().trackSelectionParameters.overrides.isEmpty() &&
                player().trackSelectionParameters.disabledTrackTypes.isEmpty() &&
                (field("video") as androidx.media3.ui.PlayerView).width == 1920 }
            check(main { summary().contains("Audio: Auto") && summary().contains("Captions: Auto") &&
                summary().contains("Display: Normal") && field("recentChannels") == historyBeforeReset &&
                state().catalog == catalogBeforeReset && field("favoriteChannels") == setOf(0) &&
                player() === recreatedOptionsPlayer && player().currentMediaItem == recreatedOptionsItem &&
                player().playWhenReady && !PlaybackSleepTimer.timer.expired })
            check(optionsPlayer !== recreatedOptionsPlayer && optionsItem == recreatedOptionsItem)
            recreate()
            await { field("optionsDialog") != null && player().isPlaying }
            check(main { (field("trackChoices") as Map<*, *>).keys.none { it.toString().startsWith("0:") } &&
                (field("trackChoices") as Map<*, *>)["2:${C.TRACK_TYPE_AUDIO}"] == alternate.key &&
                (field("displayChoices") as Map<*, *>)["0"] == null &&
                (field("displayChoices") as Map<*, *>)["1"] == "REDUCED" && field("favoriteChannels") == setOf(0) })
            key(KeyEvent.KEYCODE_BACK)
            stage = "Settings navigation, disabled controls and nested recreation"
            toolbar(13)
            fun settingsDialog() = field("settingsDialog") as android.app.AlertDialog
            val settingsPlayer = main { player() }
            val settingsItem = main { player().currentMediaItem }
            check(main {
                val adapter = settingsDialog().listView.adapter
                adapter.count == 8 && (0 until adapter.count).all { adapter.isEnabled(it) }
            })
            fun settingsRoute(row: Int) {
                val list = main { settingsDialog().listView }
                main { list.requestFocus(); list.setSelection(row) }
                await { list.selectedItemPosition == row }
                key(KeyEvent.KEYCODE_DPAD_CENTER)
            }
            listOf(0 to "trackDialog", 1 to "trackDialog", 2 to "trackDialog",
                3 to "displayDialog", 4 to "sleepDialog", 5 to "audioDelayDialog", 6 to "optionsDialog").forEach { (row, name) ->
                settingsRoute(row)
                check(main { field(name) != null && field("settingsDialog") != null })
                key(KeyEvent.KEYCODE_BACK)
                check(main { field(name) == null && settingsDialog().listView.selectedItemPosition == row &&
                    player() === settingsPlayer && player().currentMediaItem == settingsItem })
            }
            settingsRoute(3)
            recreate()
            await { field("settingsDialog") != null && field("displayDialog") != null }
            choose(DisplayMode.RATIO_4_3.ordinal, "displayDialog")
            await { (field("video") as androidx.media3.ui.PlayerView).width == 1440 }
            check(main { settingsDialog().listView.selectedItemPosition == 3 })
            recreate()
            await { field("settingsDialog") != null && player().isPlaying }
            check(main { (field("displayChoices") as Map<*, *>)["0"] == "RATIO_4_3" &&
                settingsDialog().listView.selectedItemPosition == 3 })
            // Intercept setup launch: verify routing without exposing real import/removal controls.
            val setupMonitor = runner.addMonitor(SettingsActivity::class.java.name, null, true)
            try {
                settingsRoute(7)
                check(setupMonitor.hits == 1)
            } finally { runner.removeMonitor(setupMonitor) }
            // The blocked launch has no lifecycle return; clear only its pending test navigation.
            main {
                MainActivity::class.java.getDeclaredField("pendingSettingsDialog").apply { isAccessible = true }.setBoolean(activity, false)
                MainActivity::class.java.getDeclaredField("setupOpen").apply { isAccessible = true }.setBoolean(activity, false)
                MainActivity::class.java.getDeclaredField("suppressSetupPlayback").apply { isAccessible = true }.setBoolean(activity, false)
            }
            toolbar(13)
            key(KeyEvent.KEYCODE_BACK)
            check(main { field("settingsDialog") == null && state().toolbarAction == 13 &&
                activity.currentFocus?.contentDescription == "Settings" })
            stage = "fullscreen quick tune and persistent Favorites group"
            toolbar(1)
            main { (field("channelDialog") as android.app.AlertDialog)
                .getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick() }
            await { field("channelSurface") == "favorites" }
            choose(0, "channelDialog") // Same tuned favorite still changes active group.
            check(main { state().group == state().favoritesGroup && state().tunedId == 0 })
            recreate()
            check(main { state().group == state().favoritesGroup && state().channels.map { it.id } == listOf(0) })
            val quickPlayer = main { player() }
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            check(main { state().tunedId == 0 && player() === quickPlayer })
            // Choose All via the actual group selector, then exercise Left/Right tuning.
            toolbar(1)
            main { (field("channelDialog") as android.app.AlertDialog)
                .getButton(android.app.AlertDialog.BUTTON_NEUTRAL).performClick() }
            val quickGroupsList = main { (field("channelDialog") as android.app.AlertDialog).listView }
            main { quickGroupsList.requestFocus(); quickGroupsList.setSelection(0) }
            await { quickGroupsList.selectedItemPosition == 0 }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await { field("channelSurface") == "channels" }
            choose(0, "channelDialog")
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            await { state().tunedId == 1 && player().isPlaying }
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            await { state().tunedId == 2 && player().isPlaying }
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            check(main { state().tunedId == 2 })
            toolbar(11) // Add 2, retain existing favorite 0.
            toolbar(1)
            main { (field("channelDialog") as android.app.AlertDialog)
                .getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick() }
            await { field("channelSurface") == "favorites" }
            choose(1, "channelDialog")
            key(KeyEvent.KEYCODE_DPAD_LEFT)
            await { state().tunedId == 0 && player().isPlaying }
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            await { state().tunedId == 2 && player().isPlaying }
            check(main { state().channels.map { it.id } == listOf(0, 2) && player() === quickPlayer &&
                field("recentChannels") == listOf(2, 0, 1) })
            recreate()
            check(main { state().group == state().favoritesGroup && state().channels.map { it.id } == listOf(0, 2) })
            stage = "removing tuned Favorites through empty group and rapid tune burst"
            val removalPlayer = main { player() }
            toolbar(11) // Remove tuned 2; playback stays on it until explicit switch.
            check(main { state().tunedId == 2 && state().channels.map { it.id } == listOf(0) && player() === removalPlayer })
            key(KeyEvent.KEYCODE_BACK)
            key(KeyEvent.KEYCODE_DPAD_LEFT)
            await { state().tunedId == 0 && player().isPlaying }
            toolbar(11) // Remove last member; arrows and Center must not tune.
            key(KeyEvent.KEYCODE_BACK)
            val emptySurface = main { (field("video") as androidx.media3.ui.PlayerView).videoSurfaceView }
            key(KeyEvent.KEYCODE_DPAD_RIGHT); key(KeyEvent.KEYCODE_DPAD_LEFT)
            check(main { state().channels.isEmpty() && state().tunedId == 0 && player() === removalPlayer &&
                (field("video") as androidx.media3.ui.PlayerView).videoSurfaceView === emptySurface })
            recreate()
            check(main { state().group == state().favoritesGroup && state().channels.isEmpty() })
            main {
                MainActivity::class.java.getDeclaredField("state").apply { isAccessible = true }
                    .set(activity, state().copy(group = 0, focusedGroup = 0, pane = Pane.FULLSCREEN, overlay = false, toolbar = false))
                val navigate = MainActivity::class.java.getDeclaredMethod("navigate", RemoteKey::class.java).apply { isAccessible = true }
                repeat(5) { navigate.invoke(activity, RemoteKey.RIGHT); navigate.invoke(activity, RemoteKey.LEFT) }
            }
            await { state().tunedId == 0 && player().isPlaying }
            check(main {
                val viewport = field("videoViewport") as android.view.ViewGroup
                player().mediaItemCount == 1 && viewport.childCount == 2 &&
                    viewport.getChildAt(0) === field("video") && viewport.getChildAt(1) === field("bufferingIndicator")
            })
            stage = "explicit system PiP single player and fullscreen return"
            val supportsPip = main { PictureInPicturePolicy.available(android.os.Build.VERSION.SDK_INT,
                activity.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)) }
            if (supportsPip) {
                val pipActivity = activity
                val pipPlayer = main { player() }
                val pipSurface = main { (field("video") as androidx.media3.ui.PlayerView).videoSurfaceView }
                toolbar(4)
                await { activity.isInPictureInPictureMode && player().isPlaying }
                check(main { player() === pipPlayer && (field("video") as androidx.media3.ui.PlayerView).videoSurfaceView === pipSurface &&
                    (field("chrome") as android.view.View).visibility == android.view.View.GONE &&
                    (field("guide") as android.view.View).visibility == android.view.View.GONE &&
                    (field("fullscreenChrome") as android.view.View).visibility == android.view.View.GONE })
                runner.targetContext.startActivity(Intent(runner.targetContext, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                await { !activity.isInPictureInPictureMode && player().isPlaying }
                check(main { activity === pipActivity && player() === pipPlayer && state().pane == Pane.FULLSCREEN &&
                    (field("video") as androidx.media3.ui.PlayerView).videoSurfaceView === pipSurface })
            }
            stage = "XMLTV parse encrypted persistence Activity reload and programme rendering"
            val epgVersion = main { field("revision") as String }
            val epgNow = Epg.minute()
            fun stamp(minute: Int) = java.text.SimpleDateFormat("yyyyMMddHHmmss Z", java.util.Locale.ROOT).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }.format(java.util.Date(minute * 60000L))
            val epgName = main { state().catalog.first().name }
            val guide = Epg.parse("""<tv><channel id="synthetic"><display-name>$epgName</display-name></channel>
                <programme channel="synthetic" start="${stamp(epgNow - 10)}" stop="${stamp(epgNow + 10)}"><title>XMLTV current</title><desc>Synthetic description</desc></programme>
                <programme channel="synthetic" start="${stamp(epgNow + 10)}" stop="${stamp(epgNow + 70)}"><title>XMLTV next</title></programme></tv>""")
            val epgStore = main { PlaylistStore(activity.applicationContext) }
            epgStore.saveEpg(epgVersion, guide)
            check(epgStore.loadEpg("different-revision") == null)
            check(epgStore.loadEpg(epgVersion) == guide)
            stage = "isolated EPG import Activity loopback download and failure preserves guide"
            val epgServer = java.net.ServerSocket(0, 2, java.net.InetAddress.getByName("127.0.0.1"))
            val xmlText = """<tv><channel id="synthetic"><display-name>$epgName</display-name></channel>
                <programme channel="synthetic" start="${stamp(epgNow - 10)}" stop="${stamp(epgNow + 10)}"><title>XMLTV current</title><desc>Synthetic description</desc></programme>
                <programme channel="synthetic" start="${stamp(epgNow + 10)}" stop="${stamp(epgNow + 70)}"><title>XMLTV next</title></programme></tv>"""
            val epgServing = Thread {
                try {
                    repeat(2) { request -> epgServer.accept().use { socket ->
                        socket.soTimeout = 5000
                        val reader = socket.getInputStream().bufferedReader()
                        while (!reader.readLine().isNullOrEmpty()) { }
                        val payload = (if (request == 0) xmlText else "<tv>malformed").toByteArray()
                        socket.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            write(payload); flush()
                        }
                    } }
                } catch (_: Exception) { }
            }.apply { isDaemon = true; start() }
            val epgScreen = runner.startActivitySync(Intent(runner.targetContext, EpgActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as EpgActivity
            check(epgScreen is IsolatedEpgActivity)
            check(main { epgScreen.applicationContext.noBackupFilesDir.canonicalFile == dir.canonicalFile })
            fun epgViews() = descendants(epgScreen.window.decorView)
            try {
                main {
                    epgViews().filterIsInstance<android.widget.EditText>().single().setText("http://127.0.0.1:${epgServer.localPort}/guide.xml")
                    epgViews().filterIsInstance<android.widget.Button>().single { it.text == "Import XMLTV URL" }.performClick()
                }
                await { epgViews().filterIsInstance<android.widget.TextView>().any { it.text.startsWith("EPG saved") } }
                val goodBytes = File(dir, "epg.enc").readBytes()
                // Relaunch to retry with malformed data; success intentionally disables further imports.
                main { epgScreen.finish() }
                val failedScreen = runner.startActivitySync(Intent(runner.targetContext, EpgActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as EpgActivity
                check(failedScreen is IsolatedEpgActivity)
                main {
                    val views = descendants(failedScreen.window.decorView)
                    views.filterIsInstance<android.widget.EditText>().single().setText("http://127.0.0.1:${epgServer.localPort}/invalid.xml")
                    views.filterIsInstance<android.widget.Button>().single { it.text == "Import XMLTV URL" }.performClick()
                }
                await { descendants(failedScreen.window.decorView).filterIsInstance<android.widget.TextView>().any { it.text.startsWith("EPG import failed") } }
                check(File(dir, "epg.enc").readBytes().contentEquals(goodBytes))
                main { failedScreen.finish() }
            } finally { epgServer.close(); epgServing.join(1000); main { if (!epgScreen.isFinishing) epgScreen.finish() } }
            await { field("started") as Boolean && field("player") != null }
            recreate()
            await { state().realEpg && state().catalog.first().programmes.first().title == "XMLTV current" && player().isPlaying }
            check(main { state().catalog.first().programmes[1].title == "XMLTV next" })
            val runningEpgPlayer = main { player() }
            main {
                MainActivity::class.java.getDeclaredField("state").apply { isAccessible = true }
                    .set(activity, state().copy(nowMinute = epgNow - 1))
                MainActivity::class.java.getDeclaredMethod("refreshEpg").apply { isAccessible = true }.invoke(activity)
            }
            await { state().nowMinute == Epg.minute() }
            check(main { player() === runningEpgPlayer && player().mediaItemCount == 1 })
            stage = "synthetic atomic Xtream refresh preserves stable IDs and scoped stores"
            val refreshBefore = epgStore.snapshot()
            val refreshCatalog = refreshBefore.channels!!.distinctBy { it.stream }
            val refreshConfig = ProviderConfig(SourceType.XTREAM, "https://example.invalid", "synthetic", "synthetic")
            epgStore.refreshXtream(epgVersion, refreshConfig, refreshCatalog.reversed(), guide)
            val refreshed = epgStore.snapshot()
            val expectedIds = refreshBefore.channels!!.associate { it.stream to it.id }
            check(refreshed.revision == epgVersion && refreshed.channels!!.map { it.id } == refreshCatalog.reversed().map { expectedIds.getValue(it.stream) })
            check(refreshed.tracks == refreshBefore.tracks && refreshed.display == refreshBefore.display &&
                refreshed.favorites == refreshBefore.favorites && refreshed.history == refreshBefore.history)
            check(epgStore.loadEpg(epgVersion)?.events == guide.events)
            toolbar(13)
            val epgMonitor = runner.addMonitor(SettingsActivity::class.java.name, null, true)
            try {
                val list = main { (field("settingsDialog") as android.app.AlertDialog).listView }
                main { list.requestFocus(); list.setSelection(7) }
                await { list.selectedItemPosition == 7 }
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                check(epgMonitor.hits == 1)
            } finally { runner.removeMonitor(epgMonitor) }
            main { activity.finish() }
            await { !(field("started") as Boolean) }
            // Drain serial preference/navigation writes before comparing and cleaning synthetic output.
            val queue = java.util.concurrent.CountDownLatch(1)
            val tasks = Class.forName("faisalnh.tiviman.CatalogTasks")
            val instance = tasks.getDeclaredField("INSTANCE").apply { isAccessible = true }.get(null)
            val worker = tasks.getDeclaredMethod("getWorker").apply { isAccessible = true }.invoke(instance) as java.util.concurrent.ExecutorService
            worker.execute { queue.countDown() }; check(queue.await(5, java.util.concurrent.TimeUnit.SECONDS))
            check(digests() == before)
            dir.listFiles().orEmpty().forEach { check(it.isFile && it.delete()) }
            result.putString("summary", "PASS: XMLTV Android parsing/encrypted revision scoping/isolated import Activity loopback HTTP/success and malformed preservation/Activity reload/current-next/no-retune refresh/Settings route; quick switching/Favorites, Retry, PiP ${if (supportsPip) "entry/fullscreen return" else "gating"}, PixelCopy and prior regressions. Private files unchanged; no provider fetch.")
            runner.finish(-1, result)
        } catch (error: Throwable) {
            result.putString("failure", "Isolated integration failed at $stage; synthetic residue retained, private details suppressed")
            if (::activity.isInitialized) main { activity.finish() }
            result.putString("location", error.stackTrace.firstOrNull { it.className == javaClass.name }?.let {
                "${it.methodName}:${it.lineNumber} (${error.javaClass.simpleName})"
            } ?: error.javaClass.simpleName)
            if (error.message?.startsWith("Toolbar target=") == true) result.putString("navigation", error.message)
            runner.finish(0, result)
        }
    }
}

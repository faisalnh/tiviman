package faisalnh.tiviman

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/** Opt-in, no imports or source switching. Only the existing tuned stream is opened. */
class PlayerMenuChecks(private val runner: Instrumentation) {
    private lateinit var activity: MainActivity
    private fun field(name: String): Any? = MainActivity::class.java.getDeclaredField(name).let {
        it.isAccessible = true; it.get(activity)
    }
    private fun <T> main(block: () -> T): T {
        // Nested main { } blocks are already on the main thread, where runOnMainSync throws.
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return block()
        // Rethrow on the instrumentation thread so a failed check is reported, not a process crash.
        var outcome: Result<T>? = null
        runner.runOnMainSync { outcome = runCatching(block) }
        return checkNotNull(outcome).getOrThrow()
    }
    private fun state() = main { field("state") as GuideState }
    private fun player() = field("player") as ExoPlayer
    private fun key(code: Int) {
        runner.sendKeyDownUpSync(code); runner.waitForIdleSync(); SystemClock.sleep(100)
    }
    private fun ready() {
        val deadline = SystemClock.uptimeMillis() + 20000
        while (main { field("loading") as Boolean } && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
        check(main { !(field("loading") as Boolean) })
        runner.waitForIdleSync()
    }
    private fun focus(expected: String) {
        // The toolbar brings the focused item into view with smoothScrollTo, which waitForIdleSync
        // does not wait for. Let the scroll settle before checking geometry.
        val deadline = SystemClock.uptimeMillis() + 1000
        var last = Int.MIN_VALUE
        while (SystemClock.uptimeMillis() < deadline) {
            val now = main { activity.currentFocus?.let { v ->
                generateSequence(v.parent) { it.parent }.filterIsInstance<android.widget.HorizontalScrollView>().firstOrNull()?.scrollX
            } ?: 0 }
            if (now == last) break
            last = now; SystemClock.sleep(50)
        }
        checkFocus(expected)
    }
    private fun checkFocus(expected: String) = main {
        val view = activity.currentFocus ?: error("No focus")
        check(view.isShown && view.contentDescription?.startsWith(expected) == true) {
            "Expected focus $expected, got ${view.contentDescription}"
        }
        val rect = Rect()
        check(view.getGlobalVisibleRect(rect) && rect.width() == view.width && rect.height() == view.height) {
            val scroll = generateSequence(view.parent) { it.parent }.filterIsInstance<android.widget.HorizontalScrollView>().firstOrNull()
            val viewport = Rect().also { r -> scroll?.getGlobalVisibleRect(r) }
            "Focused $expected is clipped: visible $rect, size ${view.width}x${view.height}, " +
                "scrollX ${scroll?.scrollX}, viewport $viewport, padding ${scroll?.paddingLeft}/${scroll?.paddingRight}"
        }
    }
    fun run() {
        val report = Bundle()
        var stage = "launch"
        var listener: Player.Listener? = null
        try {
            val catalog = java.io.File(runner.targetContext.noBackupFilesDir, "playlist.enc")
            fun digest() = if (catalog.exists()) java.security.MessageDigest.getInstance("SHA-256")
                .digest(catalog.readBytes()) else byteArrayOf()
            val originalDigest = digest()
            activity = runner.startActivitySync(Intent(runner.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
            ready()
            key(KeyEvent.KEYCODE_MEDIA_PAUSE)
            // Held Back enters fullscreen without tuning the focused channel.
            runner.sendKeySync(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0))
            SystemClock.sleep(800)
            runner.sendKeySync(KeyEvent(0, 800, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0))
            runner.waitForIdleSync()
            val initial = state()
            val originalPlayer = main { player() }
            val originalMedia = main { player().currentMediaItem }
            val pausedPosition = main { player().currentPosition }
            var transitions = 0
            listener = object : Player.Listener {
                override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) { transitions++ }
            }
            main { player().addListener(listener!!) }
            stage = "programme panel"
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            check(state().overlay && !state().toolbar)
            focus("TV guide")
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            focus("History")
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            check(main { field("channelDialog") != null })
            key(KeyEvent.KEYCODE_BACK)
            check(state().overlay && !state().toolbar && state().action == 1)
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            focus("Schedule")
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            check(main { (field("scheduleOverlay") as android.view.View).visibility == android.view.View.VISIBLE })
            SystemClock.sleep(7500)
            check(state().overlay && state().tunedId == initial.tunedId)
            key(KeyEvent.KEYCODE_BACK)
            check(state().action == 2 && state().overlay && !state().toolbar)
            focus("Schedule")
            key(KeyEvent.KEYCODE_DPAD_LEFT)
            stage = "toolbar overflow and unavailable actions"
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            PlayerToolbar.entries.forEachIndexed { index, name ->
                check(state().toolbarAction == index)
                val available = index != 4 || PictureInPicturePolicy.available(android.os.Build.VERSION.SDK_INT,
                    activity.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE))
                val safeToOpen = index !in listOf(2, 3, 4, 11)
                val caption = if (index == 11 && main { state().tunedId in (field("favoriteChannels") as Set<*>) }) "Remove from Favorites" else name
                focus(if (available) caption else "$name, unavailable")
                main { check(activity.currentFocus?.isClickable == available) }
                // Saved-account checks never open recording/multiview/PiP or change favorites.
                if (safeToOpen) key(KeyEvent.KEYCODE_DPAD_CENTER)
                if (available && safeToOpen) {
                    check(main { field(when (index) { 0 -> "searchDialog"; 1 -> "channelDialog"; 9 -> "displayDialog"; 10 -> "sleepDialog"; 7 -> "audioDelayDialog"; 12 -> "optionsDialog"; 13 -> "settingsDialog"; else -> "trackDialog" }) != null })
                    key(KeyEvent.KEYCODE_BACK)
                    if (index == 0 && main { field("searchDialog") != null }) key(KeyEvent.KEYCODE_BACK)
                    focus(name)
                }
                check(state().toolbar && state().tunedId == initial.tunedId)
                key(KeyEvent.KEYCODE_DPAD_RIGHT)
            }
            check(state().toolbarAction == 13)
            key(KeyEvent.KEYCODE_DPAD_UP)
            focus("History")
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            focus("Settings")
            repeat(20) { key(KeyEvent.KEYCODE_DPAD_LEFT) }
            focus("Search")
            stage = "dismissal, guide restoration, timeout"
            key(KeyEvent.KEYCODE_BACK)
            check(!state().overlay && !state().toolbar && state().pane == Pane.FULLSCREEN)
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            focus("TV guide")
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            check(state().pane == Pane.GUIDE && state().channelId == initial.channelId &&
                state().programmeIndex == initial.programmeIndex)
            // Enter without a channel activation; focused and tuned channels can differ.
            main {
                val f = MainActivity::class.java.getDeclaredField("state").apply { isAccessible = true }
                f.set(activity, (field("state") as GuideState).move(RemoteKey.LONG_BACK))
            }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            SystemClock.sleep(7500); runner.waitForIdleSync()
            check(!state().overlay && !state().toolbar)
            stage = "single playback session"
            main {
                check(player() === originalPlayer && player().currentMediaItem == originalMedia)
                check(transitions == 0 && !player().playWhenReady)
                check(kotlin.math.abs(player().currentPosition - pausedPosition) < 200)
                player().removeListener(listener!!)
            }
            check(originalDigest.contentEquals(digest()))
            stage = "recreation and native focus restoration"
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            repeat(13) { key(KeyEvent.KEYCODE_DPAD_RIGHT) }
            val monitor = runner.addMonitor(MainActivity::class.java.name, null, false)
            main { activity.recreate() }
            activity = runner.waitForMonitorWithTimeout(monitor, 10000) as? MainActivity
                ?: error("Recreation timeout")
            runner.removeMonitor(monitor)
            ready(); SystemClock.sleep(300); runner.waitForIdleSync()
            check(state().overlay && state().toolbar && state().toolbarAction == 13)
            focus("Settings")
            check(main { !player().playWhenReady })
            stage = "track dialog recreation"
            repeat(5) { key(KeyEvent.KEYCODE_DPAD_LEFT) }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            check(main { field("trackDialog") != null })
            val dialogMonitor = runner.addMonitor(MainActivity::class.java.name, null, false)
            main { activity.recreate() }
            activity = runner.waitForMonitorWithTimeout(dialogMonitor, 10000) as? MainActivity
                ?: error("Dialog recreation timeout")
            runner.removeMonitor(dialogMonitor)
            ready(); SystemClock.sleep(300); runner.waitForIdleSync()
            check(main { field("trackDialog") != null && field("trackDialogType") == androidx.media3.common.C.TRACK_TYPE_TEXT })
            key(KeyEvent.KEYCODE_BACK)
            focus("Closed captions")
            stage = "display dialog recreation"
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            check(main { field("displayDialog") != null })
            val displayMonitor = runner.addMonitor(MainActivity::class.java.name, null, false)
            main { activity.recreate() }
            activity = runner.waitForMonitorWithTimeout(displayMonitor, 10000) as? MainActivity
                ?: error("Display dialog recreation timeout")
            runner.removeMonitor(displayMonitor)
            ready(); SystemClock.sleep(300); runner.waitForIdleSync()
            check(main { field("displayDialog") != null })
            key(KeyEvent.KEYCODE_BACK)
            focus("Display mode")
            stage = "sleep dialog recreation"
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            check(main { field("sleepDialog") != null })
            val sleepMonitor = runner.addMonitor(MainActivity::class.java.name, null, false)
            main { activity.recreate() }
            activity = runner.waitForMonitorWithTimeout(sleepMonitor, 10000) as? MainActivity
                ?: error("Sleep dialog recreation timeout")
            runner.removeMonitor(sleepMonitor)
            ready(); SystemClock.sleep(300); runner.waitForIdleSync()
            check(main { field("sleepDialog") != null })
            key(KeyEvent.KEYCODE_BACK)
            focus("Sleep timer")
            check(originalDigest.contentEquals(digest()))
            main { activity.finish() }
            report.putString("summary", "PASS: 14 toolbar entries; track/caption/display/sleep dialog open/Back/recreation; overflow/native focus; Up/Down memory; guide restoration; timeout; no menu retune/media transition/player replacement. Catalog unchanged. No private track/display/timer selections changed.")
            runner.finish(-1, report)
        } catch (error: Throwable) {
            // This suite may run on a saved account, whose channel names stay out of reports.
            val dir = runner.targetContext.noBackupFilesDir
            val saved = java.io.File(dir, "playlist.enc").exists() || java.io.File(dir, "playlists.enc").exists()
            report.putString("failure", "Player menu check failed at $stage" +
                if (saved) "; private details suppressed" else ": ${error.message}")
            if (::activity.isInitialized) main { activity.finish() }
            runner.finish(0, report)
        }
    }
}

package faisalnh.tiviman

import android.app.Dialog
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.KeyEvent
import android.view.WindowManager
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import java.io.File
import java.security.MessageDigest

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class DesignCaptureChecks(private val runner: Instrumentation) {
    private lateinit var activity: MainActivity
    private fun field(name: String) = MainActivity::class.java.getDeclaredField(name).let {
        it.isAccessible = true; it.get(activity)
    }
    private fun <T> main(block: () -> T): T {
        var value: T? = null
        runner.runOnMainSync { value = block() }
        @Suppress("UNCHECKED_CAST") return value as T
    }
    private fun state() = field("state") as GuideState
    private fun await(test: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15000
        while (!main(test) && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
        check(main(test)) { "Timed out waiting for synthetic capture state" }
        runner.waitForIdleSync()
    }
    private fun key(code: Int) {
        runner.sendKeyDownUpSync(code); runner.waitForIdleSync(); SystemClock.sleep(120)
    }
    private fun toolbar(index: Int) {
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
        check(main { state().toolbar && state().toolbarAction == index }) { "Toolbar target not reached" }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
    }
    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        runner.uiAutomation.executeShellCommand(command)
    ).bufferedReader().use { it.readText() }

    fun run() {
        val result = Bundle()
        var stage = "storage isolation"
        var directory = "not selected"
        var captures = 0
        var failure: Throwable? = null
        var settingsScreen: SettingsActivity? = null
        val stores = setOf("playlist.enc", "navigation.enc", "tracks.enc", "display.enc",
            "history.enc", "favorites.enc", "epg.enc", "playlists.enc")
        val real = runner.targetContext.noBackupFilesDir
        fun digests(): Map<String, List<Byte>?> = stores.associateWith { name ->
            File(real, name).let { file -> if (file.exists())
                MessageDigest.getInstance("SHA-256").digest(file.readBytes()).toList() else null }
        }
        var before: Map<String, List<Byte>?>? = null
        val isolated = File(runner.targetContext.cacheDir, "isolated-player-tools")
        fun capture(name: String, dialogField: String? = null) {
            stage = name
            runner.waitForIdleSync(); SystemClock.sleep(400); runner.waitForIdleSync()
            check(main {
                activity is IsolatedToolsActivity && !activity.isFinishing && !activity.isDestroyed &&
                    activity.applicationContext.noBackupFilesDir.canonicalFile == isolated.canonicalFile &&
                    state().catalog == Fixtures.channels && CapturePolicy.allowed(activity) &&
                    activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE == 0 &&
                    if (dialogField == null) activity.window.decorView.hasWindowFocus() else {
                        val dialog = field(dialogField) as Dialog
                        dialog.isShowing && dialog.window?.decorView?.hasWindowFocus() == true &&
                            dialog.window!!.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE == 0
                    }
            }) { "Capture isolation or foreground window check failed" }
            shell("screencap -p $directory/$name.png")
            check(shell("stat -c %s $directory/$name.png").trim().toLongOrNull()?.let { it > 0 } == true) {
                "Screenshot was not written"
            }
            captures++
        }
        try {
            CapturePolicy.syntheticCaptureAllowed = false
            before = digests()
            check(isolated.mkdirs() || isolated.isDirectory)
            check(isolated.canonicalFile != real.canonicalFile)
            val allowed = stores + stores.map { "$it.new" } + stores.map { "$it.bak" } +
                setOf("retry.mp4", "multi.mp4", "large.mp4", "small.mp4")
            val residue = checkNotNull(isolated.listFiles())
            check(residue.all { it.isFile && it.name in allowed }) { "Unexpected isolated residue" }
            residue.forEach { check(it.delete()) }
            PlaybackSleepTimer.timer.setMinutes(0, SystemClock.elapsedRealtime())
            PlaybackSleepTimer.timer.allowPlayback()
            activity = runner.startActivitySync(Intent(runner.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
            check(activity is IsolatedToolsActivity) { "Isolated Activity was not substituted" }
            await { !(field("loading") as Boolean) && field("player") != null }
            check(main { activity.applicationContext.noBackupFilesDir.canonicalFile == isolated.canonicalFile })
            check(main { state().catalog == Fixtures.channels })
            check(main { !(field("privatePlaylist") as Boolean) && field("provider") == null })
            await { (field("player") as ExoPlayer).playbackState == Player.STATE_READY }
            main {
                check(CapturePolicy.allowed(activity).not())
                CapturePolicy.syntheticCaptureAllowed = true
                check(CapturePolicy.allowed(activity)) { "Design capture requires a debuggable build" }
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
            stage = "capture directory"
            directory = listOf("/sdcard/Download/fixturetv-design", "/data/local/tmp/fixturetv-design").firstOrNull { dir ->
                // executeShellCommand does not parse quotes, so avoid `sh -c '...'` compound commands.
                shell("mkdir -p $dir")
                shell("ls -d $dir").trim() == dir
            } ?: error("No writable shell capture directory")
            check(main { state().pane == Pane.GUIDE && state().programmeIndex == 0 })
            main {
                fun descendants(view: android.view.View): List<android.view.View> = listOf(view) +
                    if (view is android.view.ViewGroup) (0 until view.childCount).flatMap {
                        descendants(view.getChildAt(it))
                    } else emptyList()
                val chrome = field("chrome") as android.view.ViewGroup
                val views = descendants(chrome)
                val search = views.single { it.contentDescription == "Search" }
                val tv = views.single { it.contentDescription == "TV" }
                val settings = views.single { it.contentDescription == "Settings" }
                val rail = search.parent as android.view.ViewGroup
                check(tv.parent === rail && settings.parent === rail)
                check(kotlin.math.abs(search.top - (rail.height - settings.bottom)) <= 2) {
                    "Rail actions are not centered as one group"
                }
                check(search.bottom == tv.top && tv.bottom == settings.top)
                val details = views.single { it.tag == "programme-details" }
                val preview = field("videoViewport") as android.view.View
                check(details.height == preview.height) { "Header surfaces have mismatched heights" }
                val guide = field("guide") as GuideRows
                val row = guide.getChildAt(0) as android.view.ViewGroup
                val identity = row.getChildAt(0)
                check(identity.width > guide.width / 4) { "Channel identity column is too narrow" }
            }
            capture("01-guide")
            key(KeyEvent.KEYCODE_DPAD_LEFT)
            check(main { state().pane == Pane.GROUPS })
            capture("02-guide-groups")
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            check(main { state().pane == Pane.FULLSCREEN && state().overlay && !state().toolbar })
            capture("03-fullscreen-programme")
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            check(main { state().toolbar })
            capture("04-fullscreen-toolbar")
            stage = "toolbar touch swipes"
            fun toolbarScroll(): android.widget.HorizontalScrollView {
                val chrome = field("fullscreenChrome") as FullscreenChrome
                val capsule = FullscreenChrome::class.java.getDeclaredField("toolbarCapsule").let {
                    it.isAccessible = true
                    it.get(chrome) as android.view.ViewGroup
                }
                return capsule.getChildAt(0) as android.widget.HorizontalScrollView
            }
            fun swipe(left: Boolean) {
                val coordinates = main {
                    check(activity.window.decorView.hasWindowFocus() && state().toolbar && CapturePolicy.allowed(activity))
                    val scroll = toolbarScroll()
                    val location = IntArray(2)
                    scroll.getLocationOnScreen(location)
                    val start = location[0] + scroll.width * if (left) .8f else .2f
                    val end = location[0] + scroll.width * if (left) .2f else .8f
                    Triple(start, end, location[1] + scroll.height / 2f)
                }
                val down = SystemClock.uptimeMillis()
                for (step in 0..20) {
                    val event = android.view.MotionEvent.obtain(down, SystemClock.uptimeMillis(),
                        when (step) { 0 -> android.view.MotionEvent.ACTION_DOWN
                            20 -> android.view.MotionEvent.ACTION_UP
                            else -> android.view.MotionEvent.ACTION_MOVE },
                        coordinates.first + (coordinates.second - coordinates.first) * step / 20f,
                        coordinates.third, 0)
                    try { runner.sendPointerSync(event) } finally { event.recycle() }
                    SystemClock.sleep(20)
                }
                SystemClock.sleep(700)
                runner.waitForIdleSync()
            }
            val beforeSwipe = main { toolbarScroll().scrollX }
            swipe(true)
            val afterLeft = main { toolbarScroll().scrollX }
            check(afterLeft > beforeSwipe) { "Left swipe did not advance the toolbar" }
            capture("04a-toolbar-swipe-left")
            swipe(false)
            check(main { toolbarScroll().scrollX < afterLeft }) { "Right swipe did not return the toolbar" }
            capture("04b-toolbar-swipe-right")
            toolbar(13)
            await { (field("settingsDialog") as? Dialog)?.isShowing == true }
            capture("05-quick-settings", "settingsDialog")
            key(KeyEvent.KEYCODE_BACK)
            await { field("settingsDialog") == null }
            toolbar(9)
            await { (field("displayDialog") as? Dialog)?.isShowing == true }
            capture("06-display-mode", "displayDialog")
            key(KeyEvent.KEYCODE_BACK)
            await { field("displayDialog") == null }
            toolbar(1)
            await { (field("channelDialog") as? Dialog)?.isShowing == true }
            capture("07-channel-list", "channelDialog")
            key(KeyEvent.KEYCODE_BACK)
            await { field("channelDialog") == null }
            key(KeyEvent.KEYCODE_DPAD_UP)
            repeat(2) { key(KeyEvent.KEYCODE_DPAD_RIGHT) }
            check(main { state().action == 2 && !state().toolbar })
            val schedulePlayer = main { field("player") }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            check(main { (field("scheduleOverlay") as android.view.View).visibility == android.view.View.VISIBLE })
            capture("08-schedule")
            key(KeyEvent.KEYCODE_BACK)
            check(main { state().action == 2 && state().overlay && !state().toolbar &&
                field("player") === schedulePlayer && activity.currentFocus?.contentDescription == "Schedule" })
            stage = "09-settings-screen"
            // This static category screen never reads stores; do not activate its destination buttons.
            settingsScreen = runner.startActivitySync(SettingsActivity.intent(activity)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SettingsActivity
            val settings = checkNotNull(settingsScreen)
            runner.waitForIdleSync(); SystemClock.sleep(400)
            main {
                check(settings.window.decorView.hasWindowFocus() && CapturePolicy.allowed(settings) &&
                    settings.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE == 0)
                fun labels(view: android.view.View): List<String> = when (view) {
                    is android.widget.TextView -> listOf(view.text.toString()).filter { it.isNotBlank() }
                    is android.view.ViewGroup -> (0 until view.childCount).flatMap { labels(view.getChildAt(it)) }
                    else -> emptyList()
                }
                val expected = setOf("Settings", "Playlists", "EPG", "Recordings", "Multiview", "About",
                    "Choose the active playlist. Switching does not start playback.",
                    "Manage playlists", "Add playlist", "All playlists")
                val visible = labels(settings.window.decorView)
                check(visible.toSet() == expected) { "Settings capture contains unexpected content" }
            }
            check(before == digests()) { "Private files changed before Settings capture" }
            shell("screencap -p $directory/09-settings-screen.png")
            check(shell("stat -c %s $directory/09-settings-screen.png").trim().toLongOrNull()?.let { it > 0 } == true)
            captures++
            main { CapturePolicy.secure(settings.window); settings.finish() }
        } catch (error: Throwable) {
            failure = error
        } finally {
            CapturePolicy.syntheticCaptureAllowed = false
            try {
                main { settingsScreen?.let { CapturePolicy.secure(it.window); if (!it.isFinishing) it.finish() } }
                if (::activity.isInitialized) {
                    main {
                        CapturePolicy.secure(activity.window)
                        MainActivity::class.java.declaredFields.filter { Dialog::class.java.isAssignableFrom(it.type) }
                            .forEach { member ->
                                member.isAccessible = true
                                (member.get(activity) as? Dialog)?.let { dialog ->
                                    CapturePolicy.secure(dialog.window); dialog.dismiss()
                                }
                            }
                        activity.finish()
                    }
                    await { activity.isDestroyed }
                }
            } catch (error: Throwable) {
                if (failure == null) { failure = error; stage = "activity cleanup" }
            }
            var unchanged = false
            try {
                unchanged = before != null && before == digests()
                check(unchanged) { "Private file digests changed or could not be verified" }
            } catch (error: Throwable) {
                failure = error; stage = "private file verification"
            }
            result.putString("directory", directory)
            result.putInt("captures", captures)
            result.putBoolean("real_files_unchanged", unchanged)
            val error = failure
            if (error == null) {
                result.putString("summary", "PASS: $captures design captures in $directory; synthetic fixtures only; private files unchanged")
            } else {
                result.putString("summary", "FAIL: design capture at $stage; $captures captures in $directory")
                result.putString("failure", error.javaClass.simpleName)
                result.putString("stage", stage)
                result.putString("location", error.stackTrace.firstOrNull { it.className.startsWith("faisalnh.tiviman") }
                    ?.let { "${it.fileName}:${it.lineNumber}" })
            }
            runner.finish(if (error == null) -1 else 0, result)
        }
    }
}

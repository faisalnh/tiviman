package faisalnh.tiviman

import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent

/** Non-destructive saved-account checks: no import/clear, no printed metadata/credentials. */
class SavedNavigationChecks(private val runner: Instrumentation) {
    private lateinit var activity: MainActivity
    private fun field(name: String): Any? = MainActivity::class.java.getDeclaredField(name).let {
        it.isAccessible = true; it.get(activity)
    }
    private fun <T> main(block: () -> T): T {
        var value: T? = null
        runner.runOnMainSync { value = block() }
        @Suppress("UNCHECKED_CAST") return value as T
    }
    private fun awaitReady() {
        val deadline = SystemClock.uptimeMillis() + 20000
        while (main { field("loading") as Boolean } && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
        check(main { !(field("loading") as Boolean) }) { "Catalog load timed out" }
        runner.waitForIdleSync(); SystemClock.sleep(200)
    }
    private fun key(code: Int) {
        runner.sendKeyDownUpSync(code)
        runner.waitForIdleSync(); SystemClock.sleep(50)
    }
    fun run() {
        val report = Bundle()
        var stage = "startup"
        try {
            val savedFile = java.io.File(runner.targetContext.noBackupFilesDir, "playlist.enc")
            check(savedFile.exists()) { "No saved catalog" }
            fun digest() = java.security.MessageDigest.getInstance("SHA-256").digest(savedFile.readBytes())
            val originalDigest = digest()
            val launchStart = SystemClock.uptimeMillis()
            activity = runner.startActivitySync(Intent(runner.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
            var loadingHeartbeats = 0
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            val heartbeat = object : Runnable {
                override fun run() {
                    if (field("loading") as Boolean) {
                        loadingHeartbeats++
                        handler.postDelayed(this, 25)
                    }
                }
            }
            runner.runOnMainSync { handler.post(heartbeat) }
            awaitReady()
            check(main { loadingHeartbeats > 0 }) { "No UI heartbeat during loading" }
            report.putInt("ui_heartbeats_during_loading", main { loadingHeartbeats })
            report.putLong("startup_to_ready_ms", SystemClock.uptimeMillis() - launchStart)
            // Pause only the replacement player; no reference input, no additional channel tuned.
            key(KeyEvent.KEYCODE_MEDIA_PAUSE)
            val initial = main { field("state") as GuideState }
            if (initial.pane == Pane.FULLSCREEN) key(KeyEvent.KEYCODE_BACK)
            if (main { (field("state") as GuideState).pane } != Pane.GROUPS) key(KeyEvent.KEYCODE_BACK)
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            val groupState = main { field("state") as GuideState }
            if (groupState.focusedGroup == groupState.groups.size) key(KeyEvent.KEYCODE_DPAD_UP)
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            // Exercise the full large catalog, not just a potentially tiny provider group.
            key(KeyEvent.KEYCODE_BACK)
            main {
                val stateField = MainActivity::class.java.getDeclaredField("state").apply { isAccessible = true }
                stateField.set(activity, (field("state") as GuideState).copy(focusedGroup = 0))
            }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            val start = SystemClock.uptimeMillis()
            repeat(60) { key(KeyEvent.KEYCODE_DPAD_DOWN) }
            repeat(45) { key(KeyEvent.KEYCODE_DPAD_UP) }
            repeat(15) { key(KeyEvent.KEYCODE_DPAD_DOWN) }
            report.putLong("navigation_120_keys_ms_including_test_delays", SystemClock.uptimeMillis() - start)
            // Persist a non-default group as well as a non-default row.
            key(KeyEvent.KEYCODE_BACK)
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            repeat(3) { key(KeyEvent.KEYCODE_DPAD_DOWN) }
            val before = main { field("state") as GuideState }
            stage = "navigation focus and tune checks"
            check(before.tunedId == initial.tunedId) { "Navigation unexpectedly tuned" }
            val focusDeadline = SystemClock.uptimeMillis() + 2000
            while (main { activity.currentFocus?.isShown != true } && SystemClock.uptimeMillis() < focusDeadline) SystemClock.sleep(25)
            check(main { activity.currentFocus?.isShown == true }) { "Lost focus" }
            main { activity.finish() }
            val pauseDeadline = SystemClock.uptimeMillis() + 5000
            while (main { field("started") as Boolean } && SystemClock.uptimeMillis() < pauseDeadline) SystemClock.sleep(25)
            runner.waitForIdleSync()
            // A fresh activity must use the persisted encrypted bookmark, not a saved Bundle.
            stage = "fresh activity restoration"
            activity = runner.startActivitySync(Intent(runner.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            awaitReady()
            val restored = main { field("state") as GuideState }
            report.putBoolean("group_restored", restored.group == before.group)
            report.putBoolean("channel_restored", restored.channelId == before.channelId)
            report.putBoolean("tune_restored", restored.tunedId == before.tunedId)
            check(restored.group == before.group && restored.channelId == before.channelId && restored.tunedId == before.tunedId) {
                "Fresh activity did not restore navigation"
            }
            check(originalDigest.contentEquals(digest())) { "Saved catalog was modified" }
            report.putBoolean("saved_catalog_unchanged", true)
            stage = "background and resume"
            key(KeyEvent.KEYCODE_MEDIA_PAUSE)
            key(KeyEvent.KEYCODE_HOME)
            val stopDeadline = SystemClock.uptimeMillis() + 5000
            while (main { field("started") as Boolean } && SystemClock.uptimeMillis() < stopDeadline) SystemClock.sleep(25)
            check(main { !(field("started") as Boolean) && field("player") == null }) { "Player not released in background" }
            runner.targetContext.startActivity(Intent(runner.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            val resumeDeadline = SystemClock.uptimeMillis() + 5000
            while (main { !(field("started") as Boolean) } && SystemClock.uptimeMillis() < resumeDeadline) SystemClock.sleep(25)
            check(main { (field("started") as Boolean) && !(field("shouldPlay") as Boolean) }) { "Pause/resume state lost" }
            check(main { (field("state") as GuideState).channelId == before.channelId }) { "Resume focus lost" }
            report.putBoolean("background_resume_passed", true)
            main { activity.finish() }
            report.putString("summary", "PASS: async startup/UI heartbeat; 120 directional keys; focus retained; focus does not tune; fresh-activity group/channel/tune restoration; background/resume. Saved playlist unchanged.")
            runner.finish(-1, report)
        } catch (_: Exception) {
            report.putString("failure", "Saved-catalog check failed at $stage; private details suppressed")
            if (::activity.isInitialized) main { activity.finish() }
            runner.finish(0, report)
        }
    }
}

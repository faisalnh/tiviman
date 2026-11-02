package faisalnh.tiviman

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.RecyclerView
import androidx.media3.common.MediaItem
import android.net.Uri

/** Dependency-free platform instrumentation. Only launches/controls the fixture package. */
class PrototypeChecks : Instrumentation() {
    private lateinit var activity: MainActivity
    private var fullscreenContrast = false
        private var fontScale = false
    private var playlistOnly = false
    private var providersOnly = false
    private var privateProvider = false
    private var savedNavigation = false
    private var playerMenus = false
    private var trackControls = false
    private var isolatedTools = false
    private var designCapture = false
    private var advancedTools = false
    private var epgRefresh = false
    private var epgRefreshFeedback = false
    private var allPlaylistsTools = false
    private var epgBrowserTools = false
    private var epgBrowserLabels = false
    private var playlistLabels = false
    private var audioDelayTools = false
    private var refreshSavedXtream = false
    private var diagnoseSavedEpg = false
    private var alternativeEpg = false
    private var multiPlaylist = false
    private var addAlternativePlaylist = false
    private val passed = mutableListOf<String>()
    private fun settled() {
        waitForIdleSync()
        SystemClock.sleep(180)
        if (::activity.isInitialized) {
            val deadline = SystemClock.uptimeMillis() + 5000
            while (main { field("loading") as Boolean } && SystemClock.uptimeMillis() < deadline) {
                SystemClock.sleep(50)
                waitForIdleSync()
            }
        }
        waitForIdleSync()
    }
    private fun key(code: Int) { sendKeyDownUpSync(code); settled() }
    private fun check(name: String, test: () -> Unit) { test(); passed.add(name) }
    private fun requireThat(ok: Boolean, message: String) { if (!ok) throw AssertionError(message) }
    private fun <T> main(block: () -> T): T {
        // Nested main { } blocks (e.g. state() inside a check) are already on the main thread,
        // where runOnMainSync throws and crashes the app process instead of failing the check.
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return block()
        // Rethrow on the instrumentation thread so a failed check is reported, not a process crash.
        var outcome: Result<T>? = null
        runOnMainSync { outcome = runCatching(block) }
        return checkNotNull(outcome).getOrThrow()
    }
    private fun field(name: String): Any? = MainActivity::class.java.getDeclaredField(name).let {
        it.isAccessible = true; it.get(activity)
    }
    private fun state() = main { field("state") as GuideState }
    private fun focus(fragment: String) = main {
        val focused = activity.currentFocus
        requireThat(focused?.isFocusable == true && focused.isShown, "No visible native focus")
        requireThat(focused?.contentDescription?.contains(fragment) == true,
            "Expected focus $fragment, got ${focused?.contentDescription}")
        val rect = Rect()
        focused!!.getGlobalVisibleRect(rect)
        requireThat(rect.height() == focused.height, "Focused cell is vertically clipped: $rect")
    }
    private fun player() = field("player") as ExoPlayer
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        fullscreenContrast = arguments?.getString("fullscreenContrast") == "true"
                fontScale = arguments?.getString("fontScale") == "true"
        playlistOnly = arguments?.getString("playlist") == "true"
        providersOnly = arguments?.getString("providers") == "true"
        privateProvider = arguments?.getString("privateProvider") == "true"
        savedNavigation = arguments?.getString("savedNavigation") == "true"
        playerMenus = arguments?.getString("playerMenus") == "true"
        trackControls = arguments?.getString("trackControls") == "true"
        isolatedTools = arguments?.getString("isolatedTools") == "true"
        designCapture = arguments?.getString("designCapture") == "true"
        advancedTools = arguments?.getString("advancedTools") == "true"
        epgRefresh = arguments?.getString("epgRefresh") == "true"
        epgRefreshFeedback = arguments?.getString("epgRefreshFeedback") == "true"
        allPlaylistsTools = arguments?.getString("allPlaylistsTools") == "true"
        epgBrowserTools = arguments?.getString("epgBrowserTools") == "true"
        epgBrowserLabels = arguments?.getString("epgBrowserLabels") == "true"
        playlistLabels = arguments?.getString("playlistLabels") == "true"
        audioDelayTools = arguments?.getString("audioDelayTools") == "true"
        refreshSavedXtream = arguments?.getString("refreshSavedXtream") == "true"
        diagnoseSavedEpg = arguments?.getString("diagnoseSavedEpg") == "true"
        alternativeEpg = arguments?.getString("alternativeEpg") == "true"
        multiPlaylist = arguments?.getString("multiPlaylist") == "true"
        addAlternativePlaylist = arguments?.getString("addAlternativePlaylist") == "true"
        start()
    }
    override fun onStart() {
        if (designCapture) { DesignCaptureChecks(this).run(); return }
        if (fullscreenContrast) { FullscreenContrastChecks(this).run(); return }
                if (fontScale) { FontScaleChecks(this).run(); return }
        if (audioDelayTools) { AudioDelayChecks(this).run(); return }
        if (epgBrowserLabels) { EpgBrowserLabelChecks(this).run(); return }
        if (playlistLabels) { PlaylistLabelChecks(this).run(); return }
        if (epgBrowserTools) { EpgBrowserChecks(this).run(); return }
        if (allPlaylistsTools) { AllPlaylistsChecks(this).run(); return }
        if (advancedTools) { AdvancedPlaybackChecks(this).run(); return }
        if (epgRefreshFeedback) { EpgRefreshFeedbackChecks(this).run(); return }
        if (epgRefresh) { EpgRefreshChecks(this).run(); return }
        if (addAlternativePlaylist) { AddAlternativePlaylist(this).run(); return }
        if (multiPlaylist) { MultiPlaylistChecks(this).run(); return }
        if (alternativeEpg) { AlternativeEpgCheck(this).run(); return }
        if (diagnoseSavedEpg) { SavedEpgDiagnostic(this).run(); return }
        if (refreshSavedXtream) { SavedXtreamRefresh(this).run(); return }
        if (isolatedTools) { PlayerIntegrationChecks(this).run(); return }
        if (trackControls) { TrackControlChecks(this).run(); return }
        if (playerMenus) { PlayerMenuChecks(this).run(); return }
        if (savedNavigation) { SavedNavigationChecks(this).run(); return }
        if (privateProvider) { PrivateProviderCheck(this).run(); return }
        if (providersOnly) { ProviderChecks(this).run(); return }
        if (playlistOnly) {
            val checks = PlaylistChecks()
            checks.attachRunner(this)
            checks.onStart()
            return
        }
        val result = Bundle()
        try {
            requireThat(!java.io.File(targetContext.noBackupFilesDir, "playlist.enc").exists(),
                "Refusing fixture tests while a saved playlist exists")
            activity = startActivitySync(Intent(targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
            settled()
            check("native programme focus") { focus("Horizon, Horizon: Evening edition") }
            val firstHolder = main { (field("guide") as RecyclerView).findViewHolderForAdapterPosition(0) }
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            check("navigation retains holders and does not tune") {
                focus("Atelier, Atelier: Evening edition")
                requireThat(state().tunedId == 0, "Focus unexpectedly tuned")
                requireThat(main { (field("guide") as RecyclerView).findViewHolderForAdapterPosition(0) } === firstHolder,
                    "Ordinary navigation recreated row holder")
            }
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            val futureState = state()
            check("future cells are focusable information with no accessibility click") { main {
                val cell = activity.currentFocus ?: error("Missing future focus")
                requireThat(cell.isFocusable && cell.isShown && !cell.isClickable && !cell.hasOnClickListeners(), "Future cell advertises activation")
                val node = cell.createAccessibilityNodeInfo()
                requireThat(!node.isClickable && node.actionList.none {
                    it.id == android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK
                }, "Future accessibility node advertises click")
                requireThat(cell.contentDescription?.contains("Future programme. Actions unavailable in this prototype.") == true,
                    "Missing future availability explanation")
                val body = activity.window.decorView.findViewWithTag<android.widget.ScrollView>("programme-details")
                    .getChildAt(0) as ViewGroup
                requireThat((0 until body.childCount).map { body.getChildAt(it) }.filterIsInstance<android.widget.TextView>()
                    .any { it.isShown && it.text == "Future programme. Actions unavailable in this prototype." },
                    "Missing visible availability explanation")
                requireThat(!cell.performClick(), "Future performClick succeeded")
                requireThat(!cell.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK, null),
                    "Future accessibility click succeeded")
            } }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            key(KeyEvent.KEYCODE_ENTER)
            check("future Center and Enter preserve browsing and tune") {
                requireThat(state() == futureState, "Future activation changed state")
                focus("A short journey")
            }
            key(KeyEvent.KEYCODE_DPAD_LEFT)
            check("current cells retain native click semantics") { main {
                val cell = activity.currentFocus ?: error("Missing current focus")
                val node = cell.createAccessibilityNodeInfo()
                requireThat(cell.isClickable && cell.hasOnClickListeners() && node.isClickable && node.actionList.any {
                    it.id == android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK
                }, "Current cell lost activation semantics")
                requireThat(cell.contentDescription?.contains("Actions unavailable") == false, "Current description is stale")
            } }
            repeat(20) { key(KeyEvent.KEYCODE_DPAD_DOWN) }
            check("bottom boundary and fully visible focus") { focus("Playtime"); requireThat(state().channelId == 11, "Wrong last channel") }
            repeat(20) { key(KeyEvent.KEYCODE_DPAD_UP) }
            check("top boundary and focus") { focus("Horizon"); requireThat(state().channelId == 0, "Wrong first channel") }
            repeat(3) { key(KeyEvent.KEYCODE_DPAD_RIGHT) }
            check("horizontal window native focus") { focus("Night stories") }
            val tunedFuture = state()
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            check("future on tuned channel does not open fullscreen") {
                requireThat(state() == tunedFuture && state().pane == Pane.GUIDE, "Future opened fullscreen")
            }
            repeat(3) { key(KeyEvent.KEYCODE_DPAD_LEFT) }
            key(KeyEvent.KEYCODE_DPAD_LEFT)
            check("group is native focused and clickable") { main {
                requireThat(activity.currentFocus?.isClickable == true, "Group not clickable")
            } }
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            check("group switch preserves native programme focus") { focus("Atelier"); requireThat(state().group == 2, "Wrong category") }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            check("two-step tune/fullscreen") { requireThat(state().pane == Pane.FULLSCREEN && state().tunedId == 1, "Tune transition failed") }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            key(KeyEvent.KEYCODE_MEDIA_PAUSE)
            val pausedPosition = main { player().currentPosition }
            SystemClock.sleep(800)
            check("pause action actually stops playback advancement") {
                requireThat(main { !player().playWhenReady && !player().isPlaying }, "Player did not pause")
                requireThat(kotlin.math.abs(main { player().currentPosition } - pausedPosition) < 150, "Paused position advanced")
            }
            key(KeyEvent.KEYCODE_HOME)
            settled()
            targetContext.startActivity(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            settled()
            check("background preserves paused position and state") {
                requireThat(main { !player().playWhenReady }, "Resume lost paused state")
                requireThat(kotlin.math.abs(main { player().currentPosition } - pausedPosition) < 200, "Resume lost position")
            }
            // Capture recreated Activity without changing emulator-wide developer settings.
            val monitor = addMonitor(MainActivity::class.java.name, null, false)
            main { activity.recreate() }
            activity = waitForMonitorWithTimeout(monitor, 5000) as? MainActivity
                ?: throw AssertionError("Activity recreation timed out")
            removeMonitor(monitor)
            settled()
            check("activity recreation preserves channel, fullscreen and pause") {
                requireThat(state().tunedId == 1 && state().pane == Pane.FULLSCREEN, "Recreation lost UI state")
                requireThat(main { !player().playWhenReady }, "Recreation lost pause")
            }
            key(KeyEvent.KEYCODE_MEDIA_PLAY)
            SystemClock.sleep(800)
            check("media play resumes decoded playback") { requireThat(main { player().isPlaying }, "Player is not playing") }
            if (state().overlay) key(KeyEvent.KEYCODE_BACK)
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            val beforeDetails = state()
            main {
                val longDetails = beforeDetails.copy(realEpg = true, catalog = beforeDetails.catalog.map { channel ->
                    channel.copy(programmes = channel.programmes.map { programme ->
                        programme.copy(description = "Synthetic scroll and reading timeout fixture. ".repeat(100))
                    })
                })
                MainActivity::class.java.getDeclaredField("state").apply { isAccessible = true }.set(activity, longDetails)
                MainActivity::class.java.getDeclaredMethod("render").apply { isAccessible = true }.invoke(activity)
            }
            settled()
            key(KeyEvent.KEYCODE_DPAD_UP)
            check("overflowing fullscreen details accept D-pad focus") { main {
                requireThat(activity.currentFocus is android.widget.ScrollView, "Details did not receive focus")
            } }

            SystemClock.sleep(7500)
            settled()
            check("detail reading suspends overlay timeout") { main {
                requireThat(state().overlay && activity.currentFocus is android.widget.ScrollView,
                    "Overlay hid or lost reading focus (overlay=${state().overlay}, " +
                        "focus=${activity.currentFocus?.javaClass?.simpleName})")
                (activity.currentFocus as android.widget.ScrollView).fullScroll(View.FOCUS_DOWN)
            } }
            settled()
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            check("bottom of details restores action focus without tuning") { main {
                requireThat(activity.currentFocus?.isClickable == true && state().tunedId == beforeDetails.tunedId,
                    "Details did not return to actions safely")
                MainActivity::class.java.getDeclaredField("state").apply { isAccessible = true }.set(activity, beforeDetails)
                MainActivity::class.java.getDeclaredMethod("render").apply { isAccessible = true }.invoke(activity)
            } }
            SystemClock.sleep(7500)
            settled()
            check("overlay auto-hides") { requireThat(!state().overlay, "Overlay did not time out") }
            key(KeyEvent.KEYCODE_BACK)
            check("fullscreen Back restores programme focus") { focus("Atelier") }
            sendKeySync(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0))
            SystemClock.sleep(800)
            sendKeySync(KeyEvent(0, 800, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0))
            settled()
            check("held Back returns fullscreen without second Back") { requireThat(state().pane == Pane.FULLSCREEN, "Long Back failed") }
            // Controlled failure touches only a synthetic app-private file; retry the SAME URI
            // after restoring its contents, analogous to a temporarily unavailable source.
            val retryFile = java.io.File(targetContext.cacheDir, "retry-fixture.mp4")
            if (retryFile.exists()) requireThat(retryFile.delete(), "Cannot remove synthetic retry file")
            main {
                player().setMediaItem(MediaItem.fromUri(Uri.fromFile(retryFile)))
                player().prepare()
            }
            val errorDeadline = SystemClock.uptimeMillis() + 15000
            while (main { player().playerError == null } && SystemClock.uptimeMillis() < errorDeadline) SystemClock.sleep(100)
            check("missing synthetic media produces player error") { requireThat(main { player().playerError != null }, "Missing source did not fail") }
            targetContext.assets.open("fixture.mp4").use { source -> retryFile.outputStream().use { source.copyTo(it) } }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            repeat(3) { key(KeyEvent.KEYCODE_DPAD_RIGHT) }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            SystemClock.sleep(800)
            check("retry action recovers same source after availability restored") {
                requireThat(main { player().playerError == null && player().isPlaying }, "Retry did not recover")
            }
            key(KeyEvent.KEYCODE_BACK)
            key(KeyEvent.KEYCODE_BACK)
            repeat(10) {
                key(KeyEvent.KEYCODE_DPAD_RIGHT)
                key(KeyEvent.KEYCODE_DPAD_DOWN)
                key(KeyEvent.KEYCODE_DPAD_LEFT)
                key(KeyEvent.KEYCODE_DPAD_UP)
            }
            check("repeated recycled-row and window navigation retains native focus") {
                focus(state().channel.name)
                requireThat(state().tunedId == 1, "Stress navigation changed tune")
            }
            // Reach setup using the D-pad, not a direct activity launch.
            key(KeyEvent.KEYCODE_BACK)
            while (state().programmeIndex > 0) key(KeyEvent.KEYCODE_DPAD_LEFT)
            if (state().pane != Pane.GROUPS) key(KeyEvent.KEYCODE_DPAD_LEFT)
            repeat(state().groups.size + 1) { key(KeyEvent.KEYCODE_DPAD_DOWN) }
            val setupMonitor = addMonitor(SettingsActivity::class.java.name, null, false)
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            val setup = waitForMonitorWithTimeout(setupMonitor, 5000)
                ?: throw AssertionError("D-pad did not open playlist setup")
            removeMonitor(setupMonitor)
            check("D-pad reaches private playlist setup") { main {
                requireThat(setup.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_SECURE != 0,
                    "Setup allows screen capture")
            } }
            key(KeyEvent.KEYCODE_BACK)
            check("cancel setup restores existing fixture catalog") { requireThat(state().catalog == Fixtures.channels, "Cancel changed catalog") }
            main { activity.finish() }
            result.putString("checks", passed.joinToString("\n"))
            result.putString("summary", "PASS: ${passed.size} on-device checks")
            finish(RESULT_OK, result)
        } catch (error: Throwable) {
            result.putString("passed", passed.joinToString("\n"))
            result.putString("failure", error.stackTraceToString())
            finish(RESULT_CANCELED, result)
        }
    }

    override fun newActivity(cl: ClassLoader, className: String, intent: Intent): android.app.Activity {
        if (epgRefreshFeedback) EpgRefreshFeedbackChecks.substitute(className)?.let { return it }
        if (epgBrowserLabels) EpgBrowserLabelChecks.substitute(className)?.let { return it }
        if (epgBrowserTools) EpgBrowserChecks.substitute(className)?.let { return it }
        if (allPlaylistsTools) AllPlaylistsChecks.substitute(className)?.let { return it }
        if (advancedTools) AdvancedPlaybackChecks.substitute(className)?.let { return it }
        if (designCapture && className == MainActivity::class.java.name) return IsolatedToolsActivity()
        if (isolatedTools && className == MainActivity::class.java.name) return IsolatedToolsActivity()
        if (isolatedTools && className == EpgActivity::class.java.name) return IsolatedEpgActivity()
        return super.newActivity(cl, className, intent)
    }

    companion object { const val RESULT_OK = -1; const val RESULT_CANCELED = 0 }
}

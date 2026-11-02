package faisalnh.tiviman

import android.app.Activity
import android.app.AlertDialog
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private fun feedbackRoot(base: Context) = File(base.cacheDir, "synthetic-refresh-feedback")
private fun feedbackContext(base: Context): Context = object : ContextWrapper(base) {
    override fun getNoBackupFilesDir() = File(feedbackRoot(base), "nobackup").apply { mkdirs() }
    override fun getFilesDir() = File(feedbackRoot(base), "files").apply { mkdirs() }
    override fun getApplicationContext(): Context = this
    override fun getSystemService(name: String): Any? {
        check(name != Context.JOB_SCHEDULER_SERVICE) { "Real scheduler must not be used" }
        return super.getSystemService(name)
    }
}

class FeedbackCheckEpgActivity : EpgActivity() {
    @Volatile var failSchedule = false
    @Volatile var failDisable = false
    @Volatile var failRead = false
    @Volatile var readGate: CountDownLatch? = null
    private val jobs = object : EpgJobs {
        private val pending = linkedMapOf<Int, String>()
        override fun pending() = if (failSchedule) emptyList() else pending.map { PendingJob(it.key, it.value) }
        override fun schedule(jobId: Int, owner: String): Boolean {
            if (failSchedule) error("Synthetic scheduler failure: https://synthetic.invalid/?token=SECRET")
            pending[jobId] = owner
            return true
        }
        override fun cancel(jobId: Int) { pending.remove(jobId) }
    }
    override fun storageContext(): Context = feedbackContext(baseContext)
    override fun readRefreshStatus(storage: Context, id: String): EpgRefresh.Status? {
        check(readGate?.await(5, TimeUnit.SECONDS) != false) { "Synthetic read gate timed out" }
        if (failRead) error("Synthetic status failure: SECRET")
        return EpgRefreshEngine(storage, jobs).status(id)
    }
    override fun enableRefresh(storage: Context, id: String, source: EpgRefresh.Source, mappings: Map<Int, String>) =
        EpgRefreshEngine(storage, jobs).enable(id, source, mappings)
    override fun disableRefresh(storage: Context, id: String) {
        if (failDisable) error("Synthetic disable failure: SECRET")
        EpgRefreshEngine(storage, jobs).disable(id)
    }
}

class EpgRefreshFeedbackChecks(private val runner: Instrumentation) {
    companion object {
        fun substitute(className: String): Activity? =
            if (className == EpgActivity::class.java.name) FeedbackCheckEpgActivity() else null
    }
    private lateinit var activity: FeedbackCheckEpgActivity
    private fun <T> main(block: () -> T): T {
        var result: T? = null
        runner.runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST") return result as T
    }
    private fun await(name: String, test: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 10000
        while (!main(test) && SystemClock.uptimeMillis() < end) SystemClock.sleep(30)
        check(main(test)) { "Timed out: $name" }
        runner.waitForIdleSync()
    }
    private fun descendants(view: View): List<View> = listOf(view) +
        ((view as? ViewGroup)?.let { group ->
            (0 until group.childCount).flatMap { descendants(group.getChildAt(it)) }
        } ?: emptyList())
    private fun views() = descendants(activity.window.decorView)
    private fun button(label: String) = views().filterIsInstance<Button>().single { it.text.toString() == label }
    private fun texts() = views().filterIsInstance<TextView>().filter { it !is EditText && it !is Button }
    private fun contains(fragment: String) = texts().any { it.text.contains(fragment) }
    private val enable = "Enable 6h refresh • entered URL"
    private val disable = "Disable automatic refresh"
    private fun settled() = button(disable).isEnabled && button("Import XMLTV URL").isEnabled
    private fun confirmEnable() = main {
        check(button(enable).performClick())
        val field = EpgActivity::class.java.getDeclaredField("dialog").apply { isAccessible = true }
        val dialog = field.get(activity) as AlertDialog
        check(dialog.isShowing)
        check(dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick())
    }
    private fun safeCopy() {
        check(texts().none { it.text.contains("SECRET") || it.text.contains("https://synthetic.invalid") })
    }

    fun run() {
        val result = Bundle()
        val passed = mutableListOf<String>()
        var code = Activity.RESULT_CANCELED
        try {
            val base = runner.targetContext
            check(feedbackRoot(base).deleteRecursively())
            PlaylistStore(feedbackContext(base)).add("Synthetic refresh feedback fixture",
                "#EXTM3U\n#EXTINF:-1,Synthetic Channel\nhttps://synthetic.invalid/channel\n")
            val started = runner.startActivitySync(Intent(base, EpgActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            if (started !is FeedbackCheckEpgActivity) {
                main { started.finish() }
                error("Isolated refresh Activity was not substituted")
            }
            activity = started
            await("initial confirmed off") { settled() && contains("Automatic refresh: off.") }
            main {
                button(enable).performClick()
                check(contains("Could not enable automatic refresh."))
                check(contains("Automatic refresh: off."))
                views().filterIsInstance<EditText>().single().setText("https://synthetic.invalid/guide?token=SECRET")
            }
            passed.add("Invalid URL identifies failed enable without replacing confirmed off state")

            main { activity.readGate = CountDownLatch(1) }
            confirmEnable()
            await("success before configuration read completes") { contains("Automatic refresh enabled.") }
            main {
                check(!button(enable).isEnabled && !button(disable).isEnabled && !button("Import XMLTV URL").isEnabled)
                check(texts().single { it.text.toString() == "Automatic refresh enabled." }.accessibilityLiveRegion == View.ACCESSIBILITY_LIVE_REGION_POLITE)
                activity.readGate!!.countDown()
            }
            await("confirmed enabled and persistent outcome") { settled() && contains("Automatic refresh: on every 6 hours") }
            main { check(contains("Automatic refresh enabled.")); safeCopy(); activity.readGate = null; activity.failSchedule = true }
            passed.add("Enable success persists after status read; controls remain locked until read completes")

            confirmEnable()
            await("failed replacement and confirmed prior on") { settled() && contains("Could not enable automatic refresh from the entered URL.") }
            main { check(contains("Automatic refresh: on every 6 hours")); safeCopy(); activity.failSchedule = false; activity.failDisable = true }
            passed.add("Synthetic scheduler failure retains applied on configuration and safe enable recovery copy")

            main { button(disable).performClick() }
            await("disable failure") { settled() && contains("Could not disable automatic refresh.") }
            main { check(contains("Automatic refresh: on every 6 hours")); safeCopy(); activity.failRead = true }
            main { button(disable).performClick() }
            await("read failure preserves last confirmed state") { settled() && contains("Last confirmed setting: Automatic refresh: on") }
            main {
                check(contains("Could not disable automatic refresh."))
                check(contains("Current status could not be checked."))
                safeCopy()
                activity.failRead = false
                activity.failDisable = false
            }
            passed.add("Disable and status-read failures stay distinct; last confirmed on is labeled, not presented as freshly verified")

            main { button(disable).performClick() }
            await("disable recovery") { settled() && contains("Automatic refresh: off.") }
            main { check(contains("Automatic refresh disabled.")); safeCopy() }
            passed.add("Retry disable succeeds, confirms off, and retains success feedback")

            main { activity.readGate = CountDownLatch(1) }
            main { button(disable).performClick() }
            await("outcome before leaving during status read") { contains("Automatic refresh disabled.") && !settled() }
            val outcomeBeforeFinish = main { texts().map { it.text.toString() } }
            main { activity.finish() }
            activity.readGate!!.countDown()
            await("destroyed after leaving during read") { activity.isDestroyed }
            runner.waitForIdleSync()
            main { check(texts().map { it.text.toString() } == outcomeBeforeFinish) }
            passed.add("Leaving during the follow-up read prevents late UI changes and destroys safely")
            result.putString("checks", passed.joinToString("\n"))
            result.putString("summary", "PASS: synthetic refresh feedback checks; fake scheduler, isolated stores, no provider traffic")
            code = Activity.RESULT_OK
        } catch (error: Throwable) {
            result.putString("passed", passed.joinToString("\n"))
            result.putString("failure", error.stackTraceToString())
        } finally {
            runCatching {
                if (::activity.isInitialized) {
                    activity.readGate?.countDown()
                    main { activity.finish() }
                    await("destroyed") { activity.isDestroyed }
                }
                check(feedbackRoot(runner.targetContext).deleteRecursively())
            }.onFailure {
                code = Activity.RESULT_CANCELED
                result.putString("cleanupFailure", it.stackTraceToString())
            }
        }
        runner.finish(code, result)
    }
}

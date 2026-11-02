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
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

private fun labelCheckRoot(base: Context) = File(base.cacheDir, "synthetic-epg-label-checks")

private fun labelCheckContext(base: Context): Context = object : ContextWrapper(base) {
    override fun getNoBackupFilesDir() = File(labelCheckRoot(base), "nobackup").apply { mkdirs() }
    override fun getFilesDir() = File(labelCheckRoot(base), "files").apply { mkdirs() }
    override fun getApplicationContext(): Context = this
}

class LabelCheckEpgBrowserActivity : EpgBrowserActivity() {
    override fun storageContext(): Context = labelCheckContext(baseContext)
}

/** Synthetic label checks never enumerate, read, or modify the user's saved stores. */
class EpgBrowserLabelChecks(private val runner: Instrumentation) {
    companion object {
        fun substitute(className: String): Activity? =
            if (className == EpgBrowserActivity::class.java.name) LabelCheckEpgBrowserActivity() else null
    }

    private lateinit var activity: LabelCheckEpgBrowserActivity
    private fun <T> main(block: () -> T): T {
        var outcome: Result<T>? = null
        runner.runOnMainSync { outcome = runCatching(block) }
        return checkNotNull(outcome).getOrThrow()
    }
    private fun await(name: String, test: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10000
        while (!main(test) && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
        check(main(test)) { "Timed out: $name" }
        runner.waitForIdleSync()
    }
    private fun descendants(view: View): List<View> = listOf(view) +
        ((view as? ViewGroup)?.let { group ->
            (0 until group.childCount).flatMap { descendants(group.getChildAt(it)) }
        } ?: emptyList())
    private fun views() = descendants(activity.window.decorView)
    private fun label(text: String) = views().filterIsInstance<TextView>().single {
        it !is EditText && it.text.toString() == text
    }
    private fun field(text: String): EditText = activity.findViewById(label(text).labelFor)
    private fun results() = views().filterIsInstance<ListView>().single()
    private fun programmes() = views().filterIsInstance<ScrollView>().single {
        it.childCount == 1 && it.getChildAt(0) is TextView
    }
    private fun button(text: String) = views().filterIsInstance<Button>().single { it.text.toString() == text }
    private fun focusState(): String {
        val focus = activity.currentFocus
        val text = (focus as? TextView)?.text?.toString()
        val list = results()
        return "focus=${focus?.javaClass?.simpleName}, id=${focus?.id}, text=$text, " +
            "touchMode=${activity.window.decorView.isInTouchMode}, " +
            "resultsFocusable=${list.isFocusable}, resultsTouchFocusable=${list.isFocusableInTouchMode}, " +
            "resultsFocused=${list.hasFocus()}, selected=${list.selectedItemPosition}, count=${list.count}"
    }
    private fun press(key: Int, route: String, expected: () -> Boolean) {
        runner.sendKeyDownUpSync(key)
        runner.waitForIdleSync()
        main { check(expected()) { "$route: ${focusState()}" } }
    }
    private fun downTo(target: () -> View, route: String) =
        press(KeyEvent.KEYCODE_DPAD_DOWN, route) { activity.currentFocus === target() }
    private fun upTo(target: () -> View, route: String) =
        press(KeyEvent.KEYCODE_DPAD_UP, route) { activity.currentFocus === target() }
    private fun resultAt(position: Int) = results().hasFocus() && results().selectedItemPosition == position
    private fun assertLabels() {
        val fields = listOf("Search channels", "Manual XMLTV ID").map { text ->
            val label = label(text)
            val editor = field(text)
            check(label.isShown && label.width > 0 && label.height > 0) { "Label is hidden: $text" }
            check(label.labelFor != View.NO_ID && editor.id == label.labelFor) { "Missing association: $text" }
            check(!label.isFocusable && !label.isClickable) { "Label creates a D-pad stop: $text" }
            check(editor.isFocusable && editor.isFocusableInTouchMode) { "Editor is not focusable: $text" }
            check(editor.contentDescription.isNullOrEmpty()) { "Description overrides native editor text: $text" }
            val node = editor.createAccessibilityNodeInfo()
            check(node.isEditable && node.className == EditText::class.java.name) { "Native edit semantics lost: $text" }
            check(!editor.hint.isNullOrBlank()) { "Supplemental hint missing: $text" }
            val parent = label.parent as ViewGroup
            check(editor.parent === parent && parent.indexOfChild(editor) == parent.indexOfChild(label) + 1) {
                "Label is not immediately before its field: $text"
            }
            editor
        }
        check(fields[0].id != fields[1].id) { "Editors share an ID" }
    }

    fun run() {
        val result = Bundle()
        val passed = mutableListOf<String>()
        var resultCode = Activity.RESULT_CANCELED
        try {
            val probeFailure = runCatching { main<Unit> { error("Synthetic UI-thread failure probe") } }.exceptionOrNull()
            check(probeFailure is IllegalStateException && probeFailure.message == "Synthetic UI-thread failure probe") {
                "UI-thread failure was not returned to the instrumentation thread"
            }
            passed.add("UI-thread assertion failures return to the runner for failure reporting and cleanup")
            runner.setInTouchMode(true)
            val context = labelCheckContext(runner.targetContext)
            check(labelCheckRoot(runner.targetContext).deleteRecursively())
            val seed = PlaylistStore(context)
            val playlistId = seed.add("Synthetic label fixture", "#EXTM3U\n" +
                "#EXTINF:-1 tvg-id=\"synthetic\",Synthetic Label Channel\nhttps://synthetic.invalid/channel\n" +
                "#EXTINF:-1 tvg-id=\"synthetic-other\",Synthetic Label Other\nhttps://synthetic.invalid/other\n",
                guide = EpgData(emptyList(), mapOf("synthetic" to setOf("Synthetic Label Channel")), 1000L))
            val store = PlaylistStore(context, playlistId)
            val snapshot = store.snapshot()
            val channelId = snapshot.channels!!.single { it.name == "Synthetic Label Channel" }.id
            store.saveEpg(snapshot.revision, store.loadEpg(snapshot.revision)!!.copy(
                mappings = mapOf(channelId to "synthetic-manual-id")))
            val started = runner.startActivitySync(Intent(runner.targetContext, EpgBrowserActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            if (started !is LabelCheckEpgBrowserActivity) {
                main { started.finish() }
                error("Isolated label Activity was not substituted")
            }
            activity = started
            await("search enabled") { field("Search channels").isEnabled }
            main {
                assertLabels()
                check(field("Search channels").text.isEmpty())
                check(field("Manual XMLTV ID").text.isEmpty())
                check(!field("Manual XMLTV ID").isEnabled)
                field("Search channels").setText("Synthetic Label")
            }
            passed.add("Empty and disabled fields retain visible associated labels and native edit semantics")
            await("two synthetic search results") { results().adapter?.count == 2 }
            main {
                check(field("Search channels").requestFocus())
                check(activity.window.decorView.isInTouchMode) { "Touch-mode regression precondition missing: ${focusState()}" }
            }
            repeat(2) {
                press(KeyEvent.KEYCODE_DPAD_DOWN, "Search to first result") {
                    resultAt(0) && !activity.window.decorView.isInTouchMode
                }
                press(KeyEvent.KEYCODE_DPAD_DOWN, "First to second result") { resultAt(1) }
                downTo(::programmes, "Last result to programmes before selection")
                downTo({ button("Back") }, "Disabled mapping controls skipped")
                upTo(::programmes, "Back to programmes with disabled controls")
                press(KeyEvent.KEYCODE_DPAD_UP, "Programmes to last result") { resultAt(1) }
                press(KeyEvent.KEYCODE_DPAD_UP, "Second to first result") { resultAt(0) }
                upTo({ field("Search channels") }, "First result to search")
            }
            passed.add("Repeated forward/reverse search, both result rows and programme traversal skip disabled controls")
            press(KeyEvent.KEYCODE_DPAD_DOWN, "Search to result for activation") { resultAt(0) }
            runner.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            await("stored mapping rendered") { field("Manual XMLTV ID").text.toString() == "synthetic-manual-id" }
            main {
                assertLabels()
                check(field("Search channels").text.toString() == "Synthetic Label")
                check(field("Manual XMLTV ID").isEnabled)
                check(resultAt(0)) { "Center unexpectedly moved focus: ${focusState()}" }
            }
            passed.add("Populated search and programmatically restored mapping retain labels; D-pad selects result")
            repeat(2) {
                press(KeyEvent.KEYCODE_DPAD_DOWN, "First to second result after selection") { resultAt(1) }
                downTo(::programmes, "Results to programmes")
                downTo({ field("Manual XMLTV ID") }, "Programmes to mapping editor")
                downTo({ button("Save mapping") }, "Mapping editor to Save")
                downTo({ button("Clear mapping") }, "Save to Clear")
                downTo({ button("Back") }, "Clear to Back")
                upTo({ button("Clear mapping") }, "Back to Clear")
                upTo({ button("Save mapping") }, "Clear to Save")
                upTo({ field("Manual XMLTV ID") }, "Save to mapping editor")
                upTo(::programmes, "Mapping editor to programmes")
                press(KeyEvent.KEYCODE_DPAD_UP, "Programmes to last result after selection") { resultAt(1) }
                press(KeyEvent.KEYCODE_DPAD_UP, "Last to first result after selection") { resultAt(0) }
            }
            upTo({ field("Search channels") }, "Results return to populated search")
            passed.add("Repeated full forward/reverse remote route reaches mapping, Save, Clear and Back without extra label stops")
            runner.setInTouchMode(true)
            main {
                check(field("Search channels").requestFocus())
                field("Search channels").setText("No synthetic match")
            }
            await("empty filtered results") { results().count == 0 }
            downTo(::programmes, "No-match search skips empty list")
            upTo({ field("Search channels") }, "Programmes returns past empty list")
            passed.add("Zero-result search retains a reversible remote route")
            main {
                field("Search channels").setText("")
                field("Manual XMLTV ID").setText("")
                assertLabels()
            }
            passed.add("Labels remain after clearing both populated fields")
            result.putString("checks", passed.joinToString("\n"))
            result.putString("summary", "PASS: ${passed.size} synthetic label checks; TalkBack speech not tested")
            resultCode = Activity.RESULT_OK
        } catch (error: Throwable) {
            result.putString("passed", passed.joinToString("\n"))
            result.putString("failure", error.stackTraceToString())
        } finally {
            runCatching {
                if (::activity.isInitialized) {
                    main { activity.finish() }
                    await("destroyed") { activity.isDestroyed }
                }
                check(labelCheckRoot(runner.targetContext).deleteRecursively())
            }.onFailure {
                resultCode = Activity.RESULT_CANCELED
                result.putString("cleanupFailure", it.stackTraceToString())
            }
        }
        runner.finish(resultCode, result)
    }
}

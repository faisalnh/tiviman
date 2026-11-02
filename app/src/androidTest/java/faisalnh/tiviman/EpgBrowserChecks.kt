package faisalnh.tiviman

import android.app.Activity
import android.app.Instrumentation
import android.app.job.JobScheduler
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import java.io.File
import java.security.MessageDigest

/** Isolated app storage for the EPG browser: a distinct directory under the cache. Never the real files. */
internal fun isolatedEpgBrowserRoot(base: Context): File = File(base.cacheDir, "isolated-epg-browser")

internal fun isolatedEpgBrowserContext(base: Context): Context {
    val root = isolatedEpgBrowserRoot(base)
    return object : ContextWrapper(base) {
        override fun getNoBackupFilesDir(): File = File(root, "nobackup").apply { mkdirs() }
        override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
        // EpgRefresh.updateMappings() normalizes to applicationContext: keep it inside the isolated root.
        override fun getApplicationContext(): Context = this
    }
}

/** Test-only substitution, installed by the runner's newActivity. Same root as [EpgBrowserChecks]. */
class IsolatedEpgBrowserActivity : EpgBrowserActivity() {
    private val isolated by lazy { isolatedEpgBrowserContext(baseContext) }
    override fun storageContext(): Context = isolated
}

/**
 * Stored-guide browser checks on an isolated store: no stream, no network, no EpgActivity, no scheduler writes.
 * Direct view clicks only (no remote/D-pad acceptance is claimed). Real app files and pending jobs are compared
 * before/after; per-step digests prove only the pinned playlist's epg.enc ever changes.
 *
 * Parent wiring (PrototypeChecks):
 *  - `private var epgBrowserTools = false` and `epgBrowserTools = arguments?.getString("epgBrowserTools") == "true"`
 *  - onStart: `if (epgBrowserTools) { EpgBrowserChecks(this).run(); return }`
 *  - newActivity: `if (epgBrowserTools) EpgBrowserChecks.substitute(className)?.let { return it }`
 */
class EpgBrowserChecks(private val runner: Instrumentation) {
    companion object {
        /** For the parent's newActivity: returns the isolated subclass only for this Activity, else null. */
        fun substitute(className: String): Activity? =
            if (className == EpgBrowserActivity::class.java.name) IsolatedEpgBrowserActivity() else null
        private const val GHOST = "ghost-xmltv-id-7"
        private const val URL_MARKER = "path-token-xyz"
    }

    private lateinit var activity: IsolatedEpgBrowserActivity

    private fun <T> main(block: () -> T): T {
        var value: T? = null
        runner.runOnMainSync { value = block() }
        @Suppress("UNCHECKED_CAST") return value as T
    }
    private fun await(label: String, timeout: Long = 20000, test: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeout
        while (!runCatching(test).getOrDefault(false) && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
        check(runCatching(test).getOrDefault(false)) { "Timed out: $label" }
        runner.waitForIdleSync()
    }
    private fun need(ok: Boolean, label: String) { if (!ok) throw AssertionError(label) }
    private fun descendants(view: View): List<View> =
        listOf(view) + ((view as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { descendants(g.getChildAt(it)) } } ?: emptyList())
    private fun views() = descendants(activity.window.decorView)
    private fun button(text: String) = views().filterIsInstance<Button>().first { it.text.toString() == text }
    private fun labeledField(text: String): EditText {
        val label = views().filterIsInstance<TextView>().first { it !is EditText && it.text.toString() == text }
        return activity.findViewById(label.labelFor)
    }
    private fun query() = labeledField("Search channels")
    private fun mapping() = labeledField("Manual XMLTV ID")
    private fun results() = views().filterIsInstance<ListView>().first()
    private fun allText() = views().filterIsInstance<TextView>().filter { it !is EditText }.joinToString("\n") { it.text }
    private fun eventsText() = views().filterIsInstance<TextView>().firstOrNull { it !is Button && it !is EditText && it.text.contains("XMLTV ID:") }?.text?.toString().orEmpty()
    private fun titleCount(text: String) = Regex("(Alpha|Beta) \\d\\d").findAll(text).count()

    private val isolatedRoot get() = isolatedEpgBrowserRoot(runner.targetContext)
    private val isolatedNoBackup get() = File(isolatedRoot, "nobackup")

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    /** Relative path => content digest for every isolated file, including the registry. */
    private fun tree(): Map<String, String> = isolatedNoBackup.walkTopDown().filter { it.isFile }
        .associate { it.relativeTo(isolatedNoBackup).path to sha(it.readBytes()) }
    private fun changed(before: Map<String, String>, after: Map<String, String>) =
        (before.keys + after.keys).filter { before[it] != after[it] }.toSet()

    /** Content digest of everything in the real app-private dirs outside the isolated root. */
    private fun realDigest(): String {
        val sha = MessageDigest.getInstance("SHA-256")
        fun walk(dir: File) {
            dir.listFiles()?.sortedBy { it.name }?.forEach { file ->
                if (file == isolatedRoot) return@forEach
                sha.update(file.name.toByteArray())
                if (file.isDirectory) walk(file) else sha.update(file.readBytes())
            }
        }
        walk(runner.targetContext.noBackupFilesDir); walk(runner.targetContext.filesDir)
        return sha.digest().joinToString("") { "%02x".format(it) }
    }
    private fun pendingJobs(): Set<Int> =
        (runner.targetContext.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler).allPendingJobs.map { it.id }.toSet()

    private fun launch() {
        val started = runner.startActivitySync(Intent(runner.targetContext, EpgBrowserActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        // Fail closed: the isolated subclass must have been substituted by newActivity (no monitor needed).
        if (started !is IsolatedEpgBrowserActivity) {
            (started as? Activity)?.let { a -> runner.runOnMainSync { a.finish() } }
            throw AssertionError("Isolated Activity was not substituted")
        }
        activity = started
        await("loaded") { main { query().isEnabled } }
    }
    private fun close() {
        val a = activity
        main { a.finish() }
        await("destroyed") { main { a.isDestroyed } }
    }
    private fun search(term: String) {
        main { query().setText(term) }
        await("results") { main { results().adapter?.count == 1 } }
    }
    private fun select() {
        main { val list = results(); list.performItemClick(list.adapter.getView(0, null, list), 0, 0) }
        await("selected") { main { eventsText().isNotEmpty() && button("Save mapping").isEnabled } }
    }
    /** Click Save/Clear, then wait until the worker finished (store updated and controls re-enabled). */
    private fun saveMapping(text: String?, store: PlaylistStore, revision: String, channelId: Int, expected: String?) {
        if (text != null) { main { mapping().setText(text) }; main { button("Save mapping").performClick() } }
        else main { button("Clear mapping").performClick() }
        await("persisted") {
            store.loadEpg(revision)?.mappings?.get(channelId) == expected && main { button("Save mapping").isEnabled && button("Clear mapping").isEnabled }
        }
    }

    private fun logcat(): String? = runCatching {
        val pfd = runner.uiAutomation.executeShellCommand("logcat -d -v brief")
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes().toString(Charsets.UTF_8) }
    }.getOrNull()

    fun run() {
        val result = Bundle()
        val passed = mutableListOf<String>()
        var stage = "setup"
        var before = ""
        try {
            val realJobs = pendingJobs()
            before = realDigest()
            isolatedRoot.deleteRecursively()
            runCatching { runner.uiAutomation.executeShellCommand("logcat -c").let { ParcelFileDescriptor.AutoCloseInputStream(it).use { s -> s.readBytes() } } }

            stage = "seed isolated store"
            val context = isolatedEpgBrowserContext(runner.targetContext)
            val seed = PlaylistStore(context)
            need(seed.playlists().isEmpty(), "Isolated store was not empty")
            fun m3u(a: String, b: String) = "#EXTM3U\n" +
                "#EXTINF:-1 tvg-id=\"alpha\" group-title=\"Synthetic\",$a\nhttps://browser-secret.invalid/$URL_MARKER/1\n" +
                "#EXTINF:-1 tvg-id=\"beta\" group-title=\"Synthetic\",$b\nhttps://browser-secret.invalid/$URL_MARKER/2\n"
            val first = Epg.minute() - 5
            fun guide(prefixA: String, prefixB: String) = EpgData(
                (0 until 48).flatMap { i -> listOf(
                    EpgEvent("alpha", "$prefixA %02d".format(i), first + 30 * i, first + 30 * i + 30, ""),
                    EpgEvent("beta", "$prefixB %02d".format(i), first + 30 * i, first + 30 * i + 30, "")) },
                mapOf("alpha" to setOf("Alpha One"), "beta" to setOf("Beta Two")), 1000L)
            // Other playlist first; the editor playlist is added last and becomes active.
            val idOther = seed.add("Other scope", m3u("Other One", "Other Two"), guide = guide("Alpha", "Beta"), activate = false)
            val idEditor = seed.add("Editor scope", m3u("Alpha One", "Beta Two"), guide = guide("Alpha", "Beta"), activate = true)
            need(idOther != idEditor && PlaylistStore(context).playlistId == idEditor, "Editor playlist is not active")
            for (id in listOf(idOther, idEditor)) {
                val s = PlaylistStore(context, id); val rev = s.snapshot().revision
                s.saveFavorites(rev, setOf(0)); s.saveHistory(rev, listOf(1)); s.saveTracks(rev, mapOf("0:1" to "seed"))
            }
            val store = PlaylistStore(context, idEditor)
            val otherStore = PlaylistStore(context, idOther)
            val revision = store.snapshot().revision
            val otherRevision = otherStore.snapshot().revision
            val alphaId = store.snapshot().channels!!.first { it.name == "Alpha One" }.id
            need(store.loadEpg(revision)!!.mappings.isEmpty() && store.loadEpg(revision)!!.events.size == 96, "Seed guide wrong")
            val editorEpg = "playlists/$idEditor/epg.enc"
            val allowed = setOf(editorEpg)
            val seededTree = tree()
            val otherEpgBefore = seededTree.getValue("playlists/$idOther/epg.enc")
            passed.add(stage)

            stage = "search, select, next 24h includes events past the first four"
            launch()
            need(main { activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0 }, "Window is not secure")
            search("alpha")
            select()
            val text = main { eventsText() }
            need(text.contains("XMLTV ID: alpha") && !text.contains("(manual)"), "Automatic id not shown")
            need(titleCount(text) == 48 && text.contains("Alpha 04") && text.contains("Alpha 47"), "Not the full next 24h")
            need(!text.contains("Showing "), "Unexpected cap notice for 48 events")
            val catalog = store.snapshot().channels!!
            val projected = Epg.project(catalog, store.loadEpg(revision)!!, Epg.minute()).first { it.id == alphaId }.programmes
            need(projected.size <= 4, "Projection is expected to stay at four rows")
            need(tree() == seededTree, "Browsing wrote files")
            passed.add(stage)

            stage = "unknown manual ID saves encrypted, keeps events, shows No information"
            val oldImported = store.loadEpg(revision)!!.importedAt
            var step = tree()
            saveMapping(GHOST, store, revision, alphaId, GHOST)
            var saved = store.loadEpg(revision)!!
            need(saved.events.size == 96 && saved.importedAt > oldImported, "Events lost or importedAt not newer")
            need(changed(step, tree()) == allowed, "Unexpected files changed")
            need(!File(isolatedNoBackup, editorEpg).readBytes().toString(Charsets.ISO_8859_1).contains(GHOST), "Mapping stored in plaintext")
            val ghostText = main { eventsText() }
            need(ghostText.contains("XMLTV ID: $GHOST (manual)") && ghostText.contains("No stored programmes") && titleCount(ghostText) == 0, "Ghost view wrong")
            need(Epg.project(store.snapshot().channels!!, saved, Epg.minute()).first { it.id == alphaId }.programmes.first().title == "No information", "Ghost not No information")
            passed.add(stage)

            stage = "clear restores automatic matching"
            step = tree()
            saveMapping(null, store, revision, alphaId, null)
            saved = store.loadEpg(revision)!!
            need(saved.mappings.isEmpty() && saved.events.size == 96, "Clear failed")
            need(changed(step, tree()) == allowed, "Unexpected files changed")
            val clearText = main { eventsText() }
            need(clearText.contains("XMLTV ID: alpha") && !clearText.contains("(manual)") && titleCount(clearText) == 48, "Automatic view not restored")
            need(Epg.project(store.snapshot().channels!!, saved, Epg.minute()).first { it.id == alphaId }.programmes.first().title == "Alpha 00", "Auto projection wrong")
            passed.add(stage)

            stage = "known other ID overrides and persists across a new Activity"
            step = tree()
            saveMapping("beta", store, revision, alphaId, "beta")
            need(changed(step, tree()) == allowed, "Unexpected files changed")
            need(!File(isolatedNoBackup, editorEpg).readBytes().toString(Charsets.ISO_8859_1).contains("Beta 00"), "Titles stored in plaintext")
            need(Epg.project(store.snapshot().channels!!, store.loadEpg(revision)!!, Epg.minute()).first { it.id == alphaId }.programmes.first().title == "Beta 00", "Override not applied")
            close(); launch(); search("alpha"); select()
            val again = main { eventsText() }
            need(again.contains("XMLTV ID: beta (manual)") && again.contains("Beta 00") && !again.contains("Alpha 00"), "Mapping not restored after reopen")
            passed.add(stage)

            stage = "pinned store: active playlist changes while editing, save targets the original"
            // Open Activity is still pinned to idEditor. Switch the registry's active playlist underneath it.
            need(PlaylistStore(context).playlistId == idEditor, "Unexpected active before switch")
            store.select(idOther)
            need(PlaylistStore(context).playlistId == idOther, "Active did not change")
            step = tree()
            val otherRaw = File(isolatedNoBackup, "playlists/$idOther/epg.enc").readBytes()
            saveMapping(null, store, revision, alphaId, null)
            need(store.loadEpg(revision)!!.mappings.isEmpty(), "Original playlist was not updated")
            need(changed(step, tree()) == allowed, "Save touched files outside the pinned playlist, including the registry")
            need(tree().getValue("playlists/$idOther/epg.enc") == otherEpgBefore && File(isolatedNoBackup, "playlists/$idOther/epg.enc").readBytes().contentEquals(otherRaw), "Other playlist guide changed")
            need(otherStore.loadEpg(otherRevision)!!.mappings.isEmpty(), "Other playlist mappings changed")
            passed.add(stage)

            stage = "no revisions or other scopes touched, no refresh job or config, no leaked URL text"
            val finalTree = tree()
            need(changed(seededTree, finalTree) == allowed + "playlists.enc", "Only the editor guide and the test's own select may differ")
            need(store.snapshot().revision == revision && otherStore.snapshot().revision == otherRevision, "A revision changed")
            for (id in listOf(idOther, idEditor)) {
                val s = PlaylistStore(context, id); val snap = s.snapshot()
                need(snap.favorites == setOf(0) && snap.history == listOf(1) && snap.tracks == mapOf("0:1" to "seed"), "Scoped preferences changed")
            }
            need(!File(isolatedNoBackup, "epg-refresh").exists(), "A refresh config was created")
            need(pendingJobs() == realJobs, "Scheduled jobs changed")
            need(!main { allText() }.contains(URL_MARKER), "A stream URL is visible in the UI")
            passed.add(stage)

            stage = "logs contain no URL"
            close()
            val log = logcat()
            if (log == null) passed.add("$stage (logcat unavailable: not verified)")
            else { need(!log.contains(URL_MARKER) && !log.contains("browser-secret"), "A URL reached the log"); passed.add(stage) }

            stage = "real app files unchanged"
            need(realDigest() == before, "Real app-private files changed")
            passed.add(stage)
            result.putString("checks", passed.joinToString("\n"))
            result.putString("summary", "PASS: ${passed.size} EPG browser checks (direct clicks; no remote acceptance, no network, no streams)")
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

    private fun cleanup() { isolatedRoot.deleteRecursively() }
}

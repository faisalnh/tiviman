package faisalnh.tiviman

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.*
import java.util.concurrent.Executors

/**
 * Explicit one-shot XMLTV import plus opt-in 6 hour automatic refresh. Never edits/reimports the private catalog.
 * Nothing is scheduled unless the user confirms the refresh dialog. All store/refresh IO runs on [worker].
 */
open class EpgActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val client = ProviderHttp()
    private val commitLock = Any()
    @Volatile private var canceled = false
    private var dialog: AlertDialog? = null
    // Only touched on the worker thread: the store (and its playlist id) are pinned once, then reused.
    private var pinned: PlaylistStore? = null

    /**
     * Context for stores and refresh configuration. Defaults to the application context (not `this`) so tests that
     * isolate storage by overriding getApplicationContext() keep working. Resolve on the main thread, once.
     */
    protected open fun storageContext(): Context = applicationContext

    protected open fun readRefreshStatus(storage: Context, id: String): EpgRefresh.Status? {
        val status = EpgRefresh.status(storage, id)
        check(status != null || !EpgRefresh.isEnabled(storage, id))
        return status
    }
    protected open fun enableRefresh(storage: Context, id: String, source: EpgRefresh.Source, mappings: Map<Int, String>) =
        EpgRefresh.enable(storage, id, source, mappings)
    protected open fun disableRefresh(storage: Context, id: String) = EpgRefresh.disable(storage, id)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CapturePolicy.secure(window)
        val storage = storageContext()
        fun pin(): PlaylistStore = pinned ?: PlaylistStore(storage).also { pinned = it }
        val pad = GlassMaterial.dp(this, 24f)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(GlassTokens.canvas); setPadding(pad, pad, pad, pad) }
        fun note(value: String) = TextView(this).apply { text = value; textSize = 18f; setTextColor(GlassTokens.textSecondary); body.addView(this) }
        body.addView(GlassPage.header(this, "EPG • XMLTV"))
        note("Import only an authorized XMLTV source. The existing guide is kept if import fails. No playlist refresh or automatic network update unless you opt in below.")
        val address = EditText(this).apply {
            hint = "XMLTV URL (XML or XML.gz)"; contentDescription = "XMLTV URL"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true; isSaveEnabled = false
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            }
        }
        body.addView(address)
        val status = note("Matching: manual mapping, then exact EPG ID, otherwise unique XMLTV display-name (case-insensitive). No fuzzy matching. Older saved catalogs may lack EPG IDs; no reimport is required for name matching.")
        val buttons = mutableListOf<Button>()
        fun lock(on: Boolean) { buttons.forEach { it.isEnabled = !on }; address.isEnabled = !on }
        fun import(provider: Boolean) {
            val entered = if (provider) "" else try { Playlist.url(address.text.toString()) } catch (_: Exception) {
                status.text = "Enter a URL that starts with http:// or https://."; return
            }
            lock(true)
            status.text = "Downloading and checking XMLTV…"
            worker.execute {
                try {
                    val store = pin()
                    val snapshot = store.snapshot()
                    val catalog = snapshot.channels ?: error("No saved playlist")
                    val config = snapshot.provider
                    val url = if (provider) Providers(config?.takeIf { it.type == SourceType.XTREAM }
                        ?: error("Xtream unavailable")).xmltvUrl() else entered
                    val previous = store.loadEpg(snapshot.revision)
                    val mappings = previous?.mappings.orEmpty()
                    val fetched = client.epg(url, catalog, mappings)
                    val data = fetched.copy(mappings = mappings, importedAt = Epg.monotonic(fetched.importedAt, previous?.importedAt))
                    val projected = Epg.project(catalog, data, Epg.minute())
                    val matched = projected.count { channel -> channel.programmes.any { it.title != "No information" } }
                    require(matched > 0)
                    synchronized(commitLock) {
                        check(!canceled)
                        check(store.snapshot().revision == snapshot.revision)
                        store.saveEpg(snapshot.revision, data)
                    }
                    runOnUiThread { if (!isFinishing && !canceled) {
                        status.text = "EPG saved • $matched channels have current/upcoming programmes. Back returns to the guide."
                    } }
                } catch (_: Exception) {
                    runOnUiThread { if (!isFinishing && !canceled) {
                        status.text = "EPG import failed, had no current/upcoming matches, or no saved playlist is available. Existing guide kept. Check XMLTV URL, channel IDs/names and device time."
                        lock(false)
                    } }
                }
            }
        }
        fun button(label: String, action: () -> Unit) {
            Button(this).apply { text = label; setOnClickListener { action() }; body.addView(this); buttons.add(this) }
        }
        button("Import XMLTV URL") { import(false) }
        button("Import saved Xtream XMLTV • uses saved account if available") { import(true) }
        button("Use playlist EPG source") {
            lock(true)
            status.text = "Looking for EPG sources declared by the saved playlist…"
            worker.execute {
                val urls = try { pin().suggestedEpgUrls().take(4) } catch (_: Exception) { emptyList() }
                val seen = mutableMapOf<String, Int>()
                val labels = urls.map { url ->
                    val host = try { java.net.URI(url).host?.takeIf { it.isNotBlank() } } catch (_: Exception) { null } ?: "unknown host"
                    val n = (seen[host] ?: 0) + 1; seen[host] = n
                    if (n > 1) "$host ($n)" else host
                }
                runOnUiThread {
                    if (isFinishing || canceled) return@runOnUiThread
                    if (urls.isEmpty()) {
                        status.text = "This playlist declares no EPG source. Enter a URL manually."; lock(false); return@runOnUiThread
                    }
                    status.text = "Choose a declared source. Nothing is downloaded or scheduled until you press Import or confirm Enable."
                    dialog?.dismiss()
                    dialog = GlassDialogs.builder(this).setTitle("Playlist EPG source (host only)")
                        .setItems(labels.toTypedArray()) { _, which ->
                            val url = urls[which]
                            address.setText(url)
                            status.text = "Source filled from the playlist. Check the URL, then press Import XMLTV URL or Enable 6h refresh. Nothing has been downloaded or scheduled."
                            lock(false)
                        }
                        .setNegativeButton("Cancel") { _, _ -> status.text = "No source selected. Nothing was downloaded."; lock(false) }
                        .setOnCancelListener { status.text = "No source selected. Nothing was downloaded."; lock(false) }
                        .create().also { CapturePolicy.secure(it.window); it.showGlass() }
                }
            }
        }

        note("Automatic refresh (optional)")
        val refreshStatus = note("Checking automatic refresh status…")
        val refreshOutcome = note("").apply {
            visibility = View.GONE
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        fun outcome(message: String) { refreshOutcome.text = message; refreshOutcome.visibility = View.VISIBLE }
        var confirmedRefresh: String? = null
        val refreshButtons = mutableListOf<Button>()
        fun showRefresh(unlockImports: Boolean = false) {
            worker.execute {
                val text = try {
                    val id = pin().playlistId
                    val s = readRefreshStatus(storage, id)
                    if (s == null) "Automatic refresh: off. Nothing is downloaded in the background."
                    else {
                        fun at(t: Long) = if (t <= 0) "never" else java.text.DateFormat.getDateTimeInstance().format(java.util.Date(t))
                        "Automatic refresh: on every 6 hours (${if (s.usesSavedXtream) "saved Xtream account" else "entered URL"}). Last success: ${at(s.lastSuccessAt)}." +
                            (if (s.lastFailure != null) " Last background update failed. Check the source and network before retrying an import." else "")
                    }
                } catch (_: Exception) { null }
                runOnUiThread { if (!isFinishing && !canceled) {
                    if (text != null) confirmedRefresh = text
                    refreshStatus.text = text ?: (confirmedRefresh?.let { "Last confirmed setting: $it\nCurrent status could not be checked. Reopen EPG settings to check again." }
                        ?: "Automatic refresh status unavailable. Check that a playlist is saved, then reopen EPG settings. No on/off state confirmed.")
                    refreshButtons.forEach { it.isEnabled = true }
                    if (unlockImports) buttons.forEach { it.isEnabled = true }
                } }
            }
        }
        fun refreshAction(label: String, failure: String, work: () -> String) {
            refreshButtons.forEach { it.isEnabled = false }; buttons.forEach { it.isEnabled = false }
            outcome(label)
            worker.execute {
                val message = try {
                    synchronized(commitLock) {
                        check(!canceled)
                        work()
                    }
                } catch (_: Exception) { failure }
                runOnUiThread { if (!isFinishing && !canceled) {
                    outcome(message); showRefresh(unlockImports = true)
                } }
            }
        }
        fun confirm(source: String, run: () -> Unit) {
            dialog?.dismiss()
            dialog = GlassDialogs.builder(this).setTitle("Enable automatic EPG refresh?")
                .setMessage("Every 6 hours, when any network is available, this app will download the guide from $source " +
                    "and replace the saved guide for this playlist. This uses network data and may incur mobile data costs or provider limits. " +
                    "It stays on until you disable it here. Nothing is scheduled if you cancel.")
                .setPositiveButton("Enable") { _, _ -> run() }
                .setNegativeButton("Cancel", null).create().also {
                    CapturePolicy.secure(it.window); it.showGlass()
                }
        }
        fun refreshButton(label: String, action: () -> Unit) {
            Button(this).apply { text = label; isEnabled = false; setOnClickListener { action() }; body.addView(this); refreshButtons.add(this) }
        }
        refreshButton("Enable 6h refresh • entered URL") {
            val entered = try { Playlist.url(address.text.toString()) } catch (_: Exception) {
                outcome("Could not enable automatic refresh. Enter a URL that starts with http:// or https://, then retry Enable 6h refresh."); return@refreshButton
            }
            confirm("the URL entered above (stored encrypted on this device)") {
                refreshAction("Enabling automatic refresh…", "Could not enable automatic refresh from the entered URL. Check the URL, saved playlist and available refresh slots, then retry Enable 6h refresh.") {
                    val store = pin()
                    val mappings = store.snapshot().let { store.loadEpg(it.revision)?.mappings.orEmpty() }
                    enableRefresh(storage, store.playlistId, EpgRefresh.Source.Url(entered), mappings)
                    "Automatic refresh enabled."
                }
            }
        }
        refreshButton("Enable 6h refresh • saved Xtream account") {
            confirm("this playlist's saved Xtream account") {
                refreshAction("Enabling automatic refresh…", "Could not enable automatic refresh from the saved Xtream account. Check this playlist's saved Xtream account and available refresh slots, then retry Enable 6h refresh.") {
                    val store = pin()
                    val mappings = store.snapshot().let { store.loadEpg(it.revision)?.mappings.orEmpty() }
                    enableRefresh(storage, store.playlistId, EpgRefresh.Source.SavedXtream, mappings)
                    "Automatic refresh enabled."
                }
            }
        }
        refreshButton("Disable automatic refresh") {
            refreshAction("Disabling automatic refresh…", "Could not disable automatic refresh. Check the configuration status before leaving. If it is still on, retry Disable automatic refresh; reopen EPG settings if status is unavailable.") {
                disableRefresh(storage, pin().playlistId); "Automatic refresh disabled."
            }
        }
        showRefresh()

        body.addView(Button(this).apply {
            text = "Browse guide / manual mappings"
            setOnClickListener { startActivity(android.content.Intent(this@EpgActivity, EpgBrowserActivity::class.java)) }
        })
        body.addView(Button(this).apply { text = "Back / Cancel"; setOnClickListener { finish() } })
        setContentView(ScrollView(this).apply { setBackgroundColor(GlassTokens.canvas); addView(body) })
    }
    override fun finish() {
        synchronized(commitLock) { canceled = true }; client.cancel(); super.finish()
    }
    override fun onDestroy() {
        synchronized(commitLock) { canceled = true }; client.cancel(); dialog?.dismiss(); worker.shutdownNow(); super.onDestroy()
    }
}

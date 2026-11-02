package faisalnh.tiviman

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.*
import java.util.concurrent.Executors

/** Stored-guide browser: next 24h per channel plus manual XMLTV ID mapping. No network, no playback. */
open class EpgBrowserActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val lock = Any()
    @Volatile private var closed = false
    // Written once on the worker under [lock]; the id is pinned so saves never follow a later active-playlist change.
    private var catalog: List<Channel> = emptyList()
    private var revision = ""
    private var playlistId = ""
    private var data: EpgData? = null
    private var selected: Channel? = null

    /** Same contract as EpgActivity.storageContext(): application context by default, overridable for isolation. */
    protected open fun storageContext(): Context = applicationContext

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CapturePolicy.secure(window)
        val storage = storageContext()
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(GlassTokens.canvas); setPadding(dp(24), dp(24), dp(24), dp(24)) }
        val header = GlassPage.header(this, "Loading saved guide…") as LinearLayout
        val status = header.getChildAt(0) as TextView
        val query = EditText(this).apply {
            id = View.generateViewId()
            hint = "Enter all or part of a channel name"; isSingleLine = true; isEnabled = false; isSaveEnabled = false
        }
        val queryLabel = TextView(this).apply {
            text = "Search channels"; labelFor = query.id
        }
        val results = ListView(this).apply { layoutParams = LinearLayout.LayoutParams(-1, dp(160)) }
        val events = TextView(this).apply { textSize = 16f; setTextColor(GlassTokens.textPrimary) }
        val eventsScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(240)); isFocusable = true; addView(events)
        }
        val mapping = EditText(this).apply {
            id = View.generateViewId()
            hint = "ID may be absent from the current guide"; isSingleLine = true; isEnabled = false; isSaveEnabled = false
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val mappingLabel = TextView(this).apply {
            text = "Manual XMLTV ID"; labelFor = mapping.id
        }
        val save = Button(this).apply { text = "Save mapping"; isEnabled = false }
        val clear = Button(this).apply { text = "Clear mapping"; isEnabled = false }
        val back = Button(this).apply { text = "Back"; setOnClickListener { finish() } }
        listOf(queryLabel, query).forEach { header.addView(it) }
        results.setBackgroundColor(GlassTokens.surface)
        listOf(header, results, eventsScroll, mappingLabel, mapping, save, clear, back).forEach { body.addView(it) }
        setContentView(ScrollView(this).apply { setBackgroundColor(GlassTokens.canvas); isFillViewport = true; addView(body) })

        // Nested scroll containers cannot infer a reliable remote route across editor/list boundaries.
        fun moveFocus(target: View, direction: Int): Boolean {
            // Editors can retain touch-mode focus; lists/buttons cannot accept requestFocus in that mode.
            // Leave touch mode for remote navigation so ListView also exposes its selected-row state.
            return if (target.isInTouchMode) target.requestFocusFromTouch() else target.requestFocus(direction)
        }
        query.setOnKeyListener { _, key, event ->
            event.action == KeyEvent.ACTION_DOWN && key == KeyEvent.KEYCODE_DPAD_DOWN &&
                if (results.count > 0) {
                    moveFocus(results, View.FOCUS_DOWN).also { if (it) results.setSelection(0) }
                } else moveFocus(eventsScroll, View.FOCUS_DOWN)
        }
        results.setOnKeyListener { _, key, event ->
            if (event.action != KeyEvent.ACTION_DOWN) false else when {
                key == KeyEvent.KEYCODE_DPAD_UP && results.selectedItemPosition <= 0 ->
                    moveFocus(query, View.FOCUS_UP)
                key == KeyEvent.KEYCODE_DPAD_DOWN && results.selectedItemPosition == results.count - 1 ->
                    moveFocus(eventsScroll, View.FOCUS_DOWN)
                else -> false
            }
        }
        eventsScroll.setOnKeyListener { _, key, event ->
            if (event.action != KeyEvent.ACTION_DOWN) false else when {
                key == KeyEvent.KEYCODE_DPAD_UP && !eventsScroll.canScrollVertically(-1) ->
                    if (results.count > 0) {
                        moveFocus(results, View.FOCUS_UP).also { if (it) results.setSelection(results.count - 1) }
                    } else moveFocus(query, View.FOCUS_UP)
                key == KeyEvent.KEYCODE_DPAD_DOWN && !eventsScroll.canScrollVertically(1) ->
                    moveFocus(if (mapping.isEnabled) mapping else back, View.FOCUS_DOWN)
                else -> false
            }
        }
        mapping.setOnKeyListener { _, key, event ->
            if (event.action != KeyEvent.ACTION_DOWN) false else when (key) {
                KeyEvent.KEYCODE_DPAD_UP -> moveFocus(eventsScroll, View.FOCUS_UP)
                KeyEvent.KEYCODE_DPAD_DOWN -> moveFocus(save, View.FOCUS_DOWN)
                else -> false
            }
        }
        listOf(save, clear, back).forEach { it.id = View.generateViewId() }
        save.nextFocusUpId = mapping.id
        save.nextFocusDownId = clear.id
        clear.nextFocusUpId = save.id
        clear.nextFocusDownId = back.id
        back.setOnKeyListener { _, key, event ->
            event.action == KeyEvent.ACTION_DOWN && key == KeyEvent.KEYCODE_DPAD_UP &&
                moveFocus(if (clear.isEnabled) clear else eventsScroll, View.FOCUS_UP)
        }

        var shown: List<Channel> = emptyList()
        fun render(channel: Channel) {
            val guide = data ?: return
            val view = Epg.browse(channel, guide, Epg.minute())
            mapping.setText(guide.mappings[channel.id].orEmpty())
            events.text = buildString {
                append(channel.name).append(" • XMLTV ID: ").append(view.xmltvId ?: "none")
                    .append(if (view.manual) " (manual)" else "").append('\n')
                if (view.events.isEmpty()) append("No stored programmes in the next 24 hours.\n")
                view.events.forEach { append(Epg.time(it.start)).append('–').append(Epg.time(it.end)).append("  ").append(it.title.take(120)).append('\n') }
                if (view.total > view.events.size) append("Showing ${view.events.size} of ${view.total} programmes.")
            }
            eventsScroll.scrollTo(0, 0)
            mapping.isEnabled = true; save.isEnabled = true; clear.isEnabled = true
        }
        fun persist(channel: Channel, id: String?) {
            val guide = data ?: return
            val updated = try { Epg.withMapping(guide, channel.id, id) } catch (_: Exception) { status.text = "Invalid XMLTV ID."; return }
            save.isEnabled = false; clear.isEnabled = false
            val expected = revision; val pinnedId = playlistId
            worker.execute {
                val message = try {
                    synchronized(lock) {
                        check(!closed)
                        val store = PlaylistStore(storage, pinnedId)
                        check(store.snapshot().revision == expected) { "stale" }
                        // Keep old events; only the mapping changes. Newer importedAt so this copy wins over any embedded cache.
                        val next = updated.copy(importedAt = Epg.monotonic(System.currentTimeMillis(), guide.importedAt))
                        store.saveEpg(expected, next)
                        val reread = store.loadEpg(expected)
                        if (reread?.mappings != next.mappings) {
                            "Saved guide, but the store did not persist mappings. Mapping is not durable."
                        } else {
                            data = reread
                            val synced = runCatching { EpgRefresh.updateMappings(storage, pinnedId, next.mappings) }.isSuccess
                            "Mapping saved. Reopen the guide to apply it." + if (synced) "" else " Automatic refresh settings could not be updated."
                        }
                    }
                } catch (_: Exception) { "Could not save mapping; the playlist or guide changed. Reopen and retry." }
                runOnUiThread { if (!isFinishing && !closed) { status.text = message; selected?.let { render(it) } } }
            }
        }
        query.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                shown = ChannelTools.search(catalog, s.toString(), 200)
                results.adapter = GlassDialogs.itemsAdapter(this@EpgBrowserActivity, shown.map { it.name })
                status.text = if (s.isNullOrBlank()) "Type to search (max 200 results)."
                    else "${shown.size} result(s)" + if (shown.size == 200) " — capped at 200, refine search." else ""
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        results.setOnItemClickListener { _, _, position, _ -> shown.getOrNull(position)?.let { selected = it; render(it) } }
        save.setOnClickListener {
            val channel = selected ?: return@setOnClickListener
            val text = mapping.text.toString()
            if (text.isBlank()) status.text = "Enter an ID or use Clear." else persist(channel, text)
        }
        clear.setOnClickListener { selected?.let { persist(it, null) } }

        worker.execute {
            try {
                val store = PlaylistStore(storage)
                val id = store.playlistId
                val snapshot = store.snapshot()
                val loaded = snapshot.channels ?: error("No saved playlist")
                val guide = store.loadEpg(snapshot.revision) ?: error("No guide")
                synchronized(lock) { catalog = loaded; revision = snapshot.revision; playlistId = id; data = guide }
                runOnUiThread { if (!isFinishing && !closed) { status.text = "Type to search channels (max 200 results)."; query.isEnabled = true } }
            } catch (_: Exception) {
                runOnUiThread { if (!isFinishing && !closed) status.text = "No saved playlist with an imported guide. Import XMLTV first." }
            }
        }
    }
    override fun finish() { closed = true; super.finish() }
    override fun onDestroy() { closed = true; worker.shutdownNow(); super.onDestroy() }
}

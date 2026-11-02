package faisalnh.tiviman

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.content.Intent
import android.text.InputFilter
import android.view.WindowManager
import android.widget.*
import java.util.concurrent.Executors

/** Local-only playlist selection and management; no automatic network request or playback. */
class PlaylistsActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private fun alive() = !isFinishing && !isDestroyed
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CapturePolicy.secure(window)
    }
    override fun onStart() { super.onStart(); load() }
    private fun load(focusId: String? = null, message: String? = null) {
        fun dp(value: Int) = GlassMaterial.dp(this, value.toFloat())
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(GlassTokens.canvas)
            setPadding(dp(40), dp(24), dp(40), dp(24))
        }
        body.addView(GlassPage.header(this, "Playlists", "Choose a playlist or manage its saved data.").apply {
            background = null
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) }
            setPadding(0, 0, 0, 0)
        })
        val status = TextView(this).apply {
            text = "Loading saved playlists…"
            TvTypography.apply(this, 16)
            setTextColor(GlassTokens.textSecondary)
            setPadding(0, 0, 0, dp(16))
            accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        body.addView(status)
        fun action(label: String, centered: Boolean = false) = Button(this).apply {
            text = label
            isAllCaps = false
            gravity = android.view.Gravity.CENTER_VERTICAL or
                if (centered) android.view.Gravity.CENTER_HORIZONTAL else android.view.Gravity.START
            TvTypography.apply(this, 18)
            setTextColor(GlassMaterial.focusText())
            background = GlassMaterial.focusable(this@PlaylistsActivity, 12f)
            backgroundTintList = null
            minimumHeight = dp(48)
            minimumWidth = 0
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        fun addAction(label: String, click: () -> Unit) {
            body.addView(action(label).apply { setOnClickListener { click() } },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
        setContentView(ScrollView(this).apply { setBackgroundColor(GlassTokens.canvas); addView(body) })
        worker.execute {
            try {
                val store = PlaylistStore(this)
                val entries = store.playlists()
                runOnUiThread {
                    if (!alive()) return@runOnUiThread
                    status.text = (message?.plus("\n") ?: "") + "Switching playlists does not start playback. EPG, favorites and history stay separate."
                    entries.forEach { entry ->
                        val row = LinearLayout(this).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = android.view.Gravity.CENTER_VERTICAL
                            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
                        }
                        val button = action(entry.name + if (entry.id == store.playlistId) " • active" else "")
                        row.addView(button, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                        val manage = action("Manage", centered = true).apply {
                            contentDescription = "Manage ${entry.name}"
                            setOnClickListener { manage(store, entries, entry) }
                        }
                        row.addView(manage, LinearLayout.LayoutParams(dp(112), -2).apply { leftMargin = dp(12) })
                        body.addView(row)
                        if (entry.id == focusId) manage.requestFocus()
                        else if (focusId == null && entry == entries.first()) button.requestFocus()
                        button.setOnClickListener {
                            button.isEnabled = false
                            worker.execute {
                                try { store.select(entry.id); runOnUiThread { if (alive()) { setResult(RESULT_OK); finish() } } }
                                catch (_: Exception) { runOnUiThread { if (alive()) { status.text = "Playlist could not be opened. Saved data kept."; button.isEnabled = true } } }
                            }
                        }
                    }
                    body.addView(TextView(this).apply {
                        text = "Playlist actions"
                        TvTypography.apply(this, 16)
                        setTextColor(GlassTokens.textSecondary)
                        setPadding(0, dp(16), 0, dp(4))
                    })
                    addAction("All playlists • combined channel browser") { startActivity(Intent(this, AllPlaylistsActivity::class.java)) }
                    addAction("Add playlist") { startActivity(Intent(this, PlaylistActivity::class.java)) }
                    addAction("Back") { finish() }
                }
            } catch (_: Exception) { runOnUiThread { if (alive()) status.text = "Saved playlist list could not be opened. Data kept." } }
        }
    }
    private fun mutate(id: String, operation: () -> Unit) {
        worker.execute {
            val message = try { operation(); "Playlist list updated." }
            catch (_: Exception) { "Playlist list could not be updated. Reopen Manage and try again. Active/last playlists cannot be deleted." }
            runOnUiThread { if (alive()) load(id, message) }
        }
    }
    private fun manage(store: PlaylistStore, entries: List<PlaylistStore.Entry>, entry: PlaylistStore.Entry) {
        val index = entries.indexOfFirst { it.id == entry.id }
        val actions = mutableListOf("Rename")
        if (index > 0) actions += "Move up"
        if (index < entries.lastIndex) actions += "Move down"
        val canDelete = entry.id != store.playlistId && entries.size > 1
        if (canDelete) actions += "Delete…"
        else actions += "Delete unavailable • active playlist (switch first)"
        val dialog = GlassDialogs.builder(this).setTitle("Manage ${entry.name}")
            .setItems(actions.toTypedArray()) { _, which ->
                when (actions[which]) {
                    "Rename" -> rename(store, entry)
                    "Move up" -> mutate(entry.id) { store.reorder(entry.id, index - 1) }
                    "Move down" -> mutate(entry.id) { store.reorder(entry.id, index + 1) }
                    "Delete…" -> confirmDelete(store, entry, entries.getOrNull(if (index > 0) index - 1 else 1)?.id)
                    else -> StatusCapsule.show(this, "Switch to another playlist before deleting this one. The last playlist cannot be deleted.", long = true)
                }
            }.setNegativeButton("Cancel", null).create()
        CapturePolicy.secure(dialog.window)
        dialog.showGlass()
    }
    private fun rename(store: PlaylistStore, entry: PlaylistStore.Entry) {
        val input = EditText(this).apply {
            setSingleLine(true); setText(entry.name); selectAll()
            filters = arrayOf(InputFilter.LengthFilter(80))
            contentDescription = "Playlist name, 1 to 80 characters"
        }
        val dialog = GlassDialogs.builder(this).setTitle("Rename playlist")
            .setMessage("Enter a name (1–80 characters; surrounding spaces are removed).")
            .setView(input).setNegativeButton("Cancel", null).setPositiveButton("Save", null).create()
        CapturePolicy.secure(dialog.window)
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text.toString().trim()
                if (name.isEmpty() || name.length > 80) input.error = "Enter 1–80 characters"
                else { dialog.dismiss(); mutate(entry.id) { store.rename(entry.id, name) } }
            }
            input.requestFocus()
        }
        dialog.showGlass()
    }
    private fun confirmDelete(store: PlaylistStore, entry: PlaylistStore.Entry, nextFocus: String?) {
        val dialog = GlassDialogs.builder(this).setTitle("Delete ${entry.name}?")
            .setMessage("Permanently remove this playlist and its saved EPG, favorites, history and playback choices from this device? This cannot be undone. Other playlists are kept.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete permanently") { _, _ -> mutate(nextFocus ?: entry.id) { store.delete(entry.id); EpgRefresh.onPlaylistDeleted(applicationContext, entry.id) } }.create()
        CapturePolicy.secure(dialog.window)
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus() }
        dialog.showGlass()
    }
    override fun onDestroy() { worker.shutdownNow(); super.onDestroy() }
}

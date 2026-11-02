package faisalnh.tiviman

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/** Pure rules and limits. No Android types, so JVM tests can use it without loading the Activity. */
internal object AdvancedRules {
    const val MAX_RECORDING_BYTES = 256L * 1024 * 1024
    const val MAX_RECORDING_MILLIS = 30L * 60 * 1000
    const val MAX_SAVED_RECORDINGS = 10
    const val MIN_FREE_BYTES = MAX_RECORDING_BYTES + 64L * 1024 * 1024
    const val STALE_PART_MILLIS = 120_000L
    private val SAFE_RECORDING = Regex("[a-f0-9-]{36}\\.rec")

    fun isSafeRecordingName(name: String) = SAFE_RECORDING.matches(name)

    fun isManifestPath(address: String): Boolean {
        val path = try { URI(address).path.orEmpty().lowercase() } catch (_: Exception) { return true }
        return path.endsWith(".m3u8") || path.endsWith(".m3u") || path.endsWith(".mpd")
    }

    /** First bytes of a "stream" that are really a playlist/manifest/document. */
    fun looksLikeManifest(head: ByteArray, length: Int): Boolean {
        var i = 0
        val end = length.coerceAtMost(head.size).coerceAtMost(64)
        if (end >= 3 && head[0] == 0xEF.toByte() && head[1] == 0xBB.toByte() && head[2] == 0xBF.toByte()) i = 3
        while (i < end && head[i].toInt().let { it == 32 || it == 9 || it == 10 || it == 13 }) i++
        if (i >= end) return false
        val marker = "#EXTM3U".toByteArray()
        if (end - i >= marker.size && marker.indices.all { head[i + it] == marker[it] }) return true
        return head[i] == '<'.code.toByte() || head[i] == '{'.code.toByte() || head[i] == '['.code.toByte()
    }

    fun unsupportedContentType(type: String?): Boolean {
        val t = type?.lowercase().orEmpty()
        return t.contains("mpegurl") || t.contains("dash+xml") || t.contains("json") || t.contains("html") ||
            t.contains("xml") || t.startsWith("text/")
    }
}

/**
 * One bounded HTTP capture. Only touches [dir] after the connection succeeds, writes `<uuid>.part`,
 * and renames to `<uuid>.rec` on success. The clock is injectable (JVM tests); the default is SystemClock.
 * The clock is read once at start and once per read-loop iteration.
 */
internal class AdvancedRecorder(
    private val dir: File,
    private val address: String,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
    private val maxBytes: Long = AdvancedRules.MAX_RECORDING_BYTES,
    private val maxMillis: Long = AdvancedRules.MAX_RECORDING_MILLIS,
    private val onProgress: (Long, Long) -> Unit = { _, _ -> },
    private val onDone: (AdvancedRecorder, Result, Long) -> Unit = { _, _, _ -> },
) {
    enum class Result { SAVED, LIMIT, UNSUPPORTED, FAILED, CANCELED }
    private class Unsupported : Exception()
    private class Canceled : Exception()
    @Volatile private var stopRequested = false
    @Volatile private var connection: HttpURLConnection? = null

    /** Safe from the UI thread: the socket is closed on a separate thread. */
    fun stop() {
        stopRequested = true
        val c = connection ?: return
        Thread({ try { c.disconnect() } catch (_: Throwable) {} }, "recording-cancel").apply { isDaemon = true }.start()
    }

    private fun open(): HttpURLConnection {
        var next = Playlist.url(address)
        val origin = URI(next)
        for (hop in 0..5) {
            val c = URL(next).openConnection() as HttpURLConnection
            connection = c
            if (stopRequested) { c.disconnect(); throw Canceled() }
            c.instanceFollowRedirects = false
            c.connectTimeout = 15000; c.readTimeout = 15000
            c.setRequestProperty("Accept-Encoding", "identity")
            val code = c.responseCode
            if (code in listOf(301, 302, 303, 307, 308)) {
                val location = c.getHeaderField("Location"); c.disconnect()
                if (hop == 5 || location == null) throw IOException()
                next = Playlist.redirect(next, location)
                val target = URI(next)
                if (target.scheme != origin.scheme || target.host != origin.host || target.port != origin.port) throw IOException()
                continue
            }
            if (code != 200) { c.disconnect(); throw IOException() }
            return c
        }
        throw IOException()
    }

    fun run() {
        val id = UUID.randomUUID().toString()
        val part = File(dir, "$id.part"); val done = File(dir, "$id.rec")
        active.add(part.name)
        var bytes = 0L; var limit = false; var failure: Throwable? = null
        var out: FileOutputStream? = null
        var input: InputStream? = null
        try {
            if (stopRequested) throw Canceled()
            if (AdvancedRules.isManifestPath(address)) throw Unsupported()
            val c = open()
            if (AdvancedRules.unsupportedContentType(c.contentType)) throw Unsupported()
            dir.mkdirs()
            val started = clock()
            var lastReport = 0L
            val buffer = ByteArray(64 * 1024)
            out = FileOutputStream(part)
            input = c.inputStream
            while (!stopRequested) {
                val elapsed = clock() - started
                if (elapsed >= maxMillis) { limit = true; break }
                val n = input.read(buffer)
                if (n < 0) break
                if (bytes == 0L && AdvancedRules.looksLikeManifest(buffer, n)) throw Unsupported()
                val take = minOf(n.toLong(), maxBytes - bytes).toInt()
                out.write(buffer, 0, take); bytes += take
                if (bytes >= maxBytes) { limit = true; break }
                if (elapsed - lastReport >= 1000) { lastReport = elapsed; onProgress(bytes, elapsed) }
            }
            out.flush(); out.fd.sync()
        } catch (error: Throwable) {
            // A user stop closes the socket mid-read; with data captured that is a normal end.
            if (!(stopRequested && bytes > 0 && error is IOException)) failure = error
        } finally {
            // Disconnect first so closing a partly read stream can never wait on the server.
            try { connection?.disconnect() } catch (_: Throwable) {}
            connection = null
            try { input?.close() } catch (_: Throwable) {}
            try { out?.close() } catch (_: Throwable) {}
        }
        val success = failure == null && bytes > 0 && part.renameTo(done)
        if (!success) part.delete()
        active.remove(part.name)
        val result = when {
            success && limit -> Result.LIMIT
            success -> Result.SAVED
            failure is Unsupported -> Result.UNSUPPORTED
            stopRequested -> Result.CANCELED
            else -> Result.FAILED
        }
        onDone(this, result, if (success) bytes else 0L)
    }

    companion object {
        private val active: MutableSet<String> = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
        fun isActive(name: String) = name in active

        /** Deletes only old `.part` files that no running recorder owns. Returns the number deleted. */
        fun sweepStale(dir: File, now: Long, maxAgeMillis: Long): Int {
            var deleted = 0
            dir.listFiles()?.forEach { file ->
                if (file.isFile && file.name.endsWith(".part") && !isActive(file.name) &&
                    now - file.lastModified() > maxAgeMillis && !isActive(file.name) && file.delete()) deleted++
            }
            return deleted
        }
    }
}

/**
 * Foreground-only advanced playback: bounded recording of direct streams, and two-channel multiview.
 *
 * Contract (intent extras, all required):
 *  - [EXTRA_MODE]        "recordings" or "multiview"
 *  - [EXTRA_PLAYLIST_ID] the playlist the caller captured when launching (never "the active one" at read time)
 *  - [EXTRA_CHANNEL_ID]  Int id of the currently tuned channel (-1 allowed in recordings mode)
 *
 * All stores and files use [storageContext], which tests override to isolate storage.
 * Nothing starts automatically; every connection and player is released in onStop.
 * Stream addresses, credentials and provider messages are never logged, shown or persisted.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
open class AdvancedPlaybackActivity : Activity() {
    companion object {
        const val EXTRA_MODE = "mode"
        const val EXTRA_PLAYLIST_ID = "playlistId"
        const val EXTRA_CHANNEL_ID = "channelId"
        const val MODE_RECORDINGS = "recordings"
        const val MODE_MULTIVIEW = "multiview"
        private const val MAX_PICKER_ROWS = 100
        private const val RECORDINGS_DIR = "recordings"

        fun intent(context: Context, mode: String, playlistId: String, channelId: Int): Intent =
            Intent(context, AdvancedPlaybackActivity::class.java)
                .putExtra(EXTRA_MODE, mode).putExtra(EXTRA_PLAYLIST_ID, playlistId).putExtra(EXTRA_CHANNEL_ID, channelId)
    }

    /** Where playlist stores and recordings live. Open so tests can substitute an isolated context. */
    protected open fun storageContext(): Context = this

    private class Catalog(val channels: List<Channel>, val stalker: Boolean) {
        fun supported(channel: Channel) = !stalker && !channel.stream.startsWith("stalker:") &&
            (channel.stream.startsWith("https://") || channel.stream.startsWith("http://"))
    }
    private class SavedFile(val name: String, val size: Long, val modified: Long)

    private val ui = Handler(Looper.getMainLooper())
    private val io: ExecutorService = Executors.newSingleThreadExecutor()
    // Recordings run one at a time: a second start queues behind the first instead of running in parallel.
    private val recordIo: ExecutorService = Executors.newSingleThreadExecutor()
    @Volatile private var destroyed = false
    private var started = false
    private var sessionGeneration = 0
    private var pendingStart = false
    private var mode = ""
    private var playlistId = ""
    private var channelId = -1
    private var catalog: Catalog? = null
    private var dialog: AlertDialog? = null
    private lateinit var status: TextView

    // Recordings
    private var recorder: AdvancedRecorder? = null
    private var recordButton: Button? = null
    private var stopRecordButton: Button? = null
    private var savedList: ListView? = null
    private val saved = mutableListOf<SavedFile>()
    private var savedAdapter: BaseAdapter? = null
    private var playbackHolder: FrameLayout? = null
    private var recordingPlayer: ExoPlayer? = null
    private var playingName: String? = null

    // Multiview
    private val selected = LinkedHashSet<Int>()
    private var pickerRows = emptyList<Channel>()
    private var pickerAdapter: BaseAdapter? = null
    private var pickerGeneration = 0
    private var selectionView: LinearLayout? = null
    private var viewingView: LinearLayout? = null
    private var startButton: Button? = null
    private var selectedLabel: TextView? = null
    private val players = arrayOfNulls<ExoPlayer>(2)
    private var audioIndex = 0
    private var audioButton: Button? = null
    private var multiviewNames = arrayOf("", "")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CapturePolicy.secure(window)
        androidx.media3.common.util.Log.setLogLevel(androidx.media3.common.util.Log.LOG_LEVEL_OFF)
        mode = intent.getStringExtra(EXTRA_MODE).orEmpty()
        playlistId = intent.getStringExtra(EXTRA_PLAYLIST_ID).orEmpty()
        channelId = intent.getIntExtra(EXTRA_CHANNEL_ID, -1)
        if ((mode != MODE_RECORDINGS && mode != MODE_MULTIVIEW) || playlistId.isEmpty()) {
            StatusCapsule.show(this, "Missing playback request."); finish(); return
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(GlassTokens.canvas); setPadding(dp(32), dp(24), dp(32), dp(24))
        }
        status = label("Loading saved playlist…")
        if (mode == MODE_RECORDINGS) buildRecordings(root) else buildMultiview(root)
        setContentView(root)
        val store = storageContext()
        background {
            val loaded = try {
                val snap = PlaylistStore(store, playlistId).snapshot()
                Catalog(snap.channels.orEmpty(), snap.provider?.type == SourceType.STALKER)
            } catch (_: Throwable) { null }
            runOnUiThread { if (!destroyed) onCatalog(loaded) }
        }
    }

    override fun onStart() {
        super.onStart()
        started = true; sessionGeneration++
        if (mode == MODE_RECORDINGS) refreshSaved()
        // Returning never resumes streams; make sure the picker is what the user sees.
        if (mode == MODE_MULTIVIEW && players.none { it != null }) showSelection(null)
    }

    override fun onStop() {
        started = false; sessionGeneration++; pendingStart = false
        dialog?.dismiss(); dialog = null
        recorder?.stop()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        releaseRecordingPlayer()
        val hadPlayers = players.any { it != null }
        releaseMultiview()
        if (hadPlayers) showSelection("Multiview stopped when you left the screen. Choose two channels to start again.")
        super.onStop()
    }

    override fun onDestroy() {
        destroyed = true; started = false
        recorder?.stop()
        releaseRecordingPlayer(); releaseMultiview()
        io.shutdownNow(); recordIo.shutdown()
        catalog = null; pickerRows = emptyList(); selected.clear()
        ui.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    @Deprecated("Back closes the embedded player or multiview first")
    override fun onBackPressed() {
        when {
            recordingPlayer != null -> releaseRecordingPlayer()
            players.any { it != null } -> { releaseMultiview(); showSelection("Stopped. Choose two channels.") }
            else -> super.onBackPressed()
        }
    }

    private fun background(task: () -> Unit) {
        try { io.execute(task) } catch (_: RejectedExecutionException) {}
    }

    private fun recordingsDir() = File(storageContext().filesDir, RECORDINGS_DIR)

    private fun showDialog(builder: AlertDialog.Builder): AlertDialog {
        dialog?.dismiss()
        val created = builder.create()
        CapturePolicy.secure(created.window)
        created.showGlass()
        dialog = created
        return created
    }

    private fun onCatalog(loaded: Catalog?) {
        catalog = loaded
        when {
            loaded == null -> status.text = "Could not open this playlist."
            loaded.stalker -> status.text = "Stalker portals are not supported for " +
                (if (mode == MODE_RECORDINGS) "recording." else "multiview.") + " Their links are session-bound and are not handled here."
            mode == MODE_MULTIVIEW -> {
                channelId.takeIf { id -> loaded.channels.any { it.id == id && loaded.supported(it) } }?.let { selected.add(it) }
                filterPicker(""); updateSelection()
                status.text = "Choose two channels."
            }
        }
        if (mode == MODE_RECORDINGS) updateRecordingUi()
    }


    private fun buildRecordings(root: LinearLayout) {
        root.addView(label("Recordings", 24f).apply { background = GlassMaterial.panel(this@AdvancedPlaybackActivity) })
        root.addView(label("Recording opens an additional connection to your provider. That can count against your connection limit " +
            "or end another stream, and you may only record content you have the legal right to record. " +
            "Only direct HTTP/HTTPS progressive or MPEG-TS streams are supported (not HLS, DASH or Stalker). " +
            "Recording runs only while this screen is open and is limited to 256 MiB or 30 minutes."))
        root.addView(status)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        recordButton = button("Record current channel") { confirmRecord() }.also { row.addView(it) }
        stopRecordButton = button("Stop recording") { recorder?.stop() }.also { row.addView(it) }
        root.addView(row)
        root.addView(label("Saved recordings (select one to play or delete)"))
        val adapter = object : BaseAdapter() {
            override fun getCount() = saved.size
            override fun getItem(position: Int) = saved[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val file = saved[position]
                val date = java.text.DateFormat.getDateTimeInstance().format(java.util.Date(file.modified))
                return (convertView as? TextView ?: label("").apply {
                    background = getDrawable(R.drawable.glass_row)
                    setTextColor(getColorStateList(R.color.glass_row_text))
                }).apply {
                    text = "Recording • $date • ${"%.1f".format(file.size / 1048576.0)} MiB"; setPadding(dp(12), dp(14), dp(12), dp(14))
                }
            }
        }
        savedAdapter = adapter
        savedList = ListView(this).apply {
            this.adapter = adapter
            setOnItemClickListener { _, _, position, _ -> saved.getOrNull(position)?.let { chooseSaved(it) } }
        }
        root.addView(savedList, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        playbackHolder = FrameLayout(this).apply { visibility = View.GONE }
        root.addView(playbackHolder, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 2f))
        updateRecordingUi()
    }

    private fun recordTarget(): Channel? = catalog?.let { c -> c.channels.firstOrNull { it.id == channelId && c.supported(it) } }

    private fun updateRecordingUi() {
        val cat = catalog
        val target = recordTarget()
        val recordable = target != null && !AdvancedRules.isManifestPath(target.stream)
        recordButton?.isEnabled = recorder == null && !pendingStart && recordable
        stopRecordButton?.isEnabled = recorder != null
        if (recorder != null || pendingStart || cat == null || cat.stalker) return
        status.text = when {
            target == null -> "No recordable channel is tuned. You can still play or delete saved recordings."
            !recordable -> "This channel looks like HLS/DASH, which cannot be recorded."
            else -> status.text
        }
    }

    private fun confirmRecord() {
        val target = recordTarget() ?: return
        if (recorder != null || pendingStart || !started) return
        showDialog(GlassDialogs.builder(this).setTitle("Start recording?")
            .setMessage("This opens an additional connection to your provider and may exceed your plan's connection limit. " +
                "Only record content you are legally allowed to record.\n\nChannel: ${target.name}\nLimit: 256 MiB or 30 minutes. " +
                "Recording stops when you leave this screen.")
            .setPositiveButton("Record") { _, _ -> startRecording(target) }
            .setNegativeButton("Cancel", null))
    }

    private fun startRecording(target: Channel) {
        if (!started || destroyed || recorder != null || pendingStart) return
        pendingStart = true
        val generation = sessionGeneration
        val dir = recordingsDir()
        val address = target.stream
        status.text = "Checking storage…"
        updateRecordingUi()
        background {
            dir.mkdirs()
            val count = dir.listFiles()?.count { AdvancedRules.isSafeRecordingName(it.name) } ?: 0
            val problem = when {
                count >= AdvancedRules.MAX_SAVED_RECORDINGS -> "Delete a saved recording first (limit ${AdvancedRules.MAX_SAVED_RECORDINGS})."
                dir.usableSpace < AdvancedRules.MIN_FREE_BYTES -> "Not enough free storage to record."
                else -> null
            }
            runOnUiThread {
                // The screen was stopped (or restarted) while storage was being checked: do nothing.
                if (generation != sessionGeneration) return@runOnUiThread
                pendingStart = false
                if (destroyed || !started || recorder != null) return@runOnUiThread
                if (problem != null) { status.text = problem; updateRecordingUi(); return@runOnUiThread }
                val rec = AdvancedRecorder(dir, address,
                    onProgress = { bytes, millis -> runOnUiThread { if (!destroyed && recorder != null) status.text =
                        "Recording… ${"%.1f".format(bytes / 1048576.0)} MiB • ${millis / 60000}:${"%02d".format(millis / 1000 % 60)}" } },
                    onDone = { finished, result, bytes -> runOnUiThread { if (!destroyed && recorder === finished) onRecordingDone(result, bytes) } })
                recorder = rec
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                status.text = "Connecting…"
                updateRecordingUi()
                try { recordIo.execute { rec.run() } } catch (_: RejectedExecutionException) { onRecordingDone(AdvancedRecorder.Result.FAILED, 0) }
            }
        }
    }

    private fun onRecordingDone(result: AdvancedRecorder.Result, bytes: Long) {
        recorder = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        status.text = when (result) {
            AdvancedRecorder.Result.SAVED -> "Saved recording (${"%.1f".format(bytes / 1048576.0)} MiB)."
            AdvancedRecorder.Result.LIMIT -> "Stopped at the 256 MiB / 30 minute limit. Recording saved."
            AdvancedRecorder.Result.UNSUPPORTED -> "This stream is not a direct progressive/TS stream, so it cannot be recorded."
            AdvancedRecorder.Result.CANCELED -> "Recording canceled. Nothing was kept."
            AdvancedRecorder.Result.FAILED -> "Recording failed (connection, server response or storage). Nothing was kept."
        }
        updateRecordingUi()
        refreshSaved()
    }

    private fun refreshSaved() {
        val dir = recordingsDir()
        background {
            // Only stale parts that no running recorder owns are removed.
            AdvancedRecorder.sweepStale(dir, System.currentTimeMillis(), AdvancedRules.STALE_PART_MILLIS)
            val files = dir.listFiles()?.filter { AdvancedRules.isSafeRecordingName(it.name) && it.isFile }
                ?.sortedByDescending { it.lastModified() }?.map { SavedFile(it.name, it.length(), it.lastModified()) }.orEmpty()
            runOnUiThread { if (!destroyed) { saved.clear(); saved.addAll(files); savedAdapter?.notifyDataSetChanged() } }
        }
    }

    private fun chooseSaved(file: SavedFile) {
        showDialog(GlassDialogs.builder(this).setTitle("Recording")
            .setItems(arrayOf("Play", "Delete")) { _, which -> if (which == 0) playSaved(file) else confirmDelete(file) }
            .setNegativeButton("Cancel", null))
    }

    private fun confirmDelete(file: SavedFile) {
        showDialog(GlassDialogs.builder(this).setTitle("Delete recording?").setMessage("This permanently deletes the file from this device.")
            .setPositiveButton("Delete") { _, _ ->
                if (playingName == file.name) releaseRecordingPlayer()
                val target = File(recordingsDir(), file.name)
                background {
                    if (AdvancedRules.isSafeRecordingName(file.name)) target.delete()
                    runOnUiThread { if (!destroyed) refreshSaved() }
                }
            }.setNegativeButton("Cancel", null))
    }

    private fun playSaved(file: SavedFile) {
        if (!AdvancedRules.isSafeRecordingName(file.name) || !started) return
        releaseRecordingPlayer()
        val holder = playbackHolder ?: return
        val exo = ExoPlayer.Builder(this).build()
        recordingPlayer = exo; playingName = file.name
        val view = PlayerView(this).apply { player = exo; useController = true }
        holder.removeAllViews()
        holder.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        holder.addView(button("Close player") { releaseRecordingPlayer() }, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END))
        holder.visibility = View.VISIBLE
        exo.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) { status.text = "This recording could not be played." }
        })
        exo.setMediaItem(MediaItem.fromUri(Uri.fromFile(File(recordingsDir(), file.name))))
        exo.playWhenReady = true; exo.prepare() // the user explicitly chose Play
    }

    private fun releaseRecordingPlayer() {
        recordingPlayer?.release(); recordingPlayer = null; playingName = null
        playbackHolder?.apply { removeAllViews(); visibility = View.GONE }
    }


    private fun buildMultiview(root: LinearLayout) {
        val picker = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        picker.addView(label("Multiview (2 channels)", 24f).apply { background = GlassMaterial.panel(this@AdvancedPlaybackActivity) })
        picker.addView(label("Multiview opens two simultaneous streams from your provider. Many providers allow only one connection per account, " +
            "so this may fail or interrupt other devices. Support depends on the provider and stream type; Stalker portals are not supported. " +
            "Playback stops when you leave this screen."))
        picker.addView(status)
        val search = EditText(this).apply {
            hint = "Search channels or groups"; isSingleLine = true
            contentDescription = "Search channels"
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { filterPicker(s?.toString().orEmpty().trim()) }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            })
        }
        picker.addView(search)
        selectedLabel = label("").also { picker.addView(it) }
        val adapter = object : BaseAdapter() {
            override fun getCount() = pickerRows.size
            override fun getItem(position: Int) = pickerRows[position]
            override fun getItemId(position: Int) = pickerRows[position].id.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val channel = pickerRows[position]
                return (convertView as? TextView ?: label("").apply {
                    background = getDrawable(R.drawable.glass_row)
                    setTextColor(getColorStateList(R.color.glass_row_text))
                }).apply {
                    text = (if (channel.id in selected) "✓ " else "   ") + channel.name + " • " + channel.group
                    setPadding(dp(12), dp(14), dp(12), dp(14))
                }
            }
        }
        pickerAdapter = adapter
        picker.addView(ListView(this).apply {
            this.adapter = adapter
            setOnItemClickListener { _, _, position, _ ->
                val id = pickerRows.getOrNull(position)?.id ?: return@setOnItemClickListener
                if (!selected.remove(id)) {
                    if (selected.size >= 2) { status.text = "Two channels already chosen. Tap one to deselect it first."; return@setOnItemClickListener }
                    selected.add(id)
                }
                updateSelection(); adapter.notifyDataSetChanged()
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        startButton = button("Start multiview") { confirmMultiview() }.also { picker.addView(it) }
        selectionView = picker
        root.addView(picker, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        viewingView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        root.addView(viewingView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        updateSelection()
    }

    private fun filterPicker(query: String) {
        val cat = catalog ?: return
        val generation = ++pickerGeneration
        val keep = selected.toList()
        background {
            val top = keep.mapNotNull { id -> cat.channels.firstOrNull { it.id == id } }
            val matches = cat.channels.asSequence().filter { it.id !in keep && cat.supported(it) &&
                (query.isEmpty() || it.name.contains(query, true) || it.group.contains(query, true)) }.take(MAX_PICKER_ROWS).toList()
            runOnUiThread {
                if (!destroyed && generation == pickerGeneration) { pickerRows = top + matches; pickerAdapter?.notifyDataSetChanged() }
            }
        }
    }

    private fun updateSelection() {
        val cat = catalog
        val names = selected.map { id -> cat?.channels?.firstOrNull { it.id == id }?.name ?: "?" }
        selectedLabel?.text = "Selected (${selected.size}/2): " + names.joinToString(" + ")
        startButton?.isEnabled = selected.size == 2
    }

    private fun confirmMultiview() {
        if (selected.size != 2 || catalog?.stalker != false || !started) return
        showDialog(GlassDialogs.builder(this).setTitle("Start two streams?")
            .setMessage("This will play two streams at the same time, using two provider connections and more bandwidth. " +
                "If your plan allows one connection, one or both may fail. The second stream is muted.")
            .setPositiveButton("Start") { _, _ -> startMultiview() }.setNegativeButton("Cancel", null))
    }

    private fun startMultiview() {
        val cat = catalog ?: return
        if (!started || destroyed) return
        val chosen = selected.mapNotNull { id -> cat.channels.firstOrNull { it.id == id && cat.supported(it) } }
        if (chosen.size != 2 || players.any { it != null }) return
        // The launching Activity is already stopped (its player freed) because this screen is on top.
        val holder = viewingView ?: return
        holder.removeAllViews()
        val grid = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        multiviewNames = arrayOf(chosen[0].name, chosen[1].name)
        audioIndex = 0
        for (i in 0..1) {
            val exo = ExoPlayer.Builder(this).build()
            players[i] = exo
            exo.volume = if (i == audioIndex) 1f else 0f
            exo.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    status.text = "Stream ${if (i == 0) "A" else "B"} is unavailable. The provider may limit simultaneous connections."
                }
            })
            val view = PlayerView(this).apply { player = exo; useController = false; setBackgroundColor(Color.BLACK) }
            grid.addView(view, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { setMargins(dp(4), 0, dp(4), 0) })
            exo.setMediaItem(MediaItem.fromUri(Uri.parse(chosen[i].stream)))
            exo.playWhenReady = true; exo.prepare()
        }
        status.text = "Playing two streams."
        (status.parent as? ViewGroup)?.removeView(status)
        holder.addView(status)
        holder.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        audioButton = button("") { switchAudio() }.also { controls.addView(it) }
        controls.addView(button("Stop multiview") { releaseMultiview(); showSelection("Stopped. Choose two channels.") })
        holder.addView(controls)
        updateAudioLabel()
        selectionView?.visibility = View.GONE; holder.visibility = View.VISIBLE
        audioButton?.requestFocus()
    }

    private fun switchAudio() {
        if (players.any { it == null }) return
        audioIndex = 1 - audioIndex
        for (i in 0..1) players[i]?.volume = if (i == audioIndex) 1f else 0f
        updateAudioLabel()
    }

    private fun updateAudioLabel() {
        audioButton?.text = "Audio: ${if (audioIndex == 0) "A" else "B"} (${multiviewNames[audioIndex].take(30)}) • switch"
    }

    private fun releaseMultiview() {
        for (i in 0..1) { players[i]?.release(); players[i] = null }
    }

    /** Always leaves a usable picker on screen; safe to call repeatedly or before views exist. */
    private fun showSelection(message: String?) {
        val selection = selectionView ?: return
        viewingView?.apply { removeAllViews(); visibility = View.GONE }
        (status.parent as? ViewGroup)?.removeView(status)
        selection.addView(status, 2)
        if (message != null) status.text = message
        selection.visibility = View.VISIBLE
        updateSelection()
        startButton?.requestFocus()
    }


    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun label(text: String, size: Float = 16f) = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(GlassTokens.textPrimary); setPadding(0, dp(6), 0, dp(6))
    }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text; isFocusable = true; setOnClickListener { onClick() }
    }
}

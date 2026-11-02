package faisalnh.tiviman

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
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
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.util.concurrent.Executors

/**
 * Read-only "All playlists" guide. Every playlist is loaded through its own pinned [PlaylistStore]
 * (no registry writes, no select(), no preference writes). Nothing is fetched from the network and
 * nothing plays until the user clicks a channel or "Play selected". Playback is released in onStop.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
open class AllPlaylistsActivity : Activity() {
    private class Source(val store: PlaylistStore, val id: String, val name: String, val channels: List<Channel>,
        val tracks: Map<String, String>, val display: Map<String, String>, val stalker: Boolean, val revision: String)
    private class Row(val source: Source, val channel: Channel, val now: String, val next: String,
        val description: String, val playable: Boolean)

    /**
     * Where every PlaylistStore and file read comes from. Open so instrumentation can substitute an isolated
     * context; the default is the application context. Called on the main thread only.
     */
    protected open fun storageContext(): Context = applicationContext

    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var sources = emptyList<Source>()
    @Volatile private var generation = 0
    @Volatile private var loaded = false
    @Volatile private var searching = true
    @Volatile private var unreadable = 0
    private var destroyed = false
    // Only touched on the worker thread.
    private val epgCache = HashMap<String, EpgData?>()

    private var rows = emptyList<Row>()
    private var selected: Row? = null
    internal lateinit var searchBox: EditText
    internal lateinit var status: TextView
    internal lateinit var list: ListView
    internal lateinit var details: TextView
    internal lateinit var playButton: Button
    private lateinit var browser: LinearLayout
    private lateinit var playerHost: FrameLayout
    internal lateinit var video: PlayerView
    private lateinit var playerTitle: TextView
    private lateinit var playerStatus: TextView
    internal lateinit var pauseButton: Button
    internal lateinit var stopButton: Button

    internal var player: ExoPlayer? = null
        private set
    private var playing: Row? = null

    /** Plain-data views of private state for instrumentation. Main thread only. */
    internal data class RowInfo(val playlistId: String, val playlist: String, val channelId: Int, val name: String,
        val group: String, val now: String, val next: String, val playable: Boolean)
    private fun Row.info() = RowInfo(source.id, source.name, channel.id, channel.name, channel.group, now, next, playable)
    internal fun rowInfos(): List<RowInfo> = rows.map { it.info() }
    internal fun selectedInfo(): RowInfo? = selected?.info()
    internal fun playingInfo(): RowInfo? = playing?.info()
    internal fun isLoaded(): Boolean = loaded && !searching
    internal fun playerHostVisible(): Boolean = playerHost.visibility == View.VISIBLE && browser.visibility == View.GONE
    private val searchRunnable = Runnable { search(searchBox.text.toString()) }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun text(size: Float, color: Int = GlassTokens.textPrimary) = TextView(this).apply { textSize = size; setTextColor(color) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(ColorDrawable(GlassTokens.canvas))
        val root = FrameLayout(this).apply { setBackgroundColor(GlassTokens.canvas) }
        browser = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(24), dp(24), dp(24)) }
        val header = GlassPage.header(this, "All playlists") as LinearLayout
        status = text(14f, GlassTokens.textSecondary).apply { this.text = "Loading playlists…" }
        header.addView(status)
        searchBox = EditText(this).apply {
            hint = "Search channel, group or playlist"
            inputType = InputType.TYPE_CLASS_TEXT; imeOptions = EditorInfo.IME_ACTION_DONE; setSingleLine()
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    handler.removeCallbacks(searchRunnable); handler.postDelayed(searchRunnable, 250)
                }
            })
        }
        header.addView(searchBox)
        browser.addView(header)

        val body = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        list = ListView(this).apply {
            setBackgroundColor(GlassTokens.surface)
            adapter = adapterImpl
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = select(rows.getOrNull(position))
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
            setOnItemClickListener { _, _, position, _ -> rows.getOrNull(position)?.let { select(it); play(it) } }
        }
        body.addView(list, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 3f))
        val side = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), 0, 0, 0) }
        details = text(15f).apply { this.text = "Select a channel." }
        playButton = Button(this).apply { this.text = "Play selected"; isEnabled = false; setOnClickListener { selected?.let { play(it) } } }
        side.addView(details, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        side.addView(playButton)
        body.addView(side, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 2f))
        browser.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(browser, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        playerHost = FrameLayout(this).apply { setBackgroundColor(GlassTokens.canvas); visibility = View.GONE }
        video = PlayerView(this).apply { useController = false; isFocusable = false; setBackgroundColor(Color.BLACK) }
        playerHost.addView(video, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        playerTitle = text(16f); playerStatus = text(13f, GlassTokens.textSecondary)
        pauseButton = Button(this).apply { this.text = "Pause"; setOnClickListener { togglePause() } }
        stopButton = Button(this).apply { this.text = "Stop"; setOnClickListener { stopPlayback() } }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(GlassTokens.surface); setPadding(dp(24), dp(8), dp(24), dp(8))
        }
        val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(playerTitle); addView(playerStatus) }
        bar.addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(pauseButton); bar.addView(stopButton)
        playerHost.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        playerHost.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyDisplay() }
        root.addView(playerHost, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)

        load()
    }

    private val adapterImpl = object : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row = rows[position]
            val view = (convertView as? LinearLayout) ?: LinearLayout(this@AllPlaylistsActivity).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8))
                background = getDrawable(R.drawable.glass_row)
                addView(text(18f).apply {
                    isDuplicateParentStateEnabled = true
                    setTextColor(getColorStateList(R.color.glass_row_text))
                })
                addView(text(13f, GlassTokens.textSecondary).apply {
                    isDuplicateParentStateEnabled = true
                    setTextColor(getColorStateList(R.color.glass_row_text))
                })
            }
            (view.getChildAt(0) as TextView).text = row.channel.name
            (view.getChildAt(1) as TextView).text = "${row.source.name} • ${row.channel.group}\n" +
                if (row.playable) "Now: ${row.now}" else "Not playable here (Stalker link unresolved)"
            return view
        }
    }

    // ---- Loading (worker thread only; read-only store access) ----

    private fun load() {
        val context = storageContext()
        runWorker {
            val found = mutableListOf<Source>()
            var failed = 0
            val entries = try { PlaylistStore(context, "legacy").playlists() } catch (_: Throwable) { null }
            if (entries == null) failed++
            val used = HashMap<String, Int>()
            entries.orEmpty().forEach { entry ->
                try {
                    val store = PlaylistStore(context, entry.id)
                    val snap = store.snapshot()
                    val channels = snap.channels
                    if (channels == null) { failed++; return@forEach }
                    val count = (used[entry.name] ?: 0) + 1; used[entry.name] = count
                    val label = if (count > 1) "${entry.name} ($count)" else entry.name
                    found.add(Source(store, entry.id, label, channels, snap.tracks, snap.display,
                        snap.provider?.type == SourceType.STALKER, snap.revision))
                } catch (_: Throwable) { failed++ }
            }
            sources = found; unreadable = failed; loaded = true
            runOnUiThread { if (!destroyed) search(searchBox.text.toString()) }
        }
    }

    // ---- Search / merge (worker thread) ----

    private fun search(query: String) {
        if (!loaded) return
        val gen = ++generation
        searching = true
        runWorker {
            if (gen != generation) return@runWorker
            val snapshot = sources
            val tokens = query.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
            val matches = snapshot.map { source ->
                val out = ArrayList<Channel>()
                val playlist = source.name.lowercase()
                for (channel in source.channels) {
                    if (out.size >= MAX_RESULTS) break
                    if (tokens.isEmpty()) { out.add(channel); continue }
                    val hay = channel.name.lowercase() + "\n" + channel.group.lowercase() + "\n" + playlist
                    if (tokens.all { hay.contains(it) }) out.add(channel)
                }
                out
            }
            // Fair share so one huge playlist cannot crowd the others out of the 200-row cap.
            val take = IntArray(matches.size)
            var left = MAX_RESULTS
            while (left > 0) {
                var progressed = false
                for (i in matches.indices) if (left > 0 && take[i] < matches[i].size) { take[i]++; left--; progressed = true }
                if (!progressed) break
            }
            val now = Epg.minute()
            val result = ArrayList<Row>()
            snapshot.forEachIndexed { i, source ->
                if (take[i] == 0) return@forEachIndexed
                val chosen = matches[i].take(take[i])
                if (!epgCache.containsKey(source.id)) epgCache[source.id] = try { source.store.loadEpg(source.revision) } catch (_: Throwable) { null }
                val guide = epgCache[source.id]
                val projected = if (guide != null) try { Epg.project(chosen, guide, now) } catch (_: Throwable) { null } else null
                chosen.forEachIndexed { j, original ->
                    val channel = projected?.getOrNull(j) ?: original
                    val (current, upcoming, description) = summarize(channel, projected != null, now)
                    result.add(Row(source, channel, current, upcoming, description, isPlayable(source, channel)))
                }
            }
            val noun = if (tokens.isEmpty()) "channels" else "matches"
            val summary = "${result.size} $noun shown (max $MAX_RESULTS) • ${snapshot.size} playlist(s)" +
                (if (unreadable > 0) " • $unreadable unreadable" else "") +
                (if (snapshot.isEmpty()) " • none saved" else "")
            runOnUiThread { if (!destroyed && gen == generation) { show(result, summary); searching = false } }
        }
    }

    private fun runWorker(block: () -> Unit) {
        try { worker.execute(block) } catch (_: java.util.concurrent.RejectedExecutionException) { }
    }

    private fun programmeLine(p: Programme) = "${Epg.time(p.start)}–${Epg.time(p.end)}  ${p.title}"

    private fun summarize(channel: Channel, hasGuide: Boolean, now: Int): Triple<String, String, String> {
        if (!hasGuide) return Triple("No guide saved", "No guide saved", "")
        val index = channel.programmes.indexOfFirst { it.start <= now && it.end > now }
        val current = channel.programmes.getOrNull(index)
        val upcoming = channel.programmes.getOrNull(index + 1)?.takeIf { index >= 0 }
        val description = current?.takeIf { it.title != GAP }?.description.orEmpty().take(400)
        return Triple(current?.let(::programmeLine) ?: GAP, upcoming?.let(::programmeLine) ?: "—", description)
    }

    private fun isPlayable(source: Source, channel: Channel) = !source.stalker && !channel.stream.startsWith("stalker:") &&
        (channel.stream.startsWith("https://") || channel.stream.startsWith("http://"))

    // ---- Browse UI ----

    private fun show(result: List<Row>, summary: String) {
        val previous = selected
        rows = result
        status.text = summary
        adapterImpl.notifyDataSetChanged()
        val keep = result.indexOfFirst { it.source === previous?.source && it.channel.id == previous?.channel?.id }
        val index = if (keep >= 0) keep else if (result.isNotEmpty()) 0 else -1
        if (index >= 0) { list.setSelection(index); select(result[index]) } else select(null)
    }

    private fun select(row: Row?) {
        selected = row
        playButton.isEnabled = row?.playable == true
        details.text = if (row == null) "Select a channel." else buildString {
            append(row.channel.name).append("\n\nPlaylist: ").append(row.source.name)
            append("\nGroup: ").append(row.channel.group)
            append("\n\nNow:  ").append(row.now).append("\nNext: ").append(row.next)
            if (row.description.isNotBlank()) append("\n\n").append(row.description)
            if (!row.playable) append("\n\nPlayback unavailable: Stalker links need a session and are not resolved here.")
            else append("\n\nNothing plays until you press Play selected or click the channel.")
        }
    }

    // ---- Playback ----

    private fun play(row: Row) {
        if (!row.playable || destroyed) return
        stopPlayback()
        playing = row
        // AudioDelayRenderersFactory applies the session-wide positive AudioDelay when it is active; this screen never changes it.
        val exo = ExoPlayer.Builder(this, AudioDelayRenderersFactory(this)).build()
        player = exo
        video.player = exo
        exo.addListener(object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) = applyTracks()
            override fun onPlaybackStateChanged(state: Int) = updatePlayerStatus()
            override fun onIsPlayingChanged(isPlaying: Boolean) = updatePlayerStatus()
            override fun onPlayerError(error: PlaybackException) { playerStatus.text = "Playback failed (${error.errorCodeName})" }
        })
        playerTitle.text = "${row.channel.name} • ${row.source.name}"
        playerStatus.text = "Opening…"
        pauseButton.text = "Pause"
        browser.visibility = View.GONE
        playerHost.visibility = View.VISIBLE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        playerHost.post { applyDisplay() }
        exo.setMediaItem(MediaItem.fromUri(row.channel.stream))
        exo.prepare()
        exo.playWhenReady = true
        pauseButton.requestFocus()
    }

    /** Saved choices are read from this playlist's own store only, keyed exactly like MainActivity. */
    private fun applyTracks() {
        val exo = player ?: return
        val row = playing ?: return
        TrackControls.types.values.forEach { type ->
            TrackControls.apply(exo, type, row.source.tracks["${row.channel.id}:$type"] ?: "auto")
        }
    }

    private fun applyDisplay() {
        val row = playing ?: return
        if (playerHost.width <= 0 || playerHost.height <= 0) return
        DisplaySizing.apply(video, DisplayMode.parse(row.source.display[row.channel.id.toString()]), playerHost.width, playerHost.height)
    }

    private fun updatePlayerStatus() {
        val exo = player ?: return
        playerStatus.text = when {
            exo.playbackState == Player.STATE_BUFFERING -> "Buffering…"
            exo.isPlaying -> "Playing"
            exo.playbackState == Player.STATE_READY -> "Paused"
            exo.playbackState == Player.STATE_ENDED -> "Ended"
            else -> playerStatus.text.toString()
        }
    }

    private fun togglePause() {
        val exo = player ?: return
        if (exo.playWhenReady) { exo.playWhenReady = false; pauseButton.text = "Resume" }
        else { exo.playWhenReady = true; pauseButton.text = "Pause" }
        updatePlayerStatus()
    }

    private fun stopPlayback() {
        val exo = player
        player = null; playing = null
        if (exo != null) { video.player = null; exo.release() }
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (playerHost.visibility == View.VISIBLE) {
            playerHost.visibility = View.GONE
            browser.visibility = View.VISIBLE
            list.requestFocus()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (playing != null) when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { togglePause(); return true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { if (player?.playWhenReady == true) togglePause(); return true }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { if (player?.playWhenReady == false) togglePause(); return true }
            KeyEvent.KEYCODE_MEDIA_STOP -> { stopPlayback(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (playing != null) stopPlayback() else super.onBackPressed()
    }

    /** No automatic resume: returning to the activity shows the guide again. */
    override fun onStop() {
        stopPlayback()
        super.onStop()
    }

    override fun onDestroy() {
        destroyed = true
        handler.removeCallbacksAndMessages(null)
        stopPlayback()
        worker.shutdownNow()
        super.onDestroy()
    }

    private companion object {
        const val MAX_RESULTS = 200
        const val GAP = "No information"
    }
}

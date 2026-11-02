package faisalnh.tiviman

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

/** Native view prototype. Palette/timing choices are approximations, not recovered tokens. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
open class MainActivity : Activity() {
    private lateinit var root: FrameLayout
    private lateinit var chrome: FrameLayout
    private lateinit var video: PlayerView
    private lateinit var videoViewport: FrameLayout
    private var displayChoices = emptyMap<String, String>()
    private var displayDialog: android.app.AlertDialog? = null
    private var pendingDisplayDialog = false
    private var sleepDialog: android.app.AlertDialog? = null
    private var pendingSleepDialog = false
    private var channelDialog: android.app.AlertDialog? = null
    private var channelSurface = ""
    private var pendingChannelSurface = ""
    private var browseGroup = 0
    private var pendingBrowserRow = -1
    private var recentChannels = emptyList<Int>()
    private var favoriteChannels = emptySet<Int>()
    private var searchDialog: android.app.AlertDialog? = null
    private var searchQuery = ""
    private var pendingSearch = false
    private var optionsDialog: android.app.AlertDialog? = null
    private var pendingOptionsDialog = false
    private var settingsDialog: android.app.AlertDialog? = null
    private var pendingSettingsDialog = false
    private var settingsRow = 0
    private var searchGeneration = 0
    private val sleepTimer get() = PlaybackSleepTimer.timer
    private val sleepTick = object : Runnable {
        override fun run() {
            if (!started) return
            checkSleepTimer()
            if (sleepTimer.deadline != 0L) handler.postDelayed(this, 1000)
        }
    }
    private lateinit var guide: GuideRows
    private lateinit var fullscreenChrome: FullscreenChrome
    private lateinit var backdrop: GuideBackdrop
    private lateinit var bufferingIndicator: BufferingIndicator
    private lateinit var scheduleOverlay: ChannelScheduleOverlay
    private var railOpen = false
    private var railItem = 1
    private var audioDelayDialog: android.app.AlertDialog? = null
    private var restoringFocus = false
    private val focusTargets = mutableListOf<View>()
    private var playbackPosition = 0L
    private var shouldPlay = true
    private var privatePlaylist = false
    private var setupOpen = false
    private var suppressSetupPlayback = false
    private var catalogStore: PlaylistStore? = null
    private var provider: ProviderConfig? = null
    private var trackChoices = emptyMap<String, String>()
    private var trackDialog: android.app.AlertDialog? = null
    private var trackDialogType = -1
    private var pendingTrackDialog = -1
    private val resolver = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var resolveClient: ProviderHttp? = null
    private var resolveGeneration = 0
    private var portalFailed = false
    private var player: ExoPlayer? = null
    private var state = GuideState()
    private val catalogWorker = CatalogTasks.worker
    private var loading = true
    private var splash: FrameLayout? = null
    private var splashLogo: android.widget.ImageView? = null
    private var splashStarted = 0L
    private var splashPulse: android.animation.ObjectAnimator? = null
    private var resumed = false
    private var started = false
    private var loadGeneration = 0
    private var revision = ""
    private var epgData: EpgData? = null
    private var epgBaseCatalog: List<Channel>? = null
    private var epgProjecting = false
    private val epgTick = object : Runnable {
        override fun run() {
            if (!started) return
            refreshEpg()
            handler.postDelayed(this, 30000)
        }
    }
    private var pendingRestore: Bundle? = null
    private var playbackStatus = "Local fixture • loading"
    private val handler = Handler(Looper.getMainLooper())
    private val hideOverlay = Runnable { state = state.copy(overlay = false, toolbar = false); render() }

    private var scale = 1f
    private var backHeld = false
    private var backHandled = false
    private fun pipSupported() = android.os.Build.VERSION.SDK_INT >= 26 &&
        packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)
    private fun inPip() = android.os.Build.VERSION.SDK_INT >= 26 && isInPictureInPictureMode
    private val longBack = Runnable {
        if (backHeld) { backHandled = true; navigate(RemoteKey.LONG_BACK) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.AppTheme)
        super.onCreate(savedInstanceState)
        pendingRestore = savedInstanceState
        suppressSetupPlayback = savedInstanceState?.getBoolean("suppressSetupPlayback") ?: false
        CapturePolicy.secure(window)
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        root = FrameLayout(this).apply { setBackgroundColor(GlassTokens.canvas) }
        backdrop = GuideBackdrop(this)
        root.addView(backdrop, FrameLayout.LayoutParams(-1, -1))
        video = createVideoView()
        videoViewport = FrameLayout(this).apply {
            setBackgroundColor(getColor(android.R.color.black))
            clipChildren = true
            addView(video, FrameLayout.LayoutParams(-1, -1))
        }
        chrome = FrameLayout(this).apply { isFocusableInTouchMode = true }
        guide = GuideRows(this) { channel, programme, activate ->
            if (!loading) {
                state = state.copy(channelId = channel, programmeIndex = programme)
                if (activate) navigate(RemoteKey.CENTER) else render()
            }
        }
        fullscreenChrome = FullscreenChrome(this)
        root.addView(videoViewport)
        root.addView(guide)
        root.addView(chrome, FrameLayout.LayoutParams(-1, -1))
        root.addView(fullscreenChrome, FrameLayout.LayoutParams(-1, -1))
        bufferingIndicator = BufferingIndicator(this).apply { visibility = View.GONE }
        videoViewport.addView(bufferingIndicator, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        scheduleOverlay = ChannelScheduleOverlay(this)
        root.addView(scheduleOverlay, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        root.post { scale = minOf(root.width / 960f, root.height / 540f); render() }
        loadCatalog()
    }

    override fun onStart() {
        super.onStart()
        started = true
        checkSleepTimer()
        handler.removeCallbacks(sleepTick)
        handler.post(sleepTick)
        handler.removeCallbacks(epgTick); handler.post(epgTick)
        // Media3's default event logger can emit source exceptions containing credential URLs.
        // Suppress its logging before creating any private-media player.
        if (setupOpen) { setupOpen = false; loadCatalog() }
        if (loading) return
        startPlayer()
    }

    private fun startPlayer() {
        if (player != null || !started || loading) return
        checkSleepTimer()
        if (privatePlaylist) androidx.media3.common.util.Log.setLogLevel(androidx.media3.common.util.Log.LOG_LEVEL_OFF)
        player = ExoPlayer.Builder(this, AudioDelayRenderersFactory(this)).build().also { exo ->
            video.player = exo
            exo.repeatMode = Player.REPEAT_MODE_ONE
            exo.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    playbackStatus = when (playbackState) {
                        Player.STATE_READY -> "${sourceLabel()} • ${if (exo.playWhenReady) "playing" else "paused"}"
                        Player.STATE_BUFFERING -> "${sourceLabel()} • buffering"
                        else -> "${sourceLabel()} • preparing"
                    }
                    render()
                }
                override fun onPlayerError(error: PlaybackException) {
                    playbackStatus = "Playback error: ${error.errorCodeName} • Open info and choose Retry"
                    render()
                }
                override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                    applyTrackChoices()
                    refreshChannelOptions()
                    render()
                }
                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    shouldPlay = playWhenReady
                    if (exo.playbackState == Player.STATE_READY) {
                        playbackStatus = "${sourceLabel()} • ${if (playWhenReady) "playing" else "paused"}"
                        render()
                    }
                }
            })
        }
        applyTrackChoices()
        if (suppressSetupPlayback) { shouldPlay = false; playbackStatus = "Playlist ready • select a channel to play" }
        else if (!sleepTimer.expired) prepareTuned(playbackPosition)
        else { shouldPlay = false; playbackStatus = "Sleep timer ended • Press Play to resume" }
        resetOverlayTimeout()
        if (pendingSettingsDialog) { pendingSettingsDialog = false; showSettings() }
        if (pendingDisplayDialog) { pendingDisplayDialog = false; showDisplayDialog() }
        if (pendingSleepDialog) { pendingSleepDialog = false; showSleepDialog() }
        if (pendingChannelSurface.isNotEmpty()) {
            val surface = pendingChannelSurface; pendingChannelSurface = ""
            showChannels(surface)
        }
        if (pendingSearch) { pendingSearch = false; showSearch() }
        if (pendingOptionsDialog) { pendingOptionsDialog = false; showChannelOptions() }
        if (pendingTrackDialog != -1) {
            val type = pendingTrackDialog; pendingTrackDialog = -1
            showTrackDialog(type)
        }
    }

    override fun onStop() {
        started = false
        handler.removeCallbacks(sleepTick)
        handler.removeCallbacks(epgTick)
        scheduleOverlay.hide()
        audioDelayDialog?.dismiss()
        sleepDialog?.dismiss()
        channelDialog?.dismiss()
        searchDialog?.dismiss()
        optionsDialog?.dismiss()
        settingsDialog?.dismiss()
        displayDialog?.dismiss()
        trackDialog?.dismiss()
        persistNavigation()
        handler.removeCallbacks(hideOverlay)
        handler.removeCallbacks(longBack)
        backHeld = false
        resolveGeneration++; resolveClient?.cancel()
        player?.let { playbackPosition = it.currentPosition; shouldPlay = it.playWhenReady }
        video.player = null
        player?.release()
        player = null
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        if (loading) startSplashPulse()
    }

    override fun onPause() {
        resumed = false
        stopSplashPulse()
        splash?.animate()?.cancel()
        if (!loading) removeSplash()
        persistNavigation()
        super.onPause()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean,
        newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        state = state.copy(pane = Pane.FULLSCREEN, overlay = false, toolbar = false)
        handler.removeCallbacks(hideOverlay)
        render()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        root.post { scale = minOf(root.width / 960f, root.height / 540f); render() }
    }

    @android.annotation.SuppressLint("PictureInPictureIssue") // Explicit entry only; no automatic Home playback.
    private fun enterPlayerPip() {
        if (android.os.Build.VERSION.SDK_INT < 26 || inPip()) return
        if (!PictureInPicturePolicy.canEnter(pipSupported(), started, loading, player != null,
                player?.playerError != null || portalFailed, sleepTimer.expired)) {
            StatusCapsule.show(this, "Picture-in-picture unavailable. Load a working channel first.")
            return
        }
        val oldState = state
        settingsDialog?.dismiss()
        state = state.copy(pane = Pane.FULLSCREEN, overlay = false, toolbar = false)
        handler.removeCallbacks(hideOverlay)
        render()
        val entered = try {
            val source = android.graphics.Rect()
            videoViewport.getGlobalVisibleRect(source)
            val params = android.app.PictureInPictureParams.Builder()
                .setAspectRatio(android.util.Rational(16, 9))
                .setSourceRectHint(source)
            if (android.os.Build.VERSION.SDK_INT >= 31) params.setAutoEnterEnabled(false)
            enterPictureInPictureMode(params.build())
        } catch (_: IllegalStateException) { false } catch (_: IllegalArgumentException) { false }
        if (!entered) {
            state = oldState; render(); resetOverlayTimeout()
            StatusCapsule.show(this, "Picture-in-picture was declined by this device.")
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("suppressSetupPlayback", suppressSetupPlayback)
        if (loading) {
            pendingRestore?.let { outState.putAll(it) }
            super.onSaveInstanceState(outState)
            return
        }
        outState.putString("revision", revision)
        outState.putString("pane", state.pane.name)
        outState.putInt("group", state.group)
        outState.putInt("focusedGroup", state.focusedGroup)
        outState.putInt("channel", state.channelId)
        outState.putInt("programme", state.programmeIndex)
        outState.putInt("tuned", state.tunedId)
        outState.putBoolean("overlay", state.overlay)
        outState.putBoolean("toolbar", state.toolbar)
        outState.putInt("action", state.action)
        outState.putInt("toolbarAction", state.toolbarAction)
        outState.putInt("trackDialogType", trackDialogType)
        outState.putBoolean("displayDialog", displayDialog != null)
        outState.putBoolean("sleepDialog", sleepDialog != null)
        outState.putString("channelSurface", channelSurface)
        outState.putInt("browserRow", channelDialog?.listView?.selectedItemPosition ?: -1)
        outState.putBoolean("searchDialog", searchDialog != null)
        outState.putBoolean("optionsDialog", optionsDialog != null)
        outState.putBoolean("settingsDialog", settingsDialog != null || pendingSettingsDialog)
        outState.putInt("settingsRow", settingsDialog?.listView?.selectedItemPosition?.takeIf { it >= 0 } ?: settingsRow)
        outState.putString("searchQuery", searchQuery)
        outState.putInt("browseGroup", browseGroup)
        outState.putLong("position", player?.currentPosition ?: playbackPosition)
        outState.putBoolean("playing", player?.playWhenReady ?: shouldPlay)
        super.onSaveInstanceState(outState)
    }

    @Deprecated("TV remote Back is routed through the explicit state model")
    override fun onBackPressed() {
        if (scheduleOverlay.visibility == View.VISIBLE) dismissSchedule() else navigate(RemoteKey.BACK)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (inPip()) return super.dispatchKeyEvent(event)
        if (scheduleOverlay.visibility == View.VISIBLE) {
            scheduleOverlay.dispatchKeyEvent(event)
            return if (event.keyCode == KeyEvent.KEYCODE_HOME) super.dispatchKeyEvent(event) else true
        }
        if (railOpen && state.pane == Pane.GROUPS) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        railItem = (railItem + if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP) -1 else 1).coerceIn(0, 2)
                        render()
                    }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                    if (event.action == KeyEvent.ACTION_UP) { railOpen = false; render() }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> return true
            }
        }
        if (loading) {
            if (event.keyCode == KeyEvent.KEYCODE_MENU && event.action == KeyEvent.ACTION_UP) openSetup()
            return if (event.keyCode == KeyEvent.KEYCODE_HOME) super.dispatchKeyEvent(event) else true
        }
        if (event.keyCode == KeyEvent.KEYCODE_MENU || event.keyCode == KeyEvent.KEYCODE_SETTINGS) {
            if (event.action == KeyEvent.ACTION_UP) openSetup()
            return true
        }
        if (!railOpen && state.pane == Pane.GROUPS && state.focusedGroup == state.groups.size) {
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER) {
                if (event.action == KeyEvent.ACTION_UP) openSetup()
                return true
            }
        }
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                backHeld = true; backHandled = false; handler.postDelayed(longBack, 650)
            } else if (event.action == KeyEvent.ACTION_UP) {
                handler.removeCallbacks(longBack); backHeld = false
                if (!backHandled && !event.isCanceled) navigate(RemoteKey.BACK)
            } else if (event.action == KeyEvent.ACTION_DOWN && event.isLongPress && !backHandled) {
                backHandled = true; handler.removeCallbacks(longBack); navigate(RemoteKey.LONG_BACK)
            }
            return true
        }
        val key = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> RemoteKey.UP
            KeyEvent.KEYCODE_DPAD_DOWN -> RemoteKey.DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> RemoteKey.LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> RemoteKey.RIGHT
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> RemoteKey.CENTER
            else -> null
        }
        if (key != null) {
            val detailScroll = (if (state.pane == Pane.FULLSCREEN) fullscreenChrome else chrome)
                            .findViewWithTag<android.widget.ScrollView>("programme-details")
            if (currentFocus is android.widget.ScrollView) {
                val scroll = currentFocus as android.widget.ScrollView
                if (event.action == KeyEvent.ACTION_DOWN && key == RemoteKey.DOWN && !scroll.canScrollVertically(1)) {
                    // Leaving details on purpose: drop the reading focus so render() returns it to the actions.
                    scroll.isFocusableInTouchMode = false
                    scroll.clearFocus()
                    render()
                    resetOverlayTimeout()
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
            val atTop = state.pane == Pane.GUIDE && state.channels.firstOrNull()?.id == state.channelId
            val aboveActions = state.pane == Pane.FULLSCREEN && state.overlay && !state.toolbar
            if (key == RemoteKey.UP && event.action == KeyEvent.ACTION_DOWN && (atTop || aboveActions) &&
                detailScroll != null && detailScroll.getChildAt(0).height > detailScroll.height) {
                detailScroll.isFocusableInTouchMode = true
                detailScroll.requestFocus()
                handler.removeCallbacks(hideOverlay)
                return true
            }
            if (key == RemoteKey.CENTER && state.pane == Pane.GUIDE && !state.canActivateProgramme)
                return true
            // Let actual focused cells/actions receive Center, including accessibility clicks.
            if (key == RemoteKey.CENTER && currentFocus?.let { it.isClickable && it.isShown && it.hasWindowFocus() } == true)
                return super.dispatchKeyEvent(event)
            if (event.action == KeyEvent.ACTION_DOWN && (key != RemoteKey.CENTER || event.repeatCount == 0)) navigate(key)
            return true
        }
        if (event.keyCode in listOf(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE)) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (suppressSetupPlayback && event.keyCode != KeyEvent.KEYCODE_MEDIA_PAUSE) {
                    sleepTimer.allowPlayback(); shouldPlay = true; prepareTuned(); return true
                }
                if (event.keyCode != KeyEvent.KEYCODE_MEDIA_PAUSE && sleepTimer.expired) {
                    sleepTimer.allowPlayback(); shouldPlay = true
                    prepareTuned(); return true
                }
                player?.let { it.playWhenReady = when (event.keyCode) {
                    KeyEvent.KEYCODE_MEDIA_PLAY -> true
                    KeyEvent.KEYCODE_MEDIA_PAUSE -> false
                    else -> !it.playWhenReady
                } }
                playbackStatus = "${sourceLabel()} • ${if (player?.playWhenReady == true) "playing" else "paused"}"
                render()
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun navigate(key: RemoteKey) {
        if (scheduleOverlay.visibility == View.VISIBLE) return
        if (state.pane == Pane.GROUPS && key == RemoteKey.LEFT) {
            railOpen = true; render(); return
        }
        val oldTune = state.tunedId
        if (state.pane == Pane.FULLSCREEN && state.overlay && !state.toolbar &&
            key == RemoteKey.RIGHT && state.action >= 2 && (player?.playerError != null || portalFailed)) {
            state = state.copy(action = 3); resetOverlayTimeout(); render(); return
        }
        if (state.pane == Pane.GROUPS && key == RemoteKey.DOWN && state.focusedGroup >= state.groups.lastIndex) {
            state = state.copy(focusedGroup = state.groups.size); render(); return
        }
        if (state.pane == Pane.FULLSCREEN && state.overlay && state.toolbar && state.toolbarAction in listOf(2, 3, 7) && key == RemoteKey.CENTER) {
            when (state.toolbarAction) {
                2 -> openAdvanced(AdvancedPlaybackActivity.MODE_RECORDINGS)
                3 -> openAdvanced(AdvancedPlaybackActivity.MODE_MULTIVIEW)
                7 -> showAudioDelay()
            }
        } else if (state.pane == Pane.FULLSCREEN && state.overlay && state.toolbar && state.toolbarAction == 0 && key == RemoteKey.CENTER) {
            showSearch()
        } else if (state.pane == Pane.FULLSCREEN && state.overlay && state.toolbar && state.toolbarAction == 11 && key == RemoteKey.CENTER) {
            toggleTunedFavorite()
        } else if (state.pane == Pane.FULLSCREEN && state.overlay && state.toolbar && state.toolbarAction == 12 && key == RemoteKey.CENTER) {
            showChannelOptions()
        } else if (state.pane == Pane.FULLSCREEN && state.overlay && state.toolbar && state.toolbarAction == 13 && key == RemoteKey.CENTER) {
            showSettings()
        } else if (state.pane == Pane.FULLSCREEN && state.overlay && state.toolbar && state.toolbarAction == 4 && key == RemoteKey.CENTER) {
            enterPlayerPip()
        } else if (state.pane == Pane.FULLSCREEN && state.overlay && !state.toolbar && state.action == 1 && key == RemoteKey.CENTER) {
            showChannels("history")
        } else if (state.pane == Pane.FULLSCREEN && state.overlay && state.toolbar && state.toolbarAction == 1 && key == RemoteKey.CENTER) {
            browseGroup = state.group; showChannels("channels")
        } else if (state.pane == Pane.FULLSCREEN && state.overlay && !state.toolbar && state.action == 2 && key == RemoteKey.CENTER) {
            showSchedule()
        } else if (state.pane == Pane.FULLSCREEN && state.overlay && !state.toolbar && state.action == 3 && key == RemoteKey.CENTER) {
            if (player?.playerError != null || portalFailed) {
                playbackStatus = "${sourceLabel()} • retrying"
                prepareTuned()
            }
        } else if (state.pane == Pane.FULLSCREEN && state.overlay && state.toolbar &&
            key == RemoteKey.CENTER && state.toolbarAction == 10) {
            showSleepDialog()
        } else if (state.pane == Pane.FULLSCREEN && state.overlay && state.toolbar &&
            key == RemoteKey.CENTER && state.toolbarAction == 9) {
            showDisplayDialog()
        } else if (state.pane == Pane.FULLSCREEN && state.overlay && state.toolbar &&
            key == RemoteKey.CENTER && state.toolbarAction in TrackControls.types) {
            showTrackDialog(TrackControls.types.getValue(state.toolbarAction))
        } else state = state.move(key)
        if (state.tunedId != oldTune || (suppressSetupPlayback && key == RemoteKey.CENTER && state.pane == Pane.FULLSCREEN && !state.toolbar)) {
            recordTune(state.tunedId)
            sleepTimer.allowPlayback()
            playbackPosition = 0; shouldPlay = true
            player?.let { it.trackSelectionParameters = it.trackSelectionParameters.buildUpon().clearOverrides().build() }
            prepareTuned()
        }
        resetOverlayTimeout()
        render()
    }

    private fun bounds(x: Int, y: Int, w: Int, h: Int) = FrameLayout.LayoutParams(
        (w * scale).toInt(), (h * scale).toInt()
    ).apply { leftMargin = (x * scale).toInt(); topMargin = (y * scale).toInt() }

    private fun box(parent: FrameLayout, x: Int, y: Int, w: Int, h: Int, color: Int): FrameLayout =
        FrameLayout(this).apply {
            setBackgroundColor(color)
            parent.addView(this, bounds(x, y, w, h))
        }

    private fun text(parent: FrameLayout, value: String, x: Int, y: Int, w: Int, h: Int,
        size: Int = 15, color: Int = GlassTokens.textPrimary, background: Int = Color.TRANSPARENT, bold: Boolean = false,
        focused: Boolean = false, onFocus: (() -> Unit)? = null, onClick: (() -> Unit)? = null) {
        val label = TextView(this).apply {
            text = value
            setTextColor(color)
            TvTypography.apply(this, size)
            gravity = Gravity.CENTER_VERTICAL
            if (h <= 40 || h == 62) {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            setPadding((8 * scale).toInt(), 0, (6 * scale).toInt(), 0)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            if (background != Color.TRANSPARENT) this.background = GradientDrawable().apply {
                setColor(background); cornerRadius = 12 * scale
            }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            if (onClick != null) {
                isFocusable = true
                isFocusableInTouchMode = true
                isClickable = true
                setOnClickListener { onClick() }
                setOnFocusChangeListener { _, hasFocus -> if (hasFocus && !restoringFocus) onFocus?.invoke() }
            }
        }
        parent.addView(label, bounds(x, y, w, h).apply {
            height = maxOf(height, TvTypography.lineHeight(label))
        })
        if (focused) focusTargets.add(label)
    }

    private fun render() {
        if (!::root.isInitialized || root.width == 0) return
        bufferingIndicator.visibility = if (player?.playbackState == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
        if (scheduleOverlay.visibility == View.VISIBLE) return
        backdrop.visibility = if (state.pane == Pane.FULLSCREEN && !loading) View.GONE else View.VISIBLE
        if (inPip()) {
            chrome.visibility = View.GONE
            guide.visibility = View.GONE
            fullscreenChrome.visibility = View.GONE
            videoViewport.layoutParams = FrameLayout.LayoutParams(-1, -1)
            applyDisplayMode(root.width, root.height)
            return
        }
        chrome.visibility = View.VISIBLE
        // Player callbacks must not rebuild/focus controls behind an active browser.
        if (channelDialog?.isShowing == true || searchDialog?.isShowing == true || optionsDialog?.isShowing == true || settingsDialog?.isShowing == true) {
            if (!loading) applyDisplayMode(videoViewport.width, videoViewport.height)
            return
        }
        // Player and EPG callbacks re-render while the viewer reads fullscreen details. Read the
        // reading position before hiding the chrome below moves focus away from it.
        val readingScroll = (currentFocus as? android.widget.ScrollView)
            ?.takeIf { it.tag == "programme-details" && state.pane == Pane.FULLSCREEN }?.scrollY
        chrome.removeAllViews()
        focusTargets.clear()
        fullscreenChrome.visibility = View.GONE
        if (loading) {
            guide.visibility = View.GONE
            video.visibility = View.GONE
            videoViewport.visibility = View.GONE
            showSplash()
            return
        }
        video.visibility = View.VISIBLE
        videoViewport.visibility = View.VISIBLE
        chrome.contentDescription = null
        val full = state.pane == Pane.FULLSCREEN
        val p = state.programme
        val windowStart = if (state.realEpg) (maxOf(p.start, state.nowMinute) / 30) * 30 else if (p.start < 60) 0 else (p.start / 30) * 30
        val geometry = GuideGeometry(TvTypography.expansion(this, scale))
        val guideTop = geometry.guideTop.coerceAtMost(420)
        val guideLeft = if (state.pane == Pane.GROUPS) 308 else 68
        val guideWidth = 944 - guideLeft
        guide.layoutParams = bounds(guideLeft, guideTop, guideWidth, 524 - guideTop)
        guide.show(state, scale, windowStart)
        val previewWidth = if (state.pane == Pane.GROUPS) 240 else 288
        val viewportWidth = ((if (full) 960 else previewWidth) * scale).toInt()
        val viewportHeight = ((if (full) 540 else previewWidth * 9 / 16) * scale).toInt()
        videoViewport.background = GradientDrawable().apply {
            setColor(getColor(android.R.color.black)); cornerRadius = if (full) 0f else 12 * scale
        }
        videoViewport.clipToOutline = !full
        videoViewport.layoutParams = if (full) bounds(0, 0, 960, 540) else bounds(guideLeft, 16, previewWidth, previewWidth * 9 / 16)
        applyDisplayMode(viewportWidth, viewportHeight)
        if (full) {
            railOpen = false
            chrome.contentDescription = "FULLSCREEN: ${state.tunedChannel.name}, overlay ${state.overlay}"
            val failed = player?.playerError != null || portalFailed
            if (failed) handler.removeCallbacks(hideOverlay)
            if (!failed && state.action == 3) state = state.copy(action = 0)
            fullscreenChrome.show(state, scale, privatePlaylist, playbackStatus, mediaBadges(), failed,
                select = { toolbar, index ->
                    val changed = if (toolbar) state.toolbarAction != index else state.action != index
                    if (changed) {
                        state = if (toolbar) state.copy(toolbarAction = index) else state.copy(action = index)
                        resetOverlayTimeout(); render()
                    }
                }, activate = { index -> state = state.copy(action = index); navigate(RemoteKey.CENTER) },
                openToolbar = { index -> state = state.copy(toolbarAction = index); navigate(RemoteKey.CENTER) },
                sleepLabel = if (sleepTimer.deadline == 0L) "Off" else
                    "${(sleepTimer.remaining(android.os.SystemClock.elapsedRealtime()) + 59999) / 60000} min",
                favorite = state.tunedId in favoriteChannels, pictureInPicture = pipSupported(), showSchedule = true)
            if (readingScroll != null && state.overlay && !state.toolbar) {
                fullscreenChrome.findViewWithTag<android.widget.ScrollView>("programme-details")?.let { details ->
                    details.isFocusableInTouchMode = true
                    details.requestFocus()
                    details.post { details.scrollTo(0, readingScroll) }
                }
            }
            if (failed && !state.overlay) {
                val error = FrameLayout(this).apply { background = GlassMaterial.panel(this@MainActivity, GlassVariant.REGULAR, 24f) }
                chrome.addView(error, bounds(24, 384, 912, 140))
                text(error, playbackStatus, 16, 12, 880, 56, 18)
                text(error, "Retry", 16, 76, 160, 44, 18, GlassTokens.lensText, GlassTokens.lens,
                    onClick = { state = state.copy(overlay = true, action = 3); navigate(RemoteKey.CENTER) })
            }
            if (!state.overlay) restoreChromeFocus()
            return
        }
        val groupsOpen = state.pane == Pane.GROUPS
        if (!groupsOpen) railOpen = false
        val capsule = FrameLayout(this).apply {
            background = GlassMaterial.panel(this@MainActivity, GlassVariant.REGULAR, 24f)
        }
        chrome.addView(capsule, bounds(12, 16, if (groupsOpen) 280 else 48, 508))
        val rail = GuidePanels.rail(this, scale, listOf("search" to "Search", "tv" to "TV"), 1) { index ->
            when (index) {
                0 -> showSearch()
                1 -> { railOpen = false; render() }
                2 -> openSetup()
            }
        }.apply { background = null }
        capsule.addView(rail, bounds(0, 0, 48, 508))
        listOf(1, 2, 3).forEachIndexed { index, child ->
            rail.getChildAt(child).apply {
                isFocusable = groupsOpen && railOpen
                isFocusableInTouchMode = isFocusable
                if (groupsOpen && railOpen && index == railItem) focusTargets.add(this)
            }
        }
        if (state.channels.isEmpty()) text(chrome, "No favorites yet • Add channels from the player toolbar", guideLeft + 12, guideTop + 12, guideWidth - 24, 70, 20)
        val details = android.widget.ScrollView(this).apply {
            tag = "programme-details"
            isFillViewport = true
            clipToOutline = true
            background = GlassMaterial.panel(this@MainActivity, GlassVariant.REGULAR, 24f)
            contentDescription = "Programme details, scroll for more"
        }
        val detailBody = GuidePanels.detailsCard(this, scale).apply { background = null }
        details.addView(detailBody)
        val detailLeft = guideLeft + previewWidth + 8
        chrome.addView(details, bounds(detailLeft, 16, 944 - detailLeft, maxOf(previewWidth * 9 / 16, guideTop - 72)))
        fun detail(value: String, size: Int, color: Int = GlassTokens.textPrimary, title: Boolean = false, lines: Int = Int.MAX_VALUE) {
            detailBody.addView(TextView(this).apply {
                text = value
                TvTypography.apply(this, size)
                setTextColor(color)
                if (title) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                maxLines = lines
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, 0, 0, (4 * scale).toInt())
            }, android.widget.LinearLayout.LayoutParams(-1, -2))
        }
        detail(p.title, 28, title = true)
        detail(if (privatePlaylist && !state.realEpg) "No EPG configured" else "${state.time(p.start)} – ${state.time(p.end)}", 14, GlassTokens.textSecondary)
        if ((!privatePlaylist || state.realEpg) && p.duration > 0 && p.isCurrentAt(state.nowMinute)) {
            detailBody.addView(android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = p.duration
                progress = state.nowMinute - p.start
                progressTintList = android.content.res.ColorStateList.valueOf(GlassTokens.accent)
                progressBackgroundTintList = android.content.res.ColorStateList.valueOf(GlassTokens.fillQuietStrong)
                contentDescription = "Programme progress"
            }, android.widget.LinearLayout.LayoutParams(-1, (4 * scale).toInt()))
            detail("${p.end - state.nowMinute} min left", 14, GlassTokens.textSecondary)
        }
        p.unavailableActionExplanation(state.nowMinute)?.let { detail(it, 14, GlassTokens.textSecondary) }
        detail(p.description, 16, lines = 3)
        val origin = geometry.timelineOrigin
        val clockOnly = privatePlaylist && !state.realEpg
        val rulerNow = if (clockOnly) Epg.minute() else state.nowMinute
        val rulerStart = if (clockOnly) rulerNow / 30 * 30 else windowStart
        fun rulerTime(minute: Int) = if (clockOnly) Epg.time(minute) else state.time(minute)
        val nowLocal = origin + (rulerNow - rulerStart) * 320f / 60
        val labels = (rulerStart..rulerStart + 150 step 30).mapNotNull { minute ->
            val x = origin + (minute - rulerStart) * 320f / 60
            if (x < guideWidth - 12) x * scale to rulerTime(minute) else null
        }
        val visibleNow = nowLocal.takeIf { it in origin.toFloat()..guideWidth.toFloat() }
        chrome.addView(GuidePanels.ruler(this, scale, labels, visibleNow?.times(scale), visibleNow?.let { rulerTime(rulerNow) }),
            bounds(guideLeft, guideTop - 32, guideWidth, 24))
        if (visibleNow != null) box(chrome, guideLeft + visibleNow.toInt(), guideTop, 1, 524 - guideTop, GlassTokens.accent)
        if (groupsOpen) {
            text(capsule, if (privatePlaylist) "Playlist groups" else "Fixture groups", 56, 16, 216, 52, 18, bold = true)
            val options = state.groups + "Playlist setup…"
            val groupPitch = geometry.rowPitch
            val groupTop = 80
            val groupCount = ((464 - groupTop) / groupPitch).coerceAtLeast(1)
            val firstGroup = (state.focusedGroup - groupCount + 1).coerceAtLeast(0)
            options.drop(firstGroup).take(groupCount).forEachIndexed { row, name ->
                val index = firstGroup + row
                val active = !railOpen && index == state.focusedGroup
                text(capsule, name, 56, groupTop + row * groupPitch, 216, geometry.cellHeight, 16,
                    if (active) GlassTokens.lensText else GlassTokens.textPrimary,
                    if (active) GlassTokens.lens else if (index == state.group) GlassTokens.fillQuietStrong else Color.TRANSPARENT,
                    focused = active,
                    onFocus = { if (state.focusedGroup != index) { state = state.copy(focusedGroup = index); render() } },
                    onClick = { if (index == state.groups.size) openSetup() else { state = state.copy(focusedGroup = index); navigate(RemoteKey.CENTER) } })
            }
            text(capsule, "Right: guide", 56, 472, 216, 28, 12, GlassTokens.textSecondary)
        }
        chrome.contentDescription = "${state.pane}: ${state.channel.name}, ${state.programme.title}. Tuned ${state.tunedChannel.name}"
        if (state.pane == Pane.GROUPS) restoreChromeFocus()
    }

    private fun restoreChromeFocus() {
        restoringFocus = true
        try { (focusTargets.firstOrNull() ?: chrome).requestFocus() }
        finally { restoringFocus = false }
    }

    private fun openSetup() {
        setupOpen = true
        suppressSetupPlayback = true
        startActivity(SettingsActivity.intent(this, playlistId = catalogStore?.playlistId, channelId = state.tunedId))
    }

    private fun openAdvanced(mode: String) {
        val id = catalogStore?.playlistId ?: return
        suppressSetupPlayback = true
        startActivity(AdvancedPlaybackActivity.intent(this, mode, id, state.tunedId))
    }

    private fun showAudioDelay() {
        val choices = intArrayOf(0, 100, 250, 500, 750, 1000, 1500, 2000)
        handler.removeCallbacks(hideOverlay)
        audioDelayDialog?.dismiss()
        audioDelayDialog = GlassDialogs.builder(this)
            .setTitle("Audio offset • experimental PCM delay")
            .setSingleChoiceItems(choices.map { if (it == 0) "Off" else "+$it ms • audio later" }.toTypedArray(),
                choices.indexOf(AudioDelay.delayMs)) { dialog, index ->
                player?.let { AudioDelay.setDelayAndReprepare(it, choices[index]) }
                dialog.dismiss()
            }
            .setNegativeButton("Back", null)
            .setNeutralButton("Scope / limits") { _, _ ->
                GlassDialogs.builder(this).setMessage("Positive delay only; decoded PCM audio, not passthrough/offload. This process session only. Changing it re-prepares playback and can reconnect a live stream. Lip-sync still needs device acceptance.")
                    .setPositiveButton("Back", null).showGlass()
            }.create().also { dialog ->
                CapturePolicy.secure(dialog.window)
                dialog.setOnDismissListener { audioDelayDialog = null; resetOverlayTimeout() }
                dialog.showGlass()
            }
    }

    private fun sourceLabel() = if (privatePlaylist) "Playlist" else "Local fixture"

    private fun resetOverlayTimeout() {
        handler.removeCallbacks(hideOverlay)
        if (state.overlay && started && scheduleOverlay.visibility != View.VISIBLE && audioDelayDialog == null && player?.playerError == null && !portalFailed && currentFocus !is android.widget.ScrollView && trackDialog == null && displayDialog == null && sleepDialog == null && channelDialog == null && searchDialog == null && optionsDialog == null && settingsDialog == null) handler.postDelayed(hideOverlay, 7000)
    }

    private fun tuneFromBrowser(id: Int) {
        if (state.channels.none { it.id == id }) state = state.copy(group = 0, focusedGroup = 0)
        state = state.copy(channelId = id, programmeIndex = 0)
        if (id == state.tunedId) return
        state = state.copy(tunedId = id)
        recordTune(id); sleepTimer.allowPlayback(); playbackPosition = 0; shouldPlay = true
        player?.let { it.trackSelectionParameters = it.trackSelectionParameters.buildUpon().clearOverrides().build() }
        prepareTuned()
    }

    private fun showSearch() {
        searchDialog?.dismiss()
        handler.removeCallbacks(hideOverlay)
        val input = android.widget.EditText(this).apply {
            hint = "Channel name"; setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            if (android.os.Build.VERSION.SDK_INT >= 26) importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            setText(searchQuery); setSelection(text.length)
        }
        val status = android.widget.TextView(this)
        val list = android.widget.ListView(this)
        val body = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(24, 8, 24, 8)
            addView(input); addView(status)
            addView(list, android.widget.LinearLayout.LayoutParams(-1, (260 * resources.displayMetrics.density).toInt()))
        }
        var rows = emptyList<Channel>()
        val dialog = GlassDialogs.builder(this).setTitle("Search channels")
            .setView(body).setNegativeButton("Back", null).create()
        var pending: Runnable? = null
        fun update() {
            pending?.let { handler.removeCallbacks(it) }
            searchQuery = input.text.toString().take(200)
            val query = searchQuery; val catalog = state.catalog; val generation = ++searchGeneration
            rows = emptyList(); list.adapter = GlassDialogs.itemsAdapter(this, emptyList())
            status.text = if (query.isBlank()) "Enter a channel name" else "Searching…"
            pending = Runnable { catalogWorker.execute {
                val found = ChannelTools.search(catalog, query, 201)
                runOnUiThread {
                    if (searchDialog !== dialog || generation != searchGeneration || !started) return@runOnUiThread
                    rows = found.take(200)
                    status.text = if (query.isBlank()) "Enter a channel name" else if (found.size > 200)
                        "First 200 matches • Refine your search" else "${rows.size} matches"
                    list.adapter = GlassDialogs.itemsAdapter(this, rows.map { "${it.id + 1}  ${it.name}" })
                }
            } }.also { handler.postDelayed(it, 180) }
        }
        input.filters = arrayOf(android.text.InputFilter.LengthFilter(200))
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { update() }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        list.setOnItemClickListener { _, _, index, _ ->
            rows.getOrNull(index)?.let {
                tuneFromBrowser(it.id); state = state.copy(overlay = false, toolbar = false); dialog.dismiss()
            }
        }
        CapturePolicy.secure(dialog.window)
        dialog.setOnDismissListener {
            if (searchDialog === dialog) {
                pending?.let { handler.removeCallbacks(it) }
                searchGeneration++; searchDialog = null; resetOverlayTimeout()
                if (started) render()
            }
        }
        searchDialog = dialog
        dialog.showGlass(); input.requestFocus(); update()
    }

    private fun recordTune(id: Int) {
        recentChannels = RecentChannels.record(recentChannels, id)
        val ids = recentChannels; val version = revision; val context = applicationContext; val targetId = catalogStore?.playlistId
        catalogWorker.execute {
            try { PlaylistStore(context, targetId).saveHistory(version, ids) } catch (_: Exception) {
                runOnUiThread { if (!isDestroyed) StatusCapsule.show(this,
                    "History could not be saved.") }
            }
        }
    }

    private fun showChannels(surface: String) {
        channelDialog?.dismiss()
        handler.removeCallbacks(hideOverlay)
        channelSurface = surface
        val rows = state.browserChannels(surface, browseGroup, recentChannels, favoriteChannels)
        val title = if (surface == "history") "History • Recent tune requests" else if (surface == "favorites") "Favorites" else "Channels list • ${state.groups[browseGroup]}"
        val builder = GlassDialogs.builder(this).setTitle(title).setNegativeButton("Back", null)
        if (rows.isEmpty()) builder.setMessage(if (surface == "favorites" || browseGroup == state.favoritesGroup) "No favorites yet. Use Add to Favorites in the player toolbar."
            else "No history yet. Channels appear after an explicit tune request.")
        else builder.setSingleChoiceItems(ChannelBrowserAdapter(this, rows, state.tunedId), if (pendingBrowserRow >= 0)
            pendingBrowserRow.coerceAtMost(rows.lastIndex) else rows.indexOfFirst { it.id == state.tunedId }) { dialog, index ->
            val id = rows[index].id
            if (surface == "favorites" || surface == "channels") {
                val selectedGroup = if (surface == "favorites") state.favoritesGroup else browseGroup
                state = state.copy(group = selectedGroup, focusedGroup = selectedGroup,
                    channelId = id, programmeIndex = 0)
            } else if (state.channels.none { it.id == id }) {
                // Search/history destinations outside the active group explicitly return to All.
                state = state.copy(group = 0, focusedGroup = 0, channelId = id, programmeIndex = 0)
            }
            if (id != state.tunedId) {
                tuneFromBrowser(id)
            }
            state = state.copy(overlay = false, toolbar = false)
            dialog.dismiss()
        }
        if (surface == "channels") {
            builder.setNeutralButton("Groups") { _, _ -> showChannelGroups() }
            builder.setPositiveButton("Favorites") { _, _ -> showChannels("favorites") }
        }
        channelDialog = builder.create().also { dialog ->
            CapturePolicy.secure(dialog.window)
            dialog.setOnDismissListener {
                if (channelDialog === dialog) {
                    channelDialog = null; channelSurface = ""; resetOverlayTimeout()
                    if (started) render()
                }
            }
            dialog.showGlass()
            if (pendingBrowserRow >= 0 && rows.isNotEmpty()) {
                val row = pendingBrowserRow.coerceAtMost(rows.lastIndex)
                dialog.listView.post {
                    if (channelDialog === dialog && dialog.isShowing) {
                        dialog.listView.requestFocus()
                        dialog.listView.setSelection(row)
                    }
                }
            }
            pendingBrowserRow = -1
        }
    }

    private fun showChannelGroups() {
        channelSurface = "groups"
        handler.removeCallbacks(hideOverlay)
        channelDialog = GlassDialogs.builder(this).setTitle("Channel groups")
            .setSingleChoiceItems(state.groups.toTypedArray(), browseGroup) { dialog, index ->
                browseGroup = index; dialog.dismiss(); showChannels("channels")
            }.setNegativeButton("Back", null).create().also { dialog ->
                CapturePolicy.secure(dialog.window)
                dialog.setOnDismissListener {
                    if (channelDialog === dialog) {
                        channelDialog = null; channelSurface = ""; resetOverlayTimeout()
                        if (started) render()
                    }
                }
                dialog.showGlass()
            }
    }

    private fun showSettings() {
        settingsDialog?.dismiss()
        handler.removeCallbacks(hideOverlay)
        fun trackValue(type: Int): String {
            val key = trackChoices["${state.tunedId}:$type"] ?: "auto"
            if (key == "off") return "Off"
            return player?.let { TrackControls.options(it.currentTracks, type) }
                ?.firstOrNull { it.key == key && it.supported }?.label ?: "Auto"
        }
        val rows = QuickSettings.standardRows(
            trackValue(androidx.media3.common.C.TRACK_TYPE_VIDEO),
            trackValue(androidx.media3.common.C.TRACK_TYPE_AUDIO),
            trackValue(androidx.media3.common.C.TRACK_TYPE_TEXT),
            DisplayMode.parse(displayChoices[state.tunedId.toString()]).label,
            if (sleepTimer.deadline == 0L) "Off" else "${(sleepTimer.remaining(android.os.SystemClock.elapsedRealtime()) + 59999) / 60000} min",
            AudioDelay.delayMs, pipSupported())
        fullscreenChrome.recedeToolbar(true)
        settingsDialog = QuickSettings.create(this, "Quick settings",
            "${state.tunedId + 1} ${state.tunedChannel.name} · this channel", rows, settingsRow,
            onRow = { index ->
                settingsRow = index
                when (index) {
                    0 -> showTrackDialog(androidx.media3.common.C.TRACK_TYPE_VIDEO)
                    1 -> showTrackDialog(androidx.media3.common.C.TRACK_TYPE_AUDIO)
                    2 -> showTrackDialog(androidx.media3.common.C.TRACK_TYPE_TEXT)
                    3 -> showDisplayDialog()
                    4 -> showSleepDialog()
                    5 -> showAudioDelay()
                    6 -> showChannelOptions()
                }
            }, onAllSettings = {
                settingsRow = rows.size
                pendingSettingsDialog = true
                settingsDialog?.dismiss()
                openSetup()
            }, onDismiss = {
                settingsDialog = null
                fullscreenChrome.recedeToolbar(false)
                resetOverlayTimeout()
                if (started) render()
            }, toolbarTop = fullscreenChrome.toolbarTop()).also { it.show() }
    }

    private fun showSchedule() {
        handler.removeCallbacks(hideOverlay)
        fullscreenChrome.visibility = View.GONE
        chrome.visibility = View.GONE
        val updated = epgData?.importedAt?.takeIf { it > 0 }?.let {
            java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(it))
        }
        scheduleOverlay.show(state.tunedChannel, state.tunedId + 1, state.nowMinute, state::time, updated, scale, ::dismissSchedule)
    }

    private fun dismissSchedule() {
        scheduleOverlay.hide()
        state = state.copy(overlay = true, toolbar = false, action = 2)
        render()
        resetOverlayTimeout()
    }

    private fun toggleTunedFavorite() {
        favoriteChannels = ChannelTools.toggle(favoriteChannels, state.tunedId)
        state = state.copy(favorites = favoriteChannels)
        if (state.group == state.favoritesGroup && state.channels.isNotEmpty() && state.channels.none { it.id == state.channelId }) {
            state = state.copy(channelId = state.channels.first().id, programmeIndex = 0)
        }
        val ids = favoriteChannels; val version = revision; val context = applicationContext; val targetId = catalogStore?.playlistId
        catalogWorker.execute {
            try { PlaylistStore(context, targetId).saveFavorites(version, ids) } catch (_: Exception) {
                runOnUiThread { if (!isDestroyed) StatusCapsule.show(this,
                    "Favorites could not be saved.") }
            }
        }
        refreshChannelOptions()
    }

    private fun refreshChannelOptions() {
        val dialog = optionsDialog ?: return
        val exo = player
        fun trackSummary(type: Int): String {
            val key = trackChoices["${state.tunedId}:$type"] ?: "auto"
            val options = exo?.let { TrackControls.options(it.currentTracks, type) }.orEmpty()
            val saved = options.firstOrNull { it.key == key && it.supported }
            val choice = when (key) {
                "auto" -> "Auto"
                "off" -> "Off"
                else -> saved?.label ?: "Saved track unavailable; using Auto"
            }
            val selected = options.filter { it.group?.isTrackSelected(it.index) == true }
            val playing = if (key == "off") "Disabled" else
                selected.joinToString("; ") { it.label }.ifBlank { "No selected track" }
            return "$choice\nCurrent: $playing"
        }
        dialog.setMessage("Prototype controls • This channel only\n\n" +
            "Video: ${trackSummary(androidx.media3.common.C.TRACK_TYPE_VIDEO)}\n\n" +
            "Audio: ${trackSummary(androidx.media3.common.C.TRACK_TYPE_AUDIO)}\n\n" +
            "Captions: ${trackSummary(androidx.media3.common.C.TRACK_TYPE_TEXT)}\n\n" +
            "Display: ${DisplayMode.parse(displayChoices[state.tunedId.toString()]).label}\n\n" +
            "Favorite: ${if (state.tunedId in favoriteChannels) "Yes" else "No"}\n\n" +
            "Reset restores video, audio and captions to Auto and display to Normal. " +
            "Favorites, other channels, the sleep timer and playlist data are unchanged.")
        dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL)?.text =
            if (state.tunedId in favoriteChannels) "Remove Favorite" else "Add Favorite"
    }

    private fun showChannelOptions() {
        optionsDialog?.dismiss()
        handler.removeCallbacks(hideOverlay)
        optionsDialog = GlassDialogs.builder(this)
            .setTitle("Channel options • ${state.tunedChannel.name}")
            .setMessage("")
            .setPositiveButton("Reset playback choices", null)
            .setNeutralButton("Add Favorite", null)
            .setNegativeButton("Back", null).create().also { dialog ->
                CapturePolicy.secure(dialog.window)
                dialog.setOnDismissListener {
                    if (optionsDialog === dialog) {
                        optionsDialog = null; resetOverlayTimeout()
                        if (started) render()
                    }
                }
                dialog.showGlass()
                dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener { toggleTunedFavorite() }
                dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    trackChoices = ChannelOptions.resetTracks(trackChoices, state.tunedId)
                    displayChoices = ChannelOptions.resetDisplay(displayChoices, state.tunedId)
                    val tracks = trackChoices; val display = displayChoices
                    val version = revision; val context = applicationContext; val targetId = catalogStore?.playlistId
                    catalogWorker.execute {
                        // Separate existing stores are not a cross-file transaction. Report any partial save.
                        var failed = false
                        try { PlaylistStore(context, targetId).saveTracks(version, tracks) } catch (_: Exception) { failed = true }
                        try { PlaylistStore(context, targetId).saveDisplay(version, display) } catch (_: Exception) { failed = true }
                        if (failed) runOnUiThread { if (!isDestroyed) StatusCapsule.show(this,
                            "Some playback choices could not be saved. Reopening may restore previous choices.",
                            long = true) }
                    }
                    applyTrackChoices()
                    applyDisplayMode(videoViewport.width, videoViewport.height)
                    refreshChannelOptions()
                }
                dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).requestFocus()
            }
        refreshChannelOptions()
    }

    private fun checkSleepTimer() {
        sleepTimer.expire(android.os.SystemClock.elapsedRealtime())
        if (!sleepTimer.expired) return
        shouldPlay = false
        resolveGeneration++; resolveClient?.cancel()
        player?.let { it.pause(); it.stop() }
        playbackStatus = "Sleep timer ended • Press Play to resume"
        if (::root.isInitialized) render()
    }

    private fun showSleepDialog() {
        sleepDialog?.dismiss()
        handler.removeCallbacks(hideOverlay)
        val remaining = sleepTimer.remaining(android.os.SystemClock.elapsedRealtime())
        val title = if (sleepTimer.deadline == 0L) "Sleep timer • Off" else
            "Sleep timer • ${(remaining + 59999) / 60000} min remaining"
        val labels = SleepTimer.minutesOptions.map { if (it == 0) "Off" else "$it minutes" }
        sleepDialog = GlassDialogs.builder(this)
            .setTitle(title)
            .setSingleChoiceItems(labels.toTypedArray(), if (remaining == 0L) 0 else -1) { dialog, index ->
                sleepTimer.setMinutes(SleepTimer.minutesOptions[index], android.os.SystemClock.elapsedRealtime())
                handler.removeCallbacks(sleepTick); handler.post(sleepTick)
                dialog.dismiss()
            }.setNegativeButton("Back", null).create().also { dialog ->
                CapturePolicy.secure(dialog.window)
                dialog.setOnDismissListener {
                    sleepDialog = null; resetOverlayTimeout()
                    if (started) render()
                }
                dialog.showGlass()
            }
    }

    private fun applyDisplayMode(width: Int, height: Int) {
        val mode = DisplayMode.parse(displayChoices[state.tunedId.toString()])
        DisplaySizing.apply(video, mode, width, height)
    }

    private fun showDisplayDialog() {
        displayDialog?.dismiss()
        handler.removeCallbacks(hideOverlay)
        val selected = DisplayMode.parse(displayChoices[state.tunedId.toString()]).ordinal
        displayDialog = GlassDialogs.builder(this)
            .setTitle("Display mode • This channel only")
            .setSingleChoiceItems(DisplayMode.entries.map { it.label }.toTypedArray(), selected) { dialog, index ->
                displayChoices = displayChoices + (state.tunedId.toString() to DisplayMode.entries[index].name)
                val choices = displayChoices.toMap(); val version = revision; val context = applicationContext; val targetId = catalogStore?.playlistId
                catalogWorker.execute {
                    try { PlaylistStore(context, targetId).saveDisplay(version, choices) } catch (_: Exception) {
                        runOnUiThread { if (!isDestroyed) StatusCapsule.show(this,
                            "Display choice could not be saved.") }
                    }
                }
                dialog.dismiss()
            }.setNegativeButton("Back", null).create().also { dialog ->
                CapturePolicy.secure(dialog.window)
                dialog.setOnDismissListener {
                    displayDialog = null
                    resetOverlayTimeout()
                    if (started) render()
                }
                dialog.showGlass()
            }
    }

    private fun applyTrackChoices() {
        val exo = player ?: return
        TrackControls.types.values.forEach { type ->
            TrackControls.apply(exo, type, trackChoices["${state.tunedId}:$type"] ?: "auto")
        }
    }

    private fun showTrackDialog(type: Int) {
        val exo = player ?: return
        trackDialog?.dismiss()
        handler.removeCallbacks(hideOverlay)
        val options = TrackControls.options(exo.currentTracks, type)
        val choice = trackChoices["${state.tunedId}:$type"] ?: "auto"
        val title = when (type) {
            androidx.media3.common.C.TRACK_TYPE_VIDEO -> "Video tracks"
            androidx.media3.common.C.TRACK_TYPE_AUDIO -> "Audio tracks"
            else -> "Closed captions"
        }
        val labels = options.map { option ->
            val selected = option.group?.isTrackSelected(option.index) == true
            option.label + if (selected) " • Playing" else ""
        }
        val hasTracks = options.any { it.group != null }
        val availableChoice = options.indexOfFirst { it.key == choice }.coerceAtLeast(0)
        val explanation = "This channel only • Auto selects available tracks. " +
            (if (!hasTracks) "No tracks available yet. Reopen after playback loads. " else "") +
            (if (type == androidx.media3.common.C.TRACK_TYPE_TEXT) "A listed caption track may contain no cues. " else "") +
            (if (choice != "auto" && choice != "off" && options.none { it.key == choice }) "Saved track unavailable; using Auto." else "")
        trackDialogType = type
        trackDialog = GlassDialogs.builder(this)
            .setTitle(title)
            .setSingleChoiceItems(labels.toTypedArray(), availableChoice) { dialog, index ->
                val option = options[index]
                // Validate against current tracks, not the possibly stale dialog snapshot.
                val current = TrackControls.options(exo.currentTracks, type)
                if (!option.supported || current.none { it.key == option.key && it.supported }) {
                    StatusCapsule.show(this, "Track unavailable. Reopen to refresh.")
                    dialog.dismiss()
                } else {
                    trackChoices = trackChoices + ("${state.tunedId}:$type" to option.key)
                    TrackControls.apply(exo, type, option.key)
                    val choices = trackChoices.toMap(); val version = revision; val context = applicationContext; val targetId = catalogStore?.playlistId
                    catalogWorker.execute {
                        try { PlaylistStore(context, targetId).saveTracks(version, choices) } catch (_: Exception) {
                            runOnUiThread { if (!isDestroyed) StatusCapsule.show(this,
                                "Track choice could not be saved.") }
                        }
                    }
                    dialog.dismiss()
                }
            }
            .setNegativeButton("Back", null)
            .create().also { dialog ->
                // Protect ordinary screenshots; privileged UI dumps still require privacy review.
                CapturePolicy.secure(dialog.window)
                dialog.setOnDismissListener {
                    trackDialog = null; trackDialogType = -1
                    resetOverlayTimeout()
                    if (started) render()
                }
                dialog.showGlass()
                // A header keeps the channel scope and no-track fallback visible with the choices.
                dialog.setTitle("$title\n$explanation")
            }
    }

    private fun mediaBadges(): String {
        val exo = player ?: return ""
        return buildList {
            exo.videoFormat?.let { format ->
                if (format.width > 0 && format.height > 0) add("${format.width} × ${format.height}")
                if (format.frameRate > 0) add("${format.frameRate.toInt()} FPS")
            }
            exo.audioFormat?.let { format ->
                if (format.channelCount > 0) add(if (format.channelCount == 2) "Stereo" else "${format.channelCount} ch")
            }
        }.joinToString(" • ")
    }

    private fun createVideoView() = PlayerView(this).apply {
        useController = false; isFocusable = false
        setBackgroundColor(getColor(android.R.color.black))
        setShutterBackgroundColor(getColor(android.R.color.black))
        setKeepContentOnPlayerReset(false)
        setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
        findViewById<View>(androidx.media3.ui.R.id.exo_buffering)?.apply {
            contentDescription = "Video buffering"
            isFocusable = false
        }
    }

    private fun resetVideoOutput(exo: ExoPlayer) {
        // A shutter cannot erase stale pixels in a reused decoder/surface buffer when
        // output resolution changes. Stop decoding, detach and discard that surface.
        // Keep the ExoPlayer/session, but never reuse the previous channel's output.
        exo.stop()
        video.player = null
        videoViewport.removeView(video)
        video = createVideoView()
        videoViewport.addView(video, FrameLayout.LayoutParams(-1, -1))
        bufferingIndicator.bringToFront()
        applyDisplayMode(videoViewport.width, videoViewport.height)
        video.player = exo
    }

    private fun prepareTuned(position: Long = 0) {
        suppressSetupPlayback = false
        if (sleepTimer.expired) return
        val exo = player ?: return
        resetVideoOutput(exo)
        // Cover the previous surface buffer before a new channel (including portal resolution).
        // Media3 opens its shutter when the new video actually renders its first frame.
        video.findViewById<View>(androidx.media3.ui.R.id.exo_shutter)?.visibility = View.VISIBLE
        val channel = state.tunedChannel
        portalFailed = false
        val config = provider
        val generation = ++resolveGeneration
        resolveClient?.cancel()
        if (config?.type != SourceType.STALKER) {
            exo.setMediaItem(MediaItem.fromUri(Uri.parse(channel.stream)))
            exo.seekTo(position); exo.playWhenReady = shouldPlay; exo.prepare()
            return
        }
        exo.stop(); exo.clearMediaItems()
        playbackStatus = "Portal • resolving channel…"; render()
        val client = ProviderHttp().also { resolveClient = it }
        resolver.execute {
            try {
                val link = Providers(config, client).resolve(channel)
                runOnUiThread {
                    if (generation == resolveGeneration && player === exo) {
                        exo.setMediaItem(MediaItem.fromUri(Uri.parse(link)))
                        exo.playWhenReady = shouldPlay; exo.prepare()
                    }
                }
            } catch (_: Exception) {
                runOnUiThread {
                    if (generation == resolveGeneration && player === exo) {
                        portalFailed = true
                        playbackStatus = "Portal link failed • open info and choose Retry, or check setup"
                        render()
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        stopSplashPulse()
        removeSplash()
        loadGeneration++
        handler.removeCallbacksAndMessages(null)
        resolver.shutdownNow()
        super.onDestroy()
    }

    private fun persistNavigation() {
        if (loading || revision.isEmpty()) return
        val saved = state
        val version = revision
        val context = applicationContext
        val targetId = catalogStore?.playlistId
        catalogWorker.execute {
            try { PlaylistStore(context, targetId).saveNavigation(version, saved) } catch (_: Exception) { }
        }
    }

    private fun animationsEnabled(): Boolean =
        android.provider.Settings.Global.getFloat(contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f &&
            (android.os.Build.VERSION.SDK_INT < 26 || android.animation.ValueAnimator.areAnimatorsEnabled())

    private fun showSplash() {
        if (splash == null) {
            val logo = android.widget.ImageView(this).apply {
                setImageResource(R.drawable.tiviman_wordmark)
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                adjustViewBounds = true
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            splashLogo = logo
            splash = FrameLayout(this).apply {
                setBackgroundColor(getColor(R.color.tiviman_canvas))
                isFocusableInTouchMode = true
                contentDescription = "Tiviman. Loading saved playlist"
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                addView(logo)
                root.addView(this, FrameLayout.LayoutParams(-1, -1))
            }
            splashStarted = android.os.SystemClock.elapsedRealtime()
        }
        splashLogo?.layoutParams = FrameLayout.LayoutParams(
            (300 * scale).toInt(), (300 * scale * 55.414f / 256).toInt(), Gravity.CENTER)
        splash?.requestFocus()
        startSplashPulse()
    }

    private fun startSplashPulse() {
        val logo = splashLogo ?: return
        if (!resumed || splashPulse != null || !animationsEnabled()) return
        splashPulse = android.animation.ObjectAnimator.ofFloat(logo, View.ALPHA, 1f, 0.55f).apply {
            duration = 1400
            repeatMode = android.animation.ValueAnimator.REVERSE
            repeatCount = android.animation.ValueAnimator.INFINITE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun stopSplashPulse() {
        splashPulse?.cancel()
        splashPulse = null
        splashLogo?.alpha = 1f
    }

    private fun removeSplash() {
        stopSplashPulse()
        splash?.animate()?.cancel()
        splash?.let { root.removeView(it) }
        splash = null
        splashLogo = null
    }

    private fun finishCatalogLoad(generation: Int, failed: Boolean = false) {
        root.post {
            if (isDestroyed || generation != loadGeneration) return@post
            // A fast fixture load can finish before the first measured render.
            scale = minOf(root.width / 960f, root.height / 540f)
            render()
            handler.postDelayed({
                if (!isDestroyed && generation == loadGeneration) {
                    loading = false
                    stopSplashPulse()
                    render()
                    startPlayer()
                    if (failed) StatusCapsule.show(this,
                        "Saved playlist could not be opened. Menu opens setup; saved data is unchanged.", long = true)
                    val overlay = splash
                    if (overlay != null && resumed && animationsEnabled()) {
                        overlay.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                        overlay.animate().alpha(0f).setDuration(200).withEndAction {
                            if (generation == loadGeneration && splash === overlay) removeSplash()
                        }.start()
                    } else removeSplash()
                }
            }, remainingSplashDelay(splashStarted, android.os.SystemClock.elapsedRealtime()))
        }
    }

    private fun loadCatalog() {
        removeSplash()
        loading = true
        val generation = ++loadGeneration
        render()
        val restore = pendingRestore
        val oldState = state
        val oldRevision = revision
        val context = applicationContext
        catalogWorker.execute {
            try {
                if (java.io.File(context.noBackupFilesDir, "epg-refresh").isDirectory) {
                    runCatching { EpgRefresh.reconcile(context) }
                }
                val loadedStore = PlaylistStore(context)
                val snapshot = loadedStore.snapshot()
                val catalog = snapshot.channels ?: Fixtures.channels
                val guideData = try { loadedStore.loadEpg(snapshot.revision) } catch (_: Exception) { null }
                val now = Epg.minute()
                val projectedCatalog = guideData?.let { Epg.project(catalog, it, now) } ?: catalog
                val restoredFavorites = snapshot.favorites.intersect(catalog.map { it.id }.toSet())
                // Build catalog/group indexes off the main thread too.
                var next = snapshot.bookmark?.restore(projectedCatalog, restoredFavorites) ?: GuideState(catalog = projectedCatalog, favorites = restoredFavorites)
                next.groups
                if (snapshot.revision == oldRevision) next = oldState.copy(catalog = projectedCatalog, favorites = restoredFavorites,
                    programmeIndex = oldState.programmeIndex.coerceIn(0, projectedCatalog.first { it.id == oldState.channelId }.programmes.lastIndex))
                val matchingRestore = restore?.getString("revision") == snapshot.revision
                if (matchingRestore) {
                    next = NavigationBookmark(restore!!.getInt("group"), restore.getInt("focusedGroup"),
                        restore.getInt("channel"), restore.getInt("programme"), restore.getInt("tuned")).restore(projectedCatalog, restoredFavorites)
                        .copy(pane = runCatching { Pane.valueOf(restore.getString("pane") ?: "GUIDE") }.getOrDefault(Pane.GUIDE),
                            overlay = restore.getBoolean("overlay"), toolbar = restore.getBoolean("toolbar"),
                            action = restore.getInt("action").coerceIn(0, 3),
                            toolbarAction = restore.getInt("toolbarAction").coerceIn(0, PlayerToolbar.entries.lastIndex))
                }
                val ready = next.copy(realEpg = guideData != null, nowMinute = if (guideData != null) now else Fixtures.NOW)
                val restoredHistory = RecentChannels.restore(snapshot.history, catalog.map { it.id }.toSet())
                runOnUiThread {
                    if (isDestroyed || generation != loadGeneration) return@runOnUiThread
                    catalogStore = loadedStore
                    state = ready; revision = snapshot.revision; provider = snapshot.provider
                    epgData = guideData; epgBaseCatalog = catalog
                    trackChoices = snapshot.tracks
                    displayChoices = snapshot.display
                    recentChannels = restoredHistory
                    favoriteChannels = restoredFavorites
                    if (matchingRestore) {
                        pendingSearch = restore!!.getBoolean("searchDialog")
                        pendingOptionsDialog = restore.getBoolean("optionsDialog")
                        pendingSettingsDialog = pendingSettingsDialog || restore.getBoolean("settingsDialog")
                        settingsRow = restore.getInt("settingsRow").coerceIn(0, 7)
                        searchQuery = restore.getString("searchQuery", "").take(200)
                        pendingChannelSurface = restore!!.getString("channelSurface", "")
                        pendingBrowserRow = restore.getInt("browserRow", -1)
                        browseGroup = restore.getInt("browseGroup").coerceIn(0, state.groups.lastIndex)
                        if (pendingChannelSurface == "groups") {
                            pendingChannelSurface = "channels"; pendingBrowserRow = -1
                        }
                    }
                    if (matchingRestore) pendingDisplayDialog = restore!!.getBoolean("displayDialog")
                    if (matchingRestore) pendingSleepDialog = restore!!.getBoolean("sleepDialog")
                    if (matchingRestore) pendingTrackDialog = restore!!.getInt("trackDialogType", -1)
                    privatePlaylist = snapshot.channels != null
                    if (!privatePlaylist) window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
                    if (matchingRestore) {
                        playbackPosition = restore!!.getLong("position"); shouldPlay = restore.getBoolean("playing", true)
                    } else if (oldRevision != revision) { playbackPosition = 0; shouldPlay = true }
                    pendingRestore = null
                    finishCatalogLoad(generation)
                }
            } catch (_: Exception) {
                runOnUiThread {
                    if (isDestroyed || generation != loadGeneration) return@runOnUiThread
                    finishCatalogLoad(generation, failed = true)
                }
            }
        }
    }
    private fun refreshEpg() {
        val catalog = epgBaseCatalog ?: return
        val target = catalogStore ?: return
        if (loading || epgProjecting) return
        epgProjecting = true
        val prior = epgData
        val version = revision; val now = Epg.minute()
        catalogWorker.execute {
            val data = runCatching { target.loadEpg(version) }.getOrNull() ?: prior
            val projected = data?.let { Epg.project(catalog, it, now) }
            runOnUiThread {
                epgProjecting = false
                if (!started || loading || revision != version || catalogStore !== target || epgData !== prior || projected == null) return@runOnUiThread
                val selected = state.programme
                val rows = projected.first { it.id == state.channelId }.programmes
                val index = rows.indexOfFirst { it.start == selected.start && it.title == selected.title }.coerceAtLeast(0)
                epgData = data
                state = state.copy(catalog = projected, programmeIndex = index, nowMinute = now, realEpg = true)
                render()
            }
        }
    }
}

/** Serialize saves and loads across Activity instances, including a rapid finish/relaunch. */
private object CatalogTasks {
    val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
}

package faisalnh.tiviman

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
open class SettingsActivity : Activity() {
    companion object {
        const val EXTRA_CATEGORY = "category"
        const val EXTRA_PLAYLIST_ID = "playlistId"
        const val EXTRA_CHANNEL_ID = "channelId"
        const val CATEGORY_PLAYLISTS = "playlists"
        const val CATEGORY_EPG = "epg"
        const val CATEGORY_RECORDINGS = "recordings"
        const val CATEGORY_MULTIVIEW = "multiview"
        const val CATEGORY_ABOUT = "about"
        private const val STATE_CATEGORY = "settingsCategory"

        fun intent(context: Context, category: String = CATEGORY_PLAYLISTS,
            playlistId: String? = null, channelId: Int = -1): Intent =
            Intent(context, SettingsActivity::class.java).putExtra(EXTRA_CATEGORY, category)
                .putExtra(EXTRA_PLAYLIST_ID, playlistId).putExtra(EXTRA_CHANNEL_ID, channelId)
    }

    private val categories = listOf(CATEGORY_PLAYLISTS, CATEGORY_EPG, CATEGORY_RECORDINGS,
        CATEGORY_MULTIVIEW, CATEGORY_ABOUT)
    private val rows = linkedMapOf<String, Button>()
    private lateinit var content: LinearLayout
    private lateinit var contentScroll: ScrollView
    private var firstContent: View? = null
    private var selectedCategory = CATEGORY_PLAYLISTS

    private fun dp(value: Int) = GlassMaterial.dp(this, value.toFloat())
    private fun title(category: String) = when (category) {
        CATEGORY_EPG -> "EPG"
        CATEGORY_RECORDINGS -> "Recordings"
        CATEGORY_MULTIVIEW -> "Multiview"
        CATEGORY_ABOUT -> "About"
        else -> "Playlists"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CapturePolicy.secure(window)
        selectedCategory = (savedInstanceState?.getString(STATE_CATEGORY)
            ?: intent.getStringExtra(EXTRA_CATEGORY)).takeIf { it in categories } ?: CATEGORY_PLAYLISTS
        val root = FrameLayout(this)
        root.addView(SettingsBackdrop(this), FrameLayout.LayoutParams(-1, -1))
        val columns = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        val sidebar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GlassMaterial.panel(this@SettingsActivity, GlassVariant.REGULAR, 24f)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        sidebar.addView(label("Settings", 28).apply { setPadding(dp(12), dp(8), dp(12), dp(20)) })
        val categoryColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val icons = listOf("Playlists", "EPG", "Recordings", "Multiview", "About")
        categories.forEachIndexed { index, category ->
            val row = Button(this).apply {
                id = View.generateViewId()
                text = title(category)
                isAllCaps = false
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                minHeight = dp(48)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                TvTypography.apply(this, 18)
                setTextColor(GlassMaterial.focusText())
                val icon = PlayerIconDrawable(this@SettingsActivity, icons[index]).apply { setBounds(0, 0, dp(24), dp(24)) }
                setCompoundDrawablesRelative(icon, null, null, null)
                compoundDrawablePadding = dp(12)

                background = StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_focused), GlassMaterial.focusable(this@SettingsActivity))
                    addState(intArrayOf(android.R.attr.state_pressed), GlassMaterial.lens(this@SettingsActivity))
                    addState(intArrayOf(android.R.attr.state_selected), GradientDrawable().apply {
                        setColor(GlassTokens.fillQuietStrong); cornerRadius = dp(12).toFloat()
                    })
                    addState(intArrayOf(), GlassMaterial.focusable(this@SettingsActivity, quietFill = false))
                }
                setOnFocusChangeListener { _, focused -> if (focused) selectCategory(category) }
                setOnClickListener { selectCategory(category); firstContent?.requestFocus() }
                setOnKeyListener { _, key, event ->
                    when (key) {
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            if (event.action == KeyEvent.ACTION_DOWN) firstContent?.requestFocus()
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                            if (event.action == KeyEvent.ACTION_DOWN) {
                                val offset = if (key == KeyEvent.KEYCODE_DPAD_UP) -1 else 1
                                rows[categories[(index + offset).coerceIn(categories.indices)]]?.requestFocus()
                            }
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_LEFT -> true
                        else -> false
                    }
                }
            }
            rows[category] = row
            categoryColumn.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
        sidebar.addView(ScrollView(this).apply { addView(categoryColumn) }, LinearLayout.LayoutParams(-1, 0, 1f))
        columns.addView(sidebar, LinearLayout.LayoutParams(0, -1, .27f))
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), 0, dp(8)) }
        contentScroll = ScrollView(this).apply { isFillViewport = true; addView(content) }
        columns.addView(contentScroll, LinearLayout.LayoutParams(0, -1, .73f))
        root.addView(columns, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        selectCategory(selectedCategory)
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        rows[selectedCategory]?.requestFocus()
    }

    private fun selectCategory(category: String) {
        if (category == selectedCategory && content.childCount > 0) return
        selectedCategory = category
        rows.forEach { (key, row) -> row.isSelected = key == category }
        content.removeAllViews()
        contentScroll.scrollTo(0, 0)
        firstContent = null
        content.addView(label(title(category), 28))
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(GlassTokens.surface); cornerRadius = dp(12).toFloat() }
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        content.addView(page, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24) })
        fun helper(text: String) { page.addView(label(text, 18, secondary = true)) }
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun action(text: String, destination: () -> Intent) {
            val button = Button(this).apply {
                this.text = text
                isAllCaps = false
                TvTypography.apply(this, 18)
                setOnClickListener { startActivity(destination()) }
            }
            actions.addView(button, LinearLayout.LayoutParams(0, -2, 1f).apply {
                if (actions.childCount > 0) marginStart = dp(16)
                topMargin = dp(24)
            })
            if (firstContent == null) firstContent = button
        }
        when (category) {
            CATEGORY_PLAYLISTS -> {
                helper("Choose the active playlist. Switching does not start playback.")
                action("Manage playlists") { Intent(this, PlaylistsActivity::class.java) }
                action("Add playlist") { Intent(this, PlaylistActivity::class.java) }
                action("All playlists") { Intent(this, AllPlaylistsActivity::class.java) }
            }
            CATEGORY_EPG -> {
                helper("Import XMLTV, manage optional automatic refresh, or browse the saved schedule and channel mappings.")
                action("Import or refresh guide") { Intent(this, EpgActivity::class.java) }
                action("Browse schedule and mappings") { Intent(this, EpgBrowserActivity::class.java) }
            }
            CATEGORY_RECORDINGS, CATEGORY_MULTIVIEW -> {
                val playlistId = intent.getStringExtra(EXTRA_PLAYLIST_ID)
                val channelId = intent.getIntExtra(EXTRA_CHANNEL_ID, -1)
                helper(if (category == CATEGORY_RECORDINGS)
                    "Manage saved recordings and review recording limits before starting."
                    else "Choose two channels and review provider connection limits before starting streams.")
                if (playlistId.isNullOrBlank() || channelId < 0) {
                    helper("Select a channel, then reopen Settings from the player to use ${title(category).lowercase()}.")
                } else {
                    val mode = if (category == CATEGORY_RECORDINGS) AdvancedPlaybackActivity.MODE_RECORDINGS
                        else AdvancedPlaybackActivity.MODE_MULTIVIEW
                    action(if (category == CATEGORY_RECORDINGS) "Open recordings" else "Start multiview") {
                        AdvancedPlaybackActivity.intent(this, mode, playlistId, channelId)
                    }
                }
            }
            CATEGORY_ABOUT -> {
                @Suppress("DEPRECATION")
                val info = packageManager.getPackageInfo(packageName, 0)
                @Suppress("DEPRECATION")
                val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
                helper("Version ${info.versionName.orEmpty()} (build $code)")
                helper("Synthetic fixtures are invented; private playlist data stays encrypted on this device.")
                helper("Media3 (Apache 2.0)")
            }
        }
        if (actions.childCount > 0) page.addView(actions)
        if (firstContent == null) {
            page.isFocusable = true
            page.background = GlassMaterial.focusable(this, quietFill = false).let { focus ->
                StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_focused), focus)
                    addState(intArrayOf(), GradientDrawable().apply {
                        setColor(GlassTokens.surface); cornerRadius = dp(12).toFloat()
                    })
                }
            }
            // Keep supporting text readable when this informational row receives the lens.
            page.setOnFocusChangeListener { _, focused ->
                for (index in 0 until page.childCount) (page.getChildAt(index) as? TextView)
                    ?.setTextColor(if (focused) GlassTokens.lensText else GlassTokens.textSecondary)
            }
            firstContent = page
        }
    }

    private fun label(value: String, size: Int, secondary: Boolean = false) = TextView(this).apply {
        text = value
        TvTypography.apply(this, size)
        setTextColor(if (secondary) GlassTokens.textSecondary else GlassTokens.textPrimary)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_ESCAPE) {
            if (event.action == KeyEvent.ACTION_UP) onBackPressed()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    @Deprecated("Platform Back handler for API 23+ TV")
    override fun onBackPressed() {
        if (rows.values.any { it.hasFocus() }) finish()
        else rows[selectedCategory]?.requestFocus()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_CATEGORY, selectedCategory)
        super.onSaveInstanceState(outState)
    }

    private class SettingsBackdrop(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var upper: Shader? = null
        private var lower: Shader? = null

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            val radius = maxOf(w, h).toFloat().coerceAtLeast(1f) * .7f
            upper = RadialGradient(w.toFloat(), 0f, radius, 0x661E2A36, 0x001E2A36, Shader.TileMode.CLAMP)
            lower = RadialGradient(0f, h.toFloat(), radius, 0x661E2A36, 0x001E2A36, Shader.TileMode.CLAMP)
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(GlassTokens.canvas)
            paint.shader = upper
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            paint.shader = lower
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        }
    }
}

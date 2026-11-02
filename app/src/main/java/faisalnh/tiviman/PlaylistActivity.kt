package faisalnh.tiviman

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.WindowManager
import android.widget.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Never persists the source URL or puts credentials in logs/Intents/saved view state. */
class PlaylistActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var address: EditText
    private lateinit var status: TextView
    private lateinit var submit: Button
    private lateinit var source: Spinner
    private lateinit var username: EditText
    private lateinit var password: EditText
    private lateinit var mac: EditText
    private lateinit var playlistName: EditText
    private val client = ProviderHttp()
    @Volatile private var connection: HttpURLConnection? = null
    private val commitLock = Any()
    @Volatile private var canceled = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CapturePolicy.secure(window)
        val pad = (24 * resources.displayMetrics.density).toInt()
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(GlassTokens.canvas); setPadding(pad, pad, pad, pad) }
        fun note(value: String) = TextView(this).apply { text = value; textSize = 17f; setTextColor(GlassTokens.textSecondary); column.addView(this) }
        column.addView(GlassPage.header(this, "Playlist type • Live TV"))
        note("M3U, Xtream Codes or Stalker Portal. Encrypted local storage. Use only an authorized subscription/device identity.")
        fun labeledEditor(parent: LinearLayout, editor: EditText, title: String): TextView {
            editor.id = android.view.View.generateViewId()
            val label = TextView(this).apply {
                text = title; labelFor = editor.id
                textSize = 17f; setTextColor(GlassTokens.textPrimary)
                isFocusable = false; isClickable = false
            }
            parent.addView(label)
            parent.addView(editor)
            return label
        }
        playlistName = EditText(this).apply { hint = "Playlist name"; isSingleLine = true }
        labeledEditor(column, playlistName, "Playlist name")
        val providerPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = android.view.View.GONE }
        source = Spinner(this).apply {
            adapter = ArrayAdapter(this@PlaylistActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("M3U playlist", "Xtream Codes", "Stalker Portal"))
            contentDescription = "Playlist type"
        }
        column.addView(source)
        val next = Button(this).apply { text = "Next" }
        column.addView(next)
        column.addView(providerPanel)
        // Plain text, but still kept out of saved view state and keyboard learning: URLs often embed a login.
        fun plainEditor(hint: String, type: Int) = EditText(this).apply {
            this.hint = hint; isSingleLine = true; isSaveEnabled = false
            inputType = InputType.TYPE_CLASS_TEXT or type
            imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        }
        address = plainEditor("http://… or https://…", InputType.TYPE_TEXT_VARIATION_URI)
        val addressLabel = labeledEditor(providerPanel, address, "M3U playlist URL")
        fun field(editor: EditText, label: String): EditText {
            val group = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            labeledEditor(group, editor, label)
            providerPanel.addView(group)
            return editor
        }
        username = field(plainEditor("Xtream username", InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS), "Xtream username")
        password = field(plainEditor("Xtream password", InputType.TYPE_TEXT_VARIATION_PASSWORD).apply {
            transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
        }, "Xtream password")
        passwordRevealRow(password)
        mac = field(plainEditor("00:1A:79:…", InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS), "Authorized Stalker MAC (00:1A:79:…)")
        source.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                (username.parent as android.view.View).visibility = if (position == 1) android.view.View.VISIBLE else android.view.View.GONE
                (password.parent as android.view.View).visibility = (username.parent as android.view.View).visibility
                (mac.parent as android.view.View).visibility = if (position == 2) android.view.View.VISIBLE else android.view.View.GONE
                addressLabel.text = when (position) {
                    0 -> "M3U playlist URL"
                    1 -> "Xtream server URL"
                    else -> "Stalker portal URL"
                }
                address.hint = when (position) { 0 -> "http://… or https://…"; 1 -> "http://host:port"; else -> "http://host/stalker_portal/c/" }
            }
        }
        next.setOnClickListener {
            providerPanel.visibility = android.view.View.VISIBLE
            source.isEnabled = false; next.visibility = android.view.View.GONE
            address.requestFocus()
        }

        providerPanel.addView(TextView(this).apply { text = "Include TV channels: Yes • VOD is not implemented" })
        status = TextView(this).apply { text = "Adds a separate playlist. Existing playlists and their EPG/settings are kept." }
        providerPanel.addView(status)
        submit = Button(this).apply { text = "Import playlist"; setOnClickListener { importPlaylist() } }
        providerPanel.addView(submit)
        column.addView(Button(this).apply { text = "Cancel"; setOnClickListener { cancelImport(); finish() } })
        setContentView(ScrollView(this).apply { setBackgroundColor(GlassTokens.canvas); addView(column) })
        source.requestFocus()
    }
    private fun importPlaylist() {
        val url = try { Playlist.url(address.text.toString()) } catch (_: Exception) {
            status.text = "Enter a URL that starts with http:// or https://."; return
        }
        val label = playlistName.text.toString().trim().ifBlank { "${SourceType.entries[source.selectedItemPosition].name} playlist" }.take(80)
        val config = ProviderConfig(SourceType.entries[source.selectedItemPosition], url,
            username.text.toString(), password.text.toString(), mac.text.toString().trim())
        if (config.type == SourceType.XTREAM && (config.user.isBlank() || config.password.isEmpty())) {
            status.text = "Enter the Xtream username and password."; return
        }
        if (config.type == SourceType.STALKER && !config.mac.matches(Regex("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}"))) {
            status.text = "Enter your authorized MAC as six hexadecimal pairs separated by colons."; return
        }
        username.text.clear(); password.text.clear(); mac.text.clear()
        address.text.clear(); submit.isEnabled = false; status.text = "Downloading and checking playlist…"
        worker.execute {
            try {
                if (config.type != SourceType.M3U) {
                    val channels = Providers(config, client).import()
                    synchronized(commitLock) {
                        if (canceled) throw InterruptedException()
                        PlaylistStore(this).add(label, null, config = config, channels = channels)
                    }
                    complete(channels.size)
                    return@execute
                }
                var next = url
                var body: String? = null
                for (redirect in 0..5) {
                    val request = URL(next).openConnection() as HttpURLConnection
                    connection = request
                    request.instanceFollowRedirects = false
                    request.connectTimeout = 15000; request.readTimeout = 15000
                    try {
                        val code = request.responseCode
                        if (code in listOf(301, 302, 303, 307, 308)) {
                            require(redirect < 5)
                            val location = request.getHeaderField("Location") ?: error("redirect")
                            next = Playlist.redirect(next, location)
                        } else {
                            require(code == 200)
                            require((request.getHeaderField("Content-Length")?.toLongOrNull() ?: -1) <= Playlist.MAX_BYTES)
                            val data = request.inputStream.use { input ->
                                val out = java.io.ByteArrayOutputStream()
                                val buffer = ByteArray(8192)
                                while (true) {
                                    if (Thread.currentThread().isInterrupted) throw InterruptedException()
                                    val count = input.read(buffer); if (count < 0) break
                                    require(out.size() + count <= Playlist.MAX_BYTES)
                                    out.write(buffer, 0, count)
                                }
                                out.toString("UTF-8")
                            }
                            body = data; break
                        }
                    } finally { request.disconnect(); connection = null }
                }
                val text = body ?: error("download")
                synchronized(commitLock) {
                    if (canceled || Thread.currentThread().isInterrupted) throw InterruptedException()
                    PlaylistStore(this).add(label, text)
                }
                complete(Playlist.parse(text).size)
            } catch (_: Exception) {
                runOnUiThread { if (!isDestroyed) {
                    status.text = "Import failed. Check address, credentials/device authorization, network and provider compatibility. Existing data is unchanged."
                    submit.isEnabled = true
                } }
            }
        }
    }
    /** Adds a Show/Hide toggle under [field]. The field returns to hidden whenever it loses focus. */
    private fun passwordRevealRow(field: EditText) {
        val hidden = android.text.method.PasswordTransformationMethod.getInstance()
        val iconSize = (24 * resources.displayMetrics.density).toInt()
        val toggle = Button(this).apply {
            setTextColor(GlassMaterial.focusText())
            compoundDrawablePadding = (12 * resources.displayMetrics.density).toInt()
        }
        // The icon shows the action the button takes: open eye reveals, slashed eye hides.
        val showIcon = PlayerIconDrawable(this, "eye").apply { setBounds(0, 0, iconSize, iconSize) }
        val hideIcon = PlayerIconDrawable(this, "eye off").apply { setBounds(0, 0, iconSize, iconSize) }
        fun render(shown: Boolean) {
            val cursor = field.selectionEnd.coerceAtLeast(0)
            field.transformationMethod = if (shown) null else hidden
            field.setSelection(cursor.coerceAtMost(field.text.length))
            toggle.text = if (shown) "Hide password" else "Show password"
            toggle.contentDescription = toggle.text
            toggle.setCompoundDrawablesRelative(if (shown) hideIcon else showIcon, null, null, null)
        }
        toggle.setOnClickListener { render(field.transformationMethod != null) }
        // Focus moves after this callback, so check on the next frame whether it left both the field and the toggle.
        val hideWhenLeft = android.view.View.OnFocusChangeListener { view, focused ->
            if (!focused) view.post { if (!field.isFocused && !toggle.isFocused) render(false) }
        }
        field.onFocusChangeListener = hideWhenLeft
        toggle.onFocusChangeListener = hideWhenLeft
        render(false)
        (field.parent as LinearLayout).addView(toggle, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }
    private fun complete(count: Int) {
        runOnUiThread { if (!isDestroyed) {
            status.text = "Playlist is processed\nChannels: $count\nSaved encrypted on this device."
            submit.text = "Done"; submit.isEnabled = true
            submit.setOnClickListener { setResult(RESULT_OK); finish() }
            submit.requestFocus()
        } }
    }
    override fun onDestroy() {
        cancelImport(); worker.shutdownNow(); super.onDestroy()
    }
    private fun cancelImport() {
        synchronized(commitLock) { canceled = true }
        connection?.disconnect()
        client.cancel()
    }
    @Deprecated("Platform Back handler for API 23+ TV")
    override fun onBackPressed() { cancelImport(); super.onBackPressed() }
}

package faisalnh.tiviman

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder

enum class SourceType { M3U, XTREAM, STALKER }

/** Never stringify this object: it contains credentials. A stored "http" flag from older versions is ignored. */
class ProviderConfig(val type: SourceType, val address: String, val user: String = "",
    val password: String = "", val mac: String = "") {
    fun json(): JSONObject = JSONObject().put("type", type.name).put("address", address)
        .put("user", user).put("password", password).put("mac", mac)
    companion object {
        fun from(data: JSONObject) = ProviderConfig(SourceType.valueOf(data.getString("type")), data.getString("address"),
            data.optString("user"), data.optString("password"), data.optString("mac"))
    }
}

/** Bounded requests. Authenticated API requests never follow redirects to another origin. */
class ProviderHttp(private val diagnostic: ((String, Int) -> Unit)? = null) {
    @Volatile private var active: HttpURLConnection? = null
    @Volatile private var canceled = false
    fun cancel() { canceled = true; active?.disconnect() }
    fun get(address: String, headers: Map<String, String> = emptyMap()): String {
        return read(address, headers) { input -> input.readBytes().toString(Charsets.UTF_8) }
    }
    fun epg(address: String, catalog: List<Channel>, mappings: Map<Int, String> = emptyMap()): EpgData =
        read(address, emptyMap()) { raw ->
            val buffered = java.io.BufferedInputStream(raw)
            buffered.mark(2)
            val gzip = buffered.read() == 0x1f && buffered.read() == 0x8b
            buffered.reset()
            val xml = if (gzip) java.util.zip.GZIPInputStream(buffered) else buffered
            xml.use { Epg.parseStream(it, catalog, mappings = mappings) }
        }
    private fun <T> read(address: String, headers: Map<String, String>, consume: (java.io.InputStream) -> T): T {
        var next = Playlist.url(address)
        val origin = URI(next)
        for (redirect in 0..5) {
            check(!canceled && !Thread.currentThread().isInterrupted)
            val connection = URL(next).openConnection() as HttpURLConnection
            active = connection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15000; connection.readTimeout = 15000
            headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
            try {
                val code = connection.responseCode
                diagnostic?.invoke("http_status", code)
                if (code in listOf(301, 302, 303, 307, 308)) {
                    require(redirect < 5)
                    next = Playlist.redirect(next, connection.getHeaderField("Location") ?: error("redirect"))
                    val target = URI(next)
                    // Even query-based credentials must not be replayed across origins.
                    val publicGithub = origin.scheme == "https" && origin.host == "github.com" && origin.rawQuery == null &&
                        target.scheme == "https" && target.host == "raw.githubusercontent.com" && target.rawQuery == null && headers.isEmpty()
                    require(publicGithub || (target.scheme == origin.scheme && target.host == origin.host && target.port == origin.port))
                    continue
                }
                require(code == 200)
                connection.inputStream.use { input ->
                    var total = 0
                    val bounded = object : java.io.FilterInputStream(input) {
                        override fun read(): Int {
                            val one = ByteArray(1)
                            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 255
                        }
                        override fun read(b: ByteArray, off: Int, len: Int): Int {
                            check(!canceled && !Thread.currentThread().isInterrupted)
                            val count = `in`.read(b, off, len)
                            if (count > 0) total += count
                            if (total > 32 * 1024 * 1024) {
                                diagnostic?.invoke("response_limit_exceeded", 1)
                                throw IllegalArgumentException("Response exceeds limit")
                            }
                            return count
                        }
                    }
                    val result = consume(bounded)
                    diagnostic?.invoke("response_bytes", total)
                    return result
                }
            } finally { connection.disconnect(); active = null }
        }
        error("request failed")
    }
}

class Providers(private val config: ProviderConfig, private val http: ProviderHttp = ProviderHttp()) {
    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    private val base: String get() = Playlist.url(config.address).trimEnd('/')
    private fun channel(id: Int, name: String, group: String, stream: String) = Channel(id, name.take(160), group.take(80),
        listOf(Programme("No information", 0, 180, "EPG import is not configured.")), stream)
    fun import(): List<Channel> = when (config.type) {
        SourceType.M3U -> Playlist.parse(http.get(base))
        SourceType.XTREAM -> xtream()
        SourceType.STALKER -> stalkerChannels()
    }
    private fun xtream(): List<Channel> {
        require(URI(base).rawQuery == null) // Expect server base, not a get.php/player_api.php login URL.
        require(config.user.isNotBlank() && config.password.isNotEmpty())
        val endpoint = "$base/player_api.php?username=${enc(config.user)}&password=${enc(config.password)}"
        val auth = JSONObject(http.get(endpoint)).getJSONObject("user_info")
        require(auth.optString("auth") == "1" && auth.optString("status").equals("Active", true))
        val groups = JSONArray(http.get("$endpoint&action=get_live_categories"))
        val names = (0 until groups.length()).associate { groups.getJSONObject(it).let { g -> g.getString("category_id") to g.getString("category_name") } }
        val streams = JSONArray(http.get("$endpoint&action=get_live_streams"))
        require(streams.length() in 1..100000)
        return (0 until streams.length()).map { index ->
            val stream = streams.getJSONObject(index)
            val id = stream.getString("stream_id"); require(id.matches(Regex("[0-9]+")))
            channel(index, stream.getString("name"), names[stream.optString("category_id")] ?: "Ungrouped",
                "$base/live/${enc(config.user)}/${enc(config.password)}/$id.ts").copy(epgId = stream.optString("epg_channel_id").take(512))
        }
    }
    /** Called only after explicit EPG import; never on startup. */
    fun xmltvUrl(): String {
        require(config.type == SourceType.XTREAM && URI(base).rawQuery == null)
        return "$base/xmltv.php?username=${enc(config.user)}&password=${enc(config.password)}"
    }
    private fun portalEndpoint(): String {
        val uri = URI(base)
        require(uri.rawQuery == null)
        return when {
            base.endsWith(".php") -> base
            base.endsWith("/c") -> base.removeSuffix("/c") + "/server/load.php"
            else -> "$base/server/load.php"
        }
    }
    private fun headers(token: String = ""): Map<String, String> {
        require(config.mac.matches(Regex("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}")))
        return buildMap {
            put("Cookie", "mac=${enc(config.mac.uppercase())}; stb_lang=en; timezone=UTC")
            put("X-User-Agent", "Model: MAG250; Link: Ethernet")
            put("User-Agent", "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 MAG250 stbapp ver: 2 rev: 250 Safari/533.3")
            if (token.isNotEmpty()) { require(!token.contains('\r') && !token.contains('\n')); put("Authorization", "Bearer $token") }
        }
    }
    private fun portal(type: String, action: String, token: String = "", extras: Map<String, String> = emptyMap()): Any {
        val params = linkedMapOf("type" to type, "action" to action, "JsHttpRequest" to "1-xml").apply { putAll(extras) }
        return JSONObject(http.get(portalEndpoint() + "?" + params.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" },
            headers(token))).get("js")
    }
    private fun session(): String {
        val token = (portal("stb", "handshake") as JSONObject).getString("token"); require(token.isNotBlank())
        val profile = portal("stb", "get_profile", token) as JSONObject
        require(profile.optInt("blocked", 0) == 0)
        return token
    }
    private fun stalkerChannels(): List<Channel> {
        val token = session()
        val genres = portal("itv", "get_genres", token) as JSONArray
        val names = (0 until genres.length()).associate { genres.getJSONObject(it).let { g -> g.getString("id") to g.getString("title") } }
        val payload = portal("itv", "get_all_channels", token)
        val entries = if (payload is JSONArray) payload else (payload as JSONObject).getJSONArray("data")
        if (payload is JSONObject) require(payload.optInt("total_items", entries.length()) <= entries.length()) // Don't silently truncate.
        require(entries.length() in 1..20000)
        return (0 until entries.length()).map { index ->
            val row = entries.getJSONObject(index)
            val cmd = row.getString("cmd"); require(cmd.length <= 16384)
            channel(index, row.getString("name"), names[row.optString("tv_genre_id")] ?: "Ungrouped", "stalker:" + enc(cmd))
        }
    }
    /** Obtain a fresh session/link on tune, never treat server command text as executable. */
    fun resolve(channel: Channel): String {
        if (!channel.stream.startsWith("stalker:")) return Playlist.url(channel.stream)
        val cmd = java.net.URLDecoder.decode(channel.stream.removePrefix("stalker:"), "UTF-8")
        val token = session()
        val result = portal("itv", "create_link", token, mapOf("cmd" to cmd, "series" to "0", "forced_storage" to "undefined", "disable_ad" to "0", "download" to "0")) as JSONObject
        val link = result.getString("cmd").trim().removePrefix("ffmpeg ").trim()
        require(!link.any { it.isWhitespace() })
        return Playlist.url(link)
    }
}

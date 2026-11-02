package faisalnh.tiviman

import java.net.URI

/** Strict bounded M3U subset; never include source text or URLs in exceptions. */
object Playlist {
    const val MAX_BYTES = 8 * 1024 * 1024
    /** Accepts http and https. Most IPTV providers only serve HTTP, so it is not opt-in. */
    fun url(value: String): String {
        val uri = try { URI(value.trim()) } catch (_: Exception) { throw IllegalArgumentException("Invalid URL") }
        require(uri.scheme == "https" || uri.scheme == "http") { "Use an http or https URL" }
        require(!uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.fragment == null) { "Use a valid URL without user-info or fragment" }
        return uri.toASCIIString()
    }
    /** Resolves a redirect [location] against [current]. An https address never redirects down to http. */
    fun redirect(current: String, location: String): String {
        val next = url(java.net.URL(java.net.URL(current), location).toString())
        require(!(URI(current).scheme == "https" && URI(next).scheme == "http")) { "Redirect from https to http refused" }
        return next
    }
    const val MAX_EPG_URLS = 4
    private val epgAttr = Regex("(?i)(?<![\\w-])(?:x-tvg-url|url-tvg|tvg-url)=\"([^\"]*)\"")

    /**
     * Pure discovery of guide URLs advertised in the #EXTM3U header line. Never fetches anything.
     * Returns at most [MAX_EPG_URLS] distinct URLs accepted by [url]; invalid entries are skipped.
     * Non-M3U text, oversized input and HLS manifests yield an empty list.
     */
    fun epgUrls(text: String): List<String> {
        if (text.length > MAX_BYTES || text.toByteArray(Charsets.UTF_8).size > MAX_BYTES) return emptyList()
        val lines = text.removePrefix("\uFEFF").lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val header = lines.firstOrNull() ?: return emptyList()
        if (!header.startsWith("#EXTM3U") || header.length > 16384) return emptyList()
        if (lines.any { it.startsWith("#EXT-X-") && !it.startsWith("#EXT-X-CREDENTIALS:") }) return emptyList()
        val found = LinkedHashSet<String>()
        for (match in epgAttr.findAll(header)) {
            for (part in match.groupValues[1].split(',')) {
                val valid = try { url(part) } catch (_: IllegalArgumentException) { continue }
                found.add(valid)
                if (found.size >= MAX_EPG_URLS) return found.toList()
            }
        }
        return found.toList()
    }
    fun parse(text: String): List<Channel> {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Playlist is too large" }
        val lines = text.removePrefix("\uFEFF").lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        require(lines.firstOrNull()?.startsWith("#EXTM3U") == true) { "Expected an extended M3U playlist" }
        val channels = mutableListOf<Channel>()
        var name: String? = null
        var group = "Ungrouped"
        var epgId = ""
        var unsupported = false
        for (line in lines.drop(1)) {
            require(line.length <= 16384) { "Playlist line is too long" }
            when {
                line.startsWith("#EXTINF:") -> {
                    var quoted = false
                    val comma = line.indexOfFirst { c -> if (c == '"') quoted = !quoted; c == ',' && !quoted }
                    require(comma >= 0) { "Invalid channel metadata" }
                    name = line.substring(comma + 1).trim().take(160).ifBlank { "Channel ${channels.size + 1}" }
                    epgId = Regex("tvg-id=\"([^\"]*)\"").find(line.substring(0, comma))?.groupValues?.get(1)?.take(512).orEmpty()
                    group = Regex("group-title=\"([^\"]*)\"").find(line.substring(0, comma))?.groupValues?.get(1)
                        ?.take(80)?.ifBlank { "Ungrouped" } ?: "Ungrouped"
                    unsupported = false
                }
                // Catalog annotation only: never apply it as stream authentication or headers.
                line.startsWith("#EXT-X-CREDENTIALS:") -> Unit
                line.startsWith("#EXT-X-") -> throw IllegalArgumentException("This is an HLS stream, not a channel playlist")
                line.startsWith("#EXTVLCOPT") || line.startsWith("#KODIPROP") -> unsupported = true
                !line.startsWith("#") -> {
                    require(name != null) { "Missing channel metadata" }
                    require(!unsupported && !line.contains('|')) { "Custom stream headers/DRM options are not supported" }
                    val source = url(line)
                    require(channels.size < 20000) { "Too many channels" }
                    channels.add(Channel(channels.size, name!!, group, listOf(
                        Programme("No information", 0, 180, "EPG import is not configured.")
                    ), source, epgId))
                    name = null
                }
            }
        }
        require(channels.isNotEmpty() && name == null) { "Playlist is empty or incomplete" }
        return channels
    }
}

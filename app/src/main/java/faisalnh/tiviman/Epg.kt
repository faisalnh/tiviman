package faisalnh.tiviman

import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.Locale

import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler

data class EpgEvent(val channel: String, val title: String, val start: Int, val end: Int, val description: String)
/** [mappings] is channel id => manual XMLTV id override; optional so older data and callers are unchanged. */
data class EpgData(val events: List<EpgEvent>, val names: Map<String, Set<String>>, val importedAt: Long,
    val mappings: Map<Int, String> = emptyMap())

/** Events overlapping a browse window; [total] counts all overlapping events, [events] may be capped. */
data class EpgBrowse(val xmltvId: String?, val manual: Boolean, val events: List<EpgEvent>, val total: Int)

/** Bounded XMLTV subset. Epoch minutes are absolute; presentation uses the device timezone. */
object Epg {
    /**
     * Global retained-programme budget. When streaming, large guides keep fewer programmes per channel (nearest first)
     * instead of failing, so every matched channel keeps its current programme. Non-streaming parsing still fails above it.
     */
    const val MAX_EVENTS = 100000
    /** Upper bound on nearest programmes kept per matched XMLTV ID (within the 48 hour window) when streaming. */
    const val PER_CHANNEL_EVENTS = 48
    const val MAX_BROWSE_EVENTS = 200
    fun validMappingId(value: String): String {
        val id = value.trim()
        require(id.length in 1..512 && id.none { it.isISOControl() }) { "Invalid XMLTV ID" }
        return id
    }
    /**
     * Strictly newer than [prior] so the latest save always wins when stored guide copies are compared by importedAt
     * (embedded Xtream cache vs separate epg file), even if clocks collide or go backwards.
     */
    fun monotonic(candidate: Long, prior: Long?): Long = if (prior == null) candidate else maxOf(candidate, prior + 1)
    /** Returns a copy with the channel's manual override set, or removed when [xmltvId] is null/blank. */
    fun withMapping(data: EpgData, channelId: Int, xmltvId: String?): EpgData {
        val next = data.mappings.toMutableMap()
        if (xmltvId.isNullOrBlank()) next.remove(channelId) else next[channelId] = validMappingId(xmltvId)
        require(next.size <= 100000) { "Too many mappings" }
        return data.copy(mappings = next)
    }
    /** Manual override wins (even if it has no data); otherwise exact EPG ID, then unique display-name. */
    fun resolveId(channel: Channel, data: EpgData, mappings: Map<Int, String> = data.mappings): Pair<String?, Boolean> {
        mappings[channel.id]?.takeIf { it.isNotBlank() }?.let { return it to true }
        if (channel.epgId.isNotBlank() && data.events.any { it.channel == channel.epgId }) return channel.epgId to false
        val name = channel.name.lowercase(Locale.ROOT)
        val ids = data.names.filter { (_, values) -> values.any { it.lowercase(Locale.ROOT) == name } }.keys
        return ids.singleOrNull() to false
    }
    /** Programmes overlapping [from, from+minutes) for one channel, using stored data only (no network). */
    fun browse(channel: Channel, data: EpgData, from: Int, minutes: Int = 24 * 60, mappings: Map<Int, String> = data.mappings): EpgBrowse {
        val (id, manual) = resolveId(channel, data, mappings)
        val all = if (id == null) emptyList() else data.events.filter { it.channel == id && it.end > from && it.start.toLong() < from.toLong() + minutes }.sortedBy { it.start }
        return EpgBrowse(id, manual, all.take(MAX_BROWSE_EVENTS), all.size)
    }
    fun minute(now: Long = System.currentTimeMillis()) = (now / 60000).toInt()
    fun time(minute: Int): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(java.util.Date(minute * 60000L))
    /**
     * Parses `yyyyMMddHHmmss` with an optional ` +HHMM`/` -HHMM` offset into absolute epoch minutes.
     * Hand-rolled because large guides call this millions of times, and SimpleDateFormat/Regex per call
     * took minutes on Android TV hardware.
     */
    fun timestamp(value: String): Int {
        val invalid = "Invalid XMLTV time"
        require(value.length == 14 || (value.length == 20 && value[14] == ' ' && (value[15] == '+' || value[15] == '-'))) { invalid }
        fun digits(from: Int, to: Int): Int {
            var n = 0
            for (i in from until to) { val d = value[i] - '0'; require(d in 0..9) { invalid }; n = n * 10 + d }
            return n
        }
        val year = digits(0, 4); val month = digits(4, 6); val day = digits(6, 8)
        val hour = digits(8, 10); val minute = digits(10, 12); val second = digits(12, 14)
        val leap = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)
        val monthDays = intArrayOf(31, if (leap) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
        require(month in 1..12 && day in 1..monthDays[month - 1] && hour <= 23 && minute <= 59 && second <= 59) { invalid }
        var offset = 0
        if (value.length == 20) {
            val offsetMinutes = digits(18, 20)
            require(offsetMinutes <= 59) { invalid }
            offset = (digits(16, 18) * 60 + offsetMinutes) * if (value[15] == '-') -1 else 1
        }
        // Days since 1970-01-01 for a proleptic Gregorian date (Howard Hinnant's days_from_civil).
        val y = (if (month <= 2) year - 1 else year).toLong()
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val doy = (153 * (if (month > 2) month - 3 else month + 9) + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        val days = era * 146097 + doe - 719468
        val minutes = days * 1440 + hour * 60 + minute - offset
        require(minutes in 0..Int.MAX_VALUE.toLong()) { invalid }
        return minutes.toInt()
    }
    fun parse(text: String, now: Long = System.currentTimeMillis()): EpgData {
        require(text.toByteArray(Charsets.UTF_8).size <= 32 * 1024 * 1024) { "EPG too large" }
        require(!text.contains("<!DOCTYPE", true) && !text.contains("<!ENTITY", true)) { "XML declarations unsupported" }
        return parseSource(InputSource(StringReader(text)), now, null)
    }
    /** Scan at most 512 MiB / one million events; retain up to the nearest [PER_CHANNEL_EVENTS] per matched ID in 48 hours, lowering that per-ID limit as needed to stay within [MAX_EVENTS] total. Manual [mappings] IDs are also matched and carried into the result. */
    fun parseStream(input: java.io.InputStream, catalog: List<Channel>, now: Long = System.currentTimeMillis(),
        mappings: Map<Int, String> = emptyMap()): EpgData {
        var total = 0L
        val bounded = object : java.io.FilterInputStream(input) {
            override fun read(): Int {
                val one = ByteArray(1)
                return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 255
            }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                check(!Thread.currentThread().isInterrupted)
                val n = `in`.read(b, off, len)
                if (n > 0) total += n
                require(total <= 512L * 1024 * 1024) { "EPG too large" }
                return n
            }
        }
        return parseSource(InputSource(bounded), now, catalog, mappings)
    }
    private fun parseSource(source: InputSource, now: Long, catalog: List<Channel>?, mappings: Map<Int, String> = emptyMap()): EpgData {
        val events = mutableListOf<EpgEvent>()
        val retained = mutableMapOf<String, MutableList<EpgEvent>>()
        val wantedIds = (catalog?.map { it.epgId }.orEmpty() + mappings.values).filter { it.isNotBlank() }.toSet()
        var retainedCount = 0
        var perChannel = PER_CHANNEL_EVENTS
        val wantedNames = catalog?.map { it.name.lowercase(Locale.ROOT) }?.toSet().orEmpty()
        val nowMinute = minute(now)
        val names = mutableMapOf<String, MutableSet<String>>()
        // Cached per XMLTV ID: whether its programmes can match the catalog. Programmes of unmatched IDs are skipped
        // before their text is buffered. XMLTV lists channels before programmes, so the cache is stable once used.
        val matchesCatalog = HashMap<String, Boolean>()
        fun wanted(id: String) = matchesCatalog.getOrPut(id) {
            id in wantedIds || names[id].orEmpty().any { it.lowercase(Locale.ROOT) in wantedNames }
        }
        val handler = object : org.xml.sax.ext.DefaultHandler2() {
            override fun startDTD(name: String?, publicId: String?, systemId: String?) { throw IllegalArgumentException("XML declarations unsupported") }
            var channelId = ""; var eventId = ""; var start = 0; var stop = 0
            var title = ""; var description = ""; var capture = ""; val content = StringBuilder()
            var root = false; var count = 0; var depth = 0; var skip = false
            override fun resolveEntity(publicId: String?, systemId: String?): InputSource = throw IllegalArgumentException("External XML unsupported")
            override fun error(e: org.xml.sax.SAXParseException) { throw IllegalArgumentException("Invalid XMLTV") }
            override fun fatalError(e: org.xml.sax.SAXParseException) { throw IllegalArgumentException("Invalid XMLTV") }
            override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes) {
                require(++depth <= 32) { "XML nesting too deep" }
                if (!root) { require(qName == "tv"); root = true }
                when (qName) {
                    "channel" -> { channelId = attributes.getValue("id").orEmpty().take(512); require(names.size < 100000) }
                    "programme" -> {
                        require(++count <= if (catalog == null) MAX_EVENTS else 1000000)
                        eventId = attributes.getValue("channel").orEmpty().take(512)
                        start = timestamp(attributes.getValue("start").orEmpty())
                        stop = timestamp(attributes.getValue("stop").orEmpty())
                        if (catalog == null) require(stop > start && stop.toLong() - start <= 7 * 24 * 60)
                        title = ""; description = ""
                        skip = catalog != null && (eventId.isEmpty() || stop <= start || stop.toLong() - start > 7 * 24 * 60 ||
                            stop <= nowMinute || start > nowMinute + 48 * 60 || !wanted(eventId))
                    }
                    "title", "desc" -> if (!skip) { capture = qName; content.setLength(0) }
                    "display-name" -> { capture = qName; content.setLength(0) }
                }
            }
            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (capture.isNotEmpty()) { require(content.length + length <= 16384); content.append(ch, start, length) }
            }
            override fun endElement(uri: String?, localName: String?, qName: String) {
                depth--
                if (qName == capture) {
                    val value = content.toString().trim()
                    when (capture) {
                        "title" -> if (title.isEmpty()) title = value.take(512)
                        "desc" -> if (description.isEmpty()) description = value.take(8192)
                        "display-name" -> if (channelId.isNotEmpty() && value.isNotEmpty()) {
                            val values = names.getOrPut(channelId) { mutableSetOf() }; require(values.size < 32); values.add(value.take(512))
                        }
                    }
                    capture = ""
                }
                if (qName == "channel") channelId = ""
                if (qName == "programme") {
                    if (catalog == null) require(eventId.isNotEmpty() && title.isNotEmpty())
                    val event = EpgEvent(eventId, title, start, stop, description)
                    if (catalog == null) events.add(event)
                    else if (!skip && title.isNotEmpty()) {
                        val rows = retained.getOrPut(eventId) { mutableListOf() }
                        val full = rows.size >= perChannel
                        if ((!full || start < rows.last().start) && event !in rows) {
                            // Rows stay sorted by start: insert after equal starts, as the stable sort used to.
                            var at = rows.size
                            while (at > 0 && rows[at - 1].start > start) at--
                            rows.add(at, event)
                            if (full) rows.removeAt(rows.lastIndex) else retainedCount++
                            // Rows are sorted by start, so trimming the tail keeps each channel's nearest programmes.
                            while (retainedCount > MAX_EVENTS) {
                                require(perChannel > 1) { "EPG exceeds retained programme budget" }
                                perChannel--
                                retained.values.forEach { list ->
                                    while (list.size > perChannel) { list.removeAt(list.lastIndex); retainedCount-- }
                                }
                            }
                        }
                    }
                }
            }
        }
        val factory = SAXParserFactory.newInstance().apply { isNamespaceAware = false; isValidating = false }
        val reader = factory.newSAXParser().xmlReader
        reader.contentHandler = handler; reader.errorHandler = handler; reader.entityResolver = handler
        reader.setProperty("http://xml.org/sax/properties/lexical-handler", handler)
        reader.parse(source)
        if (catalog != null) events.addAll(retained.values.flatten())
        require(events.isNotEmpty()) { "No XMLTV programmes" }
        return EpgData(events.distinct().sortedBy { it.start }, names, now, mappings)
    }

    /** [mappings] defaults to the mappings stored in [data]; pass emptyMap() to ignore manual overrides. */
    fun project(catalog: List<Channel>, data: EpgData, now: Int, mappings: Map<Int, String> = data.mappings): List<Channel> {
        val byId = data.events.groupBy { it.channel }
        val aliases = mutableMapOf<String, MutableSet<String>>()
        data.names.forEach { (id, names) -> names.forEach { aliases.getOrPut(it.lowercase(Locale.ROOT)) { mutableSetOf() }.add(id) } }
        return catalog.map { channel ->
            val id = mappings[channel.id]?.takeIf { it.isNotBlank() }
                ?: channel.epgId.takeIf { it.isNotBlank() && byId.containsKey(it) }
                ?: aliases[channel.name.lowercase(Locale.ROOT)]?.singleOrNull()
            // Sort here: stored/merged data is not guaranteed to be ordered by start.
            val upcoming = byId[id].orEmpty().filter { it.end > now }.sortedWith(compareBy({ it.start }, { it.end })).take(4)
            val rows = mutableListOf<Programme>()
            var cursor = now
            upcoming.forEach { event ->
                if (event.start >= cursor) {
                    if (event.start > cursor) rows.add(Programme("No information", cursor, event.start - cursor, "No programme supplied for this time."))
                    rows.add(Programme(event.title, event.start, event.end - event.start, event.description)); cursor = event.end
                } else if (event.start <= now && event.end > cursor && rows.isEmpty()) {
                    rows.add(Programme(event.title, event.start, event.end - event.start, event.description)); cursor = event.end
                }
            }
            if (rows.isEmpty()) rows.add(Programme("No information", now, 180, "No matching current or upcoming XMLTV programme."))
            channel.copy(programmes = rows.take(4))
        }
    }
}

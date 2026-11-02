package faisalnh.tiviman

import org.junit.Test
import org.junit.Assert.*

class EpgStreamTest {
    @Test fun ignoresCatalogCredentialAnnotationButRejectsHls() {
        val list = "#EXTM3U\n#EXT-X-CREDENTIALS:ignored\n#EXTINF:-1,Example\nhttps://example.invalid/live"
        assertEquals(1, Playlist.parse(list).size)
        try { Playlist.parse(list.replace("#EXT-X-CREDENTIALS:ignored", "#EXT-X-TARGETDURATION:10")); fail("HLS accepted") }
        catch (_: IllegalArgumentException) { }
    }
    private val now = Epg.timestamp("20261029120000 +0000") * 60000L
    private val catalog = listOf(Channel(0, "Example", "Test", emptyList(), "https://example.invalid/live", "one"))
    @Test fun filtersAndBoundsNearestEvents() {
        val xml = "<tv><channel id='one'><display-name>Example</display-name></channel>" +
            (20 downTo 0).joinToString("") { i ->
                val time = java.text.SimpleDateFormat("yyyyMMddHHmmss Z").apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                "<programme channel='one' start='${time.format(java.util.Date(now + i * 3600000L))}' stop='${time.format(java.util.Date(now + (i+1) * 3600000L))}'><title>Show $i</title></programme>"
            } + "<programme channel='other' start='20261029120000 +0000' stop='20261029130000 +0000'><title>Other</title></programme></tv>"
        val result = Epg.parseStream(xml.byteInputStream(), catalog, now)
        assertEquals(21, result.events.size)
        assertEquals("Show 0", result.events.first().title)
        assertTrue(result.events.all { it.channel == "one" })
    }
    @Test fun largeGuideShrinksPerChannelDepthInsteadOfFailing() {
        val channels = 2500
        val wide = (0 until channels).map { Channel(it, "C$it", "Test", emptyList(), "https://example.invalid/$it", "c$it") }
        val time = java.text.SimpleDateFormat("yyyyMMddHHmmss Z").apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        val xml = StringBuilder("<tv>")
        // Later hours first, so trimming must keep each channel's nearest programmes regardless of input order.
        for (hour in 47 downTo 0) for (c in 0 until channels) xml.append("<programme channel='c$c' start='")
            .append(time.format(java.util.Date(now + hour * 3600000L))).append("' stop='")
            .append(time.format(java.util.Date(now + (hour + 1) * 3600000L))).append("'><title>S$hour</title></programme>")
        val result = Epg.parseStream(xml.append("</tv>").toString().byteInputStream(), wide, now)
        assertTrue(result.events.size <= Epg.MAX_EVENTS)
        val byChannel = result.events.groupBy { it.channel }
        assertEquals(channels, byChannel.size)
        assertTrue(byChannel.values.all { rows -> rows.minOf { it.start } == Epg.minute(now) })
    }
    @Test fun fastTimestampMatchesStrictCalendarParsing() {
        val strict = java.text.SimpleDateFormat("yyyyMMddHHmmss Z", java.util.Locale.ROOT).apply {
            isLenient = false; timeZone = java.util.TimeZone.getTimeZone("UTC")
        }
        val random = java.util.Random(7)
        repeat(20000) {
            // 1971..2096 keeps local years four digits and minutes within Int for every offset.
            val millis = 31_536_000_000L + (random.nextDouble() * 3_950_000_000_000L).toLong()
            // SimpleDateFormat's lenient-off Z parsing rejects offsets beyond 12 hours, so the reference stays inside +-12:45.
            val offset = ((random.nextInt(25) - 12) * 60).let { it + (if (it < 0) -1 else 1) * listOf(0, 30, 45)[random.nextInt(3)] }
            val zone = java.util.SimpleTimeZone(offset * 60000, "x")
            val text = java.text.SimpleDateFormat("yyyyMMddHHmmss Z", java.util.Locale.ROOT).apply { timeZone = zone }.format(java.util.Date(millis))
            assertEquals(text, (strict.parse(text)!!.time / 60000).toInt(), Epg.timestamp(text))
        }
        assertEquals(Epg.timestamp("20240229235959 +0000"), Epg.timestamp("20240229235959"))
        assertEquals(0, Epg.timestamp("19700101000000"))
        listOf("20230229000000", "20261301000000", "20261032000000", "20261029240000", "20261029126000",
            "20261029120060", "20261029120000 +0060", "20261029120000+0000", "2026102912000", "20261029120000 0000",
            "2026102912000a", "19691231235959", "").forEach {
            try { Epg.timestamp(it); fail("Accepted $it") } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun rejectsDoctypeInStream() {
        try {
            Epg.parseStream("<!DOCTYPE tv [<!ENTITY x 'bad'>]><tv/>".byteInputStream(), catalog, now)
            fail("DTD accepted")
        } catch (_: IllegalArgumentException) { }
    }
    @Test fun skipsInvalidProgrammeDurationWithoutDroppingValidListings() {
        val xml = "<tv><programme channel='one' start='20261029120000 +0000' stop='20261029120000 +0000'><title>Invalid</title></programme>" +
            "<programme channel='one' start='20261029120000 +0000' stop='20261029130000 +0000'><title>Valid</title></programme></tv>"
        assertEquals("Valid", Epg.parseStream(xml.byteInputStream(), catalog, now).events.single().title)
    }
}

package faisalnh.tiviman

import org.junit.Assert.*
import org.junit.Test

class EpgBrowseTest {
    private val now = Epg.timestamp("20261029120000 +0000")
    private fun channel(id: Int = 1, name: String = "Example", epgId: String = "") =
        Channel(id, name, "G", emptyList(), "https://example.invalid/live", epgId)
    private fun event(id: String, offset: Int, title: String = "T$offset") = EpgEvent(id, title, now + offset, now + offset + 30, "")
    private val data = EpgData((0 until 60).map { event("x", it * 30) } + event("y", 0, "Other"),
        mapOf("x" to setOf("Example"), "y" to setOf("Other")), 1L)

    @Test fun mappingOverridesIdAndNameAndDefaultIsUnchanged() {
        val c = channel(epgId = "x")
        assertEquals("T0", Epg.project(listOf(c), data, now).single().programmes.first().title)
        val mapped = Epg.withMapping(data, 1, " y ")
        assertEquals("Other", Epg.project(listOf(c), mapped, now).single().programmes.first().title)
        assertEquals("T0", Epg.project(listOf(c), mapped, now, emptyMap()).single().programmes.first().title)
        assertEquals(emptyMap<Int, String>(), data.mappings)
    }
    @Test fun clearRemovesMappingAndInvalidIdRejected() {
        val mapped = Epg.withMapping(data, 1, "y")
        assertTrue(Epg.withMapping(mapped, 1, null).mappings.isEmpty())
        assertTrue(Epg.withMapping(mapped, 1, "  ").mappings.isEmpty())
        try { Epg.withMapping(data, 1, "bad\nid"); fail("accepted") } catch (_: IllegalArgumentException) { }
    }
    @Test fun browseCoversFullDayAndReportsTotals() {
        val view = Epg.browse(channel(name = "Example"), data, now)
        assertEquals("x", view.xmltvId); assertFalse(view.manual)
        assertEquals(48, view.events.size); assertEquals(48, view.total)
        assertTrue(view.events.all { it.channel == "x" })
        val manual = Epg.browse(channel(), Epg.withMapping(data, 1, "y"), now)
        assertTrue(manual.manual); assertEquals(listOf("Other"), manual.events.map { it.title })
    }
    @Test fun unknownMappingIdIsAllowedKeepsEventsAndShowsNoInformation() {
        val mapped = Epg.withMapping(data, 1, "not-in-guide")
        assertEquals(data.events, mapped.events)
        assertEquals("not-in-guide", mapped.mappings[1])
        val view = Epg.browse(channel(epgId = "x"), mapped, now)
        assertTrue(view.manual); assertEquals("not-in-guide", view.xmltvId); assertEquals(0, view.total)
        assertEquals("No information", Epg.project(listOf(channel(epgId = "x")), mapped, now).single().programmes.first().title)
    }
    @Test fun monotonicImportedAtAlwaysWins() {
        assertEquals(5L, Epg.monotonic(5, null))
        assertEquals(10L, Epg.monotonic(10, 7))
        assertEquals(8L, Epg.monotonic(3, 7))
        assertEquals(8L, Epg.monotonic(7, 7))
    }
    @Test fun projectSortsUnorderedStoredEvents() {
        val shuffled = data.copy(events = data.events.filter { it.channel == "x" }.reversed())
        val rows = Epg.project(listOf(channel(epgId = "x")), shuffled, now).single().programmes
        assertEquals("T0", rows.first().title); assertEquals("T30", rows[1].title)
    }
    @Test fun browseCapIsReportedNotHidden() {
        val many = EpgData((0 until 300).map { EpgEvent("x", "S$it", now + it, now + it + 1, "") }, emptyMap(), 1L)
        val view = Epg.browse(channel(epgId = "x"), many, now, 24 * 60)
        assertEquals(200, view.events.size); assertEquals(300, view.total)
    }
    @Test fun streamRetainsFullDayPerChannelAndCarriesMappings() {
        val fmt = java.text.SimpleDateFormat("yyyyMMddHHmmss Z").apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        fun p(id: String, i: Int) = "<programme channel='$id' start='${fmt.format(java.util.Date((now + i * 30L) * 60000))}' " +
            "stop='${fmt.format(java.util.Date((now + i * 30L + 30) * 60000))}'><title>S$i</title></programme>"
        val xml = "<tv>" + (0 until 60).joinToString("") { p("manual", it) } + "</tv>"
        val result = Epg.parseStream(xml.byteInputStream(), listOf(channel()), now * 60000L, mapOf(1 to "manual"))
        assertEquals(48, result.events.size)
        assertEquals(mapOf(1 to "manual"), result.mappings)
        assertEquals("S0", result.events.first().title)
    }
}

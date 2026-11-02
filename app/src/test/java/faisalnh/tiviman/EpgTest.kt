package faisalnh.tiviman

import org.junit.Assert.*
import org.junit.Test

class EpgTest {
    private val xml = """<tv><channel id="id"><display-name>Example</display-name></channel>
        <programme channel="id" start="20261029120000 +0000" stop="20261029130000 +0000"><title>Current &amp; live</title><desc>Description</desc></programme>
        <programme channel="id" start="20261029130000 +0000" stop="20261029140000 +0000"><title>Next</title></programme></tv>"""
    private val now = Epg.timestamp("20261029123000 +0000")
    private fun channel(name: String = "Example", epgId: String = "") = Channel(0, name, "Group",
        listOf(Programme("No information", 0, 180, "")), "https://example.invalid/live", epgId)

    @Test fun parsesOffsetsEntitiesAndAbsoluteTimes() {
        assertEquals(now, Epg.timestamp("20261029143000 +0200"))
        assertEquals(now, Epg.timestamp("20261029123000"))
        val data = Epg.parse(xml, 123)
        assertEquals(123L, data.importedAt)
        assertEquals("Current & live", data.events.first().title)
        assertEquals("Description", data.events.first().description)
        assertEquals(setOf("Example"), data.names["id"])
    }
    @Test fun exactIdThenUniqueNameWithoutFuzzyGuessing() {
        val data = Epg.parse(xml)
        assertEquals("Current & live", Epg.project(listOf(channel("Other", "id")), data, now).single().programmes.first().title)
        assertEquals("Current & live", Epg.project(listOf(channel("EXAMPLE")), data, now).single().programmes.first().title)
        assertEquals("No information", Epg.project(listOf(channel("Example HD")), data, now).single().programmes.first().title)
        val ambiguous = data.copy(names = data.names + ("other" to setOf("Example")))
        assertEquals("No information", Epg.project(listOf(channel()), ambiguous, now).single().programmes.first().title)
    }
    @Test fun rolloverGapAndExpiryNeverPresentFutureAsCurrent() {
        val data = Epg.parse(xml)
        assertEquals("Next", Epg.project(listOf(channel()), data, now + 30).single().programmes.first().title)
        assertEquals("No information", Epg.project(listOf(channel()), data, now + 100).single().programmes.first().title)
        val gap = Epg.project(listOf(channel()), data.copy(events = data.events.drop(1)), now).single().programmes
        assertEquals("No information", gap.first().title)
        assertEquals("Next", gap[1].title)
        assertEquals(now, gap.first().start)
        assertEquals(gap[1].start, gap.first().end)
    }
    @Test fun malformedAndExternalXmlRejected() {
        listOf("<!DOCTYPE tv SYSTEM 'https://example.invalid/private'><tv/>",
            "<tv/>", "<wrong/>", xml.replace("20261029130000 +0000", "20261029110000 +0000"),
            xml.replace("20261029120000 +0000", "20261329120000 +0000")).forEach {
            try { Epg.parse(it); fail("Accepted invalid XMLTV") } catch (_: Exception) { }
        }
    }
    @Test fun retainsM3uIdentifiersAndStableChannelFields() {
        val parsed = Playlist.parse("#EXTM3U\n#EXTINF:-1 tvg-id=\"id\",Example\nhttps://example.invalid/live").single()
        assertEquals("id", parsed.epgId)
        val projected = Epg.project(listOf(parsed), Epg.parse(xml), now).single()
        assertEquals(parsed.copy(programmes = projected.programmes), projected)
        val state = GuideState(catalog = listOf(projected), realEpg = true, nowMinute = now)
        assertEquals(0, state.move(RemoteKey.CENTER).tunedId)
    }
    @Test fun overlapIsDeterministicAndGuideFocusOnlyNeverTunesFuture() {
        val data = Epg.parse(xml)
        val overlap = data.copy(events = data.events + data.events.first().copy(title = "Overlap", start = now - 5))
        val rows = Epg.project(listOf(channel()), overlap, now).single()
        assertEquals(listOf("Current & live", "Next"), rows.programmes.map { it.title })
        val initial = GuideState(catalog = listOf(rows), realEpg = true, nowMinute = now)
        val future = initial.move(RemoteKey.RIGHT)
        assertEquals(1, future.programmeIndex)
        assertEquals(future, future.move(RemoteKey.CENTER))
    }
}

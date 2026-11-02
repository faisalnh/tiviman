package faisalnh.tiviman

import org.junit.Assert.*
import org.junit.Test

class PlaylistTest {
    @Test fun largeCatalogKeepsGroupAndChannelNavigationCorrect() {
        val catalog = (0 until 40000).map { Channel(it, "Synthetic $it", "Group ${it % 900}",
            listOf(Programme("No information", 0, 180, "Synthetic"))) }
        val state = GuideState(catalog = catalog)
        assertEquals(902, state.groups.size)
        assertEquals(40000, state.channels.size)
        val focused = state.copy(channelId = 39999)
        assertEquals(39999, focused.channel.id)
        assertEquals(39999, focused.move(RemoteKey.DOWN).channelId)
        val filtered = state.copy(group = 1)
        assertEquals(45, filtered.channels.size)
        assertEquals(900, filtered.move(RemoteKey.DOWN).channelId)
    }
    @Test fun parsesBomGroupsQuotedCommasAndDuplicateNames() {
        val channels = Playlist.parse("\uFEFF#EXTM3U\n#EXTINF:-1 group-title=\"Art, music\",One\nhttps://example.invalid/a?token=synthetic\n#EXTINF:-1,One\nhttps://example.invalid/b")
        assertEquals(2, channels.size)
        assertEquals("Art, music", channels[0].group)
        assertEquals("Ungrouped", channels[1].group)
        assertEquals(listOf("All channels", "Art, music", "Ungrouped", "Favorites"), GuideState(catalog = channels).groups)
    }
    @Test fun rejectsUnsupportedAndMalformedInputsWithoutEchoingPrivateData() {
        listOf("not m3u", "#EXTM3U\n#EXTINF:-1,Private\nftp://example.invalid/secret",
            "#EXTM3U\n#EXT-X-TARGETDURATION:6", "#EXTM3U\n#EXTINF:-1,Private",
            "#EXTM3U\n#EXTINF:-1,Private\n#EXTVLCOPT:http-user-agent=secret\nhttps://example.invalid/secret").forEach {
            try { Playlist.parse(it); fail("Accepted invalid playlist") }
            catch (error: IllegalArgumentException) { assertFalse(error.message.orEmpty().contains("secret")) }
        }
    }
    @Test fun acceptsHttpAndHttpsButRejectsUserInfoAndOtherSchemes() {
        assertEquals("http://example.invalid/a", Playlist.url("http://example.invalid/a"))
        assertEquals("https://example.invalid/a", Playlist.url(" https://example.invalid/a "))
        listOf("https://user:secret@example.invalid/a", "ftp://example.invalid/a", "file:///etc/hosts",
            "https://example.invalid/a#f", "example.invalid/a", "not a url").forEach {
            try { Playlist.url(it); fail("Accepted $it") } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun httpPlaylistWithHttpStreamsParsesWithoutOptIn() {
        val channels = Playlist.parse("#EXTM3U\n#EXTINF:-1,One\nhttp://example.invalid/1.ts\n#EXTINF:-1,Two\nhttps://example.invalid/2.ts")
        assertEquals(listOf("http://example.invalid/1.ts", "https://example.invalid/2.ts"), channels.map { it.stream })
    }
    @Test fun redirectsResolveRelativeAndNeverDowngradeHttps() {
        assertEquals("https://example.invalid/b/c", Playlist.redirect("https://example.invalid/a/x", "/b/c"))
        assertEquals("https://cdn.invalid/p", Playlist.redirect("http://example.invalid/a", "https://cdn.invalid/p"))
        assertEquals("http://cdn.invalid/p", Playlist.redirect("http://example.invalid/a", "http://cdn.invalid/p"))
        try { Playlist.redirect("https://example.invalid/a", "http://example.invalid/a"); fail("Downgrade accepted") }
        catch (_: IllegalArgumentException) { }
        try { Playlist.redirect("https://example.invalid/a", "https://user:pw@example.invalid/a"); fail("User-info accepted") }
        catch (_: IllegalArgumentException) { }
    }
}

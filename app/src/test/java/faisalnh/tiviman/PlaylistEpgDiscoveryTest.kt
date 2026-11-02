package faisalnh.tiviman

import org.junit.Assert.*
import org.junit.Test

class PlaylistEpgDiscoveryTest {
    private fun m3u(header: String) = "$header\n#EXTINF:-1,One\nhttps://example.invalid/a"

    @Test fun readsAllSupportedAttributesAndCommaSeparatedValues() {
        val urls = Playlist.epgUrls(m3u("#EXTM3U x-tvg-url=\"https://e.invalid/a.xml, https://e.invalid/b.xml\" url-tvg=\"https://e.invalid/c.xml\" tvg-url=\"https://e.invalid/d.xml\""))
        assertEquals(listOf("https://e.invalid/a.xml", "https://e.invalid/b.xml", "https://e.invalid/c.xml", "https://e.invalid/d.xml"), urls)
    }
    @Test fun deduplicatesAndKeepsOrder() {
        val urls = Playlist.epgUrls(m3u("#EXTM3U x-tvg-url=\"https://e.invalid/a.xml,https://e.invalid/a.xml\" url-tvg=\"https://e.invalid/a.xml\""))
        assertEquals(listOf("https://e.invalid/a.xml"), urls)
    }
    @Test fun acceptsHttpAndHttpsSources() {
        val text = m3u("#EXTM3U x-tvg-url=\"http://e.invalid/a.xml,https://e.invalid/b.xml\"")
        assertEquals(listOf("http://e.invalid/a.xml", "https://e.invalid/b.xml"), Playlist.epgUrls(text))
    }
    @Test fun skipsMalformedUnsafeAndEmptyEntries() {
        val urls = Playlist.epgUrls(m3u("#EXTM3U x-tvg-url=\",not a url,ftp://e.invalid/x,https://user:pw@e.invalid/x,https://e.invalid/x#f,file:///etc/passwd,https://e.invalid/ok.xml\""))
        assertEquals(listOf("https://e.invalid/ok.xml"), urls)
    }
    @Test fun unquotedOrUnterminatedAttributesAreIgnored() {
        assertTrue(Playlist.epgUrls(m3u("#EXTM3U x-tvg-url=https://e.invalid/a.xml")).isEmpty())
        assertTrue(Playlist.epgUrls(m3u("#EXTM3U x-tvg-url=\"https://e.invalid/a.xml")).isEmpty())
        assertTrue(Playlist.epgUrls(m3u("#EXTM3U other-x-tvg-url=\"https://e.invalid/a.xml\"")).isEmpty())
    }
    @Test fun boundsResultToFourUrls() {
        val list = (1..10).joinToString(",") { "https://e.invalid/$it.xml" }
        val urls = Playlist.epgUrls(m3u("#EXTM3U x-tvg-url=\"$list\""))
        assertEquals(4, urls.size)
        assertEquals("https://e.invalid/4.xml", urls.last())
    }
    @Test fun ignoresNonHeaderLinesAndNonM3uText() {
        assertTrue(Playlist.epgUrls("#EXTM3U\n#EXTINF:-1 x-tvg-url=\"https://e.invalid/a.xml\",One\nhttps://e.invalid/a").isEmpty())
        assertTrue(Playlist.epgUrls("x-tvg-url=\"https://e.invalid/a.xml\"").isEmpty())
        assertTrue(Playlist.epgUrls("").isEmpty())
    }
    @Test fun hlsManifestsAreNotTrusted() {
        val hls = "#EXTM3U x-tvg-url=\"https://e.invalid/a.xml\"\n#EXT-X-TARGETDURATION:6\nseg.ts"
        assertTrue(Playlist.epgUrls(hls).isEmpty())
    }
    @Test fun acceptsBomAndCredentialsAnnotationButDoesNotLeakHeaders() {
        val text = "\uFEFF#EXTM3U x-tvg-url=\"https://e.invalid/a.xml\"\n#EXT-X-CREDENTIALS:synthetic\n#EXTINF:-1,One\nhttps://e.invalid/a"
        assertEquals(listOf("https://e.invalid/a.xml"), Playlist.epgUrls(text))
    }
    @Test fun existingParseIsUnaffectedByEpgAttributes() {
        val channels = Playlist.parse(m3u("#EXTM3U x-tvg-url=\"https://e.invalid/a.xml\""))
        assertEquals(1, channels.size)
    }
}

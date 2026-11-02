package faisalnh.tiviman

import java.net.ServerSocket
import java.util.zip.GZIPOutputStream
import java.io.ByteArrayOutputStream
import kotlin.concurrent.thread
import org.junit.Test
import org.junit.Assert.*

class EpgHttpTest {
    private val catalog = listOf(Channel(0, "Example", "Test", emptyList(), "https://example.invalid/live", "one"))
    private fun response(body: ByteArray, test: (String) -> Unit) {
        ServerSocket(0).use { server ->
            val task = thread {
                server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val reader = socket.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) { }
                    val out = socket.getOutputStream()
                    out.write("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    out.write(body); out.flush()
                }
            }
            try { test("http://127.0.0.1:${server.localPort}/guide") } finally { task.join(6000) }
        }
    }
    @Test fun httpMagicDetectsGzipAndPlainWithoutSuffix() {
        val start = Epg.minute() * 60000L
        val format = java.text.SimpleDateFormat("yyyyMMddHHmmss Z").apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        val xml = "<tv><programme channel='one' start='${format.format(java.util.Date(start))}' stop='${format.format(java.util.Date(start + 3600000))}'><title>Example</title></programme></tv>".toByteArray()
        val compressed = ByteArrayOutputStream().apply { GZIPOutputStream(this).use { it.write(xml) } }.toByteArray()
        for (body in listOf(xml, compressed)) response(body) { url ->
            assertEquals("Example", ProviderHttp().epg(url, catalog).events.single().title)
        }
    }
    @Test fun corruptGzipRejected() {
        response(byteArrayOf(0x1f, 0x8b.toByte(), 0)) { url ->
            try { ProviderHttp().epg(url, catalog); fail("Corrupt gzip accepted") }
            catch (_: java.io.IOException) { }
        }
    }
}

package faisalnh.tiviman

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Pure JVM checks: synthetic bytes over loopback only. Never loads AdvancedPlaybackActivity. */
class AdvancedPlaybackTest {
    private fun tempDir(): File = Files.createTempDirectory("advanced-recorder").toFile().also { it.deleteOnExit() }

    /** Serves exactly one request on 127.0.0.1. [respond] writes the full raw HTTP response. */
    private fun <T> serve(respond: (OutputStream) -> Unit, test: (String) -> T): T {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 10000
            val worker = thread {
                try {
                    server.accept().use { socket ->
                        socket.soTimeout = 10000
                        val reader = socket.getInputStream().bufferedReader()
                        while (!reader.readLine().isNullOrEmpty()) { }
                        respond(socket.getOutputStream())
                    }
                } catch (_: Exception) { }
            }
            try { return test("http://127.0.0.1:${server.localPort}/stream") } finally { server.close(); worker.join(5000) }
        }
    }

    private fun ok(type: String, body: ByteArray): (OutputStream) -> Unit = { out ->
        out.write("HTTP/1.1 200 OK\r\nContent-Type: $type\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(body); out.flush()
    }

    private class Outcome(val result: AdvancedRecorder.Result, val bytes: Long)

    private fun record(dir: File, address: String, clock: () -> Long = { System.nanoTime() / 1_000_000 },
        maxBytes: Long = AdvancedRules.MAX_RECORDING_BYTES, maxMillis: Long = AdvancedRules.MAX_RECORDING_MILLIS): Outcome {
        val seen = AtomicReference<Outcome>()
        AdvancedRecorder(dir, address, clock, maxBytes, maxMillis, onDone = { _, r, b -> seen.set(Outcome(r, b)) }).run()
        return seen.get()
    }

    @Test fun manifestPathsAreRejected() {
        assertTrue(AdvancedRules.isManifestPath("https://example.invalid/a/master.M3U8"))
        assertTrue(AdvancedRules.isManifestPath("https://example.invalid/a/live.mpd?x=1"))
        assertTrue(AdvancedRules.isManifestPath("not a url at all"))
        assertFalse(AdvancedRules.isManifestPath("https://example.invalid/live/1.ts"))
        assertFalse(AdvancedRules.isManifestPath("http://127.0.0.1:1/fixture.mp4"))
    }

    @Test fun manifestBodiesAndTypesAreDetected() {
        fun head(s: String) = s.toByteArray()
        assertTrue(AdvancedRules.looksLikeManifest(head("#EXTM3U\n#EXT-X-VERSION:3"), 24))
        assertTrue(AdvancedRules.looksLikeManifest(head("\n  <?xml version"), 15))
        assertTrue(AdvancedRules.looksLikeManifest(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte(), '#'.code.toByte()) + head("EXTM3U"), 10))
        assertFalse(AdvancedRules.looksLikeManifest(byteArrayOf(0x47, 0x40, 0x00, 0x10), 4))
        assertFalse(AdvancedRules.looksLikeManifest(ByteArray(0), 0))
        assertTrue(AdvancedRules.unsupportedContentType("application/vnd.apple.mpegurl"))
        assertTrue(AdvancedRules.unsupportedContentType("text/html; charset=utf-8"))
        assertFalse(AdvancedRules.unsupportedContentType("video/mp2t"))
        assertFalse(AdvancedRules.unsupportedContentType(null))
    }

    @Test fun onlyUuidRecordingNamesAreSafe() {
        assertTrue(AdvancedRules.isSafeRecordingName("0b2f3c4d-1111-2222-3333-444455556666.rec"))
        assertFalse(AdvancedRules.isSafeRecordingName("../0b2f3c4d-1111-2222-3333-444455556666.rec"))
        assertFalse(AdvancedRules.isSafeRecordingName("0b2f3c4d-1111-2222-3333-444455556666.part"))
        assertFalse(AdvancedRules.isSafeRecordingName("channel-name.rec"))
    }

    @Test fun progressiveCaptureSavesUuidNamedFileAndNoPart() {
        val dir = tempDir()
        val body = ByteArray(300_000) { (it % 251).toByte() }
        val outcome = serve(ok("video/mp2t", body)) { record(dir, it) }
        assertEquals(AdvancedRecorder.Result.SAVED, outcome.result)
        assertEquals(body.size.toLong(), outcome.bytes)
        val files = dir.listFiles()!!
        assertEquals(1, files.size)
        assertTrue(AdvancedRules.isSafeRecordingName(files[0].name))
        assertTrue(body.contentEquals(files[0].readBytes()))
    }

    @Test fun byteLimitStopsAndKeepsCapturedPrefix() {
        val dir = tempDir()
        val outcome = serve(ok("video/mp2t", ByteArray(500_000) { 7 })) { record(dir, it, maxBytes = 100_000) }
        assertEquals(AdvancedRecorder.Result.LIMIT, outcome.result)
        assertEquals(100_000L, outcome.bytes)
        assertEquals(100_000L, dir.listFiles()!!.single().length())
    }

    @Test fun timeLimitUsesInjectedClock() {
        val dir = tempDir()
        var now = 0L
        val outcome = serve(ok("video/mp2t", ByteArray(100) { 1 })) { record(dir, it, clock = { now.also { now += 1000 } }, maxMillis = 1500) }
        assertEquals(AdvancedRecorder.Result.LIMIT, outcome.result)
        assertEquals(100L, outcome.bytes)
        assertEquals(1, dir.listFiles()!!.size)
    }

    @Test fun hlsManifestBodyIsRejectedEvenWithGenericType() {
        val dir = tempDir()
        val outcome = serve(ok("application/octet-stream", "#EXTM3U\n#EXT-X-VERSION:3\n".toByteArray())) { record(dir, it) }
        assertEquals(AdvancedRecorder.Result.UNSUPPORTED, outcome.result)
        assertEquals(0, dir.listFiles()!!.size)
    }

    @Test fun manifestContentTypeIsRejectedBeforeAnyFileExists() {
        val dir = tempDir()
        val outcome = serve(ok("application/vnd.apple.mpegurl", ByteArray(10) { 1 })) { record(dir, it) }
        assertEquals(AdvancedRecorder.Result.UNSUPPORTED, outcome.result)
        assertEquals(0, dir.listFiles()!!.size)
    }

    @Test fun manifestPathNeverConnects() {
        val dir = tempDir()
        // Port 1 on loopback: a connection attempt would fail as FAILED, not UNSUPPORTED.
        assertEquals(AdvancedRecorder.Result.UNSUPPORTED, record(dir, "http://127.0.0.1:1/live.m3u8").result)
        assertEquals(0, dir.listFiles()!!.size)
    }

    @Test fun serverErrorLeavesNothingBehind() {
        val dir = tempDir()
        val outcome = serve({ it.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); it.flush() }) { record(dir, it) }
        assertEquals(AdvancedRecorder.Result.FAILED, outcome.result)
        assertEquals(0, dir.listFiles()!!.size)
    }

    @Test fun midStreamFailureWithoutDataRemovesPart() {
        val dir = tempDir()
        val outcome = serve({ it.write("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\nConnection: close\r\n\r\n".toByteArray()); it.flush() }) { record(dir, it) }
        assertEquals(AdvancedRecorder.Result.FAILED, outcome.result)
        assertEquals(0, dir.listFiles()!!.size)
    }

    @Test fun staleSweepSkipsActiveFileAndRecordings() {
        val dir = tempDir()
        val stale = File(dir, "11111111-1111-1111-1111-111111111111.part").apply { writeBytes(ByteArray(3)); setLastModified(1000) }
        val fresh = File(dir, "22222222-2222-2222-2222-222222222222.part").apply { writeBytes(ByteArray(3)) }
        val rec = File(dir, "33333333-3333-3333-3333-333333333333.rec").apply { writeBytes(ByteArray(3)); setLastModified(1000) }
        assertEquals(1, AdvancedRecorder.sweepStale(dir, System.currentTimeMillis(), 120_000))
        assertFalse(stale.exists()); assertTrue(fresh.exists()); assertTrue(rec.exists())

        // A running recorder's part file survives even a sweep that treats everything as stale.
        // Handshake: the server holds the connection open after sending a known prefix; the test
        // proceeds only once the recorder reports that exact prefix is on disk, or has already ended.
        val release = CountDownLatch(1)
        val ended = CountDownLatch(1)
        val outcome = AtomicReference<Outcome>()
        val prefix = 2048
        serve({ out ->
            out.write("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\nConnection: close\r\n\r\n".toByteArray())
            out.write(ByteArray(prefix) { 5 }); out.flush()
            release.await(10, TimeUnit.SECONDS)
        }) { address ->
            val active = tempDir()
            // Explicit clock: the default SystemClock is an unmocked android.jar stub on the JVM.
            val recorder = AdvancedRecorder(active, address, clock = { System.nanoTime() / 1_000_000 },
                onDone = { _, r, b -> outcome.set(Outcome(r, b)); ended.countDown() })
            val runner = thread { recorder.run() }
            try {
                val deadline = System.currentTimeMillis() + 8000
                fun partBytes() = active.listFiles()?.filter { it.name.endsWith(".part") }?.maxOfOrNull { it.length() } ?: 0L
                while (System.currentTimeMillis() < deadline && ended.count > 0 && partBytes() < prefix) Thread.sleep(10)
                assertEquals("Recorder ended before the test could observe it: ${outcome.get()?.result}", 1L, ended.count)
                val part = active.listFiles()!!.single()
                assertTrue(part.name.endsWith(".part"))
                assertEquals(prefix.toLong(), part.length())
                assertEquals(0, AdvancedRecorder.sweepStale(active, System.currentTimeMillis() + 10_000_000, 0))
                assertTrue(part.exists())
                recorder.stop() // must be safe from the caller's thread and must end the run
                assertTrue(ended.await(8, TimeUnit.SECONDS))
                runner.join(8000)
                assertFalse(runner.isAlive)
                assertEquals(0, active.listFiles()!!.count { it.name.endsWith(".part") })
            } finally { release.countDown() }
        }
        assertTrue(outcome.get().result == AdvancedRecorder.Result.SAVED || outcome.get().result == AdvancedRecorder.Result.CANCELED)
    }
}

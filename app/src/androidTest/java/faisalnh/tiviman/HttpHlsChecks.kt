package faisalnh.tiviman

import android.app.Instrumentation
import android.os.SystemClock
import android.view.View
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Loopback-only segmented HLS. No Activity, saved storage or provider access. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class HttpHlsChecks(private val runner: Instrumentation) {
    private fun <T> main(block: () -> T): T {
        var value: T? = null
        runner.runOnMainSync { value = block() }
        @Suppress("UNCHECKED_CAST") return value as T
    }
    private fun await(label: String, test: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 20000
        while (!main(test) && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        check(main(test)) { label }
    }
    fun run(segment: ByteArray) {
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val gate = CountDownLatch(1)
        val requested = CountDownLatch(1)
        val fail = AtomicBoolean(true)
        val paths = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val workers = java.util.concurrent.Executors.newCachedThreadPool()
        val playlist = ("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:6\n#EXT-X-MEDIA-SEQUENCE:0\n" +
            "#EXTINF:5.1,\nfirst.aac\n#EXTINF:5.1,\nsecond.aac\n#EXTINF:5.1,\nthird.aac\n#EXT-X-ENDLIST\n").toByteArray()
        val master = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=64000,CODECS=\"mp4a.40.2\"\nmedia.m3u8\n".toByteArray()
        val serving = Thread {
            try {
                while (!server.isClosed) {
                    val socket = server.accept()
                    workers.execute {
                        try {
                            socket.use {
                                it.soTimeout = 5000
                                val reader = it.getInputStream().bufferedReader()
                                val path = reader.readLine()?.split(' ')?.getOrNull(1) ?: return@execute
                                while (!reader.readLine().isNullOrEmpty()) { }
                                paths.add(path)
                                if (path == "/second.aac") {
                                    requested.countDown()
                                    check(gate.await(30, TimeUnit.SECONDS))
                                }
                                val error = path == "/failure.aac" && fail.get()
                                val bytes = when (path) {
                                    "/master.m3u8" -> master
                                    "/media.m3u8" -> playlist
                                    "/retry.m3u8" -> "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:5.1,\nfailure.aac\n#EXT-X-ENDLIST\n".toByteArray()
                                    else -> segment
                                }
                                val output = it.getOutputStream()
                                output.write((if (error) "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                                    else "HTTP/1.1 200 OK\r\nContent-Length: ${bytes.size}\r\nContent-Type: ${if (path.endsWith("m3u8")) "application/vnd.apple.mpegurl" else "audio/aac"}\r\nConnection: close\r\n\r\n").toByteArray())
                                if (!error) output.write(bytes)
                                output.flush()
                            }
                        } catch (_: Exception) { /* State assertions report only synthetic stages. */ }
                    }
                }
            } catch (_: Exception) { /* Closing listener ends accept. */ }
        }.apply { isDaemon = true; start() }
        lateinit var player: ExoPlayer
        lateinit var view: PlayerView
        var created = false
        var viewCreated = false
        fun spinner() = view.findViewById<View>(androidx.media3.ui.R.id.exo_buffering)
        try {
            main {
                player = ExoPlayer.Builder(runner.targetContext).setLoadControl(DefaultLoadControl.Builder()
                    .setBufferDurationsMs(500, 1000, 100, 100).build()).build()
                created = true
                player.volume = 0f
                view = PlayerView(runner.targetContext).apply {
                    useController = false; setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
                    this.player = player
                }
                viewCreated = true
                player.setMediaItem(MediaItem.fromUri("http://127.0.0.1:${server.localPort}/master.m3u8"))
                player.prepare(); player.play()
            }
            await("HTTP HLS begins playback") { player.isPlaying && player.currentPosition > 300 }
            check(requested.await(10, TimeUnit.SECONDS))
            await("mid-stream starvation shows spinner") {
                player.currentPosition > 1000 && player.playbackState == Player.STATE_BUFFERING && spinner().visibility == View.VISIBLE
            }
            val stalled = main { player.currentPosition }
            gate.countDown()
            await("released segment resumes and hides spinner") {
                player.isPlaying && player.currentPosition > stalled + 300 && spinner().visibility != View.VISIBLE
            }
            check(paths.containsAll(listOf("/master.m3u8", "/media.m3u8", "/first.aac", "/second.aac")))
            main {
                player.setMediaItem(MediaItem.fromUri("http://127.0.0.1:${server.localPort}/retry.m3u8"))
                player.prepare(); player.play()
            }
            await("HTTP missing segment becomes player error") { player.playerError != null }
            check(main { spinner().visibility != View.VISIBLE })
            val item = main { player.currentMediaItem }
            fail.set(false)
            main { player.prepare(); player.play() }
            await("prepare retry recovers restored HTTP segment") {
                player.playerError == null && player.isPlaying && player.currentPosition > 300 && spinner().visibility != View.VISIBLE
            }
            check(main { player.currentMediaItem == item })
        } finally {
            gate.countDown(); server.close(); workers.shutdownNow(); serving.join(2000)
            main { if (viewCreated) view.player = null; if (created) player.release() }
            check(workers.awaitTermination(6, TimeUnit.SECONDS))
        }
    }
}

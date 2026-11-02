package faisalnh.tiviman

import android.app.Instrumentation
import android.os.Bundle
import java.net.ServerSocket
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList

/** Synthetic localhost protocol tests; no private service contacted. */
class ProviderChecks(private val runner: Instrumentation) {
    fun run() {
        val result = Bundle()
        var server: ServerSocket? = null
        try {
            check(!java.io.File(runner.targetContext.noBackupFilesDir, "playlist.enc").exists()) { "Refusing tests over existing saved playlist" }
            val socket = ServerSocket(0).also { server = it }
            val base = "http://127.0.0.1:${socket.localPort}"
            val actions = CopyOnWriteArrayList<String>()
            val thread = Thread {
                try { while (!socket.isClosed) socket.accept().use { client ->
                    val reader = client.getInputStream().bufferedReader()
                    val request = reader.readLine().split(' ')[1]
                    val headers = mutableMapOf<String, String>()
                    while (true) {
                        val line = reader.readLine(); if (line.isNullOrEmpty()) break
                        headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
                    }
                    val params = request.substringAfter('?', "").split('&').associate {
                        it.substringBefore('=') to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
                    }
                    val action = params["action"].orEmpty(); actions.add(action)
                    if (request == "/media.mp4") {
                        val bytes = runner.targetContext.assets.open("fixture.mp4").use { it.readBytes() }
                        client.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray() + bytes)
                        return@use
                    }
                    var code = "200 OK"
                    val body = if (request.startsWith("/player_api.php")) {
                        when {
                            params["username"] != "synthetic user" || params["password"] != "synthetic/pass" -> "{\"user_info\":{\"auth\":0}}"
                            action == "get_live_categories" -> "[{\"category_id\":\"7\",\"category_name\":\"Test group\"}]"
                            action == "get_live_streams" -> "[{\"stream_id\":42,\"name\":\"Test channel\",\"category_id\":\"7\"}]"
                            else -> "{\"user_info\":{\"auth\":1,\"status\":\"Active\"}}"
                        }
                    } else {
                        val authorized = headers["cookie"].orEmpty().contains("00%3A1A%3A79%3A00%3A00%3A01") &&
                            (action == "handshake" || headers["authorization"] == "Bearer synthetic-session")
                        if (!authorized) { code = "403 Forbidden"; "{}" } else when (action) {
                            "handshake" -> "{\"js\":{\"token\":\"synthetic-session\"}}"
                            "get_profile" -> "{\"js\":{\"blocked\":0}}"
                            "get_genres" -> "{\"js\":[{\"id\":\"9\",\"title\":\"Portal group\"}]}"
                            "get_all_channels" -> "{\"js\":{\"total_items\":1,\"data\":[{\"id\":\"1\",\"name\":\"Portal channel\",\"tv_genre_id\":\"9\",\"cmd\":\"ffmpeg http://localhost/ch/1\"}]}}"
                            "create_link" -> if (params["cmd"] == "ffmpeg http://localhost/ch/1") "{\"js\":{\"cmd\":\"ffmpeg $base/media.mp4\"}}" else "{}"
                            else -> "{}"
                        }
                    }
                    val bytes = body.toByteArray()
                    client.getOutputStream().write(("HTTP/1.1 $code\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray() + bytes)
                } } catch (_: Exception) { }
            }.apply { isDaemon = true; start() }
            val xc = ProviderConfig(SourceType.XTREAM, base, "synthetic user", "synthetic/pass")
            val imported = Providers(xc).import()
            check(imported.single().group == "Test group")
            check(imported.single().stream == "$base/live/synthetic%20user/synthetic%2Fpass/42.ts")
            try { Providers(ProviderConfig(SourceType.XTREAM, base, "wrong", "bad")).import(); error("Denied login accepted") }
            catch (_: IllegalArgumentException) { }
            val stalker = ProviderConfig(SourceType.STALKER, "$base/c/", mac = "00:1A:79:00:00:01")
            val channels = Providers(stalker).import()
            check(channels.single().name == "Portal channel" && channels.single().group == "Portal group")
            check(Providers(stalker).resolve(channels.single()) == "$base/media.mp4")
            check(actions.containsAll(listOf("get_profile", "get_genres", "get_all_channels", "create_link")))
            val store = PlaylistStore(runner.targetContext)
            store.saveProvider(stalker, channels)
            check(store.provider()?.mac == stalker.mac)
            check(store.load()?.single()?.stream == channels.single().stream)
            val activity = runner.startActivitySync(android.content.Intent(runner.targetContext, MainActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
            val deadline = android.os.SystemClock.uptimeMillis() + 10000
            var playing = false
            while (!playing && android.os.SystemClock.uptimeMillis() < deadline) {
                runner.runOnMainSync {
                    val field = MainActivity::class.java.getDeclaredField("player").apply { isAccessible = true }
                    playing = (field.get(activity) as? androidx.media3.exoplayer.ExoPlayer)?.isPlaying == true
                }
                android.os.SystemClock.sleep(100)
            }
            check(playing) { "Portal resolved URL did not play through activity" }
            runner.runOnMainSync { activity.finish() }
            store.clear()
            socket.close(); thread.join(1000)
            result.putString("summary", "PASS: Xtream auth/categories/live streams/credential encoding/denied login; Stalker handshake/profile/cookie/bearer/genres/channels/fresh create_link; encrypted provider persistence; resolved portal playback through MainActivity")
            runner.finish(-1, result)
        } catch (error: Throwable) {
            result.putString("failure", error.stackTraceToString()); runner.finish(0, result)
        } finally { server?.close() }
    }
}

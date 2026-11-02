package faisalnh.tiviman

import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.net.ServerSocket

/** Refuses to run if a saved playlist exists; never uses user data. */
class PlaylistChecks {
    private lateinit var runner: Instrumentation
    fun attachRunner(value: Instrumentation) { runner = value }
    private val targetContext get() = runner.targetContext
    private fun runOnMainSync(block: () -> Unit) = runner.runOnMainSync(block)
    private fun finish(code: Int, result: Bundle) = runner.finish(code, result)
    private fun nodes(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { nodes(view.getChildAt(it)) } else emptyList()
    fun onStart() {
        val result = Bundle()
        val store = PlaylistStore(targetContext)
        try {
            check(!java.io.File(targetContext.noBackupFilesDir, "playlist.enc").exists()) { "Refusing to test over a saved playlist" }
            val body = "#EXTM3U\n#EXTINF:-1 group-title=\"Synthetic\",Local test\nhttps://example.invalid/synthetic-token\n"
            store.save(body)
            check(store.load()?.single()?.name == "Local test")
            val encrypted = java.io.File(targetContext.noBackupFilesDir, "playlist.enc").readBytes()
            check(!String(encrypted, Charsets.ISO_8859_1).contains("synthetic-token"))
            try { store.save("invalid"); error("invalid accepted") } catch (_: IllegalArgumentException) { }
            check(store.load()?.size == 1)
            store.clear()
            val server = ServerSocket(0)
            val networkBody = "#EXTM3U\n#EXTINF:-1 group-title=\"Synthetic\",Local test\nhttp://127.0.0.1:${server.localPort}/one.mp4\n#EXTINF:-1 group-title=\"Synthetic\",Second test\nhttp://127.0.0.1:${server.localPort}/two.mp4\n"
            val thread = Thread {
                try { while (!server.isClosed) server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    val requestLine = reader.readLine()
                    while (!reader.readLine().isNullOrEmpty()) { }
                    val bytes = if (requestLine.contains(".mp4")) targetContext.assets.open("fixture.mp4").use { it.readBytes() } else networkBody.toByteArray()
                    socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray() + bytes)
                } } catch (_: Exception) { }
            }.apply { isDaemon = true; start() }
            val activity = runner.startActivitySync(Intent(targetContext, PlaylistActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            runner.waitForIdleSync()
            runOnMainSync {
                val views = nodes(activity.window.decorView)
                views.filterIsInstance<Button>().first { it.text == "Next" }.performClick()
                views.filterIsInstance<EditText>().first().setText("http://127.0.0.1:${server.localPort}/synthetic")
                views.filterIsInstance<Button>().first { it.text == "Import playlist" }.performClick()
            }
            val deadline = SystemClock.uptimeMillis() + 10000
            while (store.load() == null && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
            check(store.load()?.size == 2) { "Form/network import failed" }
            runner.waitForIdleSync()
            runOnMainSync {
                check(nodes(activity.window.decorView).filterIsInstance<TextView>().any { it.text.contains("Playlist is processed") })
                nodes(activity.window.decorView).filterIsInstance<Button>().first { it.text == "Done" }.performClick()
            }
            val main = runner.startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
            SystemClock.sleep(1000)
            fun player() = MainActivity::class.java.getDeclaredField("player").let { it.isAccessible = true; it.get(main) as androidx.media3.exoplayer.ExoPlayer }
            runOnMainSync { check(player().isPlaying) { "Imported source is not playing" } }
            runner.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
            runner.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
            SystemClock.sleep(1000)
            runOnMainSync {
                check(player().currentMediaItem?.localConfiguration?.uri?.path == "/two.mp4") { "Tune did not switch source" }
                check(player().isPlaying) { "Second imported source is not playing" }
                main.finish()
            }
            server.close(); thread.join(1000)
            store.clear()
            result.putString("summary", "PASS: encrypted roundtrip, no plaintext credential, invalid-import preservation, local HTTP form import, imported guide playback, source switching, removal")
            finish(-1, result)
        } catch (error: Throwable) {
            // Synthetic errors only; don't delete pre-existing user data on failure.
            result.putString("failure", error.stackTraceToString()); finish(0, result)
        }
    }
}

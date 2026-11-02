package faisalnh.tiviman

import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.media3.exoplayer.ExoPlayer
import org.json.JSONObject
import java.io.File

/** Explicit opt-in only. Input is supplied locally, never in test args/source/results. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PrivateProviderCheck(private val runner: Instrumentation) {
    fun run() {
        val report = Bundle()
        val input = File(runner.targetContext.cacheDir, "private-provider-input.json")
        var stage = "read local configuration"
        try {
            check(!File(runner.targetContext.noBackupFilesDir, "playlist.enc").exists())
            androidx.media3.common.util.Log.setLogLevel(androidx.media3.common.util.Log.LOG_LEVEL_OFF)
            val config = ProviderConfig.from(JSONObject(input.readText()))
            input.delete()
            stage = "authenticate and load live channels"
            val channels = Providers(config).import()
            report.putInt("channel_count", channels.size)
            report.putInt("group_count", channels.map { it.group }.distinct().size)
            PlaylistStore(runner.targetContext).saveProvider(config, channels)
            report.putString("import", "PASS; saved encrypted in replacement app")
            stage = "play first channel"
            val activity = runner.startActivitySync(Intent(runner.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
            val deadline = SystemClock.uptimeMillis() + 45000
            var playing = false
            var errorCode: String? = null
            while (!playing && errorCode == null && SystemClock.uptimeMillis() < deadline) {
                runner.runOnMainSync {
                    val field = MainActivity::class.java.getDeclaredField("player").apply { isAccessible = true }
                    val player = field.get(activity) as? ExoPlayer
                    playing = player?.isPlaying == true
                    errorCode = player?.playerError?.errorCodeName
                }
                SystemClock.sleep(250)
            }
            if (playing) {
                SystemClock.sleep(5000)
                runner.runOnMainSync {
                    val field = MainActivity::class.java.getDeclaredField("player").apply { isAccessible = true }
                    playing = (field.get(activity) as? ExoPlayer)?.isPlaying == true
                }
            }
            report.putString("playback", if (playing) "PASS; playing after five-second observation" else "NOT VERIFIED; ${errorCode ?: "timeout"}")
            runner.runOnMainSync { activity.finish() } // Release the stream; no concurrent reference playback test.
            runner.finish(-1, report)
        } catch (_: Exception) {
            report.putString("failure", "Failed to $stage; private exception details suppressed")
            runner.finish(0, report)
        } finally { input.delete() }
    }
}

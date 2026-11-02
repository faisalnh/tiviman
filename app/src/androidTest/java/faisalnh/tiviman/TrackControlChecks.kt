package faisalnh.tiviman

import android.app.Instrumentation
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/** Isolated synthetic player. Never starts MainActivity or opens/imports a private stream. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class TrackControlChecks(private val runner: Instrumentation) {
    private var player: ExoPlayer? = null
    private fun <T> main(block: () -> T): T {
        var value: T? = null
        runner.runOnMainSync { value = block() }
        @Suppress("UNCHECKED_CAST") return value as T
    }
    private fun await(test: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10000
        while (!main(test) && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
        check(main(test))
    }
    fun run() {
        val report = Bundle()
        var stage = "synthetic setup"
        val subtitle = java.io.File(runner.targetContext.cacheDir, "track-control-synthetic.vtt")
        val audio = java.io.File(runner.targetContext.cacheDir, "track-control-synthetic.wav")
        val multi = java.io.File(runner.targetContext.cacheDir, "track-control-multi.mp4")
        val hls = java.io.File(runner.targetContext.cacheDir, "track-control-hls")
        val dash = java.io.File(runner.targetContext.cacheDir, "track-control-dash")
        try {
            val format = androidx.media3.common.Format.Builder().setId("synthetic")
                .setSampleMimeType("audio/mp4a-latm").setLanguage("en").setChannelCount(2).build()
            val group = androidx.media3.common.Tracks.Group(androidx.media3.common.TrackGroup(format),
                false, intArrayOf(C.FORMAT_UNSUPPORTED_TYPE), booleanArrayOf(false))
            val unsupported = TrackControls.options(androidx.media3.common.Tracks(listOf(group)), C.TRACK_TYPE_AUDIO).last()
            check(!unsupported.supported && unsupported.label.contains("Stereo") && unsupported.label.contains("Unsupported"))
            check(unsupported.key == TrackControls.identity(format) && !unsupported.key.contains("synthetic"))
            stage = "isolated encrypted preference persistence"
            val isolatedDir = java.io.File(runner.targetContext.cacheDir, "track-preferences-synthetic").apply { mkdirs() }
            val isolatedContext = object : android.content.ContextWrapper(runner.targetContext) {
                override fun getNoBackupFilesDir() = isolatedDir
            }
            val store = PlaylistStore(isolatedContext)
            val choices = mapOf("0:${C.TRACK_TYPE_AUDIO}" to "off", "1:${C.TRACK_TYPE_TEXT}" to "auto")
            store.saveTracks("fixtures-v1", choices)
            check(store.snapshot().tracks == choices)
            store.saveTracks("different-revision", choices)
            check(store.snapshot().tracks.isEmpty())
            store.saveDisplay("fixtures-v1", mapOf("0" to "RATIO_4_3", "1" to "REDUCED"))
            check(store.snapshot().display == mapOf("0" to "RATIO_4_3", "1" to "REDUCED"))
            store.saveDisplay("different-revision", mapOf("0" to "ZOOM"))
            check(store.snapshot().display.isEmpty())
            java.io.File(isolatedDir, "display.enc").delete()
            java.io.File(isolatedDir, "tracks.enc").delete()
            subtitle.writeText("WEBVTT\n\n00:00.000 --> 00:04.900\nSynthetic caption\n")
            val samples = 8000 * 5
            val buffer = java.nio.ByteBuffer.allocate(44 + samples * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            buffer.put("RIFF".toByteArray()).putInt(36 + samples * 2).put("WAVEfmt ".toByteArray())
                .putInt(16).putShort(1).putShort(1).putInt(8000).putInt(16000).putShort(2).putShort(16)
                .put("data".toByteArray()).putInt(samples * 2)
            repeat(samples) { buffer.putShort(0) }
            audio.writeBytes(buffer.array())
            val item = MediaItem.Builder().setUri("asset:///fixture.mp4")
                .setSubtitleConfigurations(listOf(MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(subtitle))
                    .setMimeType(MimeTypes.TEXT_VTT).setLanguage("en").build())).build()
            var transitions = 0
            var captions = 0
            main {
                player = ExoPlayer.Builder(runner.targetContext).build().apply {
                    volume = 0f
                    addListener(object : Player.Listener {
                        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { transitions++ }
                        override fun onCues(cueGroup: androidx.media3.common.text.CueGroup) {
                            if (cueGroup.cues.isNotEmpty()) captions++
                        }
                    })
                    setMediaItem(item); prepare()
                }
            }
            await { player!!.playbackState == Player.STATE_READY }
            stage = "actual display view measurement"
            main {
                val viewport = android.widget.FrameLayout(runner.targetContext)
                val view = androidx.media3.ui.PlayerView(runner.targetContext).apply { useController = false }
                viewport.addView(view)
                view.player = player
                val originalItem = player!!.currentMediaItem
                val content = view.findViewById<androidx.media3.ui.AspectRatioFrameLayout>(androidx.media3.ui.R.id.exo_content_frame)
                // Synthetic 4:3 source inside a 16:9 screen verifies actual child measurements.
                for (mode in DisplayMode.entries) {
                    content.setAspectRatio(4f / 3)
                    DisplaySizing.apply(view, mode, 1920, 1080)
                    viewport.measure(android.view.View.MeasureSpec.makeMeasureSpec(1920, android.view.View.MeasureSpec.EXACTLY),
                        android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY))
                    viewport.layout(0, 0, 1920, 1080)
                    val expected = mode.size(1920, 1080)
                    check(view.width == expected.width && view.height == expected.height)
                    check(view.left == (1920 - expected.width) / 2 && view.top == (1080 - expected.height) / 2)
                    when (mode) {
                        DisplayMode.NORMAL -> check(content.measuredWidth == 1440 && content.measuredHeight == 1080)
                        DisplayMode.FULL -> check(content.measuredWidth == 1920 && content.measuredHeight == 1080)
                        DisplayMode.ZOOM -> check(content.measuredWidth == 1920 && content.measuredHeight == 1440)
                        DisplayMode.REDUCED -> check(content.measuredWidth == 1152 && content.measuredHeight == 864)
                        else -> check(content.measuredWidth > 0 && content.measuredHeight > 0)
                    }
                    check(player!!.currentMediaItem == originalItem)
                }
                view.player = null
            }
            stage = "video override and text selection"
            val videoKey = main { TrackControls.options(player!!.currentTracks, C.TRACK_TYPE_VIDEO)
                .first { it.group != null && it.supported }.key }
            val textKey = main { TrackControls.options(player!!.currentTracks, C.TRACK_TYPE_TEXT)
                .first { it.group != null && it.supported }.key }
            val before = main { transitions }
            main {
                TrackControls.apply(player!!, C.TRACK_TYPE_VIDEO, videoKey)
                TrackControls.apply(player!!, C.TRACK_TYPE_TEXT, textKey)
                check(player!!.trackSelectionParameters.overrides.size == 2)
                player!!.play()
            }
            await { captions > 0 }
            stage = "Off, Auto and uninterrupted media identity"
            main {
                TrackControls.apply(player!!, C.TRACK_TYPE_TEXT, "off")
                check(player!!.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT))
                TrackControls.apply(player!!, C.TRACK_TYPE_VIDEO, "auto")
                check(player!!.trackSelectionParameters.overrides.isEmpty())
                check(player!!.currentMediaItem == item && transitions == before && player!!.playWhenReady)
                player!!.pause()
                player!!.release()
                player = null
            }
            stage = "audio-only selection and mute"
            main {
                player = ExoPlayer.Builder(runner.targetContext).build().apply {
                    volume = 0f; setMediaItem(MediaItem.fromUri(Uri.fromFile(audio))); prepare()
                }
            }
            await { player!!.playbackState == Player.STATE_READY }
            main {
                check(TrackControls.options(player!!.currentTracks, C.TRACK_TYPE_VIDEO).size == 1)
                val option = TrackControls.options(player!!.currentTracks, C.TRACK_TYPE_AUDIO).first { it.group != null }
                TrackControls.apply(player!!, C.TRACK_TYPE_AUDIO, option.key)
                check(player!!.trackSelectionParameters.overrides.size == 1)
                TrackControls.apply(player!!, C.TRACK_TYPE_AUDIO, "off")
                check(player!!.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_AUDIO))
                TrackControls.apply(player!!, C.TRACK_TYPE_AUDIO, "auto")
                check(!player!!.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_AUDIO))
                check(player!!.trackSelectionParameters.overrides.isEmpty())
            }
            stage = "synthetic timer expiry stops player"
            val timer = SleepTimer()
            timer.setMinutes(15, 1000)
            main { player!!.play() }
            await { player!!.isPlaying }
            check(!timer.expire(900999))
            check(timer.expire(901000))
            main {
                player!!.pause(); player!!.stop()
                check(!player!!.playWhenReady && player!!.playbackState == Player.STATE_IDLE)
            }
            check(timer.expired)
            timer.setMinutes(0, 901001)
            check(timer.expired) // Off is not implicit Play.
            timer.allowPlayback()
            main { player!!.prepare(); player!!.play() }
            await { player!!.isPlaying }
            stage = "generated dual-audio media"
            SyntheticMultiAudio.create(runner.targetContext, multi)
            main {
                player!!.setMediaItem(MediaItem.fromUri(Uri.fromFile(multi))); player!!.prepare(); player!!.play()
            }
            await { player!!.isPlaying }
            val multiItem = main { player!!.currentMediaItem }
            val audioOptions = main { TrackControls.options(player!!.currentTracks, C.TRACK_TYPE_AUDIO).filter { it.group != null && it.supported } }
            check(audioOptions.size == 2 && audioOptions.map { it.key }.distinct().size == 2)
            stage = "actual selected audio changes without media transition"
            val originalMultiPlayer = main { player }
            var multiTransitions = 0
            val observer = object : Player.Listener {
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { multiTransitions++ }
            }
            main { player!!.addListener(observer) }
            for (option in audioOptions) {
                main { TrackControls.apply(player!!, C.TRACK_TYPE_AUDIO, option.key) }
                await { player!!.currentTracks.groups.any { group -> group.type == C.TRACK_TYPE_AUDIO &&
                    (0 until group.length).any { group.isTrackSelected(it) && TrackControls.identity(group.getTrackFormat(it)) == option.key } } }
                check(main { player === originalMultiPlayer && multiTransitions == 0 &&
                    player!!.currentMediaItem == multiItem && player!!.playWhenReady && player!!.playerError == null })
            }
            main { player!!.removeListener(observer) }
            stage = "sequential synthetic media continuity"
            main {
                TrackControls.apply(player!!, C.TRACK_TYPE_AUDIO, "auto")
                player!!.setMediaItems(listOf(MediaItem.fromUri("asset:///fixture.mp4"), MediaItem.fromUri(Uri.fromFile(multi))))
                player!!.prepare(); player!!.play()
            }
            await { player!!.currentMediaItemIndex == 1 && player!!.isPlaying }
            check(main { player!!.playerError == null })
            stage = "local HLS master setup"
            check(hls.mkdirs() || hls.isDirectory)
            val allowedHls = setOf("tone.aac", "low.m3u8", "high.m3u8", "master.m3u8")
            check(hls.listFiles().orEmpty().all { it.isFile && it.name in allowedHls })
            val manifest = SyntheticHls.create(multi, hls)
            main {
                player!!.setMediaItem(MediaItem.fromUri(Uri.fromFile(manifest)))
                player!!.prepare(); player!!.play()
            }
            await { player!!.isPlaying }
            stage = "HLS variant overrides and Auto"
            val variants = main { TrackControls.options(player!!.currentTracks, C.TRACK_TYPE_AUDIO)
                .filter { it.group != null && it.supported } }
            check(variants.size == 2 && variants.map { it.key }.distinct().size == 2)
            val hlsItem = main { player!!.currentMediaItem }
            for (variant in variants) {
                main { TrackControls.apply(player!!, C.TRACK_TYPE_AUDIO, variant.key); player!!.seekTo(0); player!!.play() }
                await { player!!.currentTracks.groups.any { group -> group.type == C.TRACK_TYPE_AUDIO &&
                    (0 until group.length).any { group.isTrackSelected(it) && TrackControls.identity(group.getTrackFormat(it)) == variant.key } } }
                await { player!!.isPlaying }
                await { player!!.currentPosition >= 500 && player!!.isPlaying }
                check(main { player!!.currentMediaItem == hlsItem && player!!.playerError == null })
            }
            main { TrackControls.apply(player!!, C.TRACK_TYPE_AUDIO, "auto") }
            check(main { player!!.trackSelectionParameters.overrides.isEmpty() })
            stage = "local fragmented DASH representations"
            check(dash.mkdirs() || dash.isDirectory)
            check(dash.listFiles().orEmpty().all { it.isFile && it.name in setOf("init.mp4", "segment.m4s", "manifest.mpd") })
            val dashManifest = SyntheticDash.create(multi, dash)
            stage = "DASH initial playback"
            main {
                player!!.setMediaItem(MediaItem.fromUri(Uri.fromFile(dashManifest)))
                player!!.prepare(); player!!.play()
            }
            await { player!!.isPlaying }
            stage = "DASH representation inventory"
            val representations = main { TrackControls.options(player!!.currentTracks, C.TRACK_TYPE_AUDIO).filter { it.group != null && it.supported } }
            check(representations.size == 2 && representations.map { it.key }.distinct().size == 2)
            val dashItem = main { player!!.currentMediaItem }
            for (representation in representations) {
                stage = "DASH selected representation playback"
                main { TrackControls.apply(player!!, C.TRACK_TYPE_AUDIO, representation.key); player!!.seekTo(0); player!!.play() }
                await { player!!.currentTracks.groups.any { group -> group.type == C.TRACK_TYPE_AUDIO &&
                    (0 until group.length).any { group.isTrackSelected(it) && TrackControls.identity(group.getTrackFormat(it)) == representation.key } } }
                await { player!!.isPlaying && player!!.currentPosition >= 500 }
                check(main { player!!.currentMediaItem == dashItem && player!!.playerError == null })
            }
            main { TrackControls.apply(player!!, C.TRACK_TYPE_AUDIO, "auto") }
            check(main { player!!.trackSelectionParameters.overrides.isEmpty() })
            main { player?.release(); player = null }
            stage = "loopback HTTP HLS starvation, spinner and missing-segment Retry"
            HttpHlsChecks(runner).run(java.io.File(hls, "tone.aac").readBytes())
            report.putString("summary", "PASS: local fragmented audio DASH two representations, actual selection/playback progression and Auto; prior tracks and HTTP HLS buffering/404/Retry matrix. No private streams/catalog changes; no bandwidth-driven adaptation/live/video DASH claim.")
            subtitle.delete(); audio.delete(); multi.delete()
            hls.listFiles().orEmpty().forEach { check(it.delete()) }; check(hls.delete())
            dash.listFiles().orEmpty().forEach { check(it.delete()) }; check(dash.delete())
            runner.finish(-1, report)
        } catch (error: Throwable) {
            report.putString("location", error.stackTrace.firstOrNull { it.className == javaClass.name }?.lineNumber?.toString() ?: error.javaClass.simpleName)
            report.putString("fixtureLocation", error.stackTrace.firstOrNull { it.className == SyntheticDash::class.java.name }?.lineNumber?.toString() ?: "none")
            report.putString("playerCode", main { player?.playerError?.errorCodeName ?: "none" })
            main { player?.release(); player = null }
            report.putString("failure", "Synthetic track check failed at $stage; details suppressed")
            runner.finish(0, report)
        }
    }
}

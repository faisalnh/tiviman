package faisalnh.tiviman

import android.app.Instrumentation
import android.content.ContextWrapper
import android.os.Bundle
import java.io.File
import java.security.MessageDigest

/** Storage-only isolation checks: no Activities, providers or streams. */
class MultiPlaylistChecks(private val runner: Instrumentation) {
    fun run() {
        val result = Bundle()
        fun digest() = runner.targetContext.noBackupFilesDir.walkTopDown().filter { it.isFile }
            .associate { it.relativeTo(runner.targetContext.noBackupFilesDir).path to MessageDigest.getInstance("SHA-256").digest(it.readBytes()).toList() }
        val before = digest()
        val root = File(runner.targetContext.cacheDir, "multi-playlist-${System.nanoTime()}")
        try {
            check(root.mkdirs())
            val context = object : ContextWrapper(runner.targetContext) { override fun getNoBackupFilesDir() = root }
            val a = "#EXTM3U\n#EXTINF:-1 tvg-id=\"one\",First\nhttps://example.invalid/first"
            val b = a.replace("First", "Second").replace("/first", "/second")
            val legacy = PlaylistStore(context)
            legacy.save(a)
            val first = legacy.snapshot()
            legacy.saveFavorites(first.revision, setOf(0))
            legacy.saveHistory(first.revision, listOf(0))
            legacy.saveTracks(first.revision, mapOf("0" to "test"))
            val guide = EpgData(listOf(EpgEvent("one", "Example", Epg.minute(), Epg.minute()+60, "")), emptyMap(), System.currentTimeMillis())
            legacy.saveEpg(first.revision, guide)
            fun rootBytes() = root.listFiles().orEmpty().filter { it.isFile && it.name != "playlists.enc" }
                .associate { it.name to it.readBytes().toList() }
            val original = rootBytes()
            val id = legacy.add("Second", b, guide = guide, activate = false)
            check(PlaylistStore(context).playlistId == "legacy")
            check(rootBytes() == original)
            check(legacy.playlists().size == 2)
            legacy.select(id)
            val secondStore = PlaylistStore(context)
            val second = secondStore.snapshot()
            check(second.channels!!.single().name == "Second" && second.favorites.isEmpty() && second.history.isEmpty() && second.tracks.isEmpty())
            check(secondStore.loadEpg(second.revision)?.events == guide.events)
            secondStore.saveFavorites(second.revision, emptySet())
            // A late write pinned before switch must still hit the old playlist.
            legacy.saveHistory(first.revision, listOf(0))
            val afterLateSave = rootBytes()
            secondStore.select("legacy")
            val returned = PlaylistStore(context).snapshot()
            check(returned.revision == first.revision && returned.favorites == setOf(0) && returned.history == listOf(0) && returned.tracks == mapOf("0" to "test"))
            check(PlaylistStore(context).loadEpg(first.revision)?.events == guide.events)
            check(rootBytes() == afterLateSave)
            val count = legacy.playlists().size
            try { legacy.add("Bad", "not a playlist"); error("Invalid import accepted") } catch (_: IllegalArgumentException) { }
            check(legacy.playlists().size == count && PlaylistStore(context).playlistId == "legacy")
            try { legacy.select("../bad"); error("Invalid selection accepted") } catch (_: IllegalArgumentException) { }
            fun isolatedBytes() = root.walkTopDown().filter { it.isFile }
                .associate { it.relativeTo(root).path to it.readBytes().toList() }
            fun rejected(operation: () -> Unit) {
                val saved = isolatedBytes()
                try { operation(); error("Invalid mutation accepted") } catch (_: IllegalArgumentException) { }
                check(isolatedBytes() == saved) { "Rejected mutation changed saved files" }
            }
            val registry = File(root, "playlists.enc")
            val legacyFiles = rootBytes()
            val secondFiles = File(root, "playlists/$id").walkTopDown().filter { it.isFile }
                .associate { it.name to it.readBytes().toList() }
            legacy.rename("legacy", "  Renamed original  ")
            check(PlaylistStore(context).playlists().first().name == "Renamed original")
            legacy.rename(id, "x".repeat(80))
            check(legacy.playlists().last().name.length == 80)
            rejected { legacy.rename(id, "  \t ") }
            rejected { legacy.rename(id, "x".repeat(81)) }
            rejected { legacy.rename("../bad", "Unknown") }
            legacy.reorder(id, 0)
            check(PlaylistStore(context).playlists().map { it.id } == listOf(id, "legacy"))
            check(PlaylistStore(context).playlistId == "legacy")
            legacy.reorder(id, 1)
            legacy.reorder(id, 1)
            check(legacy.playlists().map { it.id } == listOf("legacy", id))
            rejected { legacy.reorder(id, -1) }
            rejected { legacy.reorder(id, 2) }
            rejected { legacy.reorder("../bad", 0) }
            rejected { legacy.delete("legacy") }
            rejected { legacy.delete("../bad") }
            check(rootBytes() == legacyFiles)
            check(File(root, "playlists/$id").walkTopDown().filter { it.isFile }
                .associate { it.name to it.readBytes().toList() } == secondFiles)

            // Independent instances must not lose each other's registry updates.
            val third = legacy.add("Third", a, activate = false)
            val fourth = legacy.add("Fourth", a, activate = false)
            val gate = java.util.concurrent.CountDownLatch(1)
            val errors = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
            val threads = listOf(third to "Third renamed", fourth to "Fourth renamed").map { (target, name) ->
                Thread {
                    try { gate.await(); repeat(8) { PlaylistStore(context).rename(target, name) } }
                    catch (error: Throwable) { errors.add(error) }
                }.apply { start() }
            }
            gate.countDown()
            threads.forEach { it.join(10000); check(!it.isAlive) }
            check(errors.isEmpty())
            check(legacy.playlists().filter { it.id == third || it.id == fourth }.map { it.name }.toSet() == setOf("Third renamed", "Fourth renamed"))
            legacy.delete(third); legacy.delete(fourth)
            check(!File(root, "playlists/$third").exists() && !File(root, "playlists/$fourth").exists())

            // Populate every scoped store before deleting the inactive UUID playlist.
            secondStore.saveHistory(second.revision, listOf(0))
            secondStore.saveTracks(second.revision, mapOf("0" to "test"))
            secondStore.saveDisplay(second.revision, mapOf("0" to DisplayMode.values().first().name))
            secondStore.saveNavigation(second.revision, GuideState(catalog = second.channels!!))
            legacy.delete(id)
            check(legacy.playlists().map { it.id } == listOf("legacy"))
            check(!File(root, "playlists/$id").exists())
            check(rootBytes() == legacyFiles && PlaylistStore(context).snapshot().revision == first.revision)
            rejected { secondStore.save(b) }
            rejected { secondStore.saveHistory(second.revision, listOf(0)) }
            rejected { secondStore.saveFavorites(second.revision, setOf(0)) }
            rejected { secondStore.saveTracks(second.revision, mapOf("0" to "late")) }
            rejected { secondStore.saveDisplay(second.revision, emptyMap()) }
            rejected { secondStore.saveEpg(second.revision, guide) }
            rejected { legacy.select(id) }
            rejected { legacy.delete("legacy") }
            rejected { legacy.rename(id, "Deleted") }
            rejected { legacy.reorder(id, 0) }
            check(!File(root, "playlists/$id").exists())

            // Legacy removal deletes only its known stores, never the registry or UUID sibling.
            val survivor = legacy.add("Survivor", b)
            val survivorRevision = PlaylistStore(context).snapshot().revision
            legacy.delete("legacy")
            check(registry.exists() && rootBytes().isEmpty())
            check(PlaylistStore(context).playlists() == listOf(PlaylistStore.Entry(survivor, "Survivor")))
            check(PlaylistStore(context).snapshot().revision == survivorRevision)
            rejected { legacy.save(a) }
            rejected { PlaylistStore(context).delete(survivor) }
            check(digest() == before)
            result.putString("summary", "PASS: isolated legacy/add/select/scoped data; rename limits, reorder bounds, concurrent registry mutations, inactive deletion/cleanup, active and last deletion rejected, stale writes rejected; private files unchanged; no network/player")
            runner.finish(-1, result)
        } catch (error: Exception) {
            result.putString("failure", error.javaClass.simpleName)
            result.putString("location", error.stackTrace.firstOrNull { it.className.startsWith("faisalnh.tiviman") }?.let { "${it.fileName}:${it.lineNumber}" })
            result.putBoolean("saved_files_unchanged", before == digest())
            runner.finish(0, result)
        } finally {
            root.deleteRecursively()
        }
    }
}

package faisalnh.tiviman

import android.app.Instrumentation
import android.app.job.JobScheduler
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import java.io.File
import java.security.MessageDigest

/**
 * Worker/config checks with an isolated storage root, a fake scheduler and a fake fetcher.
 * No real JobScheduler job is created (the isolated context throws if the real scheduler is requested),
 * no network is used, and the app's real files and real pending jobs are compared before/after.
 */
class EpgRefreshChecks(private val runner: Instrumentation) {
    private class IsolatedContext(base: Context, private val root: File) : ContextWrapper(base) {
        override fun getNoBackupFilesDir(): File = root
        override fun getFilesDir(): File = File(root, "files").also { it.mkdirs() }
        override fun getApplicationContext(): Context = this
        override fun getSystemService(name: String): Any? {
            check(name != Context.JOB_SCHEDULER_SERVICE) { "real JobScheduler requested" }
            return super.getSystemService(name)
        }
    }

    private class FakeJobs : EpgJobs {
        val jobs = linkedMapOf<Int, String>()
        var failSchedule = false
        override fun pending() = jobs.map { PendingJob(it.key, it.value) }
        override fun schedule(jobId: Int, owner: String): Boolean { if (failSchedule) return false; jobs[jobId] = owner; return true }
        override fun cancel(jobId: Int) { jobs.remove(jobId) }
    }

    fun run() {
        val result = Bundle()
        val real = runner.targetContext
        fun digest() = real.noBackupFilesDir.walkTopDown().filter { it.isFile }
            .associate { it.relativeTo(real.noBackupFilesDir).path to MessageDigest.getInstance("SHA-256").digest(it.readBytes()).toList() }
        fun realJobs() = real.getSystemService(JobScheduler::class.java).allPendingJobs.map { it.id }
            .filter { EpgRefreshPolicy.slotOf(it) != null }.sorted()
        val beforeFiles = digest()
        val beforeJobs = realJobs()
        val root = File(real.cacheDir, "epg-refresh-${System.nanoTime()}")
        var stage = "setup"
        var failure: String? = null
        try {
            check(root.mkdirs())
            val context = IsolatedContext(real, root)
            val store = PlaylistStore(context)
            val jobs = FakeJobs()
            val engine = EpgRefreshEngine(context, jobs)
            val m3u = "#EXTM3U\n#EXTINF:-1 tvg-id=\"one\",First\nhttps://example.invalid/first"
            val secret = "SECRETQUERYVALUE"
            val url = "https://example.invalid/guide.xml?token=$secret"
            val a = store.add("One", m3u)
            val b = store.add("Two", m3u.replace("First", "Second"), activate = false)

            fun data(importedAt: Long, mappings: Map<Int, String> = emptyMap()) =
                EpgData(listOf(EpgEvent("one", "T", Epg.minute(), Epg.minute() + 60, "")), emptyMap(), importedAt, mappings)
            fun revision(id: String) = PlaylistStore(context, id).snapshot().revision
            fun epgBytes(id: String) = File(root, "playlists/$id/epg.enc").takeIf { it.exists() }?.readBytes()?.toList()
            fun cfgBytes(id: String) = File(root, "epg-refresh/$id.enc").takeIf { it.exists() }?.readBytes()?.toList()
            fun saved(id: String) = PlaylistStore(context, id).loadEpg(revision(id))
            fun refresh(id: String, jobId: Int? = null, canceled: () -> Boolean = { false }, fetch: EpgFetcher) =
                engine.run(id, jobId, fetch, canceled)
            fun noFetch() = EpgFetcher { _, _, _ -> error("fetch must not run") }
            fun throwsState(block: () -> Unit) = try { block(); false } catch (_: IllegalStateException) { true }

            stage = "enable stores encrypted source and schedules id-only job"
            engine.enable(a, EpgRefresh.Source.Url(url))
            check(jobs.jobs.size == 1 && jobs.jobs.values.single() == a)
            check(EpgRefreshPolicy.slotOf(jobs.jobs.keys.single()) != null)
            check(cfgBytes(a) != null && !String(File(root, "epg-refresh/$a.enc").readBytes(), Charsets.ISO_8859_1).contains(secret))
            check(engine.status(a)?.lastSuccessAt == 0L && engine.isEnabled(a) && !engine.isEnabled(b))

            stage = "first refresh"
            var seenUrl: String? = null
            var seenMappings: Map<Int, String>? = null
            check(refresh(a) { u, _, m -> seenUrl = u; seenMappings = m; data(5) } == EpgRefresh.Outcome.UPDATED)
            check(seenUrl == url && seenMappings == emptyMap<Int, String>() && saved(a)?.importedAt == 5L)
            check(engine.status(a)!!.lastSuccessAt > 0)

            stage = "importedAt is monotonic"
            check(refresh(a) { _, _, _ -> data(1) } == EpgRefresh.Outcome.UPDATED)
            check(saved(a)?.importedAt == 6L)

            stage = "failure keeps cache and hides message"
            var before = epgBytes(a)
            check(refresh(a) { _, _, _ -> throw IllegalStateException("boom $secret") } == EpgRefresh.Outcome.FAILED)
            check(epgBytes(a) == before)
            check(engine.status(a)!!.lastFailure == "IllegalStateException")

            stage = "empty stored mappings are authoritative over config fallback"
            engine.updateMappings(a, mapOf(0 to "cfg"))
            check(refresh(a) { _, _, m -> seenMappings = m; data(1) } == EpgRefresh.Outcome.UPDATED)
            check(seenMappings == emptyMap<Int, String>() && saved(a)?.mappings == emptyMap<Int, String>())
            val cur = saved(a)!!
            PlaylistStore(context, a).saveEpg(revision(a), cur.copy(mappings = mapOf(0 to "m"), importedAt = Epg.monotonic(System.currentTimeMillis(), cur.importedAt)))
            check(refresh(a) { _, _, m -> seenMappings = m; data(1) } == EpgRefresh.Outcome.UPDATED)
            check(seenMappings == mapOf(0 to "m") && saved(a)?.mappings == mapOf(0 to "m"))

            stage = "cancel before and during fetch"
            before = epgBytes(a)
            check(refresh(a, canceled = { true }, fetch = noFetch()) == EpgRefresh.Outcome.CANCELED)
            var flag = false
            check(refresh(a, canceled = { flag }) { _, _, _ -> flag = true; data(1) } == EpgRefresh.Outcome.CANCELED)
            check(epgBytes(a) == before)

            stage = "disable during flight cannot commit"
            check(refresh(a) { _, _, _ -> engine.disable(a); data(1) } == EpgRefresh.Outcome.DISABLED)
            check(epgBytes(a) == before && cfgBytes(a) == null && jobs.jobs.isEmpty())

            stage = "replace during flight cannot commit"
            engine.enable(a, EpgRefresh.Source.Url(url))
            check(refresh(a) { _, _, _ -> engine.enable(a, EpgRefresh.Source.Url("https://example.invalid/other.xml")); data(1) } == EpgRefresh.Outcome.STALE)
            check(epgBytes(a) == before && engine.isEnabled(a) && jobs.jobs.size == 1)

            stage = "disable then re-enable during flight cannot commit"
            check(refresh(a) { _, _, _ -> engine.disable(a); engine.enable(a, EpgRefresh.Source.Url(url)); data(1) } == EpgRefresh.Outcome.STALE)
            check(epgBytes(a) == before)

            stage = "concurrent mapping write wins"
            check(refresh(a) { _, _, _ ->
                val g = saved(a)!!
                PlaylistStore(context, a).saveEpg(revision(a), g.copy(mappings = mapOf(0 to "race"), importedAt = Epg.monotonic(System.currentTimeMillis(), g.importedAt)))
                data(1)
            } == EpgRefresh.Outcome.STALE)
            check(saved(a)?.mappings == mapOf(0 to "race"))

            stage = "schedule failure preserves previous config"
            jobs.jobs.clear()
            val cfgBefore = cfgBytes(a)
            jobs.failSchedule = true
            check(throwsState { engine.enable(a, EpgRefresh.Source.Url("https://example.invalid/new.xml")) })
            jobs.failSchedule = false
            check(cfgBytes(a) == cfgBefore && jobs.jobs.isEmpty() && engine.isEnabled(a))

            stage = "mismatched job id is rejected without fetch"
            engine.enable(a, EpgRefresh.Source.Url(url))
            val slotJob = jobs.jobs.keys.single()
            val wrong = EpgRefreshPolicy.jobId((EpgRefreshPolicy.slotOf(slotJob)!! + 1) % EpgRefreshPolicy.MAX_JOBS)
            check(refresh(a, jobId = wrong, fetch = noFetch()) == EpgRefresh.Outcome.JOB_MISMATCH && jobs.jobs.keys.single() == slotJob)

            stage = "many playlists get unique job slots"
            val extras = (0 until 12).map { i -> store.add("P$i", m3u.replace("First", "P$i"), activate = false) }
            extras.forEach { engine.enable(it, EpgRefresh.Source.Url(url)) }
            check(jobs.jobs.size == 13 && jobs.jobs.values.toSet().size == 13)

            stage = "reconcile restores missing jobs and drops orphans"
            val expected = jobs.jobs.toMap()
            jobs.jobs.clear()
            val free = (0 until EpgRefreshPolicy.MAX_JOBS).first { EpgRefreshPolicy.jobId(it) !in expected }
            jobs.jobs[EpgRefreshPolicy.jobId(free)] = "00000000-0000-0000-0000-000000000000"
            check(engine.reconcile() == 13)
            check(jobs.jobs == expected)

            stage = "deleted playlist is stale and cleaned"
            store.select(b)
            store.delete(a)
            check(refresh(a, fetch = noFetch()) == EpgRefresh.Outcome.STALE)
            check(cfgBytes(a) == null && jobs.jobs.values.none { it == a })

            stage = "playlist deleted during flight"
            val victim = extras.first()
            check(refresh(victim) { _, _, _ -> store.delete(victim); data(1) } == EpgRefresh.Outcome.STALE)
            check(cfgBytes(victim) == null && jobs.jobs.values.none { it == victim } && !File(root, "playlists/$victim").exists())
        } catch (error: Throwable) {
            failure = "$stage: ${error.javaClass.simpleName}: ${error.message}"
        } finally {
            root.deleteRecursively()
        }
        if (failure == null && (digest() != beforeFiles || realJobs() != beforeJobs)) failure = "real app files or jobs changed"
        if (failure == null) {
            result.putString("summary", "PASS: EPG refresh config, stale-commit guards and scheduler logic verified with fake scheduler/fetcher; real files and jobs unchanged.")
            runner.finish(-1, result)
        } else {
            result.putString("failure", failure)
            runner.finish(0, result)
        }
    }
}

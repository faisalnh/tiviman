package faisalnh.tiviman

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.PersistableBundle
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Serializes config mutation and worker commits. Never held across network I/O or ProviderHttp.cancel(). */
private val refreshLock = Any()
private val keyLock = Any()

/** Pure scheduling policy (no Android types) so it can be unit tested on the JVM. */
object EpgRefreshPolicy {
    const val MAX_JOBS = 32
    const val JOB_ID_BASE = 41000
    const val INTERVAL_MS = 6L * 60 * 60 * 1000
    private val uuid = Regex("[a-f0-9-]{36}")

    /** Same id grammar PlaylistStore accepts, so ids are also safe as file names. */
    fun isValidId(id: String) = id == "legacy" || uuid.matches(id)
    fun jobId(slot: Int): Int { require(slot in 0 until MAX_JOBS); return JOB_ID_BASE + slot }
    fun slotOf(jobId: Int): Int? = (jobId - JOB_ID_BASE).takeIf { it in 0 until MAX_JOBS }

    /**
     * Unique slot per playlist. Keeps [current] when still free; otherwise starts at the stable
     * String.hashCode() slot and probes linearly past slots [taken] by other playlists. Null when all 32 are used.
     */
    fun assignSlot(id: String, taken: Set<Int>, current: Int? = null): Int? {
        if (current != null && current in 0 until MAX_JOBS && current !in taken) return current
        val start = Math.floorMod(id.hashCode(), MAX_JOBS)
        for (step in 0 until MAX_JOBS) {
            val slot = (start + step) % MAX_JOBS
            if (slot !in taken) return slot
        }
        return null
    }
}

internal data class PendingJob(val jobId: Int, val owner: String?)

/** Scheduling seam: production uses JobScheduler; instrumentation checks use a fake so no real job is ever created. */
internal interface EpgJobs {
    /** Only jobs inside the reserved id range. */
    fun pending(): List<PendingJob>
    fun schedule(jobId: Int, owner: String): Boolean
    fun cancel(jobId: Int)
}

/** Network seam. Production wraps [ProviderHttp.epg]. */
internal fun interface EpgFetcher {
    fun fetch(url: String, catalog: List<Channel>, mappings: Map<Int, String>): EpgData
}

private class AndroidJobs(private val context: Context) : EpgJobs {
    private val scheduler get() = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
    override fun pending() = scheduler.allPendingJobs.filter { EpgRefreshPolicy.slotOf(it.id) != null }
        .map { PendingJob(it.id, it.extras.getString(EpgRefresh.EXTRA_ID)) }
    override fun schedule(jobId: Int, owner: String): Boolean {
        val info = JobInfo.Builder(jobId, ComponentName(context, EpgRefreshService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPeriodic(EpgRefreshPolicy.INTERVAL_MS)
            .setPersisted(false) // persisting needs RECEIVE_BOOT_COMPLETED; reconcile() restores after reboot
            .setExtras(PersistableBundle().apply { putString(EpgRefresh.EXTRA_ID, owner) }) // id only: never the URL
            .build()
        return scheduler.schedule(info) == JobScheduler.RESULT_SUCCESS
    }
    override fun cancel(jobId: Int) = scheduler.cancel(jobId)
}

/**
 * Opt-in background XMLTV refresh, one JobScheduler periodic job per playlist (6 h, any network, not persisted).
 * Every method uses exactly the [Context] it is given (no applicationContext substitution): pass an application context.
 * Job extras carry only the playlist id; the source URL lives in an encrypted file per playlist.
 * All methods except [isEnabled]/[status] touch the Keystore/disk and the scheduler: call off the UI thread.
 */
object EpgRefresh {
    const val EXTRA_ID = "playlistId"

    sealed class Source {
        /** Explicit guide URL. Stored encrypted; [toString] never prints it. */
        class Url(val url: String) : Source() { override fun toString() = "Source.Url" }
        /** Derive the XMLTV URL from the playlist's saved Xtream account at refresh time (no second copy of credentials). */
        object SavedXtream : Source() { override fun toString() = "Source.SavedXtream" }
    }

    /** Never contains the URL. */
    data class Status(val usesSavedXtream: Boolean, val mappingCount: Int,
        val lastAttemptAt: Long, val lastSuccessAt: Long, val lastFailure: String?)

    enum class Outcome { UPDATED, DISABLED, STALE, NO_SOURCE, CANCELED, FAILED, JOB_MISMATCH }

    private fun engine(context: Context) = EpgRefreshEngine(context, AndroidJobs(context))

    fun isEnabled(context: Context, id: String): Boolean = engine(context).isEnabled(id)
    fun status(context: Context, id: String): Status? = engine(context).status(id)

    /**
     * Opt in, or replace the source of, a playlist. Replacing starts a new generation: any in-flight refresh of the
     * old source is discarded at commit. Throws IllegalArgumentException (unknown/invalid playlist, bad URL, non-Xtream
     * playlist with [Source.SavedXtream], no free slot) or IllegalStateException (scheduler refused). On any failure
     * the previous enabled configuration is left exactly as it was.
     */
    fun enable(context: Context, id: String, source: Source, mappings: Map<Int, String> = emptyMap()) =
        engine(context).enable(id, source, mappings)

    /**
     * Fallback mappings, used only while no guide is stored yet. Once a guide exists its own mappings
     * (even an empty map) are authoritative. No-op when not enabled.
     */
    fun updateMappings(context: Context, id: String, mappings: Map<Int, String>) = engine(context).updateMappings(id, mappings)

    /** Opt out: deletes the encrypted source and cancels the job; an in-flight refresh cannot commit afterwards. Idempotent. */
    fun disable(context: Context, id: String) = engine(context).disable(id)

    /** Call after PlaylistStore.delete(id) succeeds. Same as [disable]. */
    fun onPlaylistDeleted(context: Context, id: String) = disable(context, id)

    /**
     * Call once at app start (jobs are not persisted across reboot). Drops sources of deleted playlists, cancels orphan
     * or mis-slotted jobs, repairs duplicate slots and re-schedules enabled playlists whose job is missing.
     * Returns the number of jobs scheduled.
     */
    fun reconcile(context: Context): Int = engine(context).reconcile()

    /** Run one refresh now on the calling (background) thread. [canceled] is polled; interrupts are honored. */
    fun refreshNow(context: Context, id: String, http: ProviderHttp = ProviderHttp(), canceled: () -> Boolean = { false }): Outcome =
        engine(context).run(id, null, EpgFetcher { url, catalog, mappings -> http.epg(url, catalog, mappings) }, canceled)

    internal fun runJob(context: Context, id: String, jobId: Int, http: ProviderHttp, canceled: () -> Boolean): Outcome =
        engine(context).run(id, jobId, EpgFetcher { url, catalog, mappings -> http.epg(url, catalog, mappings) }, canceled)
}

/** Never stringify: contains the source URL. */
internal data class EpgRefreshConfig(val token: String, val slot: Int, val xtream: Boolean, val url: String,
    val mappings: Map<Int, String>, val lastAttempt: Long = 0, val lastSuccess: Long = 0,
    val lastFailure: String? = null) {
    override fun toString() = "EpgRefreshConfig"
}

internal class EpgRefreshEngine(private val context: Context, private val jobs: EpgJobs) {
    private val alias = "fixturetv.playlist.v1"

    // ---- Control -----------------------------------------------------------------------------------------

    fun isEnabled(id: String) = EpgRefreshPolicy.isValidId(id) && file(id).baseFile.exists()

    fun status(id: String): EpgRefresh.Status? {
        if (!EpgRefreshPolicy.isValidId(id)) return null
        val c = runCatching { load(id) }.getOrNull() ?: return null
        return EpgRefresh.Status(c.xtream, c.mappings.size, c.lastAttempt, c.lastSuccess, c.lastFailure)
    }

    fun enable(id: String, source: EpgRefresh.Source, mappings: Map<Int, String> = emptyMap()) {
        require(EpgRefreshPolicy.isValidId(id)) { "Invalid playlist id" }
        require(PlaylistStore(context, id).playlists().any { it.id == id }) { "Unknown playlist" }
        val clean = validMappings(mappings)
        val xtream: Boolean; val url: String
        when (source) {
            is EpgRefresh.Source.Url -> { Playlist.url(source.url); xtream = false; url = source.url }
            EpgRefresh.Source.SavedXtream -> {
                val provider = PlaylistStore(context, id).provider()
                require(provider?.type == SourceType.XTREAM) { "Playlist has no saved Xtream account" }
                xtream = true; url = ""
            }
        }
        // Scheduling happens before the config swap and both are inside the lock: the worker (which loads config under
        // the same lock) sees the old state or the new state, and a refused/failed schedule leaves the old config untouched.
        synchronized(refreshLock) {
            prune()
            val pending = jobs.pending()
            val existing = runCatching { load(id) }.getOrNull()
            val own = pending.filter { it.owner == id }
            val current = existing?.slot ?: own.firstNotNullOfOrNull { EpgRefreshPolicy.slotOf(it.jobId) }
            val slot = requireNotNull(EpgRefreshPolicy.assignSlot(id, takenSlots(id, pending), current)) { "No free refresh slot" }
            val jobId = EpgRefreshPolicy.jobId(slot)
            val needSchedule = own.none { it.jobId == jobId }
            if (needSchedule) check(jobs.schedule(jobId, id)) { "Could not schedule EPG refresh" }
            try {
                save(id, EpgRefreshConfig(UUID.randomUUID().toString(), slot, xtream, url, clean))
            } catch (error: Exception) {
                if (needSchedule) jobs.cancel(jobId)
                throw error
            }
            own.filter { it.jobId != jobId }.forEach { jobs.cancel(it.jobId) }
        }
    }

    fun updateMappings(id: String, mappings: Map<Int, String>) {
        require(EpgRefreshPolicy.isValidId(id))
        val clean = validMappings(mappings)
        synchronized(refreshLock) {
            val c = load(id) ?: return
            save(id, c.copy(mappings = clean)) // token unchanged: the commit-time mapping comparison handles the race
        }
    }

    fun disable(id: String) {
        if (!EpgRefreshPolicy.isValidId(id)) return
        synchronized(refreshLock) { dropLocked(id) }
    }

    /** Scheduler cancel is a short non-network call; kept inside the lock so a concurrent enable() cannot be cancelled by us. */
    private fun dropLocked(id: String) {
        deleteConfig(id)
        jobs.pending().filter { it.owner == id }.forEach { jobs.cancel(it.jobId) }
    }

    fun reconcile(): Int = synchronized(refreshLock) {
        prune()
        val pending = jobs.pending()
        val ids = configIds()
        val readable = ids.mapNotNull { id -> runCatching { load(id) }.getOrNull()?.let { id to it } }.sortedBy { it.first }
        val unreadable = ids.toSet() - readable.map { it.first }.toSet()
        // Slots of jobs belonging to unreadable configs are unknown-but-occupied: never hand them out.
        val claimed = pending.filter { it.owner in unreadable }.mapNotNull { EpgRefreshPolicy.slotOf(it.jobId) }.toMutableSet()
        var count = 0
        for ((id, original) in readable) {
            var config = original
            if (config.slot in claimed) {
                val slot = EpgRefreshPolicy.assignSlot(id, claimed) ?: continue
                config = config.copy(slot = slot)
                if (runCatching { save(id, config) }.isFailure) continue
            }
            claimed.add(config.slot)
            val jobId = EpgRefreshPolicy.jobId(config.slot)
            pending.filter { it.owner == id && it.jobId != jobId }.forEach { jobs.cancel(it.jobId) }
            if (pending.none { it.jobId == jobId && it.owner == id } && jobs.schedule(jobId, id)) count++
        }
        count
    }

    // ---- Worker ------------------------------------------------------------------------------------------

    fun run(id: String, jobId: Int?, fetcher: EpgFetcher, canceled: () -> Boolean): EpgRefresh.Outcome {
        fun stopped() = canceled() || Thread.currentThread().isInterrupted
        if (!EpgRefreshPolicy.isValidId(id)) return EpgRefresh.Outcome.JOB_MISMATCH
        val config = synchronized(refreshLock) { runCatching { load(id) } }.getOrElse { return EpgRefresh.Outcome.FAILED }
        if (config == null) {
            if (jobId != null) synchronized(refreshLock) { if (!file(id).baseFile.exists()) cancelOwned(id, jobId) }
            return EpgRefresh.Outcome.DISABLED
        }
        if (jobId != null && jobId != EpgRefreshPolicy.jobId(config.slot)) {
            synchronized(refreshLock) { cancelOwned(id, jobId) }
            return EpgRefresh.Outcome.JOB_MISMATCH
        }
        val token = config.token
        try {
            if (stopped()) return EpgRefresh.Outcome.CANCELED
            val store = PlaylistStore(context, id)
            if (store.playlists().none { it.id == id }) { dropIfToken(id, token); return EpgRefresh.Outcome.STALE }
            val snapshot = store.snapshot()
            val catalog = snapshot.channels ?: run { recordFailure(id, token, "NoCatalog"); return EpgRefresh.Outcome.NO_SOURCE }
            val url = if (config.xtream) {
                val provider = snapshot.provider?.takeIf { it.type == SourceType.XTREAM }
                    ?: run { recordFailure(id, token, "NoXtreamAccount"); return EpgRefresh.Outcome.NO_SOURCE }
                Providers(provider).xmltvUrl()
            } else config.url
            // A stored guide's mappings are authoritative, including an empty map; config mappings only seed a first import.
            val prior = store.loadEpg(snapshot.revision)
            val priorMappings = prior?.mappings ?: config.mappings
            val priorImported = prior?.importedAt
            if (stopped()) return EpgRefresh.Outcome.CANCELED
            val fresh = fetcher.fetch(url, catalog, priorMappings) // network: no lock held
            return synchronized(refreshLock) {
                if (stopped()) return@synchronized EpgRefresh.Outcome.CANCELED
                val now = try { load(id) } catch (_: Exception) { return@synchronized EpgRefresh.Outcome.FAILED }
                    ?: return@synchronized EpgRefresh.Outcome.DISABLED
                if (now.token != token) return@synchronized EpgRefresh.Outcome.STALE // disabled then re-enabled, or source replaced
                if (store.playlists().none { it.id == id }) { dropLocked(id); return@synchronized EpgRefresh.Outcome.STALE }
                if (store.snapshot().revision != snapshot.revision) return@synchronized EpgRefresh.Outcome.STALE
                val current = store.loadEpg(snapshot.revision)
                if ((current?.mappings ?: now.mappings) != priorMappings || current?.importedAt != priorImported) {
                    return@synchronized EpgRefresh.Outcome.STALE // someone saved a guide or mapping while we downloaded
                }
                store.saveEpg(snapshot.revision, fresh.copy(mappings = priorMappings,
                    importedAt = Epg.monotonic(fresh.importedAt, priorImported)))
                runCatching { save(id, now.copy(lastAttempt = System.currentTimeMillis(), lastSuccess = System.currentTimeMillis(), lastFailure = null)) }
                EpgRefresh.Outcome.UPDATED
            }
        } catch (error: Exception) {
            if (stopped()) return EpgRefresh.Outcome.CANCELED
            recordFailure(id, token, error.javaClass.simpleName) // class name only: messages may embed URLs
            return EpgRefresh.Outcome.FAILED
        }
    }

    private fun cancelOwned(id: String, jobId: Int) {
        if (jobs.pending().any { it.jobId == jobId && it.owner == id }) jobs.cancel(jobId)
    }

    private fun dropIfToken(id: String, token: String) = synchronized(refreshLock) {
        if (runCatching { load(id) }.getOrNull()?.token == token) dropLocked(id)
    }

    private fun recordFailure(id: String, token: String, failure: String) = synchronized(refreshLock) {
        val c = runCatching { load(id) }.getOrNull() ?: return@synchronized
        if (c.token != token) return@synchronized
        runCatching { save(id, c.copy(lastAttempt = System.currentTimeMillis(), lastFailure = failure)) }
        Unit
    }

    // ---- Scheduling helpers (call with refreshLock held) -------------------------------------------------

    /**
     * Removes sources of deleted playlists (only when the registry is readable) and cancels jobs that have no source,
     * belong to a source with another slot, or carry no owner. Jobs of unreadable-but-present configs are kept.
     */
    private fun prune() {
        val known = runCatching { PlaylistStore(context).playlists().map { it.id }.toSet() }.getOrNull()
        if (known != null) configIds().filter { it !in known }.forEach { deleteConfig(it) }
        val have = configIds().toSet()
        val readable = readableConfigs()
        jobs.pending().filter { job ->
            job.owner == null || job.owner !in have ||
                readable[job.owner]?.let { EpgRefreshPolicy.jobId(it.slot) != job.jobId } == true
        }.forEach { jobs.cancel(it.jobId) }
    }

    /** Slots held by other playlists' readable configs or by their pending jobs (covers unreadable configs). */
    private fun takenSlots(id: String, pending: List<PendingJob>): Set<Int> {
        val taken = mutableSetOf<Int>()
        readableConfigs().forEach { (other, c) -> if (other != id) taken.add(c.slot) }
        pending.forEach { if (it.owner != id) EpgRefreshPolicy.slotOf(it.jobId)?.let(taken::add) }
        return taken
    }

    private fun readableConfigs(): Map<String, EpgRefreshConfig> =
        configIds().mapNotNull { id -> runCatching { load(id) }.getOrNull()?.let { id to it } }.toMap()

    private fun validMappings(input: Map<Int, String>): Map<Int, String> {
        require(input.size <= 100000) { "Too many mappings" }
        return input.mapValues { Epg.validMappingId(it.value) }
    }

    // ---- Encrypted per-playlist source file --------------------------------------------------------------

    private fun dir() = File(context.noBackupFilesDir, "epg-refresh")
    private fun file(id: String) = AtomicFile(File(dir(), "$id.enc"))
    private fun configIds(): List<String> =
        dir().list().orEmpty().filter { it.endsWith(".enc") }.map { it.removeSuffix(".enc") }.filter { EpgRefreshPolicy.isValidId(it) }

    private fun deleteConfig(id: String) { file(id).delete() }

    private fun key(): SecretKey = synchronized(keyLock) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    private fun save(id: String, c: EpgRefreshConfig) {
        dir().mkdirs()
        val maps = JSONObject(); c.mappings.forEach { (k, v) -> maps.put(k.toString(), v) }
        val json = JSONObject().put("v", 2).put("token", c.token).put("slot", c.slot).put("xtream", c.xtream).put("url", c.url)
            .put("mappings", maps).put("attempt", c.lastAttempt).put("success", c.lastSuccess)
            .put("failure", c.lastFailure ?: JSONObject.NULL)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()); updateAAD(id.toByteArray()) }
        val bytes = cipher.iv + cipher.doFinal(json.toString().toByteArray())
        val target = file(id)
        val out = target.startWrite()
        try { out.write(bytes); target.finishWrite(out) } catch (error: Exception) { target.failWrite(out); throw error }
    }

    /** Null when absent; throws when present but unreadable. The id is authenticated, so files cannot be swapped between playlists. */
    private fun load(id: String): EpgRefreshConfig? {
        val target = file(id)
        if (!target.baseFile.exists()) return null
        val bytes = target.readFully()
        require(bytes.size >= 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12))); updateAAD(id.toByteArray())
        }
        val json = JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
        val maps = json.optJSONObject("mappings")
        val mappings = maps?.keys()?.asSequence()?.associate { it.toInt() to maps.getString(it) }.orEmpty()
        val slot = json.getInt("slot"); require(slot in 0 until EpgRefreshPolicy.MAX_JOBS)
        val token = json.getString("token"); require(token.isNotBlank())
        return EpgRefreshConfig(token, slot, json.getBoolean("xtream"), json.getString("url"), mappings,
            json.optLong("attempt"), json.optLong("success"), if (json.isNull("failure")) null else json.optString("failure"))
    }
}

/** JobScheduler entry point. Work runs on one bounded, self-expiring thread; [onStopJob] cancels the in-flight request. */
class EpgRefreshService : JobService() {
    private class Running(val http: ProviderHttp) {
        @Volatile var stopped = false
        @Volatile var future: Future<*>? = null
    }
    private val running = ConcurrentHashMap<Int, Running>()
    private val executor = ThreadPoolExecutor(1, 1, 10, TimeUnit.SECONDS, LinkedBlockingQueue()).apply { allowCoreThreadTimeOut(true) }

    override fun onStartJob(params: JobParameters): Boolean {
        val id = params.extras.getString(EpgRefresh.EXTRA_ID)
        if (id == null || !EpgRefreshPolicy.isValidId(id) || EpgRefreshPolicy.slotOf(params.jobId) == null) return false
        val job = Running(ProviderHttp())
        if (running.putIfAbsent(params.jobId, job) != null) return false
        try {
            job.future = executor.submit {
                try {
                    if (!job.stopped) EpgRefresh.runJob(applicationContext, id, params.jobId, job.http) { job.stopped }
                } finally {
                    running.remove(params.jobId, job)
                    if (!job.stopped) jobFinished(params, false) // failures wait for the next 6 h period
                }
            }
        } catch (_: RejectedExecutionException) { running.remove(params.jobId, job); return false }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        running.remove(params.jobId)?.let(::stop)
        return true // constraints lost: let the system reschedule
    }

    override fun onDestroy() {
        running.values.forEach(::stop); running.clear()
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun stop(job: Running) {
        job.stopped = true
        job.future?.cancel(true)
        // disconnect() does socket I/O; keep it off the main thread. Short-lived, no leak.
        Thread { runCatching { job.http.cancel() } }.start()
    }
}

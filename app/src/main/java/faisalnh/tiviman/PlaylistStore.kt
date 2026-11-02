package faisalnh.tiviman

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** All playlist contents (including embedded stream credentials) encrypted at rest. */
class PlaylistStore(private val context: Context, requestedId: String? = null) {
    private val alias = "fixturetv.playlist.v1"
    private val registry = AtomicFile(File(context.noBackupFilesDir, "playlists.enc"))
    // Pin each instance, including background import/save work, to its original playlist.
    val playlistId = requestedId ?: synchronized(registryLock) { read(registry)?.optString("active")?.takeIf { it.isNotBlank() } ?: "legacy" }
        private var staging = false
    private val directory = if (playlistId == "legacy") context.noBackupFilesDir else {
        require(playlistId.matches(Regex("[a-f0-9-]{36}")))
        File(context.noBackupFilesDir, "playlists/$playlistId")
    }
    private val file = AtomicFile(File(directory, "playlist.enc"))
    private val navigation = AtomicFile(File(directory, "navigation.enc"))
    private val trackPreferences = AtomicFile(File(directory, "tracks.enc"))
    private val displayPreferences = AtomicFile(File(directory, "display.enc"))
    private val historyFile = AtomicFile(File(directory, "history.enc"))
    private val favoritesFile = AtomicFile(File(directory, "favorites.enc"))
    private val epgFile = AtomicFile(File(directory, "epg.enc"))
    data class Entry(val id: String, val name: String)
    fun playlists(): List<Entry> = synchronized(registryLock) {
        val rows = read(registry)?.optJSONArray("entries")
        if (rows != null) (0 until rows.length()).map { rows.getJSONObject(it).let { row -> Entry(row.getString("id"), row.getString("name")) } }
        else if (File(context.noBackupFilesDir, "playlist.enc").exists()) listOf(Entry("legacy", "Original playlist")) else emptyList()
    }
    private fun activeId() = read(registry)?.getString("active") ?: "legacy"
    private fun validatedName(name: String): String = name.trim().also { require(it.isNotEmpty() && it.length <= 80) }
    fun rename(id: String, name: String) = synchronized(registryLock) {
        val label = validatedName(name)
        val entries = playlists()
        require(entries.any { it.id == id }) { "Unknown playlist" }
        saveRegistry(entries.map { if (it.id == id) it.copy(name = label) else it }, activeId())
    }
    /** Move to a zero-based final position. Identity and active selection do not change. */
    fun reorder(id: String, position: Int) = synchronized(registryLock) {
        val entries = playlists().toMutableList()
        val from = entries.indexOfFirst { it.id == id }
        require(from >= 0 && position in entries.indices) { "Invalid playlist position" }
        entries.add(position, entries.removeAt(from))
        saveRegistry(entries, activeId())
    }
    /** Select another playlist first. Commit removal before best-effort encrypted-file cleanup. */
    fun delete(id: String) = synchronized(registryLock) {
        val entries = playlists()
        val active = activeId()
        require(entries.any { it.id == id }) { "Unknown playlist" }
        require(entries.size > 1 && id != active) { "Cannot delete the active or last playlist; switch first" }
        saveRegistry(entries.filterNot { it.id == id }, active)
        // A crash/cleanup failure may leave unreachable ciphertext, never a registered half-delete.
        val target = PlaylistStore(context, id)
        runCatching { target.clear() }
        if (id != "legacy") runCatching { target.directory.deleteRecursively() }
        Unit
    }
    private fun saveRegistry(entries: List<Entry>, active: String) {
        val rows = org.json.JSONArray()
        entries.forEach { rows.put(JSONObject().put("id", it.id).put("name", it.name)) }
        write(JSONObject().put("active", active).put("entries", rows), registry)
    }
    fun select(id: String) = synchronized(registryLock) {
        val entries = playlists()
        require(entries.any { it.id == id })
        require(PlaylistStore(context, id).snapshot().channels != null)
        saveRegistry(entries, id)
    }
    /** Commit index only after catalog and optional guide are durable. Legacy files never move. */
    fun add(name: String, text: String?, config: ProviderConfig? = null,
        channels: List<Channel>? = null, guide: EpgData? = null, activate: Boolean = true): String = synchronized(registryLock) {
        val entries = playlists()
        require(entries.size < 32)
        val label = validatedName(name)
        val id = java.util.UUID.randomUUID().toString()
        val target = PlaylistStore(context, id)
        target.directory.mkdirs()
        target.staging = true
        try {
            if (text != null) target.save(text) else target.saveProvider(requireNotNull(config), requireNotNull(channels))
            if (guide != null) target.saveEpg(target.snapshot().revision, guide)
        } finally { target.staging = false }
        val active = if (activate || entries.isEmpty()) id else read(registry)?.optString("active")?.takeIf { it.isNotBlank() } ?: "legacy"
        saveRegistry(entries + Entry(id, label), active)
        id
    }
    companion object { private val registryLock = Any() }
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun save(text: String) {
        Playlist.parse(text)
        write(JSONObject().put("m3u", text))
    }
    fun saveProvider(config: ProviderConfig, channels: List<Channel>) {
        require(channels.isNotEmpty())
        val rows = org.json.JSONArray()
        channels.forEach { rows.put(JSONObject().put("name", it.name).put("group", it.group).put("stream", it.stream).put("epgId", it.epgId)) }
        write(JSONObject().put("provider", config.json()).put("channels", rows))
    }
    private fun write(data: JSONObject, target: AtomicFile = file) = synchronized(registryLock) {
        // Serialize deletion with all pinned writes, including stores constructed before removal.
        if (target !== registry && !staging && read(registry) != null) {
            require(playlists().any { it.id == playlistId }) { "Playlist was deleted; saved data was not recreated" }
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val plain = data.toString().toByteArray()
        val bytes = cipher.iv + cipher.doFinal(plain)
        val out = target.startWrite()
        try { out.write(bytes); target.finishWrite(out) } catch (error: Exception) { target.failWrite(out); throw error }
    }
    private fun channels(data: JSONObject): List<Channel> {
        if (data.has("m3u")) return Playlist.parse(data.getString("m3u"))
        val rows = data.getJSONArray("channels")
        require(rows.length() in 1..100000)
        return (0 until rows.length()).map { index -> rows.getJSONObject(index).let {
            Channel(it.optInt("id", index), it.getString("name"), it.getString("group"), listOf(Programme("No information", 0, 180, "EPG import is not configured.")), it.getString("stream"), it.optString("epgId"))
        } }
    }
    /** One decrypt/parse pass, including provider settings. Never called from the UI thread. */
    fun snapshot(): SavedCatalog {
        val bytes = if (file.baseFile.exists()) file.readFully() else null
        val data = bytes?.let { decode(it) }
        val revision = data?.optString("stableRevision")?.takeIf { it.isNotBlank() } ?: bytes?.let { java.security.MessageDigest.getInstance("SHA-256").digest(it)
            .joinToString("") { b -> "%02x".format(b) } } ?: "fixtures-v1"
        val bookmark = try { read(navigation)?.takeIf { it.optString("revision") == revision }?.let {
            NavigationBookmark(it.optInt("group"), it.optInt("focusedGroup"), it.optInt("channel"),
                it.optInt("programme"), it.optInt("tuned"))
        } } catch (_: Exception) { null }
        val tracks = try { read(trackPreferences)?.takeIf { it.optString("revision") == revision }
            ?.optJSONObject("choices")?.let { choices -> choices.keys().asSequence().associateWith { choices.getString(it) } }
            ?: emptyMap() } catch (_: Exception) { emptyMap() }
        val display = try { read(displayPreferences)?.takeIf { it.optString("revision") == revision }
            ?.optJSONObject("choices")?.let { choices -> choices.keys().asSequence().associateWith { DisplayMode.parse(choices.optString(it)).name } }
            ?: emptyMap() } catch (_: Exception) { emptyMap() }
        val history = try { read(historyFile)?.takeIf { it.optString("revision") == revision }
            ?.optJSONArray("ids")?.let { ids -> (0 until minOf(ids.length(), 50)).map { ids.getInt(it) }.distinct() }
            ?: emptyList() } catch (_: Exception) { emptyList() }
        val favorites = try { read(favoritesFile)?.takeIf { it.optString("revision") == revision }
            ?.optJSONArray("ids")?.let { ids -> (0 until minOf(ids.length(), 100000)).map { ids.getInt(it) }.toSet() }
            ?: emptySet() } catch (_: Exception) { emptySet() }
        return SavedCatalog(data?.let { channels(it) }, data?.optJSONObject("provider")?.let { ProviderConfig.from(it) }, revision, bookmark, tracks, display, history, favorites)
    }
    fun saveFavorites(revision: String, ids: Set<Int>) {
        write(JSONObject().put("revision", revision).put("ids", org.json.JSONArray(ids.sorted())), favoritesFile)
    }
    /** Explicit saved-account refresh. One atomic catalog+guide commit; stable IDs keep scoped stores valid. */
    fun refreshXtream(expectedRevision: String, config: ProviderConfig, fresh: List<Channel>, guide: EpgData) {
        val current = snapshot()
        require(current.revision == expectedRevision && config.type == SourceType.XTREAM)
        val old = current.channels ?: error("No saved catalog")
        require(fresh.isNotEmpty() && fresh.map { it.stream }.distinct().size == fresh.size)
        val ids = old.associate { it.stream to it.id }
        var next = maxOf((old.maxOfOrNull { it.id } ?: -1) + 1, read()?.optInt("nextChannelId", 0) ?: 0)
        val rows = org.json.JSONArray()
        fresh.forEach { channel -> rows.put(JSONObject().put("id", ids[channel.stream] ?: next++)
            .put("name", channel.name).put("group", channel.group).put("stream", channel.stream).put("epgId", channel.epgId)) }
        val data = JSONObject().put("provider", config.json()).put("channels", rows).put("stableRevision", expectedRevision)
            .put("embeddedEpg", epgJson(expectedRevision, guide)).put("nextChannelId", next)
        write(data)
    }
    private fun epgJson(revision: String, data: EpgData): JSONObject {
        val rows = org.json.JSONArray()
        data.events.forEach { rows.put(org.json.JSONArray(listOf(it.channel, it.title, it.start, it.end, it.description))) }
        val names = JSONObject()
        data.names.forEach { (id, values) -> names.put(id, org.json.JSONArray(values.toList())) }
        return JSONObject().put("revision", revision).put("imported", data.importedAt).put("events", rows).put("names", names)
                    .put("mappings", JSONObject(data.mappings.mapKeys { it.key.toString() }))
    }
    fun saveEpg(revision: String, data: EpgData) {
        write(epgJson(revision, data), epgFile)
    }
    fun loadEpg(revision: String): EpgData? {
        val embedded = read()?.optJSONObject("embeddedEpg")?.takeIf { it.optString("revision") == revision }
        val separate = read(epgFile)?.takeIf { it.optString("revision") == revision }
        val data = listOfNotNull(embedded, separate).maxByOrNull { it.optLong("imported") } ?: return null
        val rows = data.getJSONArray("events"); require(rows.length() <= Epg.MAX_EVENTS)
        val names = data.getJSONObject("names")
        return EpgData((0 until rows.length()).map { i -> rows.getJSONArray(i).let {
            EpgEvent(it.getString(0), it.getString(1), it.getInt(2), it.getInt(3), it.getString(4))
        } }, names.keys().asSequence().associateWith { id -> names.getJSONArray(id).let { values ->
            (0 until values.length()).map { values.getString(it) }.toSet()
        } }, data.getLong("imported"), data.optJSONObject("mappings")?.let { mappings ->
            mappings.keys().asSequence().mapNotNull { id -> id.toIntOrNull()?.let { it to mappings.getString(id) } }.toMap()
        }.orEmpty())
    }
    fun saveHistory(revision: String, ids: List<Int>) {
        write(JSONObject().put("revision", revision).put("ids", org.json.JSONArray(ids.take(50))), historyFile)
    }
    fun saveDisplay(revision: String, choices: Map<String, String>) {
        write(JSONObject().put("revision", revision).put("choices", JSONObject(choices)), displayPreferences)
    }
    fun saveTracks(revision: String, choices: Map<String, String>) {
        write(JSONObject().put("revision", revision).put("choices", JSONObject(choices)), trackPreferences)
    }
    fun saveNavigation(revision: String, state: GuideState) {
        write(JSONObject().put("revision", revision).put("group", state.group).put("focusedGroup", state.focusedGroup)
            .put("channel", state.channelId).put("programme", state.programmeIndex).put("tuned", state.tunedId), navigation)
    }
    fun load(): List<Channel>? = read()?.let { channels(it) }
    fun provider(): ProviderConfig? = read()?.optJSONObject("provider")?.let { ProviderConfig.from(it) }
    fun suggestedEpgUrls(): List<String> {
        val data = read() ?: return emptyList()
        return data.optString("m3u").takeIf { it.isNotBlank() }?.let {
            Playlist.epgUrls(it)
        }.orEmpty()
    }
    private fun read(target: AtomicFile = file): JSONObject? {
        if (!target.baseFile.exists()) return null
        return decode(target.readFully())
    }
    private fun decode(bytes: ByteArray): JSONObject {
        require(bytes.size >= 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        }
        val data = JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
        return data
    }
    fun clear() = synchronized(registryLock) { file.delete(); navigation.delete(); trackPreferences.delete(); displayPreferences.delete(); historyFile.delete(); favoritesFile.delete(); epgFile.delete() }
}

data class SavedCatalog(val channels: List<Channel>?, val provider: ProviderConfig?, val revision: String,
    val bookmark: NavigationBookmark?, val tracks: Map<String, String> = emptyMap(),
    val display: Map<String, String> = emptyMap(), val history: List<Int> = emptyList(),
    val favorites: Set<Int> = emptySet())

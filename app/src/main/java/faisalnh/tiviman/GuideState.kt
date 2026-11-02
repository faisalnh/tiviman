package faisalnh.tiviman

enum class Pane { GUIDE, GROUPS, FULLSCREEN }
enum class RemoteKey { UP, DOWN, LEFT, RIGHT, CENTER, BACK, LONG_BACK }
data class Programme(val title: String, val start: Int, val duration: Int, val description: String) {
    val end: Int get() = start + duration
    fun isCurrentAt(minute: Int) = start <= minute && minute < end
    fun unavailableActionExplanation(minute: Int): String? = when {
        isCurrentAt(minute) -> null
        start > minute -> "Future programme. Actions unavailable in this prototype."
        else -> "Past programme. Playback unavailable in this prototype."
    }
}
data class Channel(val id: Int, val name: String, val group: String, val programmes: List<Programme>, val stream: String = "asset:///fixture.mp4", val epgId: String = "")

object Fixtures {
    const val NOW = 20 // Fixed 18:20 fixture clock, deliberately not real EPG.
    val groups = listOf("All channels", "Discovery", "Culture", "Family")
    val channels = (0 until 12).map { index ->
        val group = groups[1 + index % 3]
        val names = listOf("Horizon", "Atelier", "Wonder", "Field Notes", "Stage", "Bright Days",
            "Orbit", "Canvas", "Storybook", "Wild Trails", "Encore", "Playtime")
        val firstDuration = listOf(30, 45, 60)[index % 3]
        Channel(index, names[index], group, if (index == 7) listOf(
            Programme("No information", 0, 180, "Programme information is not available for this fixture channel.")
        ) else listOf(
            Programme("${names[index]}: Evening edition", 0, firstDuration,
                "An invented programme exploring new ideas, places and stories. All guide data is synthetic; no private playlist is used."),
            Programme("A short journey", firstDuration, 15, "A compact fictional interlude with a deliberately narrow guide tile."),
            Programme("Beyond the familiar", firstDuration + 15, 60, "A longer fictional programme for shared timeline and focus testing."),
            Programme("Night stories", firstDuration + 75, 60, "The final programme in this fixture window.")
        ))
    }
    fun time(minute: Int): String = "%02d:%02d".format(18 + minute / 60, minute % 60)
}

data class GuideState(
    val pane: Pane = Pane.GUIDE,
    val group: Int = 0,
    val focusedGroup: Int = 0,
    val channelId: Int = 0,
    val programmeIndex: Int = 0,
    val tunedId: Int = 0,
    val overlay: Boolean = false,
    val action: Int = 0,
    val toolbar: Boolean = false,
    val toolbarAction: Int = 0,
    val catalog: List<Channel> = Fixtures.channels,
    val favorites: Set<Int> = emptySet(),
    val realEpg: Boolean = false,
    val nowMinute: Int = Fixtures.NOW,
) {
    fun time(minute: Int) = if (realEpg) Epg.time(minute) else Fixtures.time(minute)
    private val index get() = CatalogIndex.forCatalog(catalog)
    val groups: List<String> get() = index.groups
    val favoritesGroup: Int get() = groups.lastIndex
    fun browserChannels(surface: String, group: Int, recent: List<Int>, favorites: Set<Int>): List<Channel> = when (surface) {
        "history" -> recent.mapNotNull { index.byId[it] }
        "favorites" -> favorites.mapNotNull { index.byId[it] }.sortedBy { index.positions[it.id] }
        else -> channelsInGroup(group)
    }
    fun channelsInGroup(group: Int): List<Channel> = when (group) {
        0 -> catalog
        favoritesGroup -> index.favoriteRows(favorites)
        else -> index.byGroup[groups.getOrNull(group)].orEmpty()
    }
    val channels: List<Channel> get() = channelsInGroup(group)
    fun selectGroup(selected: Int): GuideState {
        val first = channelsInGroup(selected).firstOrNull()
        return copy(group = selected, focusedGroup = selected,
            channelId = first?.id ?: channelId, programmeIndex = 0)
    }
    private fun quickChannel(delta: Int): GuideState {
        val rows = channels
        if (rows.isEmpty()) return this
        val current = rows.indexOfFirst { it.id == tunedId }
        val next = if (current < 0) { if (delta > 0) 0 else rows.lastIndex }
            else (current + delta).coerceIn(0, rows.lastIndex)
        val id = rows[next].id
        return copy(tunedId = id, channelId = id, programmeIndex = 0)
    }
    val channel: Channel get() = index.byId.getValue(channelId)
    val programme: Programme get() = channel.programmes[programmeIndex.coerceIn(0, channel.programmes.lastIndex)]
    val tunedChannel: Channel get() = index.byId.getValue(tunedId)
    val canActivateProgramme: Boolean get() = channels.isNotEmpty() && programme.isCurrentAt(nowMinute)

    fun move(key: RemoteKey): GuideState {
        if (key == RemoteKey.LONG_BACK) return copy(pane = Pane.FULLSCREEN, overlay = false, toolbar = false)
        if (pane == Pane.FULLSCREEN) return when (key) {
            RemoteKey.BACK -> if (overlay) copy(overlay = false, toolbar = false) else copy(pane = Pane.GUIDE)
            RemoteKey.CENTER -> if (!overlay) copy(overlay = true, toolbar = false, action = 0)
                else if (!toolbar && action == 0) copy(pane = Pane.GUIDE, overlay = false) else this
            RemoteKey.DOWN -> if (overlay) copy(toolbar = true) else this
            RemoteKey.UP -> if (overlay && toolbar) copy(toolbar = false) else this
            RemoteKey.LEFT -> if (!overlay) quickChannel(-1) else if (toolbar)
                copy(toolbarAction = (toolbarAction - 1).coerceAtLeast(0))
                else copy(action = (action - 1).coerceAtLeast(0))
            RemoteKey.RIGHT -> if (!overlay) quickChannel(1) else if (toolbar)
                copy(toolbarAction = (toolbarAction + 1).coerceAtMost(PlayerToolbar.entries.lastIndex))
                else copy(action = (action + 1).coerceAtMost(2))
            else -> this
        }
        if (pane == Pane.GROUPS) return when (key) {
            RemoteKey.UP -> copy(focusedGroup = (focusedGroup - 1).coerceAtLeast(0))
            RemoteKey.DOWN -> copy(focusedGroup = (focusedGroup + 1).coerceAtMost(groups.lastIndex))
            RemoteKey.CENTER -> {
                selectGroup(focusedGroup).copy(pane = Pane.GUIDE)
            }
            RemoteKey.BACK, RemoteKey.RIGHT -> copy(pane = Pane.GUIDE)
            else -> this
        }
        return when (key) {
            RemoteKey.UP, RemoteKey.DOWN -> {
                if (channels.isEmpty()) return this
                val next = (channels.indexOfFirst { it.id == channelId } + if (key == RemoteKey.UP) -1 else 1)
                    .coerceIn(0, channels.lastIndex)
                val target = channels[next]
                val index = target.programmes.indexOfFirst { programme.start >= it.start && programme.start < it.end }
                copy(channelId = target.id, programmeIndex = index.coerceAtLeast(0))
            }
            RemoteKey.LEFT -> if (programmeIndex == 0) copy(pane = Pane.GROUPS, focusedGroup = group)
                else copy(programmeIndex = programmeIndex - 1)
            RemoteKey.RIGHT -> copy(programmeIndex = (programmeIndex + 1).coerceAtMost(channel.programmes.lastIndex))
            RemoteKey.CENTER -> if (!canActivateProgramme) this
                else if (channelId != tunedId) copy(tunedId = channelId)
                else copy(pane = Pane.FULLSCREEN, overlay = false, toolbar = false)
            RemoteKey.BACK -> copy(pane = Pane.GROUPS, focusedGroup = group)
            else -> this
        }
    }
}

/** Cache by list identity: reducer copies share a catalog without retaining old private catalogs. */
private class CatalogIndex(catalog: List<Channel>) {
    val groups = listOf("All channels") + catalog.map { it.group }.distinct() + "Favorites"
    val byGroup = catalog.groupBy { it.group }
    val byId = catalog.associateBy { it.id }
    val positions = catalog.withIndex().associate { it.value.id to it.index }
    private var favoriteIds: Set<Int>? = null
    private var favoriteChannels = emptyList<Channel>()
    fun favoriteRows(ids: Set<Int>): List<Channel> {
        if (favoriteIds != ids) {
            favoriteIds = ids.toSet()
            favoriteChannels = ids.mapNotNull { byId[it] }.sortedBy { positions[it.id] }
        }
        return favoriteChannels
    }
    companion object {
        private var source: List<Channel>? = null
        private var cached: CatalogIndex? = null
        @Synchronized fun forCatalog(catalog: List<Channel>): CatalogIndex {
            if (source !== catalog) { source = catalog; cached = CatalogIndex(catalog) }
            return cached!!
        }
    }
}

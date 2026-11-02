package faisalnh.tiviman

/** Numeric bookmarks contain no channel metadata; persisted encrypted and bound to a catalog revision. */
data class NavigationBookmark(val group: Int, val focusedGroup: Int, val channel: Int,
    val programme: Int, val tuned: Int) {
    fun restore(catalog: List<Channel>, favorites: Set<Int> = emptySet()): GuideState {
        val initial = GuideState(catalog = catalog, favorites = favorites)
        val selectedGroup = group.takeIf { it in initial.groups.indices } ?: 0
        val filtered = initial.copy(group = selectedGroup)
        val selected = filtered.channels.firstOrNull { it.id == channel } ?: filtered.channels.firstOrNull()
            ?: catalog.firstOrNull { it.id == channel } ?: catalog.first()
        return filtered.copy(
            focusedGroup = focusedGroup.coerceIn(0, initial.groups.size),
            channelId = selected.id,
            programmeIndex = programme.coerceIn(0, selected.programmes.lastIndex),
            tunedId = catalog.firstOrNull { it.id == tuned }?.id ?: catalog.first().id,
        )
    }
}

package faisalnh.tiviman

import java.util.Locale

object ChannelTools {
    /** Name-only literal matching; blank input deliberately lists nothing. */
    fun search(catalog: List<Channel>, query: String, limit: Int = 200): List<Channel> {
        val term = query.trim().lowercase(Locale.ROOT)
        if (term.isEmpty()) return emptyList()
        return catalog.asSequence().filter { it.name.lowercase(Locale.ROOT).contains(term) }.take(limit).toList()
    }
    fun toggle(ids: Set<Int>, id: Int): Set<Int> = if (id in ids) ids - id else ids + id
}

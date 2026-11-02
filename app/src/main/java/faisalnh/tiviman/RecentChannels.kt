package faisalnh.tiviman

/** Recent explicit tune requests, not focus moves or proof of successfully decoded viewing. */
object RecentChannels {
    fun record(ids: List<Int>, id: Int) = (listOf(id) + ids.filter { it != id }).take(50)
    fun restore(ids: List<Int>, valid: Set<Int>) = ids.filter { it in valid }.distinct().take(50)
}

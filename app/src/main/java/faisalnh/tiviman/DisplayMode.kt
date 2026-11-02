package faisalnh.tiviman

/** Display modes offered by the player and their sizing policies. */
enum class DisplayMode(val label: String, val ratio: Float = 0f, val reduced: Boolean = false) {
    NORMAL("Normal"), FULL("Full"), ZOOM("Zoom"),
    CINEMA_ZOOM("Zoom - 2.35:1", 2.35f), REDUCED("Reduced", reduced = true),
    RATIO_4_3("4:3", 4f / 3), RATIO_16_9("16:9", 16f / 9), RATIO_235("2.35:1", 2.35f);

    data class Size(val width: Int, val height: Int)
    fun size(width: Int, height: Int): Size {
        if (width <= 0 || height <= 0) return Size(0, 0)
        val factor = if (reduced) 0.8f else 1f
        var w = width * factor
        var h = height * factor
        if (ratio > 0) {
            if (w / h > ratio) w = h * ratio else h = w / ratio
        }
        return Size(w.toInt().coerceAtLeast(1), h.toInt().coerceAtLeast(1))
    }
    companion object {
        fun parse(value: String?) = entries.firstOrNull { it.name == value } ?: NORMAL
    }
}

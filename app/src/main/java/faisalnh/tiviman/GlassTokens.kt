package faisalnh.tiviman

object GlassTokens {
    val canvas: Int = 0xFF101216.toInt()
    val surface: Int = 0xFF1C2027.toInt()
    val surfaceRaised: Int = 0xFF292F38.toInt()
    val fillQuiet: Int = 0x0FFFFFFF
    val fillQuietStrong: Int = 0x1AFFFFFF
    val textPrimary: Int = 0xFFF5F7FA.toInt()
    val textSecondary: Int = 0xFFB8C0CC.toInt()
    val accent: Int = 0xFF77B8FF.toInt()
    val lens: Int = 0xF0F5F7FA.toInt()
    val lensText: Int = 0xFF101216.toInt()
    val scrimOverlay: Int = 0x4D000000
    val scrimSheet: Int = 0x73000000

    const val RADIUS_PANEL_DP = 24f
    const val RADIUS_SHEET_DP = 28f
    const val RADIUS_ROW_DP = 12f

    fun innerRadius(outerDp: Float, paddingDp: Float): Float = maxOf(4f, outerDp - paddingDp)
}

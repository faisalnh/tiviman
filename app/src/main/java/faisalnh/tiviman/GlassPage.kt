package faisalnh.tiviman

import android.app.Activity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

object GlassPage {
    fun header(activity: Activity, title: String, subtitle: String? = null): View {
        fun dp(value: Int) = GlassMaterial.dp(activity, value.toFloat())
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GlassMaterial.panel(activity, GlassVariant.REGULAR, 24f)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                setMargins(dp(16), dp(16), dp(16), dp(16))
            }
            addView(TextView(activity).apply {
                text = title
                TvTypography.apply(this, 28)
                setTextColor(GlassTokens.textPrimary)
            })
            if (subtitle != null) addView(TextView(activity).apply {
                text = subtitle
                TvTypography.apply(this, 18)
                setTextColor(GlassTokens.textSecondary)
            })
        }
    }
}

package faisalnh.tiviman

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import java.util.WeakHashMap
import java.lang.ref.WeakReference

object StatusCapsule {
    private val handler = Handler(Looper.getMainLooper())
    private val capsules = WeakHashMap<Activity, WeakReference<TextView>>()

    fun show(activity: Activity, message: CharSequence, long: Boolean = false) {
        if (activity.isFinishing || activity.isDestroyed) return
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { show(activity, message, long) }
            return
        }
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        capsules.remove(activity)?.get()?.let { old ->
            old.animate().cancel()
            val host = old.parent as? ViewGroup
            (host?.parent as? ViewGroup)?.removeView(host)
        }
        val capsule = TextView(activity).apply {
            text = message
            TvTypography.apply(this, 16)
            setTextColor(GlassTokens.textPrimary)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            maxWidth = GlassMaterial.dp(activity, 560f)
            gravity = Gravity.CENTER
            setPadding(GlassMaterial.dp(activity, 24f), GlassMaterial.dp(activity, 12f),
                GlassMaterial.dp(activity, 24f), GlassMaterial.dp(activity, 12f))
            background = GlassMaterial.panel(activity, radiusDp = 1000f)
            isFocusable = false
            isClickable = false
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        // Activity content is normally a FrameLayout; the wrapper keeps placement correct for custom roots too.
        val host = FrameLayout(activity).apply {
            isFocusable = false
            isClickable = false
            clipChildren = false
            addView(capsule, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = GlassMaterial.dp(activity, 32f)
                leftMargin = GlassMaterial.dp(activity, 16f)
                rightMargin = GlassMaterial.dp(activity, 16f)
            })
        }
        content.addView(host, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT))
        capsules[activity] = WeakReference(capsule)
        val animate = Settings.Global.getFloat(activity.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
        if (animate) {
            capsule.alpha = 0f
            capsule.animate().alpha(1f).setDuration(160).start()
        }
        val remove = Runnable {
            if (animate && capsule.isAttachedToWindow) {
                capsule.animate().alpha(0f).setDuration(160).withEndAction {
                    (host.parent as? ViewGroup)?.removeView(host)
                }.start()
            } else (host.parent as? ViewGroup)?.removeView(host)
        }
        capsule.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit
            override fun onViewDetachedFromWindow(view: View) {
                capsule.animate().cancel()
                handler.removeCallbacks(remove)
            }
        })
        capsule.post { if (capsule.isAttachedToWindow) capsule.announceForAccessibility(message) }
        handler.postDelayed(remove, (if (long) 3500L else 2000L) - if (animate) 160L else 0L)
    }
}

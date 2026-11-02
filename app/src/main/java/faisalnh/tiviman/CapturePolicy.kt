package faisalnh.tiviman

import android.content.Context
import android.content.pm.ApplicationInfo
import android.view.Window
import android.view.WindowManager

object CapturePolicy {
    /** Instrumentation-only in-process opt-in for synthetic isolated sessions; ignored in non-debuggable builds. */
    @Volatile var syntheticCaptureAllowed: Boolean = false

    fun secure(window: Window?) {
        if (window != null && !allowed(window.context)) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    fun allowed(context: Context): Boolean = syntheticCaptureAllowed &&
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
}

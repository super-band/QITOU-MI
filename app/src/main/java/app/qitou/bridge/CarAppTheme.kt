package app.qitou.bridge

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat

/** Shared light/dark palette and system-bar behavior for QITOU surfaces. */
object CarAppTheme {
    fun color(context: Context, @ColorRes color: Int): Int =
        ContextCompat.getColor(context, color)

    fun isNight(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    fun canvas(context: Context): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        intArrayOf(
            color(context, R.color.app_background_top),
            color(context, R.color.app_background_bottom),
        ),
    )

    fun rounded(
        color: Int,
        radiusPx: Int,
        strokeColor: Int? = null,
        strokeWidthPx: Int = 1,
    ): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radiusPx.toFloat()
        if (strokeColor != null) setStroke(strokeWidthPx, strokeColor)
    }

    /**
     * Keeps app content edge-to-edge while preserving readable system bar icons.
     * Activities paint their own surfaces behind both bars.
     */
    fun prepare(activity: ComponentActivity) {
        activity.enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        activity.window.isNavigationBarContrastEnforced = false
        val controller = WindowCompat.getInsetsController(
            activity.window,
            activity.window.decorView,
        )
        val lightIcons = isNight(activity)
        controller.isAppearanceLightStatusBars = !lightIcons
        controller.isAppearanceLightNavigationBars = !lightIcons
    }
}

package app.qitou.bridge

import android.content.Context

enum class AudioOutputRoute(val storageValue: String) {
    PHONE("phone"),
    BIKE_BLUETOOTH("bike");

    companion object {
        fun fromStorage(value: String?): AudioOutputRoute =
            entries.firstOrNull { it.storageValue == value } ?: PHONE
    }
}

data class AudioVolumeSettings(
    val mediaPercent: Int = 100,
    val navigationPercent: Int = 100,
    val duckMediaDuringNavigation: Boolean = true,
    val outputRoute: AudioOutputRoute = AudioOutputRoute.PHONE,
) {
    val mediaGain: Float get() = mediaPercent.coerceIn(0, 100) / 100f
    val navigationGain: Float get() = navigationPercent.coerceIn(0, 100) / 100f
}

/**
 * Independent mixer levels for QITOU's two PCM tracks. These values scale the
 * AudioTracks after Android routes them to speaker, USB, or the bike's A2DP sink.
 */
object AudioVolumeStore {
    private const val PREFS = "qitou_audio"
    private const val KEY_MEDIA = "media_percent"
    private const val KEY_NAVIGATION = "navigation_percent"
    private const val KEY_AUTO_DUCK = "duck_media_during_navigation"
    private const val KEY_OUTPUT_ROUTE = "output_route"

    @Volatile private var loaded = false
    @Volatile var current = AudioVolumeSettings()
        private set
    @Volatile var listener: ((AudioVolumeSettings) -> Unit)? = null

    fun attach(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            current = AudioVolumeSettings(
                mediaPercent = prefs.getInt(KEY_MEDIA, 100).coerceIn(0, 100),
                navigationPercent = prefs.getInt(KEY_NAVIGATION, 100).coerceIn(0, 100),
                duckMediaDuringNavigation = prefs.getBoolean(KEY_AUTO_DUCK, true),
                outputRoute = AudioOutputRoute.fromStorage(prefs.getString(KEY_OUTPUT_ROUTE, null)),
            )
            loaded = true
        }
    }

    fun setMedia(context: Context, percent: Int) {
        attach(context)
        update(current.copy(mediaPercent = percent.coerceIn(0, 100)), context)
    }

    fun setNavigation(context: Context, percent: Int) {
        attach(context)
        update(current.copy(navigationPercent = percent.coerceIn(0, 100)), context)
    }

    fun setAutoDuck(context: Context, enabled: Boolean) {
        attach(context)
        update(current.copy(duckMediaDuringNavigation = enabled), context)
    }

    fun setOutputRoute(context: Context, route: AudioOutputRoute) {
        attach(context)
        update(current.copy(outputRoute = route), context)
    }

    private fun update(value: AudioVolumeSettings, context: Context) {
        current = value
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_MEDIA, value.mediaPercent)
            .putInt(KEY_NAVIGATION, value.navigationPercent)
            .putBoolean(KEY_AUTO_DUCK, value.duckMediaDuringNavigation)
            .putString(KEY_OUTPUT_ROUTE, value.outputRoute.storageValue)
            .apply()
        listener?.invoke(value)
    }
}

package io.github.keplersj.selkie.link

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.Executor

/**
 * Android's look, as `droidc host-link` takes it in an `appearance` line: GNOME follows dark mode, the
 * accent (as the nearest of GNOME's accents), font size, "Remove animations" and high contrast.
 * A null field isn't sent, and the guest leaves that setting alone.
 */
@Serializable
data class Appearance(
    val dark: Boolean? = null,
    /** `#rrggbb`. */
    val accent: String? = null,
    @SerialName("text_scale") val textScale: Float? = null,
    val animations: Boolean? = null,
    @SerialName("high_contrast") val highContrast: Boolean? = null,
) {
    /** The line for `droidc host-link`'s stdin. */
    fun toLine(): String = buildJsonObject {
        put("kind", "appearance")
        dark?.let { put("dark", it) }
        accent?.let { put("accent", it) }
        textScale?.let { put("text_scale", it) }
        animations?.let { put("animations", it) }
        highContrast?.let { put("high_contrast", it) }
    }.toString()

    companion object {
        /** `#rrggbb` for an ARGB color int. */
        fun hex(argb: Int): String = "#%06x".format(argb and 0xffffff)

        /** Android's current look. */
        fun read(context: Context): Appearance {
            val config = context.resources.configuration
            val dark = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            val accent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                runCatching { hex(context.getColor(android.R.color.system_accent1_500)) }.getOrNull() else null
            val animatorScale = runCatching {
                Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
            }.getOrDefault(1f)
            val contrast = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
                runCatching { context.getSystemService(UiModeManager::class.java)?.contrast }.getOrNull() else null
            return Appearance(
                dark = dark,
                accent = accent,
                textScale = config.fontScale.takeIf { it > 0f },
                animations = animatorScale > 0f,
                highContrast = contrast?.let { it >= 0.5f },
            )
        }
    }
}

/**
 * Calls [onChange] when part of Android's look changes that doesn't arrive as a configuration change:
 * the animator scale and (Android 14+) the contrast level. Night mode, font size and the wallpaper
 * accent come through the service's `onConfigurationChanged`.
 */
class AppearanceWatcher(private val context: Context, private val onChange: () -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val observer = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) = this@AppearanceWatcher.onChange()
    }
    private var contrastListener: Any? = null

    fun start() {
        runCatching {
            context.contentResolver.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val ui = context.getSystemService(UiModeManager::class.java) ?: return
            val l = UiModeManager.ContrastChangeListener { onChange() }
            runCatching { ui.addContrastChangeListener(Executor { main.post(it) }, l); contrastListener = l }
        }
    }

    fun stop() {
        runCatching { context.contentResolver.unregisterContentObserver(observer) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val l = contrastListener as? UiModeManager.ContrastChangeListener ?: return
            runCatching { context.getSystemService(UiModeManager::class.java)?.removeContrastChangeListener(l) }
            contrastListener = null
        }
    }
}

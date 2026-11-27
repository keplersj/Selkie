package io.github.keplersj.selkie.platform

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import io.github.keplersj.selkie.AppActivity
import io.github.keplersj.selkie.engine.AppEntry
import io.github.keplersj.selkie.ui.loadAppIcon

/**
 * Linux apps as Android launcher shortcuts (canvas: Phone-Launcher). A shortcut opens [AppActivity]
 * for one app of one container; each app gets its own task in Recents. Apps opened from Selkie also
 * become dynamic shortcuts, shown on a long press of Selkie's icon.
 */
object AppShortcuts {
    const val ACTION = "io.github.keplersj.selkie.action.OPEN_APP"
    const val EXTRA_CONTAINER = "container"
    const val EXTRA_APP = "app"
    const val EXTRA_NAME = "name"

    /** What a shortcut opens. */
    data class Target(val container: String, val app: String, val name: String)

    fun id(container: String, app: String) = "app:$container/$app"

    /** One document per app, so `documentLaunchMode="intoExisting"` gives each app its own task. */
    private fun uri(container: String, app: String): Uri =
        Uri.Builder().scheme("selkie-app").authority(container).appendPath(app).build()

    fun intent(context: Context, container: String, app: AppEntry): Intent =
        Intent(ACTION, uri(container, app.id), context, AppActivity::class.java)
            .putExtra(EXTRA_CONTAINER, container)
            .putExtra(EXTRA_APP, app.id)
            .putExtra(EXTRA_NAME, app.name)

    /** The app a shortcut's intent names, or null for any other intent. */
    fun target(intent: Intent?): Target? {
        if (intent?.action != ACTION) return null
        val container = intent.getStringExtra(EXTRA_CONTAINER) ?: return null
        val app = intent.getStringExtra(EXTRA_APP) ?: return null
        return Target(container, app, intent.getStringExtra(EXTRA_NAME) ?: app.removeSuffix(".desktop"))
    }

    /** Whether the launcher takes pinned shortcuts. */
    fun canPin(context: Context): Boolean =
        runCatching { ShortcutManagerCompat.isRequestPinShortcutSupported(context) }.getOrDefault(false)

    /** Asks the launcher to pin [app]; the system shows its own "Add to Home screen" dialog. */
    fun pin(context: Context, container: String, app: AppEntry): Boolean =
        runCatching { ShortcutManagerCompat.requestPinShortcut(context, info(context, container, app), null) }
            .getOrDefault(false)

    /** Adds [app] to the shortcuts on a long press of Selkie's icon, most recent first. */
    fun opened(context: Context, container: String, app: AppEntry) {
        runCatching { ShortcutManagerCompat.pushDynamicShortcut(context, info(context, container, app)) }
    }

    /** Greys out a pinned shortcut whose app or container is gone (launchers can't remove pins). */
    fun disable(context: Context, target: Target, reason: String) {
        val id = id(target.container, target.app)
        runCatching {
            ShortcutManagerCompat.removeDynamicShortcuts(context, listOf(id))
            ShortcutManagerCompat.disableShortcuts(context, listOf(id), reason)
        }
    }

    private fun info(context: Context, container: String, app: AppEntry): ShortcutInfoCompat =
        ShortcutInfoCompat.Builder(context, id(container, app.id))
            .setShortLabel(app.name)
            .setLongLabel("${app.name} ($container)")
            .setIcon(icon(context, app))
            .setIntent(intent(context, container, app))
            .build()

    /**
     * An adaptive icon: the app's icon from the container's theme in the middle of a light square
     * (the launcher masks it to its own shape), or the app's initial when the theme has none.
     */
    private fun icon(context: Context, app: AppEntry): IconCompat {
        val density = context.resources.displayMetrics.density
        val size = (108 * density).toInt()
        val inner = (56 * density).toInt()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.rgb(0xF2, 0xF4, 0xF7))
        val path = app.icon?.hostPath
        val drawn = path?.let { loadAppIcon(it, app.icon?.format, inner) }
        if (drawn != null) {
            val at = (size - inner) / 2f
            canvas.drawBitmap(drawn, at, at, Paint(Paint.FILTER_BITMAP_FLAG))
        } else {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(0x3A, 0x44, 0x52)
                textSize = 40 * density
                textAlign = Paint.Align.CENTER
                typeface = Typeface.DEFAULT_BOLD
            }
            val y = size / 2f - (paint.descent() + paint.ascent()) / 2
            canvas.drawText(app.name.take(1).uppercase(), size / 2f, y, paint)
        }
        return IconCompat.createWithAdaptiveBitmap(bmp)
    }
}

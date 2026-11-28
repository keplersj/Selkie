package io.github.keplersj.selkie.engine

import android.content.SharedPreferences
import android.content.res.Configuration
import io.github.keplersj.selkie.ui.DeviceClass
import io.github.keplersj.selkie.ui.deviceClassForWidth

/** What Selkie does when the device changes shape and the running session no longer suits it. */
enum class OnShapeChange {
    /** Offer the session made for the new shape; the running one stays until the user picks. */
    Ask,
    /** Keep the running session, resized to the new screen, and say nothing. */
    Keep,
}

/**
 * A change of the device's shape under a running desktop: a fold opening or closing, a display or dock
 * that turns it into a desktop. Turning the device is not one. The session always resizes to the new
 * screen; this decides whether Selkie also offers a session made for the new shape.
 */
object ShapeChange {
    /**
     * The device's shape for [ShapeChange], as droidc's `form_factors` name it. It reads the smallest
     * width, so turning the device keeps its shape, where [io.github.keplersj.selkie.ui.deviceClassOf]
     * follows the current width.
     */
    fun shapeOf(uiModeType: Int, smallestWidthDp: Int): String = SessionChoice.formFactor(when (uiModeType) {
        Configuration.UI_MODE_TYPE_TELEVISION -> DeviceClass.Tv
        Configuration.UI_MODE_TYPE_CAR -> DeviceClass.Car
        Configuration.UI_MODE_TYPE_WATCH -> DeviceClass.Watch
        Configuration.UI_MODE_TYPE_VR_HEADSET -> DeviceClass.Xr
        Configuration.UI_MODE_TYPE_DESK -> DeviceClass.Desktop
        else -> deviceClassForWidth(smallestWidthDp)
    })

    fun shapeOf(c: Configuration): String = shapeOf(c.uiMode and Configuration.UI_MODE_TYPE_MASK, c.smallestScreenWidthDp)

    /**
     * The session to offer when the device becomes [pick]'s shape while [running] runs: the one that
     * would start there now, if it is another session and suits the shape while [running] doesn't.
     * Null when there is nothing worth asking about.
     */
    fun offer(sessions: List<SessionEntry>, defaultId: String?, running: String?, pick: SessionPick): SessionEntry? {
        val now = sessions.firstOrNull { it.id == running } ?: return null
        if (SessionChoice.fits(now, pick.formFactor)) return null
        val next = SessionChoice.choose(sessions, defaultId, pick) ?: return null
        return next.takeIf { it.id != running && SessionChoice.fits(it, pick.formFactor) }
    }

    // ---- stored: one choice for every container, and containers told to keep theirs ----

    private const val KEY = "shape_change"
    private fun keepKey(container: String) = "shape_change_keep:$container"

    fun read(prefs: SharedPreferences): OnShapeChange =
        prefs.getString(KEY, null)?.let { v -> OnShapeChange.entries.firstOrNull { it.name == v } } ?: OnShapeChange.Ask

    /** What to do for [container]: Keep when it was told to, else the choice for every container. */
    fun read(prefs: SharedPreferences, container: String): OnShapeChange =
        if (prefs.getBoolean(keepKey(container), false)) OnShapeChange.Keep else read(prefs)

    /** Sets the choice for every container. Ask also asks again for those told to keep their session. */
    fun write(prefs: SharedPreferences, v: OnShapeChange) {
        val e = prefs.edit().putString(KEY, v.name)
        if (v == OnShapeChange.Ask) keptContainers(prefs).forEach { e.remove(keepKey(it)) }
        e.apply()
    }

    /** Remembers to keep [container]'s session when the shape changes, without asking. */
    fun keep(prefs: SharedPreferences, container: String) {
        prefs.edit().putBoolean(keepKey(container), true).apply()
    }

    /** Containers told to keep their session. */
    fun keptContainers(prefs: SharedPreferences): List<String> =
        prefs.all.keys.filter { it.startsWith("shape_change_keep:") }.map { it.removePrefix("shape_change_keep:") }.sorted()

    /** Forgets [container]'s choice (when it is removed). */
    fun forget(prefs: SharedPreferences, container: String) {
        prefs.edit().remove(keepKey(container)).apply()
    }
}

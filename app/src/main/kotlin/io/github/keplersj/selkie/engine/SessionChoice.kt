package io.github.keplersj.selkie.engine

import android.content.SharedPreferences
import io.github.keplersj.selkie.ui.DeviceClass

/**
 * How a container picks its graphical session on this device: by the device's shape (the session
 * whose `form_factors` name it, unless the user chose another for this device) or one choice for
 * every device.
 */
data class SessionPick(
    /** Choose by device: each device shape remembers its own session. */
    val byDevice: Boolean = true,
    /** The device shape, as droidc's `form_factors` name it. */
    val formFactor: String = "tablet",
    /** The session the user chose for this shape (or for every device), if any. */
    val chosen: String? = null,
)

object SessionChoice {
    /** droidc's form-factor name for a device class. A fold is a tablet while it is open. */
    fun formFactor(d: DeviceClass): String = when (d) {
        DeviceClass.Phone -> "phone"
        DeviceClass.Foldable, DeviceClass.Tablet -> "tablet"
        DeviceClass.Desktop -> "desktop"
        DeviceClass.Tv -> "tv"
        DeviceClass.Watch -> "watch"
        DeviceClass.Xr -> "xr"
        DeviceClass.Car -> "car"
    }

    /** Whether [e] suits [formFactor]: it names it, or names no device at all. */
    fun fits(e: SessionEntry, formFactor: String): Boolean = e.formFactors.isEmpty() || formFactor in e.formFactors

    /**
     * The session to start: the one chosen, while it can run; else, by device, the first runnable
     * session made for this shape, then one that names no device; else droidc's default.
     */
    fun choose(sessions: List<SessionEntry>, defaultId: String?, pick: SessionPick): SessionEntry? {
        val runnable = sessions.filter { it.runnable }
        pick.chosen?.let { id -> runnable.firstOrNull { it.id == id }?.let { return it } }
        val default = runnable.firstOrNull { it.id == defaultId } ?: runnable.firstOrNull()
        if (!pick.byDevice) return default
        return runnable.firstOrNull { pick.formFactor in it.formFactors }
            ?: default?.takeIf { fits(it, pick.formFactor) }
            ?: runnable.firstOrNull { it.formFactors.isEmpty() }
            ?: default
    }

    /** Sessions for the picker: those that fit this device, and those made for other devices. */
    fun split(sessions: List<SessionEntry>, formFactor: String): Pair<List<SessionEntry>, List<SessionEntry>> =
        sessions.filter { it.runnable }.partition { fits(it, formFactor) }

    // ---- stored per container ----

    private fun byDeviceKey(container: String) = "session_by_device:$container"
    private fun chosenKey(container: String, formFactor: String?) = "session:$container:${formFactor ?: "any"}"

    fun read(prefs: SharedPreferences, container: String, formFactor: String): SessionPick {
        val byDevice = prefs.getBoolean(byDeviceKey(container), true)
        return SessionPick(byDevice, formFactor,
            prefs.getString(chosenKey(container, formFactor.takeIf { byDevice }), null))
    }

    /** Remembers [id] (null: let the device decide) for this shape, or for every device. */
    fun write(prefs: SharedPreferences, container: String, pick: SessionPick, id: String?) {
        val key = chosenKey(container, pick.formFactor.takeIf { pick.byDevice })
        prefs.edit().apply { if (id == null) remove(key) else putString(key, id) }.apply()
    }

    fun writeByDevice(prefs: SharedPreferences, container: String, on: Boolean) {
        prefs.edit().putBoolean(byDeviceKey(container), on).apply()
    }

    /** Forgets every choice for [container] (when it is removed). */
    fun forget(prefs: SharedPreferences, container: String) {
        val e = prefs.edit()
        prefs.all.keys.filter { it == byDeviceKey(container) || it.startsWith("session:$container:") }.forEach { e.remove(it) }
        e.apply()
    }
}

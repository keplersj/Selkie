package io.github.keplersj.selkie.platform

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * Detects whether Android's phantom-process killer is disabled ("Disable child process
 * restrictions" in Developer options). Deliberately one small class so it is easy to fix.
 *
 * Mirrors AOSP FeatureFlagUtils.isEnabled for `settings_enable_monitor_phantom_procs`, in order:
 *  1. Settings.Global key of that name (non-null wins);
 *  2. system property `persist.sys.fflag.override.settings_enable_monitor_phantom_procs`
 *     (this is what the Developer options toggle writes);
 *  3. default "true" (monitoring on).
 * "false" means restrictions are disabled (good). Only relevant on API 32+ (12L+); on API 31 the
 * limit is device_config max_phantom_processes, which cannot be changed from the UI.
 */
class PhantomProcessCheck(private val context: Context) {
    enum class State { OK, RESTRICTED, UNVERIFIED, NOT_APPLICABLE }

    data class Result(val state: State, val source: String)

    fun check(): State = checkDetailed().state

    fun checkDetailed(): Result {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S_V2) return Result(State.NOT_APPLICABLE, "api<32")
        val setting = try { Settings.Global.getString(context.contentResolver, SETTING) } catch (_: Exception) { null }
        val prop = if (setting == null) readProperty() else PropRead.Skipped
        val r = evaluate(setting, prop)
        Log.i(TAG, "phantom check: ${r.state} via ${r.source} (setting=$setting prop=$prop)")
        return r
    }

    private fun readProperty(): PropRead {
        try {
            val c = Class.forName("android.os.SystemProperties")
            val v = c.getMethod("get", String::class.java).invoke(null, PROP) as String
            return PropRead.Value(v)
        } catch (_: Throwable) {}
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("/system/bin/getprop", PROP))
            val out = p.inputStream.bufferedReader().readText().trim()
            p.waitFor()
            PropRead.Value(out)
        } catch (_: Throwable) { PropRead.Unreadable }
    }

    sealed interface PropRead {
        data object Skipped : PropRead
        data object Unreadable : PropRead
        data class Value(val v: String) : PropRead
    }

    companion object {
        const val TAG = "SelkiePhantom"
        const val SETTING = "settings_enable_monitor_phantom_procs"
        const val PROP = "persist.sys.fflag.override.settings_enable_monitor_phantom_procs"

        /** Pure decision logic (unit-tested). */
        fun evaluate(setting: String?, prop: PropRead): Result = when {
            setting != null ->
                Result(if (setting.equals("false", true)) State.OK else State.RESTRICTED, "settings.global")
            prop is PropRead.Value ->
                Result(if (prop.v.equals("false", true)) State.OK else State.RESTRICTED,
                    if (prop.v.isEmpty()) "default(true)" else "property")
            else -> Result(State.UNVERIFIED, "unreadable")
        }
    }
}

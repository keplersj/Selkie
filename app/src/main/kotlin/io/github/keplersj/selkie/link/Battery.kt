package io.github.keplersj.selkie.link

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Android's battery, as `droidc host-link` takes it in a `battery` line: GNOME's top bar and gsd-power
 * see it as UPower's battery.
 */
data class BatteryState(
    val present: Boolean,
    val percent: Int?,
    /** `charging`, `discharging`, `full`, `not-charging`, or null when Android doesn't know. */
    val status: String?,
    val plugged: Boolean,
    /** Seconds until full while charging, if Android estimates it. */
    val timeToFull: Long? = null,
) {
    fun toLine(): String = buildJsonObject {
        put("kind", "battery")
        put("present", present)
        percent?.let { put("percent", it) }
        status?.let { put("status", it) }
        put("plugged", plugged)
        timeToFull?.let { put("time_to_full", it) }
    }.toString()

    companion object {
        /** From `ACTION_BATTERY_CHANGED`'s extras. */
        fun of(present: Boolean, level: Int, scale: Int, status: Int, plugged: Int, timeToFullMs: Long = -1): BatteryState =
            BatteryState(
                present = present,
                percent = if (level >= 0 && scale > 0) (level * 100 / scale).coerceIn(0, 100) else null,
                status = when (status) {
                    BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
                    BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
                    BatteryManager.BATTERY_STATUS_FULL -> "full"
                    BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not-charging"
                    else -> null
                },
                plugged = plugged != 0,
                timeToFull = if (timeToFullMs > 0) timeToFullMs / 1000 else null,
            )

        fun from(context: Context, i: Intent): BatteryState {
            val ttf = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) runCatching {
                context.getSystemService(BatteryManager::class.java)?.computeChargeTimeRemaining() ?: -1
            }.getOrDefault(-1) else -1
            return of(
                present = i.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true),
                level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1),
                scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100),
                status = i.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN),
                plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0),
                timeToFullMs = ttf,
            )
        }
    }
}

/**
 * Sends [onChange] the battery's line each time it changes (`ACTION_BATTERY_CHANGED` also fires for
 * temperature and voltage, which GNOME doesn't show), and [current] for a link that just came up.
 */
class BatteryWatcher(private val context: Context, private val onChange: (String) -> Unit) {
    @Volatile private var last: String? = null
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val line = BatteryState.from(c, i).toLine()
            if (line != last) { last = line; onChange(line) }
        }
    }

    /** The last line seen, sent again when the guest's link restarts. */
    val current: String? get() = last

    fun start() {
        val sticky = runCatching {
            ContextCompat.registerReceiver(context, receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED)
        }.getOrNull()
        sticky?.let { last = BatteryState.from(context, it).toLine() }
    }

    fun stop() {
        runCatching { context.unregisterReceiver(receiver) }
        last = null
    }
}

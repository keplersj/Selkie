package io.github.keplersj.selkie.link

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationManagerCompat
import io.github.keplersj.selkie.platform.adb.AdbMdns
import io.github.keplersj.selkie.service.AdbPairingService
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Answers `droidc adb` in the guest, whose requests come over host-link with an `id`:
 *
 * - `adb-pair`: open Wireless debugging and take the pairing code in a notification
 *   ([AdbPairingService]); the reply carries the pairing port and the code, and the guest's adb pairs.
 * - `adb-port`: Wireless debugging's current port, found by mDNS, so the guest's adb can connect.
 *
 * Each request gets any number of `progress` lines and one `reply`, all with its `id`.
 */
object AdbRequests {
    /** How long `adb-port` looks before saying Wireless debugging is off. */
    private const val PORT_LOOKUP_MS = 5_000L

    fun handle(context: Context, container: String, m: GuestLinkMessage, send: (String) -> Unit) {
        val id = (m.body["id"]?.jsonPrimitive?.longOrNull) ?: return
        when (m.kind) {
            "adb-pair" -> pair(context, container, id, send)
            "adb-port" -> port(context, id, send)
            else -> android.util.Log.i(TAG, "host-link: skipped '${m.kind}'")
        }
    }

    fun reply(id: Long, port: Int, code: String? = null): String = buildJsonObject {
        put("kind", "reply"); put("id", id); put("ok", true); put("port", port)
        code?.let { put("code", it) }
    }.toString()

    fun refuse(id: Long, error: String): String = buildJsonObject {
        put("kind", "reply"); put("id", id); put("ok", false); put("error", error)
    }.toString()

    fun progress(id: Long, message: String): String = buildJsonObject {
        put("kind", "progress"); put("id", id); put("message", message)
    }.toString()

    /** Only the digits of what was typed: Settings shows the code as six digits. */
    fun cleanCode(typed: CharSequence?): String = typed?.filter { it in '0'..'9' }?.toString().orEmpty()

    /** Why a device on Android [sdk] can't answer `droidc adb` at all, or null. */
    fun unsupported(sdk: Int): String? =
        if (sdk < Build.VERSION_CODES.R) "Wireless debugging needs Android 11 or later" else null

    private fun pair(context: Context, container: String, id: Long, send: (String) -> Unit) {
        unsupported(Build.VERSION.SDK_INT)?.let { send(refuse(id, it)); return }
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            send(refuse(id, "Selkie's notifications are off, and the pairing code is typed into one. " +
                "Turn them on in Android's settings for Selkie, then try again"))
            return
        }
        Handler(Looper.getMainLooper()).post {
            val started = AdbPairingService.begin(context, container) { outcome ->
                send(when (outcome) {
                    is AdbPairingService.Outcome.Code -> reply(id, outcome.port, outcome.code)
                    is AdbPairingService.Outcome.Stopped -> refuse(id, outcome.why)
                })
            }
            if (started) send(progress(id, "In Wireless debugging, tap Pair device with pairing code, then type " +
                "the code in Selkie's notification."))
            else send(refuse(id, "Selkie couldn't open Wireless debugging. Open Selkie and try again"))
        }
    }

    private fun port(context: Context, id: Long, send: (String) -> Unit) {
        unsupported(Build.VERSION.SDK_INT)?.let { send(refuse(id, it)); return }
        val main = Handler(Looper.getMainLooper())
        main.post {
            var done = false
            lateinit var search: AdbMdns
            search = AdbMdns(context, AdbMdns.TLS_CONNECT) { port ->
                if (port != null && !done) { done = true; search.stop(); send(reply(id, port)) }
            }
            search.start()
            main.postDelayed({
                if (!done) {
                    done = true; search.stop()
                    send(refuse(id, "Wireless debugging is off. Turn it on in Developer options, then try again"))
                }
            }, PORT_LOOKUP_MS)
        }
    }

    private const val TAG = "SelkieAdb"
}

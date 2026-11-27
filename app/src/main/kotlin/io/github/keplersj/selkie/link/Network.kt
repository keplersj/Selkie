package io.github.keplersj.selkie.link

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Android's default network, as `droidc host-link` takes it in a `network` line: GLib's network monitor
 * in the guest (and the NetworkMonitor portal behind it) sees it as NetworkManager's connectivity, so
 * apps notice a lost connection, a sign-in page or a metered network.
 */
data class NetworkState(
    val connected: Boolean,
    val validated: Boolean = false,
    val portal: Boolean = false,
    val metered: Boolean = false,
    /** `wifi`, `cellular`, `ethernet`, `bluetooth`, `vpn`, or null. */
    val transport: String? = null,
) {
    fun toLine(): String = buildJsonObject {
        put("kind", "network")
        put("connected", connected)
        if (connected) {
            put("validated", validated)
            put("portal", portal)
            put("metered", metered)
            transport?.let { put("transport", it) }
        }
    }.toString()

    companion object {
        val NONE = NetworkState(connected = false)

        /** From the default network's capabilities, given as booleans so a unit test needs no framework. */
        fun of(
            validated: Boolean,
            portal: Boolean,
            notMetered: Boolean,
            wifi: Boolean = false,
            cellular: Boolean = false,
            ethernet: Boolean = false,
            bluetooth: Boolean = false,
            vpn: Boolean = false,
        ) = NetworkState(
            connected = true,
            validated = validated,
            portal = portal,
            metered = !notMetered,
            // A VPN rides on another transport; name it, as NetworkManager's primary connection would.
            transport = when {
                vpn -> "vpn"
                wifi -> "wifi"
                ethernet -> "ethernet"
                cellular -> "cellular"
                bluetooth -> "bluetooth"
                else -> null
            },
        )

        fun from(caps: NetworkCapabilities) = of(
            validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            portal = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL),
            notMetered = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_TEMPORARILY_NOT_METERED)),
            wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
            cellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
            ethernet = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
            bluetooth = caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH),
            vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
        )
    }
}

/**
 * Follows Android's default network and sends [onChange] its line each time the line changes, and
 * [current] for a link that just came up. Needs `ACCESS_NETWORK_STATE`.
 */
class NetworkWatcher(context: Context, private val onChange: (String) -> Unit) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    @Volatile private var last: String? = null
    private var registered = false

    private fun report(state: NetworkState) {
        val line = state.toLine()
        if (line != last) { last = line; onChange(line) }
    }

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
            report(NetworkState.from(caps))

        override fun onLost(network: Network) = report(NetworkState.NONE)
    }

    /** The last line seen, sent again when the guest's link restarts. */
    val current: String? get() = last

    fun start() {
        val cm = cm ?: return
        // What there is now, before the first callback.
        last = (cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }?.let(NetworkState::from)
            ?: NetworkState.NONE).toLine()
        registered = runCatching { cm.registerDefaultNetworkCallback(callback) }.isSuccess
    }

    fun stop() {
        if (registered) runCatching { cm?.unregisterNetworkCallback(callback) }
        registered = false
        last = null
    }
}

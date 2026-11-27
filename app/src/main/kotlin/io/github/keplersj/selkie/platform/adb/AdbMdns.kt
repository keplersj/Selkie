package io.github.keplersj.selkie.platform.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import java.io.IOException
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket

/**
 * Finds this device's own Wireless debugging services by mDNS, as Shizuku does (its `AdbMdns`):
 * [TLS_PAIRING] is advertised only while Settings shows "Pair device with pairing code", and [TLS_CONNECT]
 * while Wireless debugging is on. A service counts only if it resolves to one of this device's addresses
 * and its port is taken on loopback, so another phone on the same Wi-Fi is never picked.
 *
 * [onPort] gets the port when the service appears and null when it goes, on the main thread.
 */
@RequiresApi(Build.VERSION_CODES.R)
class AdbMdns(context: Context, private val serviceType: String, private val onPort: (Int?) -> Unit) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var running = false
    private var discovering = false
    /** The service [onPort] last reported, by name. */
    private var current: String? = null
    /** Before Android 14, NsdManager resolves one service at a time; the rest wait here. */
    private val pending = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    private val discovery = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(type: String) { main.post { discovering = true } }
        override fun onDiscoveryStopped(type: String) { main.post { discovering = false } }
        override fun onStartDiscoveryFailed(type: String, code: Int) {
            Log.w(TAG, "discovery of $type failed: $code")
            main.post { discovering = false }
        }
        override fun onStopDiscoveryFailed(type: String, code: Int) { Log.w(TAG, "stop of $type failed: $code") }
        override fun onServiceFound(info: NsdServiceInfo) { main.post { enqueue(info) } }
        override fun onServiceLost(info: NsdServiceInfo) {
            main.post {
                if (info.serviceName == current) { current = null; onPort(null) }
            }
        }
    }

    fun start() {
        if (running) return
        running = true
        nsd.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discovery)
    }

    fun stop() {
        if (!running) return
        running = false
        pending.clear()
        runCatching { nsd.stopServiceDiscovery(discovery) }
    }

    private fun enqueue(info: NsdServiceInfo) {
        if (!running) return
        pending.addLast(info)
        resolveNext()
    }

    @Suppress("DEPRECATION") // resolveService still works; its replacement needs Android 14.
    private fun resolveNext() {
        if (resolving || !running) return
        val info = pending.removeFirstOrNull() ?: return
        resolving = true
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(failed: NsdServiceInfo, code: Int) {
                main.post {
                    resolving = false
                    // Another resolve was still running: try this one again after it.
                    if (code == NsdManager.FAILURE_ALREADY_ACTIVE) main.postDelayed({ enqueue(failed) }, RETRY_MS)
                    resolveNext()
                }
            }
            override fun onServiceResolved(resolved: NsdServiceInfo) {
                main.post {
                    resolving = false
                    if (running) {
                        val host = resolved.host?.hostAddress
                        val port = resolved.port
                        Thread {
                            val mine = host != null && isOwnAddress(host) && portTakenOnLoopback(port)
                            main.post {
                                if (running && mine) { current = resolved.serviceName; onPort(port) }
                            }
                        }.start()
                    }
                    resolveNext()
                }
            }
        })
    }

    companion object {
        const val TLS_PAIRING = "_adb-tls-pairing._tcp"
        const val TLS_CONNECT = "_adb-tls-connect._tcp"
        private const val TAG = "SelkieAdbMdns"
        private const val HOST = "127.0.0.1"
        private const val RETRY_MS = 300L

        private fun isOwnAddress(host: String): Boolean = runCatching {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .flatMap { it.inetAddresses.asSequence() }
                .any { it.hostAddress == host }
        }.getOrDefault(false)

        /** adbd is listening there if we can't bind it. */
        private fun portTakenOnLoopback(port: Int): Boolean = try {
            ServerSocket().use { it.bind(InetSocketAddress(HOST, port), 1) }
            false
        } catch (_: IOException) {
            true
        }
    }
}

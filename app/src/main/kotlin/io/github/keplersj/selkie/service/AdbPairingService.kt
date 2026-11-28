package io.github.keplersj.selkie.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.github.keplersj.selkie.engine.EngineEnv
import io.github.keplersj.selkie.link.AdbRequests
import io.github.keplersj.selkie.platform.adb.AdbMdns

/**
 * Takes a Wireless debugging pairing code from a notification, the way Shizuku does, for `droidc adb pair`
 * in a container: the user opens Wireless debugging (Selkie links straight to it) and taps "Pair device with
 * pairing code"; Selkie finds that pairing service by mDNS and asks for the six-digit code in the
 * notification, so Settings can stay on screen. The port and code go back to the guest ([begin]'s callback),
 * whose own adb pairs, so the key stays in the container.
 */
@RequiresApi(Build.VERSION_CODES.R)
class AdbPairingService : Service() {
    private var search: AdbMdns? = null
    private var container = EngineEnv.DEFAULT_CONTAINER
    private val main = Handler(Looper.getMainLooper())
    private val giveUp = Runnable { finish(Outcome.Stopped("Nobody typed a pairing code in time")) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra(EXTRA_CONTAINER)?.takeIf { EngineEnv.isValidContainerName(it) }?.let { container = it }
        when (intent?.action) {
            ACTION_START -> {
                foreground(searching())
                startSearch()
                main.removeCallbacks(giveUp)
                main.postDelayed(giveUp, WAIT_MS)
            }
            ACTION_REPLY -> {
                val port = intent.getIntExtra(EXTRA_PORT, -1)
                val code = AdbRequests.cleanCode(RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_CODE))
                when {
                    port <= 0 -> { foreground(searching()); startSearch() }
                    code.isEmpty() -> foreground(found(port, "Type the six-digit code Settings shows."))
                    else -> finish(Outcome.Code(port, code))
                }
            }
            ACTION_STOP -> finish(Outcome.Stopped("Pairing was stopped from the notification"))
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        main.removeCallbacks(giveUp)
        search?.stop()
        deliver(Outcome.Stopped("Pairing stopped"))
        super.onDestroy()
    }

    private fun startSearch() {
        if (search != null) return
        search = AdbMdns(this, AdbMdns.TLS_PAIRING) { port ->
            if (port != null) notify(found(port, null)) else notify(searching())
        }.apply { start() }
    }

    /** Hands [outcome] to the guest, leaves the foreground and stops. */
    private fun finish(outcome: Outcome) {
        search?.stop(); search = null
        main.removeCallbacks(giveUp)
        deliver(outcome)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        if (outcome is Outcome.Code) notify(sent())
        else getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
        stopSelf()
    }

    private fun foreground(n: Notification) {
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, n, type)
        } catch (e: Exception) {
            // Not allowed from the background on some devices: the notification still works without it.
            Log.w(TAG, "startForeground failed", e)
            notify(n)
        }
    }

    private fun notify(n: Notification) = getSystemService(NotificationManager::class.java).notify(NOTIF_ID, n)

    private fun base(title: String): NotificationCompat.Builder = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(android.R.drawable.stat_notify_sync)
        .setContentTitle(title)
        .setOnlyAlertOnce(true)
        .setCategory(NotificationCompat.CATEGORY_STATUS)

    private fun searching(): Notification = base("Looking for the pairing code")
        .setContentText("In Wireless debugging, tap Pair device with pairing code.")
        .setStyle(NotificationCompat.BigTextStyle().bigText(
            "In Wireless debugging, tap Pair device with pairing code. Keep the dialog open and come back here " +
                "to type the code, so adb in $container can pair with this device."))
        .setOngoing(true)
        .addAction(0, "Open Wireless debugging", openWirelessDebugging(this))
        .addAction(0, "Stop", service(ACTION_STOP, REQ_STOP))
        .build()

    private fun found(port: Int, hint: String?): Notification {
        val input = RemoteInput.Builder(KEY_CODE).setLabel("Pairing code").build()
        val reply = PendingIntent.getForegroundService(this, REQ_REPLY,
            Intent(this, AdbPairingService::class.java).setAction(ACTION_REPLY)
                .putExtra(EXTRA_PORT, port).putExtra(EXTRA_CONTAINER, container),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val action = Notification.Action.Builder(null, "Enter pairing code", reply).addRemoteInput(input).build()
        // Platform builder: NotificationCompat's RemoteInput is a different class from the one read above.
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Pairing code found")
            .setContentText(hint ?: "Tap Enter pairing code and type the six digits Settings shows.")
            .setOngoing(true)
            .setOnlyAlertOnce(hint == null)
            .addAction(action)
            .addAction(Notification.Action.Builder(null, "Stop", service(ACTION_STOP, REQ_STOP)).build())
            .build()
    }

    private fun sent(): Notification = base("Pairing code sent")
        .setContentText("The terminal in $container shows whether adb paired.")
        .setAutoCancel(true)
        .setTimeoutAfter(SENT_SHOWN_MS)
        .build()

    private fun service(action: String, req: Int): PendingIntent = PendingIntent.getService(this, req,
        Intent(this, AdbPairingService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE)

    /** What came of one `droidc adb pair`. */
    sealed interface Outcome {
        data class Code(val port: Int, val code: String) : Outcome
        data class Stopped(val why: String) : Outcome
    }

    companion object {
        private const val TAG = "SelkieAdbPairing"
        const val CHANNEL = "adb_pairing"
        private const val NOTIF_ID = NotificationIds.ADB_PAIRING
        private const val ACTION_START = "io.github.keplersj.selkie.adb.START"
        private const val ACTION_REPLY = "io.github.keplersj.selkie.adb.REPLY"
        private const val ACTION_STOP = "io.github.keplersj.selkie.adb.STOP"
        private const val EXTRA_PORT = "port"
        private const val EXTRA_CONTAINER = "container"
        private const val KEY_CODE = "pairing_code"
        private const val REQ_REPLY = 1
        private const val REQ_STOP = 2
        private const val REQ_OPEN = 4
        /** As long as `droidc adb pair` waits. */
        private const val WAIT_MS = 10 * 60 * 1000L
        private const val SENT_SHOWN_MS = 15_000L

        /** The waiting `droidc adb pair`'s answer; one at a time. Main thread only. */
        private var waiting: ((Outcome) -> Unit)? = null

        private fun deliver(outcome: Outcome) {
            val cb = waiting ?: return
            waiting = null
            cb(outcome)
        }

        fun ensureChannel(context: Context) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Wireless debugging pairing", NotificationManager.IMPORTANCE_HIGH).apply {
                    setSound(null, null)
                    setShowBadge(false)
                })
        }

        /**
         * Developer options, scrolled to Wireless debugging and highlighting it, as Shizuku opens it. The
         * row's left part opens its page; the switch alone only turns it on.
         */
        fun wirelessDebuggingIntent(): Intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            .putExtra(":settings:fragment_args_key", "toggle_adb_wireless")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)

        private fun openWirelessDebugging(context: Context): PendingIntent =
            PendingIntent.getActivity(context, REQ_OPEN, wirelessDebuggingIntent(), PendingIntent.FLAG_IMMUTABLE)

        /**
         * Starts looking for the pairing service for [container], opens Wireless debugging, and calls
         * [onOutcome] once with the code or why there is none. An earlier request still waiting is told a
         * newer one took over. Main thread. Returns false if the service couldn't start.
         */
        fun begin(context: Context, container: String, onOutcome: (Outcome) -> Unit): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
            deliver(Outcome.Stopped("A newer droidc adb pair took over"))
            waiting = onOutcome
            try {
                context.startForegroundService(Intent(context, AdbPairingService::class.java)
                    .setAction(ACTION_START).putExtra(EXTRA_CONTAINER, container))
            } catch (e: Exception) {
                // Android refuses a foreground service from the background (no Selkie window showing).
                Log.w(TAG, "can't start pairing", e)
                waiting = null
                return false
            }
            try { context.startActivity(wirelessDebuggingIntent()) } catch (_: ActivityNotFoundException) {}
            return true
        }
    }
}

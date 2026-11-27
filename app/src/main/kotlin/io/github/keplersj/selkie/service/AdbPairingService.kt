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
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.github.keplersj.selkie.engine.EngineEnv
import io.github.keplersj.selkie.platform.adb.AdbBridge
import io.github.keplersj.selkie.platform.adb.AdbMdns
import io.github.keplersj.selkie.platform.adb.GuestAdb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Pairs a container's adb with this device from a notification, the way Shizuku does: the user opens
 * Wireless debugging (Selkie links straight to it) and taps "Pair device with pairing code"; Selkie finds
 * that pairing service by mDNS and asks for the six-digit code in the notification, so Settings can stay
 * on screen. The container's own adb then pairs ([AdbBridge.pair]) and stays connected.
 */
@RequiresApi(Build.VERSION_CODES.R)
class AdbPairingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var search: AdbMdns? = null
    private var container = EngineEnv.DEFAULT_CONTAINER

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra(EXTRA_CONTAINER)?.takeIf { EngineEnv.isValidContainerName(it) }?.let { container = it }
        when (intent?.action) {
            ACTION_START -> { foreground(searching()); startSearch() }
            ACTION_REPLY -> {
                val port = intent.getIntExtra(EXTRA_PORT, -1)
                val code = GuestAdb.cleanCode(RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_CODE))
                if (port <= 0) { foreground(searching()); startSearch() }
                else if (code.isEmpty()) foreground(found(port, "Type the six-digit code Settings shows."))
                else { foreground(working()); pair(port, code) }
            }
            ACTION_STOP -> finish(null)
            else -> return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        search?.stop()
        scope.cancel()
        super.onDestroy()
    }

    private fun startSearch() {
        if (search != null) return
        search = AdbMdns(this, AdbMdns.TLS_PAIRING) { port ->
            if (port != null) notify(found(port, null))
            else notify(searching())
        }.apply { start() }
    }

    private fun pair(port: Int, code: String) {
        search?.stop(); search = null
        val name = container
        scope.launch {
            val result = AdbBridge.get(this@AdbPairingService).pair(name, port, code)
            Log.i(TAG, "pairing $name: $result")
            finish(when (result) {
                GuestAdb.PairResult.Paired -> done("Paired with this device",
                    "adb in $name connects whenever Wireless debugging is on.", retry = false)
                GuestAdb.PairResult.WrongCode -> done("Pairing failed",
                    "The code was wrong or the dialog closed. Open the pairing code dialog again and retry.", retry = true)
                GuestAdb.PairResult.NoAdb -> done("No adb in $name", AdbBridge.noAdb(name), retry = false)
                is GuestAdb.PairResult.Failed -> done("Pairing failed", result.message, retry = true)
            })
        }
    }

    /** Leaves the foreground, posting [last] as an ordinary notification if there is one, and stops. */
    private fun finish(last: Notification?) {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        if (last != null) notify(last)
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
                "to type the code. $container's adb pairs with this device."))
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

    private fun working(): Notification = base("Pairing…")
        .setContentText("$container's adb is pairing with this device.")
        .setProgress(0, 0, true)
        .setOngoing(true)
        .build()

    private fun done(title: String, text: String, retry: Boolean): Notification = base(title)
        .setContentText(text)
        .setStyle(NotificationCompat.BigTextStyle().bigText(text))
        .setAutoCancel(true)
        .apply { if (retry) addAction(0, "Try again", startPending(this@AdbPairingService, container)) }
        .build()

    private fun service(action: String, req: Int): PendingIntent = PendingIntent.getService(this, req,
        Intent(this, AdbPairingService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE)

    companion object {
        private const val TAG = "SelkieAdbPairing"
        const val CHANNEL = "adb_pairing"
        private const val NOTIF_ID = 2
        private const val ACTION_START = "io.github.keplersj.selkie.adb.START"
        private const val ACTION_REPLY = "io.github.keplersj.selkie.adb.REPLY"
        private const val ACTION_STOP = "io.github.keplersj.selkie.adb.STOP"
        private const val EXTRA_PORT = "port"
        private const val EXTRA_CONTAINER = "container"
        private const val KEY_CODE = "pairing_code"
        private const val REQ_REPLY = 1
        private const val REQ_STOP = 2
        private const val REQ_RETRY = 3
        private const val REQ_OPEN = 4

        fun ensureChannel(context: Context) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Wireless debugging pairing", NotificationManager.IMPORTANCE_HIGH).apply {
                    setSound(null, null)
                    setShowBadge(false)
                })
        }

        fun startIntent(context: Context, container: String): Intent =
            Intent(context, AdbPairingService::class.java).setAction(ACTION_START).putExtra(EXTRA_CONTAINER, container)

        private fun startPending(context: Context, container: String): PendingIntent =
            PendingIntent.getForegroundService(context, REQ_RETRY, startIntent(context, container),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        /**
         * Developer options, scrolled to Wireless debugging and highlighting it, as Shizuku opens it. The
         * row's left part opens its page; the switch alone only turns it on.
         */
        fun wirelessDebuggingIntent(): Intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            .putExtra(":settings:fragment_args_key", "toggle_adb_wireless")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)

        private fun openWirelessDebugging(context: Context): PendingIntent =
            PendingIntent.getActivity(context, REQ_OPEN, wirelessDebuggingIntent(), PendingIntent.FLAG_IMMUTABLE)

        /** Starts looking for the pairing service and opens Wireless debugging. Returns false if it can't. */
        fun begin(context: Context, container: String): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
            context.startForegroundService(startIntent(context, container))
            return try { context.startActivity(wirelessDebuggingIntent()); true } catch (_: ActivityNotFoundException) { true }
        }
    }
}

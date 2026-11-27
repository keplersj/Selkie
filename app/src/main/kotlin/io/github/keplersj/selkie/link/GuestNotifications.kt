package io.github.keplersj.selkie.link

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import io.github.keplersj.selkie.MainActivity
import io.github.keplersj.selkie.service.SessionService
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One button on a guest notification. [reply] buttons take typed text. */
data class GuestAction(val id: String, val label: String, val reply: Boolean = false)

/**
 * A notification from an app in the guest, as `droidc host-link` reports it in a `notify` line. The [key]
 * stays the same across updates of one notification.
 */
data class GuestNotification(
    val key: String,
    val title: String,
    val body: String = "",
    val appId: String? = null,
    val appName: String? = null,
    /** `low`, `normal`, `high` or `urgent`. */
    val priority: String = "normal",
    val category: String? = null,
    val actions: List<GuestAction> = emptyList(),
) {
    /** What the notification shade shows as the sender: the app's own name, else the last part of its ID. */
    val source: String? get() = appName ?: appId?.substringAfterLast('.')

    companion object {
        private fun JsonObject.str(k: String): String? =
            (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content

        /** Null when the line has no key. */
        fun parse(o: JsonObject): GuestNotification? {
            val key = o.str("key")?.takeIf { it.isNotEmpty() } ?: return null
            val actions = (o["actions"] as? JsonArray).orEmpty().mapNotNull { a ->
                val b = a as? JsonObject ?: return@mapNotNull null
                GuestAction(
                    id = b.str("id") ?: return@mapNotNull null,
                    label = b.str("label") ?: return@mapNotNull null,
                    reply = (b["reply"] as? JsonPrimitive)?.booleanOrNull == true,
                )
            }
            return GuestNotification(
                key = key,
                title = o.str("title").orEmpty(),
                body = o.str("body").orEmpty(),
                appId = o.str("app_id"),
                appName = o.str("app_name"),
                priority = o.str("priority") ?: "normal",
                category = o.str("category"),
                actions = actions,
            )
        }

        /** The host's tap on a button ([action]) or on the notification itself (null), with typed [text]. */
        fun actionLine(key: String, action: String?, text: String?): String = buildJsonObject {
            put("kind", "notification-action")
            put("key", key)
            action?.let { put("action", it) }
            text?.let { put("text", it) }
        }.toString()

        /** The user dismissed it on Android. */
        fun closedLine(key: String): String = buildJsonObject {
            put("kind", "notification-closed")
            put("key", key)
        }.toString()

        /** Android's category for a freedesktop one (`im.received`, `email.arrived`, …). */
        fun androidCategory(category: String?): String? = when (category?.substringBefore('.')) {
            "im" -> NotificationCompat.CATEGORY_MESSAGE
            "email" -> NotificationCompat.CATEGORY_EMAIL
            "alarm" -> NotificationCompat.CATEGORY_ALARM
            "call" -> NotificationCompat.CATEGORY_CALL
            "transfer" -> NotificationCompat.CATEGORY_PROGRESS
            "device", "network", "battery" -> NotificationCompat.CATEGORY_SYSTEM
            else -> null
        }

        /** Which of the three desktop channels a priority goes to. */
        fun channelFor(priority: String): String = when (priority) {
            "low" -> CHANNEL_LOW
            "high", "urgent" -> CHANNEL_URGENT
            else -> CHANNEL
        }

        const val CHANNEL = "guest"
        const val CHANNEL_URGENT = "guest_urgent"
        const val CHANNEL_LOW = "guest_low"
    }
}

/**
 * Shows the guest's notifications in Android's shade, from which Wear OS bridges them to a paired watch
 * (replies included). Taps, button presses and dismissals come back to [SessionService] as intents
 * ([ACTION_TAP], [ACTION_BUTTON], [ACTION_DISMISSED]) and go to the guest as host-link lines.
 */
class GuestNotifier(private val context: Context) {
    private val shown = mutableSetOf<String>()
    private var channelsMade = false

    private fun ensureChannels() {
        if (channelsMade) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannelGroup(NotificationChannelGroup(GROUP, "Desktop apps"))
        listOf(
            Triple(GuestNotification.CHANNEL, "Desktop notifications", NotificationManager.IMPORTANCE_DEFAULT),
            Triple(GuestNotification.CHANNEL_URGENT, "Urgent desktop notifications", NotificationManager.IMPORTANCE_HIGH),
            Triple(GuestNotification.CHANNEL_LOW, "Quiet desktop notifications", NotificationManager.IMPORTANCE_LOW),
        ).forEach { (id, name, importance) ->
            nm.createNotificationChannel(NotificationChannel(id, name, importance).apply { group = GROUP })
        }
        channelsMade = true
    }

    private fun allowed(): Boolean = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED

    private fun serviceIntent(action: String, key: String, button: String? = null) =
        Intent(context, SessionService::class.java).setAction(action)
            .putExtra(EXTRA_KEY, key).apply { button?.let { putExtra(EXTRA_BUTTON, it) } }

    private fun requestCode(vararg parts: String) = parts.joinToString("\u0000").hashCode()

    @Synchronized
    fun show(n: GuestNotification) {
        if (!allowed()) return
        ensureChannels()
        val tap = PendingIntent.getActivity(context, requestCode("tap", n.key),
            Intent(context, MainActivity::class.java).setAction(ACTION_TAP).putExtra(EXTRA_KEY, n.key)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val dismissed = PendingIntent.getService(context, requestCode("dismissed", n.key),
            serviceIntent(ACTION_DISMISSED, n.key),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = NotificationCompat.Builder(context, GuestNotification.channelFor(n.priority))
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(n.title)
            .setContentIntent(tap)
            .setDeleteIntent(dismissed)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .extend(NotificationCompat.WearableExtender().setDismissalId(n.key))
        if (n.body.isNotEmpty()) {
            b.setContentText(n.body).setStyle(NotificationCompat.BigTextStyle().bigText(n.body))
        }
        n.source?.let { b.setSubText(it) }
        GuestNotification.androidCategory(n.category)?.let { b.setCategory(it) }
        b.setPriority(when (n.priority) {
            "low" -> NotificationCompat.PRIORITY_LOW
            "high", "urgent" -> NotificationCompat.PRIORITY_HIGH
            else -> NotificationCompat.PRIORITY_DEFAULT
        })
        for (a in n.actions.take(3)) {
            val code = requestCode("button", n.key, a.id)
            if (a.reply) {
                // RemoteInput fills the intent in, so this one must be mutable.
                val pi = PendingIntent.getService(context, code, serviceIntent(ACTION_BUTTON, n.key, a.id),
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                val input = RemoteInput.Builder(REPLY_TEXT).setLabel(a.label).build()
                b.addAction(NotificationCompat.Action.Builder(0, a.label, pi)
                    .addRemoteInput(input)
                    .setAllowGeneratedReplies(true)
                    .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
                    .setShowsUserInterface(false)
                    .build())
            } else {
                val pi = PendingIntent.getService(context, code, serviceIntent(ACTION_BUTTON, n.key, a.id),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                b.addAction(NotificationCompat.Action.Builder(0, a.label, pi).setShowsUserInterface(false).build())
            }
        }
        try {
            NotificationManagerCompat.from(context).notify(n.key, NOTIFICATION_ID, b.build())
            shown += n.key
        } catch (e: SecurityException) {
            android.util.Log.w(TAG, "desktop notification not shown", e)
        }
    }

    /** Takes one down without telling the guest (it asked, or the user already acted on it). */
    @Synchronized
    fun cancel(key: String) {
        NotificationManagerCompat.from(context).cancel(key, NOTIFICATION_ID)
        shown -= key
    }

    /** The desktop or its link went away: none of its notifications can be acted on any more. */
    @Synchronized
    fun cancelAll() {
        val nm = NotificationManagerCompat.from(context)
        shown.forEach { nm.cancel(it, NOTIFICATION_ID) }
        shown.clear()
    }

    companion object {
        const val ACTION_TAP = "io.github.keplersj.selkie.GUEST_NOTIFICATION_TAP"
        const val ACTION_BUTTON = "io.github.keplersj.selkie.GUEST_NOTIFICATION_BUTTON"
        const val ACTION_DISMISSED = "io.github.keplersj.selkie.GUEST_NOTIFICATION_DISMISSED"
        const val EXTRA_KEY = "guest_notification_key"
        const val EXTRA_BUTTON = "guest_notification_button"
        const val REPLY_TEXT = "guest_notification_reply"
        private const val GROUP = "guest"
        /** Notifications are told apart by their tag (the key); the ID only keeps them off the service's. */
        private const val NOTIFICATION_ID = 0x5e1c
        private const val TAG = "SelkieNotify"

        /** Typed reply text in a button's intent, if any. */
        fun replyText(intent: Intent): String? =
            RemoteInput.getResultsFromIntent(intent)?.getCharSequence(REPLY_TEXT)?.toString()
    }
}

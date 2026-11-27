package io.github.keplersj.selkie.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.File
import java.net.URI

/**
 * The guest's active media player as an Android [MediaSession], with a media notification: what the
 * desktop plays shows on the lock screen and in the shade, and its buttons, a headset's and the
 * system's media controls go back to the player through [send]. [hostPath] finds a `file://` cover in
 * the container. Call [update] with each snapshot and [release] when the desktop stops.
 */
class MediaBridge(
    private val context: Context,
    private val hostPath: (String) -> File?,
    private val send: (String) -> Unit,
    /** Opens Selkie from the notification. */
    private val contentIntent: PendingIntent?,
    /** Builds the intent a notification button sends back (to [handleAction]) for a verb. */
    private val actionIntent: (String) -> PendingIntent,
) {
    private val main = Handler(Looper.getMainLooper())
    private val nm = context.getSystemService(NotificationManager::class.java)
    private val session = MediaSession(context, "Selkie desktop")
    @Volatile private var current: GuestPlayer? = null
    private var art: Pair<String, Bitmap?>? = null

    val token: MediaSession.Token get() = session.sessionToken

    init {
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Desktop media", NotificationManager.IMPORTANCE_LOW)
            .apply { setShowBadge(false) })
        session.setCallback(object : MediaSession.Callback() {
            override fun onPlay() = command("play")
            override fun onPause() = command("pause")
            override fun onStop() = command("stop")
            override fun onSkipToNext() = command("next")
            override fun onSkipToPrevious() = command("previous")
            override fun onSeekTo(pos: Long) = command("seek-to", pos * 1000)
        }, main)
    }

    /** A notification button's verb, from [actionIntent]. */
    fun handleAction(verb: String) {
        if (verb in MediaMapping.VERBS) command(verb)
    }

    private fun command(verb: String, arg: Long? = null) {
        val p = current ?: return
        mediaCommand(verb, p.name, arg)?.let(send)
    }

    fun update(snapshot: GuestMediaSnapshot) = main.post { show(snapshot.current) }

    fun release() = main.post {
        current = null
        nm.cancel(NOTIF_ID)
        session.isActive = false
        session.release()
    }

    private fun show(p: GuestPlayer?) {
        current = p
        if (p == null) {
            nm.cancel(NOTIF_ID)
            session.isActive = false
            return
        }
        val cover = cover(p.artUrl)
        session.setMetadata(MediaMetadata.Builder().apply {
            putString(MediaMetadata.METADATA_KEY_TITLE, p.title.ifEmpty { p.identity })
            putString(MediaMetadata.METADATA_KEY_ARTIST, p.artist)
            putString(MediaMetadata.METADATA_KEY_ALBUM, p.album)
            putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, MediaMapping.subtitle(p))
            p.lengthUs?.let { putLong(MediaMetadata.METADATA_KEY_DURATION, it / 1000) }
            cover?.let { putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) }
        }.build())
        session.setPlaybackState(PlaybackState.Builder()
            .setActions(MediaMapping.actions(p))
            .setState(MediaMapping.state(p), MediaMapping.positionMs(p), MediaMapping.speed(p),
                SystemClock.elapsedRealtime())
            .build())
        session.isActive = true
        nm.notify(NOTIF_ID, notification(p, cover))
    }

    private fun notification(p: GuestPlayer, cover: Bitmap?): Notification {
        val playing = p.status == "Playing"
        val actions = buildList {
            if (p.canPrevious) add(Notification.Action.Builder(null, "Previous", actionIntent("previous")).build())
            if (if (playing) p.canPause else p.canPlay)
                add(Notification.Action.Builder(null, if (playing) "Pause" else "Play", actionIntent("play-pause")).build())
            if (p.canNext) add(Notification.Action.Builder(null, "Next", actionIntent("next")).build())
        }
        return Notification.Builder(context, CHANNEL)
            .setSmallIcon(if (playing) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause)
            .setContentTitle(p.title.ifEmpty { p.identity })
            .setContentText(MediaMapping.subtitle(p))
            .setLargeIcon(cover)
            .setContentIntent(contentIntent)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(playing)
            .apply { actions.forEach { addAction(it) } }
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken)
                .setShowActionsInCompactView(*actions.indices.toList().toIntArray()))
            .build()
    }

    /** The player's cover, read from the container, cached by URL; none for web or missing art. */
    private fun cover(url: String): Bitmap? {
        art?.let { (u, b) -> if (u == url) return b }
        val b = MediaMapping.guestFilePath(url)?.let(hostPath)?.let { decode(it) }
        art = url to b
        return b
    }

    private fun decode(f: File): Bitmap? = try {
        if (!f.isFile || f.length() > MAX_ART_BYTES) null else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_ART_PX) sample *= 2
            BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    } catch (e: Exception) { null }

    companion object {
        private const val CHANNEL = "media"
        const val NOTIF_ID = 2
        private const val MAX_ART_BYTES = 8L * 1024 * 1024
        private const val MAX_ART_PX = 512
    }
}

/** How a guest player maps onto Android's media session. Pure, so it is unit-tested. */
object MediaMapping {
    val VERBS = listOf("play", "pause", "play-pause", "stop", "next", "previous")

    fun state(p: GuestPlayer): Int = when (p.status) {
        "Playing" -> PlaybackState.STATE_PLAYING
        "Paused" -> PlaybackState.STATE_PAUSED
        else -> PlaybackState.STATE_STOPPED
    }

    fun actions(p: GuestPlayer): Long {
        var a = PlaybackState.ACTION_STOP
        if (p.canPlay) a = a or PlaybackState.ACTION_PLAY
        if (p.canPause) a = a or PlaybackState.ACTION_PAUSE
        if (p.canPlay || p.canPause) a = a or PlaybackState.ACTION_PLAY_PAUSE
        if (p.canNext) a = a or PlaybackState.ACTION_SKIP_TO_NEXT
        if (p.canPrevious) a = a or PlaybackState.ACTION_SKIP_TO_PREVIOUS
        if (p.canSeek && p.lengthUs != null) a = a or PlaybackState.ACTION_SEEK_TO
        return a
    }

    fun positionMs(p: GuestPlayer): Long = p.positionUs?.let { it / 1000 } ?: PlaybackState.PLAYBACK_POSITION_UNKNOWN

    fun speed(p: GuestPlayer): Float = if (p.status == "Playing") 1f else 0f

    /** Artist and album, or the player's name when the track gives neither. */
    fun subtitle(p: GuestPlayer): String =
        listOf(p.artist, p.album).filter { it.isNotEmpty() }.joinToString(" · ").ifEmpty { p.identity }

    /** The guest path of a `file://` URL; null for anything else (web art isn't fetched). */
    fun guestFilePath(url: String): String? = runCatching {
        val u = URI(url)
        if (u.scheme != "file" || u.host?.isNotEmpty() == true) null else u.path?.takeIf { it.startsWith("/") }
    }.getOrNull()
}

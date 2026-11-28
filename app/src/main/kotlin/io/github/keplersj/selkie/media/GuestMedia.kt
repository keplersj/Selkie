package io.github.keplersj.selkie.media

import android.os.SystemClock
import io.github.keplersj.selkie.audio.RestartPolicy
import io.github.keplersj.selkie.engine.SelkieJson
import io.github.keplersj.selkie.proot.Invocation
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File
import java.io.OutputStream

/** One of the guest's media players, as `droidc media` reports it. */
@Serializable
data class GuestPlayer(
    val name: String,
    val identity: String = "",
    /** `Playing`, `Paused` or `Stopped`. */
    val status: String = "Stopped",
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    /** As the player gives it; a `file://` path is in the guest's filesystem. */
    @SerialName("art_url") val artUrl: String = "",
    @SerialName("length_us") val lengthUs: Long? = null,
    @SerialName("position_us") val positionUs: Long? = null,
    @SerialName("can_play") val canPlay: Boolean = false,
    @SerialName("can_pause") val canPause: Boolean = false,
    @SerialName("can_next") val canNext: Boolean = false,
    @SerialName("can_previous") val canPrevious: Boolean = false,
    @SerialName("can_seek") val canSeek: Boolean = false,
)

/** One line of `droidc media`: the guest's players and the one to show. */
@Serializable
data class GuestMediaSnapshot(
    val active: String? = null,
    val players: List<GuestPlayer> = emptyList(),
) {
    val current: GuestPlayer? get() = players.firstOrNull { it.name == active }

    companion object {
        /** Parses one line, or null for anything that isn't one. */
        fun parse(line: String): GuestMediaSnapshot? =
            runCatching { SelkieJson.decodeFromString(serializer(), line) }.getOrNull()
    }
}

/** A command line for `droidc media`'s stdin, or null if [player] can't be named safely. */
fun mediaCommand(verb: String, player: String, arg: Long? = null): String? {
    if (player.isEmpty() || player.any { it.isWhitespace() }) return null
    return listOfNotNull(verb, player, arg?.toString()).joinToString(" ")
}

/**
 * The guest's media players while the desktop runs: `droidc media` in the container, whose JSON lines go
 * to [onSnapshot] and whose stdin takes [send]'s commands. Restarted like [io.github.keplersj.selkie.audio.GuestAudio],
 * and given up on if it keeps dying right away (a droidc without `media`, say).
 */
class GuestMedia(
    private val inv: Invocation,
    private val log: File,
    private val onSnapshot: (GuestMediaSnapshot) -> Unit,
) {
    @Volatile private var stopped = false
    @Volatile private var process: Process? = null
    @Volatile private var stdin: OutputStream? = null
    private var thread: Thread? = null

    /** The latest snapshot, for tests and diagnostics. */
    @Volatile var latest: GuestMediaSnapshot? = null
        private set

    fun start() {
        thread = Thread({ loop() }, "selkie-guest-media").apply { isDaemon = true; start() }
    }

    fun stop() {
        stopped = true
        process?.let { p ->
            p.destroy()
            Thread { if (!p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly() }.start()
        }
        thread?.interrupt()
        thread = null
        onSnapshot(GuestMediaSnapshot())
    }

    /** Sends one command line; dropped when `droidc media` isn't running. */
    fun send(line: String) {
        val out = stdin ?: return
        Thread {
            try { synchronized(this) { out.write("$line\n".toByteArray()); out.flush() } }
            catch (e: Exception) { android.util.Log.w(TAG, "media command '$line' not sent", e) }
        }.start()
    }

    private fun loop() {
        val policy = RestartPolicy()
        while (!stopped) {
            val began = SystemClock.elapsedRealtime()
            val code = runOnce()
            if (stopped) break
            publish(GuestMediaSnapshot())
            val ran = SystemClock.elapsedRealtime() - began
            android.util.Log.i(TAG, "media exited with $code after ${ran}ms")
            val wait = policy.next(ran) ?: run {
                android.util.Log.w(TAG, "media keeps failing; no media controls (see ${log.name})")
                return
            }
            try { Thread.sleep(wait) } catch (_: InterruptedException) { break }
        }
    }

    private fun runOnce(): Int {
        val p = try {
            ProcessBuilder(inv.argv).redirectError(ProcessBuilder.Redirect.appendTo(log))
                .apply { environment().apply { clear(); putAll(inv.env) } }
                .start()
        } catch (e: Exception) {
            android.util.Log.w(TAG, "media failed to start", e); return -1
        }
        process = p
        stdin = p.outputStream
        if (stopped) p.destroy()
        try {
            p.inputStream.bufferedReader().forEachLine { line ->
                GuestMediaSnapshot.parse(line)?.let { publish(it) }
            }
        } catch (e: Exception) {
            if (!stopped) android.util.Log.w(TAG, "media stream ended", e)
        }
        stdin = null
        runCatching { p.outputStream.close() }
        return try { p.waitFor() } catch (_: InterruptedException) { -1 }
    }

    private fun publish(s: GuestMediaSnapshot) {
        latest = s
        if (!stopped) onSnapshot(s)
    }

    private companion object {
        const val TAG = "SelkieMedia"
    }
}

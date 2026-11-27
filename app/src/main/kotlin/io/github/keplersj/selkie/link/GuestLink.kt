package io.github.keplersj.selkie.link

import android.os.SystemClock
import io.github.keplersj.selkie.audio.RestartPolicy
import io.github.keplersj.selkie.engine.SelkieJson
import io.github.keplersj.selkie.proot.Invocation
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.OutputStream

/**
 * One line from `droidc host-link`: its [kind] and the whole object, for the handler of that kind.
 * Null for anything that isn't a JSON object with a string `kind`.
 */
data class GuestLinkMessage(val kind: String, val body: JsonObject) {
    companion object {
        fun parse(line: String): GuestLinkMessage? = runCatching {
            val o = SelkieJson.parseToJsonElement(line) as JsonObject
            GuestLinkMessage(o.getValue("kind").jsonPrimitive.content, o)
        }.getOrNull()
    }
}

/**
 * The desktop's channel to Android while it runs: `droidc host-link` in the container. Android state goes
 * in with [send] (one JSON line per call), and the guest's lines come back through [onMessage]. Each time
 * the link comes up it says `ready`, and the caller sends the state again, since a restarted link starts
 * from nothing. Restarted like [io.github.keplersj.selkie.audio.GuestAudio], and given up on if it keeps
 * dying right away (a droidc without `host-link`, say).
 */
class GuestLink(
    private val inv: Invocation,
    private val log: File,
    private val onMessage: (GuestLinkMessage) -> Unit,
) {
    @Volatile private var stopped = false
    @Volatile private var process: Process? = null
    @Volatile private var stdin: OutputStream? = null
    private var thread: Thread? = null
    private val writeLock = Any()

    /** Whether the link has said `ready` since it last started, for tests and diagnostics. */
    @Volatile var ready = false
        private set

    fun start() {
        thread = Thread({ loop() }, "selkie-guest-link").apply { isDaemon = true; start() }
    }

    fun stop() {
        stopped = true
        process?.let { p ->
            p.destroy()
            Thread { if (!p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly() }.start()
        }
        thread?.interrupt()
        thread = null
    }

    /** Sends one line; dropped when the link isn't up (the next `ready` brings the state again). */
    fun send(line: String) {
        val out = stdin ?: return
        Thread {
            try { synchronized(writeLock) { out.write("$line\n".toByteArray()); out.flush() } }
            catch (e: Exception) { android.util.Log.w(TAG, "host-link line not sent", e) }
        }.start()
    }

    private fun loop() {
        val policy = RestartPolicy()
        while (!stopped) {
            val began = SystemClock.elapsedRealtime()
            val code = runOnce()
            if (stopped) break
            val ran = SystemClock.elapsedRealtime() - began
            android.util.Log.i(TAG, "host-link exited with $code after ${ran}ms")
            val wait = policy.next(ran) ?: run {
                android.util.Log.w(TAG, "host-link keeps failing; the desktop won't follow Android (see ${log.name})")
                return
            }
            try { Thread.sleep(wait) } catch (_: InterruptedException) { break }
        }
    }

    private fun runOnce(): Int {
        ready = false
        val p = try {
            ProcessBuilder(inv.argv).redirectError(ProcessBuilder.Redirect.appendTo(log))
                .apply { environment().apply { clear(); putAll(inv.env) } }
                .start()
        } catch (e: Exception) {
            android.util.Log.w(TAG, "host-link failed to start", e); return -1
        }
        process = p
        stdin = p.outputStream
        if (stopped) p.destroy()
        try {
            p.inputStream.bufferedReader().forEachLine { line ->
                val m = GuestLinkMessage.parse(line) ?: return@forEachLine
                if (m.kind == "ready") ready = true
                if (!stopped) onMessage(m)
            }
        } catch (e: Exception) {
            if (!stopped) android.util.Log.w(TAG, "host-link stream ended", e)
        }
        stdin = null
        ready = false
        runCatching { p.outputStream.close() }
        return try { p.waitFor() } catch (_: InterruptedException) { -1 }
    }

    private companion object {
        const val TAG = "SelkieLink"
    }
}

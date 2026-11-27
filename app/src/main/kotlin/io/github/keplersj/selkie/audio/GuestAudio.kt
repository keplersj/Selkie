package io.github.keplersj.selkie.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import io.github.keplersj.selkie.proot.Invocation
import java.io.File
import java.io.InputStream

/**
 * The guest's sound, played on Android: runs `droidc audio-out` in the container (a PipeWire sink that
 * becomes the guest's default output, recorded as raw S16LE) and writes what it prints to an
 * [AudioTrack]. Started with the desktop and stopped with it. If the recorder exits it starts again,
 * unless it keeps dying right away (an image or droidc without audio-out, say), when it gives up and
 * the desktop carries on without sound.
 */
class GuestAudio(private val inv: Invocation, private val log: File) {
    @Volatile private var stopped = false
    /** Bytes read from `audio-out`, and of those written to the [AudioTrack], for tests and diagnostics. */
    @Volatile var received = 0L
        private set
    @Volatile var played = 0L
        private set
    @Volatile private var process: Process? = null
    private var thread: Thread? = null

    fun start() {
        thread = Thread({ loop() }, "selkie-guest-audio").apply { isDaemon = true; start() }
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

    private fun loop() {
        val policy = RestartPolicy()
        val track = try { newTrack() } catch (e: Exception) {
            android.util.Log.w(TAG, "no audio track", e); return
        }
        try {
            track.play()
            while (!stopped) {
                val began = SystemClock.elapsedRealtime()
                val code = runOnce(track)
                if (stopped) break
                val ran = SystemClock.elapsedRealtime() - began
                android.util.Log.i(TAG, "audio-out exited with $code after ${ran}ms")
                val wait = policy.next(ran) ?: run {
                    android.util.Log.w(TAG, "audio-out keeps failing; the desktop has no sound (see ${log.name})")
                    return
                }
                try { Thread.sleep(wait) } catch (_: InterruptedException) { break }
            }
        } finally {
            track.pause(); track.flush(); track.release()
        }
    }

    private fun runOnce(track: AudioTrack): Int {
        val p = try {
            ProcessBuilder(inv.argv).redirectError(ProcessBuilder.Redirect.appendTo(log))
                .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
                .apply { environment().apply { clear(); putAll(inv.env) } }
                .start()
        } catch (e: Exception) {
            android.util.Log.w(TAG, "audio-out failed to start", e); return -1
        }
        process = p
        if (stopped) p.destroy()
        try {
            pumpFrames(p.inputStream, FRAME_BYTES) { buf, n ->
                received += n
                var off = 0
                while (off < n && !stopped) {
                    val w = track.write(buf, off, n - off)
                    if (w < 0) error("AudioTrack.write returned $w")
                    off += w
                }
                played += n
            }
        } catch (e: Exception) {
            if (!stopped) android.util.Log.w(TAG, "audio stream ended", e)
        }
        p.inputStream.close()
        return try { p.waitFor() } catch (_: InterruptedException) { -1 }
    }

    private fun newTrack(): AudioTrack {
        val format = AudioFormat.Builder().setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
        val min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        return AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(min, BUFFER_BYTES))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    companion object {
        private const val TAG = "SelkieAudio"
        /** What `droidc audio-out` prints by default: 48 kHz, stereo, 16-bit little-endian. */
        const val RATE = 48_000
        const val CHANNELS = 2
        const val FRAME_BYTES = CHANNELS * 2
        /** About 80 ms: enough to ride over a slow proot read, short enough that sound keeps up with the screen. */
        private const val BUFFER_BYTES = RATE * FRAME_BYTES * 80 / 1000
    }
}

/**
 * Copies [input] to [write] in whole frames of [frameBytes] each, so a sample is never split between
 * two writes; a trailing partial frame is dropped. Returns the number of bytes written.
 */
fun pumpFrames(input: InputStream, frameBytes: Int, bufferBytes: Int = 4096, write: (ByteArray, Int) -> Unit): Long {
    require(frameBytes > 0 && bufferBytes >= frameBytes)
    val buf = ByteArray(bufferBytes - bufferBytes % frameBytes)
    var have = 0
    var total = 0L
    while (true) {
        val n = input.read(buf, have, buf.size - have)
        if (n < 0) return total
        have += n
        val whole = have - have % frameBytes
        if (whole > 0) {
            write(buf, whole)
            total += whole
            System.arraycopy(buf, whole, buf, 0, have - whole)
            have -= whole
        }
    }
}

/**
 * When to start `audio-out` again. A run that lasted [healthyMs] or more resets the count; otherwise
 * each quick failure waits longer, and after [maxQuick] in a row it gives up (null).
 */
class RestartPolicy(private val healthyMs: Long = 10_000, private val maxQuick: Int = 3,
                    private val baseDelayMs: Long = 1_000) {
    private var quick = 0
    fun next(ranMs: Long): Long? {
        if (ranMs >= healthyMs) quick = 0
        quick++
        if (quick > maxQuick) return null
        return baseDelayMs * (1L shl (quick - 1))
    }
}

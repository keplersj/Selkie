package io.github.keplersj.selkie.audio

import io.github.keplersj.selkie.TestEnv
import io.github.keplersj.selkie.engine.ShellSpec
import io.github.keplersj.selkie.proot.ProotCommandBuilder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

class GuestAudioTest {
    /** Hands out at most [step] bytes per read, like a pipe that a slow writer fills. */
    private class Trickle(private val data: ByteArray, private val step: Int) : InputStream() {
        private var pos = 0
        override fun read(): Int = if (pos < data.size) data[pos++].toInt() and 0xff else -1
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos >= data.size) return -1
            val n = minOf(len, step, data.size - pos)
            System.arraycopy(data, pos, b, off, n); pos += n
            return n
        }
    }

    @Test fun writesOnlyWholeFramesEvenWhenReadsSplitThem() {
        val data = ByteArray(4 * 1000 + 3) { it.toByte() }
        val out = ByteArrayOutputStream()
        val sizes = mutableListOf<Int>()
        val total = pumpFrames(Trickle(data, 7), frameBytes = 4, bufferBytes = 64) { buf, n ->
            sizes += n; out.write(buf, 0, n)
        }
        assertEquals(4000L, total)
        assertTrue(sizes.all { it % 4 == 0 })
        // The trailing partial frame is dropped; everything before it comes out in order.
        assertArrayEquals(data.copyOf(4000), out.toByteArray())
    }

    @Test fun anEmptyStreamWritesNothing() {
        var calls = 0
        assertEquals(0L, pumpFrames(ByteArray(0).inputStream(), 4) { _, _ -> calls++ })
        assertEquals(0, calls)
    }

    @Test fun quickFailuresBackOffThenGiveUp() {
        val p = RestartPolicy(healthyMs = 10_000, maxQuick = 3, baseDelayMs = 1_000)
        assertEquals(1_000L, p.next(200))
        assertEquals(2_000L, p.next(200))
        assertEquals(4_000L, p.next(200))
        assertNull(p.next(200))
    }

    @Test fun aLongRunResetsTheCount() {
        val p = RestartPolicy(healthyMs = 10_000, maxQuick = 2, baseDelayMs = 500)
        assertEquals(500L, p.next(100))
        assertEquals(1_000L, p.next(100))
        // It played for a minute, then the guest's PipeWire restarted: start again right away.
        assertEquals(500L, p.next(60_000))
        assertEquals(1_000L, p.next(100))
        assertNull(p.next(100))
    }

    @Test fun audioOutRunsUnderTheSupervisorWithTheSessionsPipeWire() {
        val spec = ShellSpec("/files/droidc/current/rootfs", emptyList(),
            mapOf("DROIDC_ROOT" to "/run/droidc/root", "HOME" to "/root"), "/root")
        val inv = ProotCommandBuilder(TestEnv.env(), spec).audioOut()
        assertEquals(listOf("/usr/bin/droidc", "supervise", "--", "/usr/bin/droidc", "audio-out", "--timeout",
            ProotCommandBuilder.AUDIO_OUT_WAIT_SECONDS.toString()), inv.argv.takeLast(7))
        assertEquals("pipewire-0-manager", inv.env["PIPEWIRE_REMOTE"])
        assertEquals(ProotCommandBuilder.USER_SERVICES_WAIT_SECONDS.toString(), inv.env["DROIDC_USER_SERVICES_WAIT"])
        assertTrue(TestEnv.env().audioLog.name.startsWith("audio-out"))
        assertTrue(File("/files/guest/run:/run").path in inv.argv)
    }
}

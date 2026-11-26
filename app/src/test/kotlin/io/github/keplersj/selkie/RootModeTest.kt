package io.github.keplersj.selkie

import io.github.keplersj.selkie.engine.Bind
import io.github.keplersj.selkie.engine.DroidcArgs
import io.github.keplersj.selkie.engine.RootShell
import io.github.keplersj.selkie.engine.ShellSpec
import io.github.keplersj.selkie.proot.EnterCommandBuilder
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

class RootModeTest {
    private val rootEnv = TestEnv.env().copy(rootMode = true, su = "/system/bin/su")
    private val spec = ShellSpec("/files/droidc/current/rootfs",
        listOf(Bind("/files/droidc/var", "/var")),
        mapOf("HOME" to "/root", "TERM" to "xterm-256color"), "/root")

    private val hostile = listOf(
        "quay.io/x/y:it's", "a;reboot", "\$(reboot)", "`reboot`", "a\nreboot", "x y", "", "'", "\"q\"", "\\",
    )

    @Test fun quoteLeavesSafeWordsAlone() {
        assertEquals("/data/app/x/lib/libdroidc.so", RootShell.quote("/data/app/x/lib/libdroidc.so"))
        assertEquals("--links", RootShell.quote("--links"))
        assertEquals("quay.io/centos-bootc/centos-bootc:stream10", RootShell.quote("quay.io/centos-bootc/centos-bootc:stream10"))
        assertEquals("'it'\\''s'", RootShell.quote("it's"))
        assertEquals("''", RootShell.quote(""))
    }

    /** The decisive check: a real POSIX shell gives back exactly the words we put in. */
    @Test fun quotingRoundTripsThroughSh() {
        val sh = File("/bin/sh")
        assumeTrue("needs /bin/sh", sh.canExecute())
        val script = "for a in " + hostile.joinToString(" ") { RootShell.quote(it) } + "; do printf '%s\\0' \"\$a\"; done"
        val p = ProcessBuilder(sh.path, "-c", script).start()
        val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
        assertTrue(p.waitFor(10, TimeUnit.SECONDS))
        assertEquals(hostile, out.split('\u0000').dropLast(1))
    }

    @Test fun suScriptExecsWithCleanEnvAndQuotedRef() {
        val argv = RootShell.su("/system/bin/su",
            DroidcArgs.global(rootEnv, json = true) + DroidcArgs.switch("quay.io/x/y:it's;reboot", insecure = false, root = true),
            linkedMapOf("HOME" to "/files", "PATH" to "/system/bin"), RootShell.PID_ON_STDOUT)
        assertEquals(listOf("/system/bin/su", "-c"), argv.take(2))
        val script = argv[2]
        assertTrue(script, script.startsWith(RootShell.PID_ON_STDOUT + "; exec /system/bin/env -i HOME=/files PATH=/system/bin /lib/libdroidc.so --root /files/droidc --json switch "))
        assertTrue(script, script.contains(" 'quay.io/x/y:it'\\''s;reboot' "))
        assertTrue(script, script.endsWith(" --links auto --apply-ownership"))
    }

    @Test fun suRejectsBadEnvNames() {
        assertThrows(IllegalArgumentException::class.java) {
            RootShell.su("su", listOf("x"), mapOf("A;B" to "1"))
        }
    }

    @Test fun pidLineIsParsedAndOtherLinesAreNot() {
        assertEquals(4242, RootShell.parsePid("{\"event\":\"selkie-pid\",\"pid\":4242}"))
        assertNull(RootShell.parsePid("{\"event\":\"progress\",\"phase\":\"x\"}"))
        assertNull(RootShell.parsePid("{\"event\":\"selkie-pid\",\"pid\":\"1; reboot\"}"))
    }

    /** What the root shell prints is exactly what parsePid reads. */
    @Test fun pidPreamblePrintsAParsableLine() {
        val sh = File("/bin/sh")
        assumeTrue("needs /bin/sh", sh.canExecute())
        val p = ProcessBuilder(sh.path, "-c", RootShell.PID_ON_STDOUT).start()
        val line = p.inputStream.bufferedReader().readLine()
        p.waitFor()
        assertNotNull(line, RootShell.parsePid(line))
    }

    @Test fun switchArgsByMode() {
        assertEquals(listOf("switch", "r", "--links", "link2symlink"), DroidcArgs.switch("r", insecure = false))
        assertEquals(listOf("switch", "r", "--insecure-allow-unsigned", "--links", "auto", "--apply-ownership"),
            DroidcArgs.switch("r", insecure = true, root = true))
        assertEquals(listOf("fix-ownership", "--undo"), DroidcArgs.fixOwnership(undo = true))
    }

    @Test fun findSuPrefersKnownLocationsInOrder() {
        assertEquals("/debug_ramdisk/su", RootShell.findSu { it == "/debug_ramdisk/su" || it == "/su/bin/su" })
        assertNull(RootShell.findSu { false })
        assertNull(RootShell.findSu { throw SecurityException() })
    }

    @Test fun enterSessionArgv() {
        val b = EnterCommandBuilder(rootEnv, spec,
            overlays = mapOf("/proc/filesystems" to File("/files/guest/generated/proc_filesystems")))
        assertEquals(listOf(
            "/lib/libdroidc.so", "--root", "/files/droidc", "enter",
            "--bind", "/lib/libdroidc-guest.so:/usr/bin/droidc",
            "--bind", "/files/guest/generated/resolv.conf:/etc/resolv.conf",
            "--bind", "/files/guest/generated/proc_filesystems:/proc/filesystems",
            "--hostname", "selkie", "--",
            "/usr/bin/droidc", "supervise", "--", "/bin/bash", "-l"),
            b.enterArgs(listOf("/usr/bin/droidc", "supervise", "--", "/bin/bash", "-l")))
        val inv = b.interactiveShell(File("/cache/root-sessions/s1.pid"))
        assertEquals(listOf("/system/bin/su", "-c"), inv.argv.take(2))
        assertTrue(inv.argv[2], inv.argv[2].startsWith("echo $$ > /cache/root-sessions/s1.pid; exec /system/bin/env -i "))
        assertEquals("xterm-256color", inv.env["TERM"])
        assertEquals("/system/bin:/system/xbin", inv.env["PATH"])
    }

    @Test fun killFromFileQuotesThePath() {
        val argv = RootShell.killFromFile("su", File("/cache/root sessions/a'b.pid"))
        assertEquals("[ -s '/cache/root sessions/a'\\''b.pid' ] && kill -TERM \"\$(cat '/cache/root sessions/a'\\''b.pid')\"; " +
            "rm -f '/cache/root sessions/a'\\''b.pid'", argv[2])
    }

    @Test fun useRootNeedsSu() {
        assertTrue(rootEnv.useRoot)
        assertFalse(rootEnv.copy(su = null).useRoot)
        assertFalse(TestEnv.env().copy(su = "/system/bin/su").useRoot)
    }
}

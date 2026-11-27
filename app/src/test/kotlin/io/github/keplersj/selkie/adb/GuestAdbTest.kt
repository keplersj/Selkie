package io.github.keplersj.selkie.adb

import io.github.keplersj.selkie.TestEnv
import io.github.keplersj.selkie.engine.ShellSpec
import io.github.keplersj.selkie.platform.adb.AdbStatus
import io.github.keplersj.selkie.platform.adb.GuestAdb
import io.github.keplersj.selkie.platform.adb.GuestAdb.ConnectResult
import io.github.keplersj.selkie.platform.adb.GuestAdb.PairResult
import io.github.keplersj.selkie.proot.ProotCommandBuilder
import io.github.keplersj.selkie.ui.adbRow
import org.junit.Assert.*
import org.junit.Test

class GuestAdbTest {
    @Test fun commandsRunAdbThroughALoginShell() {
        val pair = GuestAdb.pair(37215)
        assertEquals(listOf("/bin/bash", "-lc"), pair.take(2))
        assertEquals(listOf("adb", "pair", "127.0.0.1:37215"), pair.drop(3))
        assertTrue(pair[2].contains("\$HOME/.local/bin"))
        assertEquals(listOf("adb", "nodaemon", "server"), GuestAdb.server().drop(3))
        assertEquals(listOf("adb", "connect", "127.0.0.1:41000"), GuestAdb.connect(41000).drop(3))
    }

    @Test fun theCodeNeverGoesInArgv() {
        assertFalse(GuestAdb.pair(1).any { it.matches(Regex("\\d{6}")) })
    }

    @Test fun cleanCodeKeepsDigits() {
        assertEquals("123456", GuestAdb.cleanCode(" 123 456\n"))
        assertEquals("", GuestAdb.cleanCode(null))
    }

    @Test fun pairOutputs() {
        assertEquals(PairResult.Paired, GuestAdb.pairResult(0,
            "Enter pairing code: Successfully paired to 127.0.0.1:37215 [guid=adb-R5CW-abc]\n"))
        assertEquals(PairResult.WrongCode, GuestAdb.pairResult(0,
            "Enter pairing code: Failed: Wrong password or connection was dropped.\n"))
        assertEquals(PairResult.Failed("Failed: Unable to start pairing client."),
            GuestAdb.pairResult(0, "Enter pairing code: Failed: Unable to start pairing client.\n"))
        assertEquals(PairResult.NoAdb, GuestAdb.pairResult(127, "no adb in this container\n"))
        assertEquals(PairResult.Failed("adb pair exited with status 1"), GuestAdb.pairResult(1, ""))
    }

    @Test fun connectOutputs() {
        assertEquals(ConnectResult.Connected, GuestAdb.connectResult(0, "connected to 127.0.0.1:41000\n"))
        assertEquals(ConnectResult.Connected, GuestAdb.connectResult(0, "already connected to 127.0.0.1:41000\n"))
        assertEquals(ConnectResult.Failed("failed to connect to '127.0.0.1:41000': Connection refused"),
            GuestAdb.connectResult(0, "failed to connect to '127.0.0.1:41000': Connection refused\n"))
        assertEquals(ConnectResult.NoAdb, GuestAdb.connectResult(127, "no adb in this container"))
    }

    @Test fun everyGuestSessionFindsSelkiesServerOnItsSocket() {
        val spec = ShellSpec("/rootfs", emptyList(), mapOf("HOME" to "/root"), "/root")
        val b = ProotCommandBuilder(TestEnv.env(), spec, emptyMap())
        assertEquals("localfilesystem:/run/selkie-adb.sock", b.interactiveShell().env["ADB_SERVER_SOCKET"])
        assertEquals("localfilesystem:/run/selkie-adb.sock", b.command(GuestAdb.server()).env["ADB_SERVER_SOCKET"])
    }

    @Test fun anImageCanPointAdbElsewhere() {
        val spec = ShellSpec("/rootfs", emptyList(), mapOf("ADB_SERVER_SOCKET" to "tcp:5038"), "/root")
        val b = ProotCommandBuilder(TestEnv.env(), spec, emptyMap())
        assertEquals("tcp:5038", b.interactiveShell().env["ADB_SERVER_SOCKET"])
    }

    @Test fun settingsRowFollowsTheStatus() {
        assertEquals("Pair", adbRow("default", AdbStatus(), notificationsOn = true).action)
        assertEquals("Turn on", adbRow("default", AdbStatus(), notificationsOn = false).action)
        assertEquals("adb connected", adbRow("default", AdbStatus("default", 41000), true).title)
        assertEquals("adb paired, not connected", adbRow("default", AdbStatus("default", problem = "x"), true).title)
        assertEquals("adb paired with this device", adbRow("default", AdbStatus("default"), true).title)
    }
}

package io.github.keplersj.selkie

import io.github.keplersj.selkie.display.DisplayScale
import io.github.keplersj.selkie.display.KeyMap
import io.github.keplersj.selkie.engine.DroidcArgs
import io.github.keplersj.selkie.engine.DroidcOutcome
import io.github.keplersj.selkie.engine.NdjsonParser
import io.github.keplersj.selkie.engine.DroidcEvent
import io.github.keplersj.selkie.engine.ShellSpec
import io.github.keplersj.selkie.engine.shellSpec
import io.github.keplersj.selkie.proot.ProotCommandBuilder
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class GraphicalSessionTest {
    private val spec = ShellSpec("/files/droidc/current/rootfs", emptyList(),
        mapOf("DROIDC_ROOT" to "/run/droidc/root", "HOME" to "/root"), "/root")

    @Test fun graphicalSessionInvocation() {
        val inv = ProotCommandBuilder(TestEnv.env(), spec).graphicalSession(1920, 1200)
        assertEquals(listOf("/usr/bin/droidc", "supervise", "--session", "--systemd", "auto", "--width", "1920", "--height", "1200",
            "--scale", "1.0"), inv.argv.takeLast(11))
        assertEquals("wayland-0", inv.env["WAYLAND_DISPLAY"])
        assertEquals("1", inv.env["LIBGL_ALWAYS_SOFTWARE"])
        assertEquals("pipewire-0-manager", inv.env["PIPEWIRE_REMOTE"])
        assertEquals("/run/droidc/root", inv.env["DROIDC_ROOT"])
        // The compositor socket is in the guest's /run/user/<uid>, inside the writable /run bind.
        assertEquals(File("/files/guest/run/user/10234/wayland-0"), TestEnv.env().waylandSocket)
        assertTrue("-b" in inv.argv && "/files/guest/run:/run" in inv.argv)
    }

    @Test fun graphicalSessionScale() {
        val inv = ProotCommandBuilder(TestEnv.env(), spec).graphicalSession(2560, 1600, 2.125f)
        assertEquals(listOf("--scale", "2.125"), inv.argv.takeLast(2))
    }

    @Test fun sessionSizeLetsGnomeOffer150And200() {
        // Each side a multiple of 12: whole logical pixels at 1.5x and 2x.
        assertEquals(1920 to 1428, DisplayScale.sessionSize(1920, 1432))
        assertEquals(1080 to 1848, DisplayScale.sessionSize(1080, 1857))
        assertEquals(2556 to 1596, DisplayScale.sessionSize(2560, 1600))
        assertEquals(1200 to 1920, DisplayScale.sessionSize(1200, 1920))
        assertEquals(8 to 1, DisplayScale.sessionSize(8, 1))
    }

    @Test fun displayScaleFollowsDensity() {
        // One GNOME logical pixel per Android dp.
        assertEquals(1f, DisplayScale.forDensity(1f))
        assertEquals(2f, DisplayScale.forDensity(2f))
        assertEquals(2.625f, DisplayScale.forDensity(2.625f))
        // Low-density screens stay at 1x; nonsense falls back to 1x; very dense screens stop at 4x.
        assertEquals(1f, DisplayScale.forDensity(0.75f))
        assertEquals(1f, DisplayScale.forDensity(0f))
        assertEquals(1f, DisplayScale.forDensity(Float.NaN))
        assertEquals(4f, DisplayScale.forDensity(5f))
    }

    @Test fun shellSpecSession() {
        val line = """{"event":"result","ok":true,"data":{"rootfs":"/r","session":{"name":"GNOME",""" +
            """"renderer":"screencast","exec":["gnome-shell","--headless"],"services":[["pipewire"]],""" +
            """"configured":true,"source":"override"}}}"""
        val r = NdjsonParser.parseLine(line) as DroidcEvent.Result
        val s = DroidcOutcome.Success(r.data).shellSpec().session!!
        assertEquals("screencast", s.renderer)
        assertEquals(listOf("gnome-shell", "--headless"), s.exec)
        assertEquals(listOf(listOf("pipewire")), s.services)
        assertTrue(s.configured)
        // older droidc: no session object
        val old = NdjsonParser.parseLine("""{"event":"result","ok":true,"data":{"rootfs":"/r"}}""") as DroidcEvent.Result
        assertNull(DroidcOutcome.Success(old.data).shellSpec().session)
        // unconfigured
        val none = NdjsonParser.parseLine("""{"event":"result","ok":true,"data":{"rootfs":"/r","session":""" +
            """{"name":null,"renderer":null,"exec":null,"services":[],"configured":false,"source":null}}}""") as DroidcEvent.Result
        assertFalse(DroidcOutcome.Success(none.data).shellSpec().session!!.configured)
    }

    @Test fun sessionSetArgs() {
        assertEquals(listOf("session", "set", "--renderer", "wayland", "--exec", """["kwin_wayland"]"""),
            DroidcArgs.sessionSet("wayland", """["kwin_wayland"]""", null, " ".trim()))
        assertEquals(listOf("session", "set", "--renderer", "screencast", "--exec", "[\"x\"]", "--services",
            "[[\"pipewire\"]]", "--name", "GNOME"),
            DroidcArgs.sessionSet("screencast", "[\"x\"]", "[[\"pipewire\"]]", "GNOME"))
    }

    @Test fun keyMap() {
        // android.view.KeyEvent constants are compile-time ints, so they work in JVM tests.
        assertEquals(30, KeyMap.evdev(android.view.KeyEvent.KEYCODE_A))
        assertEquals(16, KeyMap.evdev(android.view.KeyEvent.KEYCODE_Q))
        assertEquals(44, KeyMap.evdev(android.view.KeyEvent.KEYCODE_Z))
        assertEquals(2, KeyMap.evdev(android.view.KeyEvent.KEYCODE_1))
        assertEquals(11, KeyMap.evdev(android.view.KeyEvent.KEYCODE_0))
        assertEquals(125, KeyMap.evdev(android.view.KeyEvent.KEYCODE_META_LEFT))
        assertEquals(28, KeyMap.evdev(android.view.KeyEvent.KEYCODE_ENTER))
        assertEquals(88, KeyMap.evdev(android.view.KeyEvent.KEYCODE_F12))
        assertEquals("hardware scan code is used for unmapped keys", 183, KeyMap.evdev(9999, 183))
        assertNull(KeyMap.evdev(9999))
        assertEquals(30 to false, KeyMap.forChar('a'))
        assertEquals(30 to true, KeyMap.forChar('A'))
        assertEquals(3 to true, KeyMap.forChar('@'))
        assertEquals(11 to false, KeyMap.forChar('0'))
        assertEquals(53 to true, KeyMap.forChar('?'))
        assertEquals(43 to false, KeyMap.forChar('\\'))
        assertEquals(40 to true, KeyMap.forChar('"'))
        assertEquals(41 to true, KeyMap.forChar('~'))
        assertEquals(57 to false, KeyMap.forChar(' '))
        assertNull(KeyMap.forChar('é'))
    }
}

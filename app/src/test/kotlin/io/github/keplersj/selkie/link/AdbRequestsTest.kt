package io.github.keplersj.selkie.link

import io.github.keplersj.selkie.TestEnv
import io.github.keplersj.selkie.engine.ShellSpec
import io.github.keplersj.selkie.proot.ProotCommandBuilder
import org.junit.Assert.*
import org.junit.Test

class AdbRequestsTest {
    @Test fun answersCarryTheRequestsId() {
        assertEquals("""{"kind":"reply","id":7,"ok":true,"port":37215,"code":"123456"}""",
            AdbRequests.reply(7, 37215, "123456"))
        assertEquals("""{"kind":"reply","id":8,"ok":true,"port":41000}""", AdbRequests.reply(8, 41000))
        assertEquals("""{"kind":"reply","id":9,"ok":false,"error":"no"}""", AdbRequests.refuse(9, "no"))
        assertEquals("""{"kind":"progress","id":9,"message":"tap"}""", AdbRequests.progress(9, "tap"))
    }

    @Test fun requestsParseWithTheirId() {
        val m = GuestLinkMessage.parse("""{"kind":"adb-pair","id":3}""")!!
        assertEquals("adb-pair", m.kind)
        assertEquals("3", m.body["id"].toString())
    }

    @Test fun androidTenCantPair() {
        assertNotNull(AdbRequests.unsupported(29))
        assertNull(AdbRequests.unsupported(30))
    }

    @Test fun codesKeepTheirDigits() {
        assertEquals("123456", AdbRequests.cleanCode(" 123 456\n"))
        assertEquals("", AdbRequests.cleanCode(null))
    }

    @Test fun guestAdbUsesASocketInRun() {
        val spec = ShellSpec("/rootfs", emptyList(), mapOf("HOME" to "/root"), "/root")
        val b = ProotCommandBuilder(TestEnv.env(), spec, emptyMap())
        assertEquals("localfilesystem:/run/droidc-adb.sock", b.interactiveShell().env["ADB_SERVER_SOCKET"])
        val own = ShellSpec("/rootfs", emptyList(), mapOf("ADB_SERVER_SOCKET" to "tcp:5038"), "/root")
        assertEquals("tcp:5038", ProotCommandBuilder(TestEnv.env(), own, emptyMap()).interactiveShell().env["ADB_SERVER_SOCKET"])
    }
}

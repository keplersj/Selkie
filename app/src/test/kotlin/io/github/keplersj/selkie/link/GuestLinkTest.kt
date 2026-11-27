package io.github.keplersj.selkie.link

import io.github.keplersj.selkie.TestEnv
import io.github.keplersj.selkie.engine.ShellSpec
import io.github.keplersj.selkie.engine.SelkieJson
import io.github.keplersj.selkie.proot.ProotCommandBuilder
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestLinkTest {
    @Test fun appearanceLineCarriesOnlyWhatIsKnown() {
        val full = SelkieJson.parseToJsonElement(
            Appearance(dark = true, accent = "#6750a4", textScale = 1.15f, animations = false, highContrast = true).toLine(),
        ) as JsonObject
        assertEquals("appearance", full["kind"]!!.jsonPrimitive.content)
        assertEquals("true", full["dark"]!!.jsonPrimitive.content)
        assertEquals("#6750a4", full["accent"]!!.jsonPrimitive.content)
        assertEquals(1.15, full["text_scale"]!!.jsonPrimitive.content.toDouble(), 1e-4)
        assertEquals("false", full["animations"]!!.jsonPrimitive.content)
        assertEquals("true", full["high_contrast"]!!.jsonPrimitive.content)

        val some = SelkieJson.parseToJsonElement(Appearance(dark = false).toLine()) as JsonObject
        assertEquals(setOf("kind", "dark"), some.keys)
        assertFalse('\n' in Appearance(dark = false).toLine())
    }

    @Test fun accentHexDropsAlpha() {
        assertEquals("#6750a4", Appearance.hex(0xff6750a4.toInt()))
        assertEquals("#000000", Appearance.hex(0xff000000.toInt()))
    }

    @Test fun guestLinesParseByKind() {
        val m = GuestLinkMessage.parse("""{"kind":"ready","version":"0.1.0 (287c385)"}""")!!
        assertEquals("ready", m.kind)
        assertEquals("0.1.0 (287c385)", m.body["version"]!!.jsonPrimitive.content)
        assertNull(GuestLinkMessage.parse("host-link: ready"))
        assertNull(GuestLinkMessage.parse("""{"no":"kind"}"""))
        assertNull(GuestLinkMessage.parse("[1,2]"))
    }

    @Test fun hostLinkRunsUnderTheSupervisor() {
        val spec = ShellSpec("/files/droidc/current/rootfs", emptyList(),
            mapOf("DROIDC_ROOT" to "/run/droidc/root", "HOME" to "/root"), "/root")
        val inv = ProotCommandBuilder(TestEnv.env(), spec).hostLink()
        assertEquals(listOf("/usr/bin/droidc", "supervise", "--", "/usr/bin/droidc", "host-link"), inv.argv.takeLast(5))
        assertEquals(ProotCommandBuilder.USER_SERVICES_WAIT_SECONDS.toString(), inv.env["DROIDC_USER_SERVICES_WAIT"])
        assertTrue(TestEnv.env().hostLinkLog.name.startsWith("host-link"))
    }

    @Test fun onlyAndroidLinksAreOpened() {
        fun line(uri: String) = GuestLinkMessage.parse("""{"kind":"open-uri","uri":${SelkieJson.encodeToString(String.serializer(), uri)}}""")!!
        assertEquals("https://example.org/a?b=1", OpenUri.uriFrom(line("https://example.org/a?b=1")))
        assertEquals("mailto:someone@example.org", OpenUri.uriFrom(line("mailto:someone@example.org")))
        assertEquals("GEO:40.7,-111.9", OpenUri.uriFrom(line("GEO:40.7,-111.9")))
        assertNull(OpenUri.uriFrom(line("file:///etc/passwd")))
        assertNull(OpenUri.uriFrom(line("intent://x#Intent;end")))
        assertNull(OpenUri.uriFrom(line("https:")))
        assertNull(OpenUri.uriFrom(line("https://a\nb")))
        assertNull(OpenUri.uriFrom(GuestLinkMessage.parse("""{"kind":"open-uri"}""")!!))
        assertNull(OpenUri.uriFrom(GuestLinkMessage.parse("""{"kind":"ready","uri":"https://x"}""")!!))
    }

    @Test fun batteryLineFollowsAndroidsExtras() {
        // BatteryManager: status 2 charging, 3 discharging, 4 not charging, 5 full; plugged 1 = AC.
        val charging = SelkieJson.parseToJsonElement(BatteryState.of(true, 46, 100, 2, 1, 1_800_000).toLine()) as JsonObject
        assertEquals("battery", charging["kind"]!!.jsonPrimitive.content)
        assertEquals("46", charging["percent"]!!.jsonPrimitive.content)
        assertEquals("charging", charging["status"]!!.jsonPrimitive.content)
        assertEquals("true", charging["plugged"]!!.jsonPrimitive.content)
        assertEquals("1800", charging["time_to_full"]!!.jsonPrimitive.content)

        val low = BatteryState.of(true, 9, 100, 3, 0)
        assertEquals(BatteryState(true, 9, "discharging", false, null), low)
        assertEquals("full", BatteryState.of(true, 100, 100, 5, 2).status)
        assertEquals("not-charging", BatteryState.of(true, 80, 100, 4, 1).status)
        assertNull(BatteryState.of(true, -1, 100, 1, 0).percent)
        assertEquals(50, BatteryState.of(true, 128, 256, 3, 0).percent)
        assertFalse('\n' in low.toLine())
    }

    @Test fun networkLineFollowsTheDefaultNetwork() {
        val wifi = NetworkState.of(validated = true, portal = false, notMetered = true, wifi = true)
        assertEquals(
            """{"kind":"network","connected":true,"validated":true,"portal":false,"metered":false,"transport":"wifi"}""",
            wifi.toLine(),
        )
        val phone = NetworkState.of(validated = true, portal = false, notMetered = false, cellular = true)
        assertTrue(phone.metered)
        assertEquals("cellular", phone.transport)
        // A VPN over Wi-Fi is the VPN.
        assertEquals("vpn", NetworkState.of(true, false, true, wifi = true, vpn = true).transport)
        val hotel = NetworkState.of(validated = false, portal = true, notMetered = true, wifi = true)
        assertTrue(hotel.portal)
        assertEquals("""{"kind":"network","connected":false}""", NetworkState.NONE.toLine())
    }
}

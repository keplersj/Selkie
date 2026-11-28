package io.github.keplersj.selkie.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PipeWireServicesTest {
    private val services = Json.parseToJsonElement(PipeWireServices.JSON).jsonArray
        .map { cmd -> cmd.jsonArray.map { it.jsonPrimitive.content } }

    @Test
    fun startsPipeWireThenTheClientsThatNeedIt() {
        assertEquals(3, services.size)
        services.forEach { assertEquals(listOf("sh", "-c"), it.take(2)) }
        assertTrue(services[0][2].endsWith("exec pipewire"))
        assertTrue(services[1][2].endsWith("exec wireplumber"))
        assertTrue(services[2][2].endsWith("exec pipewire-pulse"))
    }

    @Test
    fun theClientsWaitForPipeWireToAnswer() {
        for (s in services.drop(1)) {
            assertTrue(s[2], s[2].contains("until timeout 2 pw-cli info 0"))
            // Bounded by the clock, not a try count: each try can itself take 2 s.
            assertTrue(s[2], s[2].contains("end=\$((\$(date +%s)+20))"))
            assertTrue(s[2], s[2].contains("[ \$(date +%s) -ge \$end ]"))
        }
    }

    @Test
    fun wirePlumberGivesFullAccessBackToClientsWhoseFlatpakCheckFailed() {
        val wp = services[1][2]
        assertTrue(wp, wp.contains("wireplumber/wireplumber.conf.d/50-selkie-access.conf"))
        assertTrue(wp, wp.contains("export XDG_CONFIG_DIRS="))
        assertTrue(wp, wp.indexOf("export XDG_CONFIG_DIRS=") < wp.indexOf("exec wireplumber"))
        val rule = PipeWireServices.WIREPLUMBER_ACCESS
        assertTrue(rule, rule.contains("pipewire.sec.flatpak = \"true\""))
        assertTrue(rule, rule.contains("pipewire.access.portal.app_id = null"))
        assertTrue(rule, rule.contains("default_permissions = \"all\""))
    }
}

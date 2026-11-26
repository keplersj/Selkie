package io.github.keplersj.selkie.ui

import io.github.keplersj.selkie.engine.ContainerDeployment
import io.github.keplersj.selkie.engine.ContainerInfo
import io.github.keplersj.selkie.engine.Deployment
import io.github.keplersj.selkie.engine.StatusData
import io.github.keplersj.selkie.service.DesktopState
import io.github.keplersj.selkie.service.ServiceState
import io.github.keplersj.selkie.service.SessionInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeModelTest {
    @Test fun summariesSkipContainersWithNothingInstalled() {
        val s = ServiceState(container = "default", containers = listOf(
            ContainerInfo("default"),
            ContainerInfo("dev", current = ContainerDeployment("d1", "quay.io/centos-bootc/centos-bootc:stream10")),
        ))
        assertEquals(listOf("dev"), summaries(s).map { it.name })
    }

    @Test fun theSelectedContainerCountsOnceItsStatusShowsAnImage() {
        // An older droidc without `container list` only reports the selected container's status.
        val s = ServiceState(status = StatusData(current = Deployment(imageRef = "ghcr.io/x/y:1", insecure = true)))
        val only = summaries(s).single()
        assertEquals("default", only.name)
        assertEquals("ghcr.io/x/y:1", only.imageRef)
        assertTrue(only.insecure)
        assertFalse(only.running)
    }

    @Test fun runningCountsTerminalsAndTheDesktopPerContainer() {
        val s = ServiceState(
            container = "dev",
            containers = listOf(
                ContainerInfo("default", current = ContainerDeployment("d1", "a:1")),
                ContainerInfo("dev", current = ContainerDeployment("d2", "b:1")),
            ),
            sessions = listOf(SessionInfo("1", "bash", true, "dev"), SessionInfo("2", "bash", false, "dev")),
            desktop = DesktopState.Running(1, 1), desktopContainer = "default",
        )
        val (def, dev) = summaries(s)
        assertTrue(def.desktop); assertEquals(0, def.terminals); assertTrue(def.running)
        assertFalse(dev.desktop); assertEquals(1, dev.terminals); assertTrue(dev.running)
    }

    @Test fun signerIsTheGithubOwner() {
        assertEquals("projectbluefin",
            signer("https://github.com/projectbluefin/common/.github/workflows/build.yml@refs/heads/main"))
        assertNull(signer(null))
    }

    @Test fun shortDateKeepsUnparseableText() {
        assertEquals("12 Nov", shortDate("2026-11-12T19:04:51Z"))
        assertEquals("yesterday", shortDate("yesterday"))
        assertNull(shortDate(null))
    }
}

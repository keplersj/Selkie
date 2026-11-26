package io.github.keplersj.selkie.ui

import androidx.compose.ui.unit.dp
import io.github.keplersj.selkie.engine.ContainerDeployment
import io.github.keplersj.selkie.engine.ContainerInfo
import io.github.keplersj.selkie.service.DesktopState
import io.github.keplersj.selkie.service.ServiceState
import io.github.keplersj.selkie.service.SessionInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SectionsModelTest {
    @Test fun imagesGroupContainersByImage() {
        val s = Fixtures.homeContainers.copy(containers = Fixtures.homeContainers.containers +
            ContainerInfo("scratch2", current = ContainerDeployment("d9", Images.BLUEFIN)))
        val e = imageEntries(s)
        assertEquals(listOf(Images.BLUEFIN, Images.CENTOS), e.map { it.ref })
        assertEquals(listOf("default", "scratch2"), e[0].usedBy)
        assertEquals("Bluefin LTS", e[0].title)
        // Only the selected container (dev, on CentOS) has a signature to show.
        assertNull(e[0].signature)
        assertEquals("Unsigned", e[1].signature)
        assertTrue(e[1].unsigned)
    }

    @Test fun aVerifiedImageNamesItsSigner() {
        val e = imageEntries(Fixtures.homeInstalled.copy(
            containers = listOf(ContainerInfo("default", current = ContainerDeployment("d2", Images.BLUEFIN)))))
        assertEquals("Signed by projectbluefin", e.single().signature)
        assertFalse(e.single().unsigned)
    }

    @Test fun imageTitlesFallBackToTheAddress() {
        assertEquals("CentOS Stream 10", imageTitle(Images.CENTOS))
        assertEquals("aurora", imageTitle("ghcr.io/ublue-os/aurora:stable"))
        assertEquals("image", imageTitle("registry.example/team/image@sha256:0a1b"))
    }

    @Test fun activityListsTheDesktopAndRunningTerminals() {
        val s = ServiceState(container = "dev",
            sessions = listOf(SessionInfo("1", "bash", true), SessionInfo("2", "htop", false, container = "dev")),
            desktop = DesktopState.Running(2560, 1600), desktopContainer = "default", graphical = Fixtures.gnome)
        val a = activityItems(s)
        assertEquals("default", a.desktop)
        // The session name is the selected container's, not the desktop's.
        assertNull(a.desktopName)
        assertEquals(listOf("1"), a.terminals.map { it.id })
        assertFalse(a.empty)
        assertTrue(activityItems(ServiceState(desktopContainer = "default")).empty)
    }

    @Test fun theRailNeedsRoomAndNoBook() {
        assertFalse(useRail(411.dp, book = false))
        assertTrue(useRail(600.dp, book = false))
        assertFalse(useRail(840.dp, book = true))
    }
}

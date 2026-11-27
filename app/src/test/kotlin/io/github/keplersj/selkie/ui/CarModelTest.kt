package io.github.keplersj.selkie.ui

import io.github.keplersj.selkie.service.DesktopState
import org.junit.Assert.assertEquals
import org.junit.Test

class CarModelTest {
    @Test fun withADesktopTheDrivingScreenSaysItComesBack() = assertEquals(
        "default and dev keep running in the background. The desktop comes back when you park.",
        carDrivingLine(Fixtures.homeContainers))

    @Test fun oneContainerWithTerminalsOnly() {
        val s = Fixtures.homeContainers.copy(desktop = DesktopState.Stopped, desktopContainer = null,
            sessions = Fixtures.homeContainers.sessions.filter { it.container == "dev" })
        assertEquals("dev keeps running in the background. Its terminals come back when you park.", carDrivingLine(s))
    }

    @Test fun nothingRunning() = assertEquals("Nothing is running. Selkie comes back when you park.",
        carDrivingLine(Fixtures.homeContainers.copy(desktop = DesktopState.Stopped, desktopContainer = null,
            sessions = emptyList())))
}

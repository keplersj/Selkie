package io.github.keplersj.selkie

import io.github.keplersj.selkie.engine.ContainerInfo
import io.github.keplersj.selkie.service.DesktopState
import io.github.keplersj.selkie.service.ServiceState
import io.github.keplersj.selkie.service.SessionInfo
import io.github.keplersj.selkie.service.watchContainers
import io.github.keplersj.selkie.watch.WatchContainer
import io.github.keplersj.selkie.watch.WatchDesktop
import io.github.keplersj.selkie.watch.WatchProtocol
import org.junit.Assert.assertEquals
import org.junit.Test

class WatchTest {
    private val state = ServiceState(
        containers = listOf(ContainerInfo("default"), ContainerInfo("dev"), ContainerInfo("idle")),
        sessions = listOf(
            SessionInfo("1", "bash", running = true, container = "dev"),
            SessionInfo("2", "bash", running = true, container = "dev"),
            SessionInfo("3", "bash", running = false, container = "idle"),
        ),
        desktop = DesktopState.Running(1280, 800),
        desktopContainer = "default",
    )

    @Test fun eachContainerSaysWhatItRuns() {
        assertEquals(
            listOf(
                WatchContainer("default", WatchDesktop.Running, 0),
                WatchContainer("dev", WatchDesktop.None, 2),
                WatchContainer("idle", WatchDesktop.None, 0),
            ),
            watchContainers(state),
        )
    }

    @Test fun aStartingOrFailedDesktop() {
        assertEquals(WatchDesktop.Starting, watchContainers(state.copy(desktop = DesktopState.Starting))[0].desktop)
        assertEquals(WatchDesktop.None, watchContainers(state.copy(desktop = DesktopState.Failed("x", "")))[0].desktop)
    }

    @Test fun theProtocolRoundTrips() {
        val list = watchContainers(state)
        assertEquals(list, WatchProtocol.decode(WatchProtocol.encode(list)))
        assertEquals(emptyList<WatchContainer>(), WatchProtocol.decode(WatchProtocol.encode(emptyList())))
        assertEquals(emptyList<WatchContainer>(), WatchProtocol.decode(null))
    }

    @Test fun decodingSkipsWhatItCantRead() {
        assertEquals(
            listOf(WatchContainer("a", WatchDesktop.None, 0), WatchContainer("b", WatchDesktop.Running, 1)),
            WatchProtocol.decode("a\tSomethingNew\t-3\n\n\t\t\nb\tRunning\t1\textra"),
        )
    }
}

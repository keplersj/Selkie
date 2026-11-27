package io.github.keplersj.selkie.wear

import io.github.keplersj.selkie.watch.WatchContainer
import io.github.keplersj.selkie.watch.WatchDesktop
import io.github.keplersj.selkie.watch.WatchProtocol
import org.junit.Assert.assertEquals
import org.junit.Test

class WatchModelTest {
    @Test fun headline() {
        assertEquals("Nothing running", runningHeadline(listOf(WatchContainer("default"))))
        assertEquals("2 running", runningHeadline(listOf(
            WatchContainer("a", WatchDesktop.Starting), WatchContainer("b", terminals = 1), WatchContainer("c"))))
    }

    @Test fun lines() {
        assertEquals("Not running", containerLine(WatchContainer("a")))
        assertEquals("Desktop", containerLine(WatchContainer("a", WatchDesktop.Running)))
        assertEquals("Desktop starting · 1 terminal", containerLine(WatchContainer("a", WatchDesktop.Starting, 1)))
        assertEquals("3 terminals", containerLine(WatchContainer("a", terminals = 3)))
    }

    @Test fun runningFirst() {
        val list = listOf(WatchContainer("a"), WatchContainer("b", terminals = 1), WatchContainer("c"),
            WatchContainer("d", WatchDesktop.Running))
        assertEquals(listOf("b", "d", "a", "c"), ordered(list).map { it.name })
    }

    @Test fun containerLinks() {
        assertEquals("selkie://container/dev", WatchProtocol.containerLink("dev"))
        assertEquals("my box", WatchProtocol.containerFromLink(WatchProtocol.containerLink("my box")))
        assertEquals("dev", WatchProtocol.containerFromLink("selkie://container/dev"))
        assertEquals(null, WatchProtocol.containerFromLink("selkie://container/"))
        assertEquals(null, WatchProtocol.containerFromLink("selkie://container/a/b"))
        assertEquals(null, WatchProtocol.containerFromLink("https://container/dev"))
        assertEquals(null, WatchProtocol.containerFromLink("selkie://other/dev"))
        assertEquals(null, WatchProtocol.containerFromLink(null))
    }
}

package io.github.keplersj.selkie.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClosedTest {
    @Test fun runRecordsRoundTrip() {
        val records = runRecords("default", "org.gnome.TextEditor.desktop", "Text\tEditor", listOf("default", "dev", "dev"), 7)
        assertEquals(listOf(
            RunRecord("default", true, "org.gnome.TextEditor.desktop", "Text Editor", 1, 7),
            RunRecord("dev", false, null, null, 2, 7),
        ), decodeRunRecords(encodeRunRecords(records)))
        assertEquals(emptyList<RunRecord>(), decodeRunRecords(null))
        assertEquals(emptyList<RunRecord>(), decodeRunRecords("garbage"))
    }

    @Test fun nothingWhenThePersonStoppedSelkie() {
        val r = listOf(RunRecord("default", true, terminals = 1, boot = 3))
        assertTrue(closedAfterRestart(r, ExitKind.UserStopped, 100L, 3).isEmpty())
        assertTrue(closedAfterRestart(r, ExitKind.ExitSelf, 100L, 3).isEmpty())
    }

    @Test fun causeComesFromTheExitOrARestart() {
        val r = listOf(RunRecord("default", true, terminals = 1, boot = 3), RunRecord("dev", false, terminals = 2, boot = 3))
        val low = closedAfterRestart(r, ExitKind.LowMemory, 100L, 3) { "log of $it" }
        assertEquals(listOf(ClosedCause.LowMemory, ClosedCause.LowMemory), low.map { it.cause })
        assertEquals("log of default", low[0].logTail)
        assertEquals("", low[1].logTail)
        val restart = closedAfterRestart(r, ExitKind.Other, 100L, 4).single { it.container == "default" }
        assertEquals(ClosedCause.Restart, restart.cause)
        assertNull(restart.at)
        assertEquals(ClosedCause.Crash, closedAfterRestart(r, ExitKind.Crash, 100L, 3)[0].cause)
    }

    @Test fun killsCloseTogetherShareOneNotice() {
        val desktop = Closed("default", 1_000L, desktop = true, cause = ClosedCause.ChildProcesses)
        val term = Closed("default", 5_000L, desktop = false, terminals = 1, cause = ClosedCause.ChildProcesses)
        val merged = addKilled(addKilled(listOf(desktop), term), term.copy(at = 9_000L))
        assertEquals(1, merged.size)
        assertEquals(2, merged[0].terminals)
        assertTrue(merged[0].desktop)
        val later = addKilled(merged, term.copy(at = 100_000L))
        assertEquals(listOf(term.copy(at = 100_000L)), later)
    }

    @Test fun sigkillStatuses() {
        assertTrue(isSigkill(137) && isSigkill(-9) && isSigkill(9))
        assertTrue(!isSigkill(0) && !isSigkill(1) && !isSigkill(143))
    }

    @Test fun cardText() {
        val c = Closed("default", 0L, desktop = true, terminals = 2, cause = ClosedCause.ChildProcesses)
        assertEquals("Android closed default", closedTitle(c))
        assertEquals("It happened at 14:02 while Selkie was in the background. The desktop and 2 terminals ended. " +
            "Your files are safe; unsaved work in open apps is lost.", closedLine(c, "14:02"))
        assertEquals("1 terminal", closedWhat(Closed("dev", null, desktop = false, terminals = 1)).lowercase())
        assertEquals("Text Editor", closedWhat(Closed("dev", null, desktop = true, appName = "Text Editor")))
        val low = c.copy(cause = ClosedCause.LowMemory, background = false)
        assertEquals("It happened at 14:02, when the device ran low on memory. The desktop and 2 terminals ended. " +
            "Your files are safe; unsaved work in open apps is lost.", closedLine(low, "14:02"))
        assertEquals("The device restarted while it ran. The desktop and 2 terminals ended. " +
            "Your files are safe; unsaved work in open apps is lost.", closedLine(c.copy(cause = ClosedCause.Restart), null))
        assertEquals("Selkie stopped, and default with it", closedTitle(c.copy(cause = ClosedCause.Crash)))
    }
}

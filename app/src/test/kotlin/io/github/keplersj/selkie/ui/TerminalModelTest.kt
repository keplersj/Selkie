package io.github.keplersj.selkie.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalModelTest {
    @Test fun keysFollowTheCursorMode() {
        assertEquals("\u001b[A", keySequence("Up", appCursor = false))
        assertEquals("\u001bOA", keySequence("Up", appCursor = true))
        assertEquals("\u001b[H", keySequence("Home", appCursor = false))
        assertEquals("\u001bOF", keySequence("End", appCursor = true))
        assertEquals("\u001b[5~", keySequence("PgUp", appCursor = true))
        assertEquals("\u001b", keySequence("Esc", appCursor = false))
        assertEquals("\t", keySequence("Tab", appCursor = false))
        assertNull(keySequence("Ctrl", appCursor = false))
    }

    @Test fun hingeSplitIsRelativeToThePane() {
        val fold = Fold(vertical = true, start = 400.dp, end = 424.dp, halfOpened = false)
        assertEquals(400.dp to 424.dp, hingeSplit(fold, offset = 0.dp, size = 840.dp))
        // A pane that starts 16dp in sees the hinge 16dp sooner.
        assertEquals(384.dp to 408.dp, hingeSplit(fold, offset = 16.dp, size = 800.dp))
        // A hinge outside the pane leaves one pane empty rather than going negative.
        assertEquals(0.dp to 0.dp, hingeSplit(fold, offset = 500.dp, size = 300.dp))
    }

    @Test fun postures() {
        assertTrue(Fold(vertical = true, start = 0.dp, end = 0.dp, halfOpened = false).book)
        assertTrue(Fold(vertical = false, start = 0.dp, end = 0.dp, halfOpened = true).tabletop)
        assertFalse(Fold(vertical = false, start = 0.dp, end = 0.dp, halfOpened = false).tabletop)
    }
}

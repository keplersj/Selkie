package io.github.keplersj.selkie.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class XrModelTest {
    @Test fun theDesktopPanelDefaultsTo1280By800() =
        assertEquals(XrPanelSize(1280.dp, 800.dp), xrDesktopPanel(null))

    @Test fun aRunningDesktopKeepsItsShape() =
        assertEquals(XrPanelSize(1280.dp, 800.dp), xrDesktopPanel(2560 to 1600))

    @Test fun aTallDesktopFitsTheHeight() =
        assertEquals(XrPanelSize(600.dp, 800.dp), xrDesktopPanel(1200 to 1600))

    @Test fun aBadSizeFallsBackToTheDefault() =
        assertEquals(XrPanelSize(1280.dp, 800.dp), xrDesktopPanel(0 to 0))
}

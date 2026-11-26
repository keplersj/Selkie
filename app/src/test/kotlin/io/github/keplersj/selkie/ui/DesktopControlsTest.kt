package io.github.keplersj.selkie.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class DesktopControlsTest {
    @Test fun backShowsTheControlsBeforeLeaving() {
        assertEquals(DesktopBack.ShowControls, desktopBack(controlsShowing = false))
        assertEquals(DesktopBack.Leave, desktopBack(controlsShowing = true))
    }
}

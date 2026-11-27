package io.github.keplersj.selkie

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import io.github.keplersj.selkie.ui.DESKTOP_CONTROLS_MILLIS
import io.github.keplersj.selkie.ui.DesktopControls
import io.github.keplersj.selkie.ui.DesktopControlsLayer
import io.github.keplersj.selkie.ui.DesktopControlsState
import io.github.keplersj.selkie.ui.SHOW_DESKTOP_CONTROLS
import io.github.keplersj.selkie.ui.SelkieTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * On a phone, once the desktop's controls hide they must come back by touch, or there is no way to leave
 * the desktop (the bug on main after the redesign). Phone width, the real controls, the real time on screen.
 */
class DesktopControlsPhoneTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val stop = "Stop…"

    private fun advance(ms: Long) { rule.mainClock.advanceTimeBy(ms); rule.waitForIdle() }

    @Test fun hiddenControlsComeBackFromTheTabAndFromBack() {
        val state = DesktopControlsState()
        var left = 0
        rule.mainClock.autoAdvance = false
        rule.setContent {
            SelkieTheme(dark = true, dynamicColor = false) {
                Box(Modifier.width(390.dp).height(844.dp).background(Color(0xFF101418))) {
                    DesktopControlsLayer(state, onLeave = { left++ }) {
                        DesktopControls("default", null, onSuper = {}, onKeyboard = {}, onEsc = {}, onTerminal = {},
                            onBack = {}, onStop = {}, onHide = state::hide)
                    }
                }
            }
        }
        advance(500)
        rule.onNodeWithText(stop).assertIsDisplayed()

        // They go on their own, leaving the tab.
        advance(DESKTOP_CONTROLS_MILLIS + 1000)
        rule.onNodeWithText(stop).assertDoesNotExist()

        // The tab brings them back, for their full time again.
        rule.onNodeWithContentDescription(SHOW_DESKTOP_CONTROLS).assertIsDisplayed().performClick()
        advance(1000)
        rule.onNodeWithText(stop).assertIsDisplayed()
        rule.onNodeWithContentDescription(SHOW_DESKTOP_CONTROLS).assertDoesNotExist()
        advance(DESKTOP_CONTROLS_MILLIS + 1000)
        rule.onNodeWithText(stop).assertDoesNotExist()

        // Back shows them too, and Back while they show leaves the desktop.
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        advance(1000)
        rule.onNodeWithText(stop).assertIsDisplayed()
        assertEquals(0, left)
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        advance(500)
        assertEquals(1, left)
    }
}

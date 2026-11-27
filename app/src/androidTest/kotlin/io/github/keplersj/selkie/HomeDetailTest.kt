package io.github.keplersj.selkie

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import io.github.keplersj.selkie.ui.Fixtures
import io.github.keplersj.selkie.ui.HomeScreen
import io.github.keplersj.selkie.ui.Section
import io.github.keplersj.selkie.ui.SectionScaffold
import io.github.keplersj.selkie.ui.SelkieTheme
import org.junit.Rule
import org.junit.Test

/**
 * On a phone, opening a container hides the navigation bar. That must not start Home over, or the
 * container's screen flashes and closes again (the bug on main after the sections batch).
 */
class HomeDetailTest {
    private fun openCard(name: String) = SemanticsMatcher("opens $name") {
        it.config.getOrNull(SemanticsActions.OnClick)?.label == "Open $name"
    }

    @get:Rule val rule = createComposeRule()

    @Test fun aContainerStaysOpenWhenTheBarStepsAside() {
        rule.setContent {
            SelkieTheme {
                var detail by remember { mutableStateOf(false) }
                // Phone width, so Home is a list with a bar under it, whatever the emulator's screen.
                Box(Modifier.width(390.dp).fillMaxHeight()) {
                    SectionScaffold(Section.Containers, onSection = {}, onNewContainer = {}, showNavigation = !detail) { rail ->
                        HomeScreen(Fixtures.homeContainers, onOpenTerminal = {}, onOpenDesktop = {},
                            onConfigureDesktop = {}, onUpgrade = {}, onRollback = {}, onSwitch = {}, onRefresh = {},
                            newInList = !rail, onDetailShown = { detail = it })
                    }
                }
            }
        }
        rule.onNode(openCard("dev")).performClick()
        rule.waitForIdle()
        // The container's own screen, with its tabs, and not the list it was opened from.
        rule.onAllNodesWithText("Updates").onFirst().assertIsDisplayed()
        rule.onAllNodesWithText("Settings").onFirst().assertIsDisplayed()
        rule.onNode(openCard("dev")).assertDoesNotExist()
    }
}

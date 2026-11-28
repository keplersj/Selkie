package io.github.keplersj.selkie

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.keplersj.selkie.engine.AppEntry
import io.github.keplersj.selkie.platform.AppShortcuts
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * A Linux app's home screen shortcut, on a real launcher: opening an app adds it to Selkie's
 * shortcuts, and a shortcut whose container is gone says so and can be turned off.
 */
class AppShortcutTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val app = AppEntry(id = "org.example.Gone.desktop", name = "Gone App")
    private val id = AppShortcuts.id("no-such-box", app.id)

    private fun dynamicIds() = ShortcutManagerCompat.getDynamicShortcuts(ctx).map { it.id }

    @After fun cleanUp() { ShortcutManagerCompat.removeAllDynamicShortcuts(ctx) }

    @Test fun aShortcutToAMissingContainerOffersToTurnItOff() {
        AppShortcuts.opened(ctx, "no-such-box", app)
        assertTrue("opening an app adds a shortcut", id in dynamicIds())

        ActivityScenario.launch<AppActivity>(AppShortcuts.intent(ctx, "no-such-box", app)).use {
            rule.waitUntil(timeoutMillis = 60_000) {
                rule.onAllNodesWithText("Can't open Gone App").fetchSemanticsNodes().isNotEmpty()
            }
            rule.onNodeWithText("no-such-box isn't on this device any more.").assertIsDisplayed()
            rule.onNodeWithText("Open Selkie").assertIsDisplayed()
            rule.onNodeWithText("Turn off this icon").performClick()
            rule.waitUntil(timeoutMillis = 10_000) { id !in dynamicIds() }
        }
    }
}

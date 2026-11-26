package io.github.keplersj.selkie

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.assertIsDisplayed
import org.junit.Rule
import org.junit.Test

/** The app launches and shows one of its entry screens (phantom explainer, first container, or home). */
class SmokeTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun launches() {
        val entry = hasText("New container") or hasText("One-time Android setup") or hasText("Selkie")
        rule.waitUntil(timeoutMillis = 30_000) { rule.onAllNodes(entry).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodes(entry).onFirst().assertIsDisplayed()
    }
}

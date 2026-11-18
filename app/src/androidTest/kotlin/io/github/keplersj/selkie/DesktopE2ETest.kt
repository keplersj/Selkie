package io.github.keplersj.selkie

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ServiceTestRule
import io.github.keplersj.selkie.service.DesktopState
import io.github.keplersj.selkie.service.Operation
import io.github.keplersj.selkie.service.SessionService
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * The graphical session end to end, through the real UI: install Bluefin LTS (signed; GNOME 50), set its
 * session (Bluefin sets no session labels yet), tap "Open desktop", and check that GNOME's frames reach
 * the screen through droidc bridge and Selkie's compositor, and that keyboard input reaches GNOME
 * (Super opens or closes the overview). Screenshots go to the app's external files dir.
 */
class DesktopE2ETest {
    private val tag = "SelkieE2E"
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    @get:Rule val serviceRule = ServiceTestRule.withTimeout(60, java.util.concurrent.TimeUnit.SECONDS)

    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext
    private val ref = "ghcr.io/projectbluefin/bluefin-lts:stable"

    private fun log(s: String) { Log.i(tag, s); println(s) }

    private fun service(): SessionService =
        (serviceRule.bindService(SessionService.intent(ctx)) as SessionService.LocalBinder).service

    private fun waitFor(what: String, timeoutMs: Long, pollMs: Long = 1000, f: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (!f()) {
            if (SystemClock.uptimeMillis() > deadline) fail("timed out after ${timeoutMs / 1000}s waiting for $what")
            Thread.sleep(pollMs)
        }
    }

    private fun stats(s: SessionService): JsonObject? =
        s.compositor()?.stats()?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }

    private fun frame(s: SessionService): JsonObject? = stats(s)?.get("frame") as? JsonObject

    private fun screenshot(name: String) {
        val bmp = inst.uiAutomation.takeScreenshot() ?: return
        val dir = ctx.getExternalFilesDir(null) ?: return
        File(dir, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        log("screenshot ${File(dir, name)}")
    }

    @Test fun gnomeDesktopShowsAndTakesInput() {
        val s = service()
        // 1. Install Bluefin LTS unless it is already current.
        if (s.state.value.status?.current?.imageRef != ref) {
            inst.runOnMainSync { s.switchTo(ref, insecure = false) }
            var lastPhase = ""
            waitFor("switch to $ref", 60 * 60_000L, 5000) {
                when (val op = s.state.value.operation) {
                    is Operation.Running -> { if (op.phase != lastPhase) { lastPhase = op.phase; log("phase=${op.phase}") }; false }
                    is Operation.Failed -> fail("switch failed: ${op.error} ${op.stderrTail}").let { true }
                    else -> true
                }
            }
            inst.runOnMainSync { s.dismissOperation() }
        }
        // 2. Bluefin LTS has no containers.droidc.session.* labels: use the documented override.
        var err: String? = "pending"
        inst.runOnMainSync {
            s.configureSession("screencast",
                """["gnome-shell","--headless","--virtual-monitor","{width}x{height}"]""",
                """[["pipewire"],["wireplumber"]]""", "GNOME") { err = it }
        }
        waitFor("session override", 120_000) { err != "pending" }
        assertNull("droidc session set: $err", err)
        waitFor("graphical session in shell-spec", 120_000) { s.state.value.graphical?.configured == true }

        // 3. Open it from the home screen.
        val open = hasText("Open desktop", substring = true)
        rule.waitUntil(timeoutMillis = 60_000) { rule.onAllNodes(open).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodes(open).onFirst().performClick()
        val t0 = SystemClock.uptimeMillis()
        waitFor("a GNOME frame on screen", 10 * 60_000L, 2000) {
            (s.state.value.desktop as? DesktopState.Failed)?.let { fail("session failed: ${it.message}\n${it.logTail}") }
            (frame(s)?.get("sampled_colors")?.jsonPrimitive?.intOrNull ?: 0) > 50
        }
        val first = (SystemClock.uptimeMillis() - t0) / 1000.0
        log("SELKIE_METRIC desktop_first_frame_seconds=$first stats=${stats(s)}")
        Thread.sleep(5000) // let the shell finish its start-up animation
        screenshot("desktop-1.png")

        // 4. Keyboard through the Android view: Super toggles the overview, which changes the frame.
        val view = rule.activity.window.decorView
        val (w, h) = view.width.toFloat() to view.height.toFloat()
        val now = SystemClock.uptimeMillis()
        // A tap on the right edge (empty desktop in both GNOME views) focuses the compositor view.
        inst.sendPointerSync(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, w * 0.97f, h * 0.6f, 0))
        inst.sendPointerSync(MotionEvent.obtain(now, now + 50, MotionEvent.ACTION_UP, w * 0.97f, h * 0.6f, 0))
        Thread.sleep(3000)
        val before = frame(s)?.get("hash")?.jsonPrimitive?.content
        val presentedBefore = stats(s)?.get("presented")?.jsonPrimitive?.longOrNull ?: 0
        inst.sendKeySync(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_META_LEFT))
        inst.sendKeySync(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_META_LEFT))
        waitFor("the frame to change after Super", 60_000, 500) {
            frame(s)?.get("hash")?.jsonPrimitive?.content != before
        }
        Thread.sleep(5000)
        log("SELKIE_METRIC desktop_after_super stats=${stats(s)} presented_before=$presentedBefore")
        screenshot("desktop-2-super.png")

        // 5. Stop: compositor and session go away.
        inst.runOnMainSync { s.stopDesktop() }
        waitFor("stopped", 30_000) { s.state.value.desktop is DesktopState.Stopped && s.compositor() == null }
    }
}

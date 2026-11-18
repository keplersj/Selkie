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
 * Graphical sessions end to end through the real UI on Bluefin LTS (signed). Bluefin sets no
 * `containers.droidc.session.*` labels yet, so each test sets the session with the override:
 *
 * - [gnomeHeadless]: `renderer=screencast`, gnome-shell 50 shown by droidc bridge. Checks that frames
 *   reach the screen, that a key pressed in the Android view reaches the compositor, and that Super
 *   (overlay button) changes what GNOME shows.
 * - [gtkAppNested]: `renderer=wayland`, a GTK 4 app (Nautilus) as a direct client of Selkie's
 *   compositor, the path nested session compositors (KWin, cosmic-comp, gamescope) take.
 *
 * Screenshots go to the app's external files dir.
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
        (serviceRule.bindService(SessionService.intent(ctx)) as SessionService.LocalBinder).service.also { current = it }

    /**
     * Polls [f]. The Compose test rule owns the UI's frame clock: the app only recomposes when the test
     * advances it, so each poll advances it as well as sleeping (a plain sleep would freeze the UI, e.g.
     * a click's navigation never happens).
     */
    private fun waitFor(what: String, timeoutMs: Long, pollMs: Long = 1000, f: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var lastLog = 0L
        while (!f()) {
            val now = SystemClock.uptimeMillis()
            if (now > deadline) {
                screenshot("timeout-${what.replace(Regex("[^A-Za-z0-9]+"), "-")}.png")
                fail("timed out after ${timeoutMs / 1000}s waiting for $what; desktop=${current?.state?.value?.desktop} stats=${current?.let { stats(it) }}")
            }
            if (now - lastLog > 30_000) {
                lastLog = now
                log("waiting for $what: desktop=${current?.state?.value?.desktop} stats=${current?.let { stats(it) }}")
            }
            rule.mainClock.advanceTimeBy(pollMs)
            Thread.sleep(pollMs)
        }
    }

    private var current: SessionService? = null

    private fun stats(s: SessionService): JsonObject? =
        s.compositor()?.stats()?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
    private fun long(s: SessionService, key: String): Long = stats(s)?.get(key)?.jsonPrimitive?.longOrNull ?: 0
    private fun frame(s: SessionService): JsonObject? = stats(s)?.get("frame") as? JsonObject
    private fun hash(s: SessionService): String? = frame(s)?.get("hash")?.jsonPrimitive?.content
    private fun colors(s: SessionService): Int = frame(s)?.get("sampled_colors")?.jsonPrimitive?.intOrNull ?: 0

    /** Waits until no new frame has been shown for [quietMs] (animations done). */
    private fun settle(s: SessionService, quietMs: Long = 4000, timeoutMs: Long = 120_000) {
        var last = long(s, "presented")
        var since = SystemClock.uptimeMillis()
        waitFor("frames to settle", timeoutMs, 500) {
            val now = long(s, "presented")
            if (now != last) { last = now; since = SystemClock.uptimeMillis() }
            SystemClock.uptimeMillis() - since >= quietMs
        }
    }

    private fun screenshot(name: String) {
        val bmp = inst.uiAutomation.takeScreenshot() ?: return
        val dir = ctx.getExternalFilesDir(null) ?: return
        File(dir, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        log("screenshot ${File(dir, name)}")
    }

    private fun ensureBluefin(s: SessionService) {
        // A clean desktop state: an earlier test may have left the session running or failed.
        inst.runOnMainSync { s.stopDesktop() }
        if (s.state.value.status?.current?.imageRef == ref) return
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

    private fun configure(s: SessionService, renderer: String, exec: String, services: String?, name: String) {
        var err: String? = "pending"
        inst.runOnMainSync { s.configureSession(renderer, exec, services, name) { err = it } }
        waitFor("session override", 120_000) { err != "pending" }
        assertNull("droidc session set: $err", err)
        waitFor("session in shell-spec", 120_000) {
            s.state.value.graphical?.let { it.configured && it.renderer == renderer && it.name == name } == true
        }
    }

    /** Opens the desktop from the home screen and waits for a frame with more than [minColors] colours. */
    private fun openDesktop(s: SessionService, minColors: Int, metric: String) {
        val open = hasText("Open desktop", substring = true)
        rule.waitUntil(timeoutMillis = 60_000) { rule.onAllNodes(open).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodes(open).onFirst().performClick()
        val t0 = SystemClock.uptimeMillis()
        waitFor("a frame on screen", 10 * 60_000L, 2000) {
            (s.state.value.desktop as? DesktopState.Failed)?.let {
                // Keep the whole session log: a later test restarts the desktop and truncates it.
                runCatching {
                    java.io.File(inst.targetContext.cacheDir, "desktop.log").copyTo(java.io.File(rule.activity.getExternalFilesDir(null), "$metric-session-failed.log"), overwrite = true)
                }
                fail("session failed: ${it.message}\n${it.logTail}")
            }
            colors(s) > minColors
        }
        log("SELKIE_METRIC ${metric}_first_frame_seconds=${(SystemClock.uptimeMillis() - t0) / 1000.0} stats=${stats(s)}")
    }

    private fun stop(s: SessionService) {
        inst.runOnMainSync { s.stopDesktop() }
        waitFor("stopped", 30_000) { s.state.value.desktop is DesktopState.Stopped && s.compositor() == null }
        rule.waitForIdle()
    }

    @Test fun gnomeHeadless() {
        val s = service()
        ensureBluefin(s)
        configure(s, "screencast", """["gnome-shell","--headless","--virtual-monitor","{width}x{height}"]""",
            """[["pipewire"],["wireplumber"]]""", "GNOME")
        openDesktop(s, 50, "gnome")
        settle(s)
        screenshot("gnome-1.png")

        // A tap (right edge: empty in GNOME's views) focuses the compositor view; Escape from the
        // Android key path then closes GNOME's first-run dialog if it is up. The compositor's input
        // counter proves the events got through Android.
        val view = rule.activity.window.decorView
        val (w, h) = view.width.toFloat() to view.height.toFloat()
        val inputs0 = long(s, "inputs")
        val now = SystemClock.uptimeMillis()
        inst.sendPointerSync(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, w * 0.97f, h * 0.6f, 0))
        inst.sendPointerSync(MotionEvent.obtain(now, now + 50, MotionEvent.ACTION_UP, w * 0.97f, h * 0.6f, 0))
        inst.sendKeySync(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE))
        inst.sendKeySync(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ESCAPE))
        waitFor("tap and Escape to reach the compositor", 30_000, 250) { long(s, "inputs") >= inputs0 + 4 }
        settle(s)
        screenshot("gnome-2-escape.png")

        // Super from the overlay button toggles GNOME's overview: the frame changes.
        val before = hash(s)
        val inputs1 = long(s, "inputs")
        rule.onAllNodes(hasText("Super")).onFirst().performClick()
        waitFor("Super to reach the compositor", 30_000, 250) { long(s, "inputs") >= inputs1 + 2 }
        waitFor("GNOME to redraw after Super", 60_000, 500) { hash(s) != before }
        settle(s)
        log("SELKIE_METRIC gnome_after_super stats=${stats(s)}")
        screenshot("gnome-3-super.png")
        stop(s)
    }

    @Test fun gtkAppNested() {
        val s = service()
        ensureBluefin(s)
        configure(s, "wayland", """["nautilus","--new-window"]""", null, "Files")
        openDesktop(s, 5, "gtk")
        assertTrue("a client is connected: ${stats(s)}", long(s, "clients") >= 1)
        settle(s)
        screenshot("gtk-nautilus.png")
        val inputs0 = long(s, "inputs")
        val view = rule.activity.window.decorView
        val now = SystemClock.uptimeMillis()
        inst.sendPointerSync(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, view.width * 0.5f, view.height * 0.5f, 0))
        inst.sendPointerSync(MotionEvent.obtain(now, now + 50, MotionEvent.ACTION_UP, view.width * 0.5f, view.height * 0.5f, 0))
        waitFor("touch to reach the app", 30_000, 250) { long(s, "inputs") >= inputs0 + 2 }
        stop(s)
    }
}

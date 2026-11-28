package io.github.keplersj.selkie

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ServiceTestRule
import io.github.keplersj.selkie.engine.*
import io.github.keplersj.selkie.proot.GuestFiles
import io.github.keplersj.selkie.proot.ProotCommandBuilder
import kotlinx.coroutines.runBlocking
import io.github.keplersj.selkie.service.DesktopState
import io.github.keplersj.selkie.service.Operation
import io.github.keplersj.selkie.service.RootChoice
import io.github.keplersj.selkie.service.SessionService
import io.github.keplersj.selkie.ui.OPEN_DESKTOP_TAG
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
 * - [appShortcutAtDisplayScale]: a pinned app's home screen shortcut (Nautilus) opens that app on
 *   its own, in app mode at the display's scale (e2e-gui.sh runs it again at phone size and density).
 * - [terminalInGnome]: the GNOME desktop as above, then what a user does: Super, type "terminal", Enter, and a command
 *   typed into the terminal that opens. The command creates a file in the guest's /tmp, which the test
 *   reads from the host side, so a window on screen is not the only evidence that a shell ran.
 *
 * - [gnomeScale]: GNOME's scale follows Android's density, survives a change made like GNOME
 *   Settings does, and the user's kept scale wins on the next start (only with `-e scale`).
 *
 * Screenshots go to the app's external files dir.
 */
class DesktopE2ETest {
    private val tag = "SelkieE2E"
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    @get:Rule val serviceRule = ServiceTestRule.withTimeout(60, java.util.concurrent.TimeUnit.SECONDS)

    // The test presses the desktop controls' Super long after they would hide on their own.
    @org.junit.Before fun keepDesktopControls() { io.github.keplersj.selkie.ui.desktopControlsAutoHide = false }
    @org.junit.After fun restoreDesktopControls() { io.github.keplersj.selkie.ui.desktopControlsAutoHide = true }

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
            allowRootRequests()
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

    /**
     * The guest's root commands ran unasked before Selkie asked about them; this test isn't about them, so
     * it allows each once, as before, and logs it: a root screen would cover the desktop it waits for.
     */
    private fun allowRootRequests() {
        val s = current ?: return
        for (r in s.state.value.rootRequests) {
            log("SELKIE_METRIC root_request container=${r.container} via=${r.via} from=${r.from} argv=${r.commandLine}")
            inst.runOnMainSync { s.answerRootRequest(r, RootChoice.Once) }
        }
    }

    private fun stats(s: SessionService): JsonObject? =
        s.compositor()?.stats()?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
    private fun long(s: SessionService, key: String): Long = stats(s)?.get(key)?.jsonPrimitive?.longOrNull ?: 0
    private fun frame(s: SessionService): JsonObject? = stats(s)?.get("frame") as? JsonObject
    private fun hash(s: SessionService): String? = frame(s)?.get("hash")?.jsonPrimitive?.content
    private fun colors(s: SessionService): Int = frame(s)?.get("sampled_colors")?.jsonPrimitive?.intOrNull ?: 0

    /**
     * Waits until the picture has been unchanged for [quietMs]. GNOME on llvmpipe keeps presenting frames
     * (about 16 per second) after startup, so the count of presented frames never goes quiet; the frame
     * hash is what settles. A picture that is still changing at [timeoutMs] is logged, not failed: what
     * the tests assert is rendering and input, and the screenshot shows the state.
     */
    private fun settle(s: SessionService, quietMs: Long = 4000, timeoutMs: Long = 120_000) {
        var last = hash(s)
        var since = SystemClock.uptimeMillis()
        val deadline = since + timeoutMs
        while (true) {
            val now = SystemClock.uptimeMillis()
            val h = hash(s)
            if (h != last) { last = h; since = now }
            if (now - since >= quietMs) return
            if (now > deadline) { log("settle: picture still changing after ${timeoutMs / 1000}s stats=${stats(s)}"); return }
            rule.mainClock.advanceTimeBy(500)
            Thread.sleep(500)
        }
    }

    private fun findCompositorView(v: android.view.View): io.github.keplersj.selkie.display.CompositorView? {
        if (v is io.github.keplersj.selkie.display.CompositorView) return v
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) findCompositorView(v.getChildAt(i))?.let { return it }
        return null
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
        val open = hasTestTag(OPEN_DESKTOP_TAG)
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

    /** From the desktop screen back to the home screen: Back shows Selkie's controls, Back again leaves. */
    private fun backToHome() {
        val open = hasTestTag(OPEN_DESKTOP_TAG)
        repeat(4) {
            if (rule.onAllNodes(open).fetchSemanticsNodes().isNotEmpty()) return
            rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
            pause(1_000)
        }
        rule.waitUntil(timeoutMillis = 10_000) { rule.onAllNodes(open).fetchSemanticsNodes().isNotEmpty() }
    }

    /** Types [text] with key events delivered straight to the compositor view (letters, digits, - / space, \n). */
    private fun typeText(text: String) {
        for (c in text) {
            val code = when (c) {
                in 'a'..'z' -> KeyEvent.KEYCODE_A + (c - 'a')
                in '0'..'9' -> KeyEvent.KEYCODE_0 + (c - '0')
                '-' -> KeyEvent.KEYCODE_MINUS
                '/' -> KeyEvent.KEYCODE_SLASH
                ' ' -> KeyEvent.KEYCODE_SPACE
                '\n' -> KeyEvent.KEYCODE_ENTER
                '\u001b' -> KeyEvent.KEYCODE_ESCAPE
                else -> error("no key for '$c'")
            }
            inst.runOnMainSync {
                val v = findCompositorView(rule.activity.window.decorView)
                v?.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
                v?.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
            }
            rule.mainClock.advanceTimeBy(80); Thread.sleep(80)
        }
    }

    /**
     * Runs [script] in a second proot against the live session (same /run, so the user bus and the
     * display are there) and returns its output.
     */
    private fun guestRun(script: String, timeoutSec: Long = 90): String {
        val env = engineEnvFrom(ctx)
        val spec = runBlocking { (DroidcClient(env).shellSpec() as DroidcOutcome.Success).shellSpec() }
        val b = ProotCommandBuilder(env, spec, GuestFiles.prepare(env, spec))
        val inv = b.command(listOf("/bin/sh", "-c", script))
        val pb = ProcessBuilder(inv.argv).redirectErrorStream(true)
        pb.environment().apply { clear(); putAll(inv.env) }
        val p = pb.start()
        val text = StringBuilder()
        val t = Thread { text.append(p.inputStream.bufferedReader().readText()) }.also { it.start() }
        if (!p.waitFor(timeoutSec, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly()
        t.join(5000)
        return text.toString()
    }

    /** [guestRun], logging the output as SELKIE_GUEST lines, and returning it (empty on failure). */
    private fun guestProbe(label: String, script: String, timeoutSec: Long = 90): String {
        return try {
            guestRun(script, timeoutSec).also { out ->
                out.lines().take(80).forEach { log("SELKIE_GUEST $label ${it.take(300)}") }
            }
        } catch (e: Throwable) { log("SELKIE_GUEST $label failed: $e"); "" }
    }

    /**
     * Turns Android's night mode on and checks that GNOME's color scheme follows it through
     * `droidc host-link`, then puts night mode back. Logged as a metric, like the sound check.
     */
    private fun checkAppearance() {
        val ui = inst.uiAutomation
        fun shell(cmd: String) = ui.executeShellCommand(cmd).use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
        // Under the supervisor, as host-link runs, so gsettings reaches the same session bus and dconf.
        val read = listOf("color-scheme", "accent-color", "text-scaling-factor").joinToString("\n") { key ->
            "echo \"$key=$(DROIDC_USER_SERVICES_WAIT=10 timeout 20 /usr/bin/droidc supervise -- " +
                "gsettings get org.gnome.desktop.interface $key 2>&1 | tail -1)\""
        }
        guestProbe("appearance-before", read)
        shell("cmd uimode night yes")
        var dark = false
        val deadline = SystemClock.uptimeMillis() + 30_000
        while (SystemClock.uptimeMillis() < deadline && !dark) {
            Thread.sleep(2000)
            dark = "color-scheme='prefer-dark'" in guestProbe("appearance-dark", read)
        }
        log("SELKIE_METRIC appearance_follows_night_mode=$dark")
        shell("cmd uimode night no")
        val linkLog = engineEnvFrom(ctx).hostLinkLog
        val text = if (linkLog.exists()) linkLog.readText() else "(no ${linkLog.name})"
        text.lines().takeLast(20).forEach { log("SELKIE_GUEST host-link-log ${it.take(300)}") }
    }

    /**
     * Plays a short tone in the guest (the default sink is droidc audio-out's) and checks that the guest's
     * sound reaches Selkie. Android's side is only measured: the emulator runs with -noaudio.
     */
    private fun checkSound(s: SessionService) {
        val tone = File(ctx.filesDir, "guest/tmp/selkie-tone.wav")
        writeTone(tone)
        val before = s.audioBytes()
        logProcs("sound-before", "audio-out|pw-dump|pw-record|pw-cli|pipewire|wireplumber")
        guestProbe("sound", """
            uid=${'$'}(id -u)
            export XDG_RUNTIME_DIR=/run/user/${'$'}uid PIPEWIRE_REMOTE=pipewire-0-manager
            ls /run/user/${'$'}uid 2>&1 | grep -i pipewire
            echo "nodes:"; timeout 10 pw-cli ls Node 2>&1 | grep -E 'node.name|media.class' | head -20
            echo "default sink:"; timeout 10 pw-metadata 0 default.configured.audio.sink 2>&1 | tail -2
            timeout 20 pw-play /tmp/selkie-tone.wav 2>&1 | head -10
            echo "pw-play exit=${'$'}?"
        """.trimIndent())
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline && (s.audioBytes()?.first ?: 0) == 0L) Thread.sleep(500)
        val after = s.audioBytes()
        log("SELKIE_METRIC audio_bytes received/played before=$before after=$after")
        logProcs("sound-after", "audio-out|pw-dump|pw-record|pw-cli")
        val audioLog = engineEnvFrom(ctx).audioLog
        val text = if (audioLog.exists()) audioLog.readText() else "(no ${audioLog.name})"
        text.lines().takeLast(20).forEach { log("SELKIE_GUEST sound-log ${it.take(300)}") }
        tone.delete()
        assertTrue("no guest sound reached Selkie (audio_bytes $before -> $after)", (after?.first ?: 0) > 0L)
    }

    /** One second of a 440 Hz tone at half scale, 48 kHz stereo S16LE, as a WAV file. */
    private fun writeTone(f: File) {
        val rate = 48_000; val frames = rate
        val data = java.nio.ByteBuffer.allocate(frames * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        repeat(frames) { i ->
            val v = (Math.sin(2 * Math.PI * 440 * i / rate) * 16_000).toInt().toShort()
            data.putShort(v); data.putShort(v)
        }
        val h = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt(36 + frames * 4).put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(2).putInt(rate).putInt(rate * 4)
            .putShort(4).putShort(16)
        h.put("data".toByteArray()).putInt(frames * 4)
        f.parentFile?.mkdirs()
        f.writeBytes(h.array() + data.array())
    }

    private val terminalProcs = "ptyxis|gnome-console|kgx|xterm|konsole|alacritty"

    private fun countProcs(pattern: String): Int {
        val re = Regex(pattern, RegexOption.IGNORE_CASE)
        return java.io.File("/proc").listFiles { f -> f.name.all { it.isDigit() } }.orEmpty().count { d ->
            try { re.containsMatchIn(java.io.File(d, "cmdline").readText().replace('\u0000', ' ')) } catch (e: Exception) { false }
        }
    }

    /** Types [query] into the open overview, presses Enter, and reports whether a terminal process appeared. */
    private fun launchFromOverview(s: SessionService, query: String, shot: String): Boolean {
        val h0 = hash(s)
        typeText(query)
        settle(s, 3000, 30_000)
        log("SELKIE_STEP typed '$query': hash $h0 -> ${hash(s)}, colors=${colors(s)}")
        screenshot("$shot-search.png")
        val h1 = hash(s)
        typeText("\n")
        settle(s, 5000, 60_000)
        log("SELKIE_STEP enter: hash $h1 -> ${hash(s)}, colors=${colors(s)}")
        screenshot("$shot-launched.png")
        val deadline = SystemClock.uptimeMillis() + 30_000
        while (SystemClock.uptimeMillis() < deadline && countProcs(terminalProcs) == 0) {
            rule.mainClock.advanceTimeBy(500); Thread.sleep(500)
        }
        logProcs("after-enter-$query", "ptyxis|terminal|gnome-console|kgx|xterm|konsole|alacritty")
        return countProcs(terminalProcs) > 0
    }

    /** Logs the command lines of the session's processes matching [pattern] (the instrumentation shares the app's uid). */
    private fun logProcs(label: String, pattern: String) {
        val re = Regex(pattern, RegexOption.IGNORE_CASE)
        val hits = java.io.File("/proc").listFiles { f -> f.name.all { it.isDigit() } }.orEmpty().mapNotNull { d ->
            try {
                val c = java.io.File(d, "cmdline").readText().replace('\u0000', ' ').trim()
                if (c.isNotEmpty() && re.containsMatchIn(c)) "${d.name}: ${c.take(160)}" else null
            } catch (e: Exception) { null }
        }
        log("SELKIE_PROCS $label count=${hits.size}")
        hits.take(40).forEach { log("SELKIE_PROCS $label $it") }
    }

    @Test fun gnomeHeadless() {
        val s = service()
        ensureBluefin(s)
        configure(s, "screencast", """["gnome-shell","--headless","--virtual-monitor","{width}x{height}"]""",
            PipeWireServices.JSON, "GNOME")
        openDesktop(s, 50, "gnome")
        settle(s)
        screenshot("gnome-1.png")
        checkSound(s)
        checkAppearance()

        // A tap (right edge: empty in GNOME's views) focuses the compositor view; Escape from the
        // Android key path then closes GNOME's first-run dialog if it is up. The compositor's input
        // counter proves the events got through Android.
        val view = rule.activity.window.decorView
        val (w, h) = view.width.toFloat() to view.height.toFloat()
        val inputs0 = long(s, "inputs")
        val now = SystemClock.uptimeMillis()
        inst.sendPointerSync(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, w * 0.97f, h * 0.6f, 0))
        inst.sendPointerSync(MotionEvent.obtain(now, now + 50, MotionEvent.ACTION_UP, w * 0.97f, h * 0.6f, 0))
        waitFor("the tap to reach the compositor", 30_000, 250) { long(s, "inputs") >= inputs0 + 2 }
        log("input: tap arrived, inputs=${long(s, "inputs")}")
        inst.sendKeySync(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE))
        inst.sendKeySync(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ESCAPE))
        var keysArrived = false
        val keyDeadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < keyDeadline && !keysArrived) {
            rule.mainClock.advanceTimeBy(250); Thread.sleep(250)
            keysArrived = long(s, "inputs") >= inputs0 + 4
        }
        if (!keysArrived) {
            // The injected keys did not get to the compositor view through Android's focus routing
            // (seen on some emulator runs). Deliver them to the view directly so the rest of the key path
            // (KeyMap, compositor, GNOME) is still exercised, and say so in the metrics.
            log("SELKIE_METRIC key_path=direct-to-view inputs=${long(s, "inputs")}")
            inst.runOnMainSync {
                val v = findCompositorView(rule.activity.window.decorView)
                v?.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE))
                v?.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ESCAPE))
            }
            waitFor("Escape to reach the compositor", 30_000, 250) { long(s, "inputs") >= inputs0 + 4 }
        } else log("SELKIE_METRIC key_path=android-focus")
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

        // The overview is up: typing searches the installed apps, Enter launches the first result
        // (Bluefin's terminal), and a command typed afterwards runs in its shell. Done in this first
        // session of the run, which does not depend on reopening the desktop.
        // The guest's /tmp is files/guest/tmp on the host (ProotCommandBuilder binds it).
        val marker = File(ctx.filesDir, "guest/tmp/selkie-terminal-ok")
        marker.delete()
        // Try the search twice (the second with the app's own name, after closing and reopening the
        // overview), logging what the frame does at each step: a terminal process is the evidence.
        var launched = launchFromOverview(s, "terminal", "terminal-1")
        if (!launched) {
            typeText("\u001b\u001b")
            settle(s, 3000, 30_000)
            rule.onAllNodes(hasText("Super")).onFirst().performClick()
            settle(s, 3000, 30_000)
            launched = launchFromOverview(s, "ptyxis", "terminal-1b")
        }
        log("SELKIE_METRIC terminal_launched_from_overview=$launched")
        typeText("touch /tmp/selkie-terminal-ok\n")
        try {
            try {
                waitFor("a command typed into the terminal to run", 120_000, 1000) { marker.exists() }
            } catch (t: AssertionError) {
                // The shell's search/launch did not start a terminal. Ask the guest directly: is the
                // session bus there, which terminal apps are installed, and does one run if started by hand?
                guestProbe("direct-launch", """
                    uid=${'$'}(id -u)
                    export XDG_RUNTIME_DIR=/run/user/${'$'}uid DBUS_SESSION_BUS_ADDRESS=unix:path=/run/user/${'$'}uid/bus
                    ls /run/user/${'$'}uid 2>&1 | head -30
                    ls /usr/share/applications 2>&1 | grep -i -E 'ptyxis|terminal|console'
                    gdbus call --session --dest org.freedesktop.DBus --object-path /org/freedesktop/DBus --method org.freedesktop.DBus.ListNames 2>&1 | head -c 700; echo
                    for w in wayland-0 wayland-1; do [ -S /run/user/${'$'}uid/${'$'}w ] && echo "display ${'$'}w"; done
                    export WAYLAND_DISPLAY=${'$'}(ls /run/user/${'$'}uid | grep -m1 '^wayland-[0-9]*${'$'}')
                    echo "using ${'$'}WAYLAND_DISPLAY"
                    timeout 40 ptyxis --new-window -x 'touch /tmp/selkie-terminal-ok' 2>&1 | head -60
                    echo "ptyxis exit=${'$'}?"
                    ls -l /tmp/selkie-terminal-ok 2>&1
                """.trimIndent())
                throw t
            }
        } finally {
            settle(s, 3000, 30_000)
            screenshot("terminal-3-after-command.png")
            logProcs("final", "ptyxis|terminal|gnome-console|kgx|xterm|bash|gnome-shell|nautilus")
            // The next session overwrites desktop.log; keep this one's.
            try {
                java.io.File(inst.targetContext.cacheDir, "desktop.log").copyTo(
                    java.io.File(rule.activity.getExternalFilesDir(null), "desktop-gnome.log"), overwrite = true)
            } catch (e: Exception) { log("could not keep desktop.log: $e") }
        }
        log("SELKIE_METRIC terminal_command_ran=true")
        checkNetwork()
        checkLinks()
        stop(s)
    }

    /**
     * Reads the NetworkManager stand-in's connectivity on the container's system bus, which host-link
     * feeds from Android's default network: 4 (full) on the emulator's validated network.
     */
    private fun checkNetwork() {
        val text = guestProbe("network", """
            DROIDC_USER_SERVICES_WAIT=10 timeout 20 /usr/bin/droidc supervise -- gdbus call --system \
              --dest org.freedesktop.NetworkManager --object-path /org/freedesktop/NetworkManager \
              --method org.freedesktop.DBus.Properties.GetAll org.freedesktop.NetworkManager 2>&1 | tail -2
        """.trimIndent())
        val connectivity = Regex("'Connectivity': <uint32 (\\d+)>").find(text)?.groupValues?.get(1)
        val metered = Regex("'Metered': <uint32 (\\d+)>").find(text)?.groupValues?.get(1)
        log("SELKIE_METRIC network_connectivity=$connectivity metered=$metered")
    }

    /**
     * Opens a web link from the guest the way GNOME apps do (`gio open`, GIO's default handler) and
     * checks that Android started an activity for it through `droidc host-link`. Last, since the browser
     * takes the screen. Logged as a metric: the emulator image may have no browser.
     */
    private fun checkLinks() {
        val ui = inst.uiAutomation
        fun shell(cmd: String) = ui.executeShellCommand(cmd).use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes().decodeToString()
        }
        val url = "https://example.org/selkie-e2e-link"
        guestProbe("links", """
            run() { DROIDC_USER_SERVICES_WAIT=10 timeout 20 /usr/bin/droidc supervise -- "${'$'}@" 2>&1 | tail -3; }
            echo "https handler: ${'$'}(run gio mime x-scheme-handler/https | head -1)"
            echo "user default: ${'$'}(grep -s scheme-handler/https= ${'$'}HOME/.config/mimeapps.list)"
            ls ${'$'}HOME/.local/share/applications/dev.droidc.AndroidOpen.desktop 2>&1
            # GIO's default handler is the path GNOME apps and xdg-open take inside a GNOME session;
            # a bare xdg-open here has no display, so it skips the scheme handlers for a text browser.
            run gio open $url
            echo "open exit=${'$'}?"
        """.trimIndent())
        // Selkie logs each link it hands to Android; dumpsys shows only the host of a web link.
        var opened = false
        var seen = false
        val deadline = SystemClock.uptimeMillis() + 20_000
        while (SystemClock.uptimeMillis() < deadline && !(opened && seen)) {
            Thread.sleep(1000)
            opened = opened || "opened a https link" in shell("logcat -d -s SelkieLink:I")
            seen = seen || "example.org" in shell("dumpsys activity activities")
        }
        log("SELKIE_METRIC link_opened_on_android=$opened activity_seen=$seen")
        shell("logcat -d -s SelkieLink:*").lines().filter { it.isNotBlank() }.takeLast(5)
            .forEach { log("SELKIE_GUEST links-host ${it.trim().take(300)}") }
        shell("dumpsys activity activities").lines().filter { "example.org" in it || "act=android.intent.action.VIEW" in it }.take(5)
            .forEach { log("SELKIE_GUEST links-activity ${it.trim().take(300)}") }
    }

    /**
     * GNOME's monitor through Mutter's DisplayConfig, from inside the guest (gjs ships with
     * gnome-shell): layout mode, current scale, mode size and supported scales. With [want] > 0 it
     * first applies the supported scale closest to [want] the way GNOME Settings does (method 2,
     * persistent: GNOME then asks to keep it) and reports it as `applied`.
     */
    private fun displayState(want: Double = 0.0): JsonObject {
        File(ctx.filesDir, "guest/tmp/selkie-scale.js").writeText("""
            const { Gio, GLib } = imports.gi;
            const want = parseFloat(ARGV[0] || '0');
            const call = (m, args) => Gio.DBus.session.call_sync('org.gnome.Mutter.DisplayConfig',
                '/org/gnome/Mutter/DisplayConfig', 'org.gnome.Mutter.DisplayConfig', m, args, null,
                Gio.DBusCallFlags.NONE, -1, null);
            const [serial, monitors, logical, props] = call('GetCurrentState', null).recursiveUnpack();
            const lm = logical[0];
            const [spec, modes] = monitors.find(m => m[0][0] === lm[5][0][0]);
            const mode = modes.find(m => m[6]['is-current']);
            const out = { layout: props['layout-mode'], scale: lm[2], width: mode[1], height: mode[2], supported: mode[5] };
            if (want > 0) {
                const scale = mode[5].reduce((a, b) => Math.abs(b - want) < Math.abs(a - want) ? b : a);
                call('ApplyMonitorsConfig', new GLib.Variant('(uua(iiduba(ssa{sv}))a{sv})',
                    [serial, 2, [[lm[0], lm[1], scale, lm[3], lm[4], [[spec[0], mode[0], {}]]]], {}]));
                out.applied = scale;
            }
            print('SCALE ' + JSON.stringify(out));
        """.trimIndent())
        val out = guestRun("""
            uid=${'$'}(id -u)
            export XDG_RUNTIME_DIR=/run/user/${'$'}uid DBUS_SESSION_BUS_ADDRESS=unix:path=/run/user/${'$'}uid/bus
            gjs /tmp/selkie-scale.js $want 2>&1
        """.trimIndent())
        val line = out.lines().firstOrNull { it.startsWith("SCALE ") }
            ?: throw AssertionError("no display state from the guest:\n$out")
        return Json.parseToJsonElement(line.removePrefix("SCALE ")).jsonObject
    }

    /** The session log's bridge and display lines, for a scale check that failed. */
    private fun logDesktop(what: String) {
        val f = File(inst.targetContext.cacheDir, "desktop.log")
        val lines = runCatching { f.readLines() }.getOrDefault(emptyList())
            .filter { Regex("bridge|DisplayConfig|monitor|scale|Keep|revert", RegexOption.IGNORE_CASE).containsMatchIn(it) }
        log("$what: desktop.log (${lines.size} matching lines):\n" + lines.takeLast(60).joinToString("\n"))
    }

    private fun JsonObject.num(key: String): Double = get(key)?.jsonPrimitive?.doubleOrNull ?: Double.NaN

    private fun pause(ms: Long) {
        val end = SystemClock.uptimeMillis() + ms
        while (SystemClock.uptimeMillis() < end) { rule.mainClock.advanceTimeBy(500); Thread.sleep(500) }
    }

    private fun assertRunning(s: SessionService, what: String) {
        val d = s.state.value.desktop
        if (d is DesktopState.Failed) fail("$what: the session stopped: ${d.message}\n${d.logTail}")
        assertTrue("$what: desktop is $d", d is DesktopState.Running)
    }

    /**
     * Changes GNOME's scale to the one closest to [want] as GNOME Settings does, keeps it with Enter
     * in GNOME's "Keep these display settings?" dialog, and checks the session goes on: the scale
     * stays, the session is running, and Super (input through the re-recorded stream) still changes
     * the picture. Returns the scale applied.
     */
    private fun changeScale(s: SessionService, want: Double, shot: String): Double {
        val before = displayState()
        val h0 = hash(s)
        val st = displayState(want)
        val applied = st.num("applied")
        log("SELKIE_METRIC ${shot}_scale_change from=${before.num("scale")} to=$applied state=$st")
        assertNotEquals("$shot: no supported scale near $want other than the current one: $st",
            before.num("scale"), applied, 0.01)
        val changed = SystemClock.uptimeMillis()
        waitFor("GNOME to redraw at scale $applied", 60_000, 500) {
            (s.state.value.desktop as? DesktopState.Failed)?.let { fail("$shot: session failed: ${it.message}\n${it.logTail}") }
            hash(s) != h0
        }
        // GNOME reverts in 20 s unless kept, and its dialog's countdown redraws every second (so
        // nothing settles meanwhile): give the dialog a moment to show, then keep it.
        pause(3_000)
        screenshot("$shot-confirm.png")
        assertRunning(s, "$shot after the scale change")
        log("SELKIE_METRIC ${shot}_keep_after_ms=${SystemClock.uptimeMillis() - changed}")
        typeText("\n") // "Keep Changes" is the dialog's default button
        // Wait past GNOME's 20 s.
        pause(25_000)
        val after = displayState()
        log("SELKIE_METRIC ${shot}_scale_kept state=$after")
        if (kotlin.math.abs(after.num("scale") - applied) > 0.01) logDesktop(shot)
        assertEquals("$shot: scale after keeping it", applied, after.num("scale"), 0.01)
        assertRunning(s, "$shot after keeping the scale")
        screenshot("$shot-kept.png")
        val h1 = hash(s)
        val inputs = long(s, "inputs")
        rule.onAllNodes(hasText("Super")).onFirst().performClick()
        waitFor("Super to reach the compositor", 30_000, 250) { long(s, "inputs") >= inputs + 2 }
        waitFor("GNOME to redraw after Super at scale $applied", 60_000, 500) { hash(s) != h1 }
        settle(s, 3000, 60_000)
        screenshot("$shot-super.png")
        typeText("\u001b")
        settle(s, 3000, 30_000)
        return applied
    }

    /**
     * Display scale, run by e2e-gui.sh with `-e scale tablet` or `-e scale phone` after it sets the
     * emulator's `wm size` and `wm density` (skipped otherwise). GNOME starts at the scale from
     * Android's density, changing the scale like GNOME Settings keeps the session running and taking
     * input, and (tablet) the scale the user kept wins over the density on the next start.
     */
    @Test fun gnomeScale() {
        val form = InstrumentationRegistry.getArguments().getString("scale")
        org.junit.Assume.assumeTrue("runs only with -e scale tablet|phone", form != null)
        val s = service()
        ensureBluefin(s)
        configure(s, "screencast", """["gnome-shell","--headless","--virtual-monitor","{width}x{height}"]""",
            PipeWireServices.JSON, "GNOME")
        val density = ctx.resources.displayMetrics.density
        val want = io.github.keplersj.selkie.display.DisplayScale.forDensity(density).toDouble()
        // Start from no saved display settings: the tablet run is the first that changes them.
        if (form == "tablet") File(ctx.filesDir, "guest/tmp/selkie-forget-monitors.sh").let {
            it.writeText("rm -f \"${'$'}HOME/.config/monitors.xml\"\n")
            guestRun("sh /tmp/selkie-forget-monitors.sh")
        }
        openDesktop(s, 50, "scale_$form")
        settle(s)
        val st0 = displayState()
        log("SELKIE_METRIC scale_${form}_default density=$density want=$want state=$st0")
        screenshot("scale-$form-1-default.png")
        assertNotEquals("GNOME starts above 1x on a ${density}x display: $st0", 1.0, st0.num("scale"), 0.01)
        val supported = st0["supported"]!!.jsonArray.map { it.jsonPrimitive.double }
        for (want in listOf(1.5, 2.0)) assertTrue("GNOME offers ${want}x at ${st0.num("width")}x${st0.num("height")}: $st0",
            supported.any { kotlin.math.abs(it - want) < 0.01 })
        assertEquals("GNOME starts at the supported scale closest to $want: $st0",
            supported.minBy { kotlin.math.abs(it - want) },
            st0.num("scale"), 0.01)

        if (form == "tablet") {
            val kept = changeScale(s, 1.5, "scale-tablet-2-150")
            // The scale the user kept is saved, and wins over the density next time.
            stop(s)
            backToHome()
            openDesktop(s, 50, "scale_tablet_restart")
            settle(s)
            val st = displayState()
            log("SELKIE_METRIC scale_tablet_restart state=$st")
            screenshot("scale-tablet-3-restarted.png")
            assertEquals("the kept scale after a restart: $st", kept, st.num("scale"), 0.01)
            changeScale(s, 2.0, "scale-tablet-4-200")
        } else {
            changeScale(s, 1.5, "scale-phone-2-150")
            changeScale(s, 2.0, "scale-phone-3-200")
        }
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

    /** A pinned app's shortcut opens that app on its own in [AppActivity], in app mode at the display's scale. */
    @Test fun appShortcutAtDisplayScale() {
        val s = service()
        ensureBluefin(s)
        val id = "org.gnome.Nautilus.desktop"
        waitFor("the container's apps", 5 * 60_000L, 2000) { s.state.value.apps?.apps?.any { it.id == id } == true }
        val app = s.state.value.apps!!.apps.first { it.id == id }
        val container = s.state.value.container
        val dm = ctx.resources.displayMetrics
        log("display ${dm.widthPixels}x${dm.heightPixels} density=${dm.density}")
        ctx.startActivity(io.github.keplersj.selkie.platform.AppShortcuts.intent(ctx, container, app)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        val t0 = SystemClock.uptimeMillis()
        waitFor("${app.name} on screen", 10 * 60_000L, 2000) {
            (s.state.value.desktop as? DesktopState.Failed)?.let { fail("app failed: ${it.message}\n${it.logTail}") }
            s.state.value.desktopApp?.id == id && colors(s) > 5
        }
        log("SELKIE_METRIC app_shortcut_first_frame_seconds=${(SystemClock.uptimeMillis() - t0) / 1000.0} stats=${stats(s)}")
        val want = io.github.keplersj.selkie.display.DisplayScale.of(ctx).toDouble()
        val got = stats(s)?.get("app_scale")?.jsonPrimitive?.doubleOrNull
        assertNotNull("app mode: ${stats(s)}", got)
        assertEquals("app mode at the display's scale", want, got!!, 0.001)
        settle(s)
        screenshot("app-shortcut-${dm.widthPixels}x${dm.heightPixels}.png")
        stop(s)
    }
}

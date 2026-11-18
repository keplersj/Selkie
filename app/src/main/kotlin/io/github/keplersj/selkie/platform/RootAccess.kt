package io.github.keplersj.selkie.platform

import android.util.Log
import io.github.keplersj.selkie.engine.RootShell
import java.util.concurrent.TimeUnit

/**
 * Talks to `su` (Magisk, KernelSU, ...). Only used once the user turns root mode on: probing pops
 * the root manager's grant prompt, so nothing here runs on its own.
 */
class RootAccess(val su: String? = RootShell.findSu()) {
    enum class Probe { GRANTED, DENIED, NO_SU }

    /** `su -c 'id -u'` must print 0. Waits for the grant prompt. */
    fun probe(timeoutSec: Long = 60): Pair<Probe, String> {
        val s = su ?: return Probe.NO_SU to "No su binary found. Root mode needs Magisk, KernelSU or APatch."
        return try {
            val p = ProcessBuilder(s, "-c", "id -u").redirectErrorStream(true).start()
            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                p.destroyForcibly()
                return Probe.DENIED to "su did not answer within $timeoutSec s."
            }
            val out = p.inputStream.bufferedReader().readText().trim()
            if (p.exitValue() == 0 && out.lines().lastOrNull()?.trim() == "0") Probe.GRANTED to "Root granted."
            else Probe.DENIED to "Root was not granted ($out)."
        } catch (e: Exception) {
            Probe.DENIED to "Could not run su: ${e.message}"
        }
    }

    /** Turns off the phantom-process killer (what the Developer options toggle does). */
    fun disablePhantomKiller(): Boolean = run(
        "settings put global settings_enable_monitor_phantom_procs false; " +
            "device_config put activity_manager max_phantom_processes 2147483647",
    )

    fun kill(pid: Int) { su?.let { exec(RootShell.kill(it, pid)) } }

    private fun run(script: String): Boolean = su?.let { exec(listOf(it, "-c", script)) } ?: false

    private fun exec(argv: List<String>): Boolean = try {
        val p = ProcessBuilder(argv).redirectErrorStream(true).start()
        val ok = p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0
        if (!ok) Log.w(TAG, "${argv.first()} -c ... failed: ${p.inputStream.bufferedReader().readText().take(300)}")
        ok
    } catch (e: Exception) { Log.w(TAG, "su failed", e); false }

    companion object { const val TAG = "SelkieRoot" }
}

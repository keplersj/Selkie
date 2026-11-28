package io.github.keplersj.selkie

import android.os.Bundle
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import io.github.keplersj.selkie.engine.*
import io.github.keplersj.selkie.platform.PhantomProcessCheck
import io.github.keplersj.selkie.proot.GuestFiles
import io.github.keplersj.selkie.proot.Invocation
import io.github.keplersj.selkie.proot.ProotCommandBuilder
import io.github.keplersj.selkie.service.RootAskHost
import io.github.keplersj.selkie.service.RootChoice
import io.github.keplersj.selkie.service.RootRequest
import io.github.keplersj.selkie.service.SpawnerEvent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Drives the real engine classes (no UI): switch -> status -> commands under proot -> upgrade ->
 * second switch -> rollback. Needs network (quay.io) and the real droidc/proot binaries.
 * Uses CentOS Stream 10 bootc with --insecure-allow-unsigned and --links link2symlink.
 */
class EngineE2ETest {
    private val tag = "SelkieE2E"
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val env = engineEnvFrom(ctx)
    private val client = DroidcClient(env)
    private val ref = "quay.io/centos-bootc/centos-bootc:stream10"

    private fun log(s: String) { Log.i(tag, s); println(s) }

    private suspend fun runSwitch(r: String): Pair<DroidcOutcome, Double> {
        val t0 = System.nanoTime()
        var lastPhase = ""
        val out = client.run(DroidcArgs.switch(r, insecure = true)) { ev ->
            if (ev is DroidcEvent.Progress && ev.phase != lastPhase) { lastPhase = ev.phase; log("phase=${ev.phase}") }
        }
        val secs = (System.nanoTime() - t0) / 1e9
        if (out is DroidcOutcome.Failure) fail("switch $r failed: ${out.error} ${out.stderrTail}")
        return out to secs
    }

    private suspend fun status(): StatusData = (client.status() as DroidcOutcome.Success).status()

    private fun inGuest(inv: Invocation, timeoutSec: Long = 180): Pair<Int, String> {
        val pb = ProcessBuilder(inv.argv).redirectErrorStream(true)
        pb.environment().apply { clear(); putAll(inv.env) }
        val p = pb.start()
        val text = StringBuilder()
        val t = Thread { text.append(p.inputStream.bufferedReader().readText()) }.also { it.start() }
        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) { p.destroyForcibly(); fail("timeout: ${inv.argv.takeLast(3)}") }
        t.join(5000)
        return p.exitValue() to text.toString()
    }

    /** A host process Selkie keeps beside the sessions (keeper, spawner), output in [log]. */
    private fun startHost(inv: Invocation, log: File): Process {
        val pb = ProcessBuilder(inv.argv).redirectErrorStream(true).redirectOutput(log)
        pb.environment().apply { clear(); putAll(inv.env) }
        return pb.start()
    }

    private fun stopHost(p: Process) {
        p.destroy()
        if (!p.waitFor(5, TimeUnit.SECONDS)) p.destroyForcibly()
    }

    /**
     * A second, older digest of centos-bootc stream10, from Quay's tag history (the previous
     * build the `stream10` tag pointed at), referenced by digest.
     */
    private fun findOtherTag(currentDigest: String): String? {
        val url = "https://quay.io/api/v1/repository/centos-bootc/centos-bootc/tag/" +
            "?specificTag=stream10&onlyActiveTags=false&limit=20"
        val c = URL(url).openConnection() as HttpURLConnection
        val body = c.inputStream.bufferedReader().readText()
        val history = SelkieJson.parseToJsonElement(body).jsonObject["tags"]!!.jsonArray.map { it.jsonObject }
        fun digest(o: JsonObject) = o["manifest_digest"]!!.jsonPrimitive.content
        val indexDigest = history.firstOrNull { it["end_ts"] == null || it["end_ts"] is kotlinx.serialization.json.JsonNull }
            ?.let { digest(it) }
        log("stream10 history: ${history.map { digest(it).take(19) }}")
        return history
            .filter { it["is_manifest_list"]?.jsonPrimitive?.booleanOrNull != false }
            .map { digest(it) }
            .firstOrNull { it != indexDigest && it != currentDigest }
            ?.let { "quay.io/centos-bootc/centos-bootc@$it" }
    }

    @Test fun fullLifecycle() = runBlocking {
        // 0. Environment sanity
        val phantom = PhantomProcessCheck(ctx).checkDetailed()
        log("phantom check: $phantom")
        assertEquals("phantom check should report OK after settings put global", PhantomProcessCheck.State.OK, phantom.state)
        assertTrue("libdroidc.so missing", env.droidcBin.isFile)
        assertTrue("libproot.so missing", env.prootBin.isFile)
        assertTrue("libdroidc-guest.so missing", env.droidcGuestBin.isFile)
        env.droidcRoot.deleteRecursively()
        log("droidc: ${client.version()} / proot: ${client.prootVersion()}")

        // 1. switch + metrics
        val (_, secs) = runSwitch(ref)
        val st1 = status()
        val cur1 = st1.current ?: error("no current after switch")
        assertEquals(ref, cur1.imageRef); assertTrue(cur1.insecure)
        assertEquals("link2symlink", cur1.stats?.linkStrategy)
        val unpack = cur1.stats?.unpackSeconds ?: -1.0
        val metric = "SELKIE_METRIC unpack_seconds=$unpack total_switch_seconds=${"%.1f".format(secs)} " +
            "files=${cur1.stats?.files} bytes_on_disk=${cur1.stats?.bytesOnDisk} hardlinks=${cur1.stats?.hardlinks} " +
            "download_bytes=${cur1.stats?.downloadBytes}"
        log(metric)
        File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "selkie_metric.txt").writeText(metric + "\n")
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putString("SELKIE_METRIC_unpack_seconds", unpack.toString())
            putString("SELKIE_METRIC", metric)
        })

        // 2. commands inside proot with the same argv builder and overlays the terminal uses (the SELinux
        // hiding matters: without it systemd --user cannot label, so cannot create, the session bus socket)
        val spec = (client.shellSpec() as DroidcOutcome.Success).shellSpec()
        val fake = GuestFiles.prepare(env, spec)
        val b = ProotCommandBuilder(env, spec, fake,
            overlays = GuestFiles.prepareIdentity(env, spec) + GuestFiles.prepareSelinuxHiding(env))
        log("proot argv: ${b.command(listOf("/bin/true")).argv}")
        log("proot env: ${b.environment()}")
        for (probe in listOf(listOf("/usr/bin/droidc", "--version"), listOf("/bin/sh", "-c", "echo probe-ok"))) {
            val (pc, po) = inGuest(b.command(probe))
            log("probe $probe exit=$pc output=$po")
        }
        val (c1, osr) = inGuest(b.command(listOf("/bin/cat", "/etc/os-release")))
        log("os-release exit=$c1\n$osr")
        assertEquals("os-release output: $osr", 0, c1); assertTrue(osr, osr.contains("CentOS Stream"))
        val (c2, st) = inGuest(b.command(listOf("/usr/bin/droidc", "--json", "status")))
        log("guest droidc status exit=$c2\n$st")
        assertEquals("guest status output: $st", 0, c2); assertTrue(st, st.contains("\"ok\":true"))
        val (c3, ls) = inGuest(b.command(listOf("/bin/sh", "-c", "ls /usr/bin | head")))
        assertEquals("ls output: $ls", 0, c3); assertTrue("ls output empty", ls.isNotBlank())
        val (c4, id) = inGuest(b.command(listOf("/usr/bin/id")))
        log("id: $id")
        assertEquals("id output: $id", 0, c4)
        val (c5, root) = inGuest(b.command(listOf("/usr/bin/id", "-u"), rootMode = true))
        assertEquals("root id output: $root", 0, c5); assertEquals("0", root.trim())
        // Network interfaces (droidc's ifshim): supervise preloads it, so getifaddrs/if_nameindex
        // answer even where Android denies netlink to apps.
        val ifScript = "echo \"preload=\$LD_PRELOAD\"; " +
            "if command -v python3 >/dev/null; then python3 -c 'import socket; print(\"names=\" + \",\".join(n for _, n in socket.if_nameindex()))'; fi"
        val (c6, ifs) = inGuest(b.command(listOf("/usr/bin/droidc", "supervise", "--", "/bin/sh", "-c", ifScript)))
        log("interfaces exit=$c6\n$ifs")
        assertEquals("interfaces output: $ifs", 0, c6)
        assertTrue("ifshim not preloaded: $ifs", ifs.contains("/usr/lib/droidc/ifshim.so"))
        // USB devices (droidc's usbshim, UsbBridge): preloaded the same way.
        assertTrue("usbshim not preloaded: $ifs", ifs.contains("/usr/lib/droidc/usbshim.so"))
        if (ifs.contains("names=")) assertTrue("no loopback listed: $ifs", Regex("names=.*\\blo\\b").containsMatchIn(ifs))

        // sudo (droidc's spawner): the shim asks Selkie's host spawn-server, which asks Selkie (here, this
        // test) over its stdin and stdout and, once allowed, runs the command under proot -0.
        assertTrue("shell-spec lists no sudo/su/pkexec shims", spec.shims.isNotEmpty())
        val asked = java.util.concurrent.LinkedBlockingQueue<RootRequest>()
        val answers = java.util.concurrent.LinkedBlockingQueue<RootChoice>()
        var host: RootAskHost? = null
        host = RootAskHost.start(b.spawnServer(), File(env.cacheDir, "e2e-spawner.log"), env.container, b.rootfs,
            onEvent = { e ->
                if (e is SpawnerEvent.Asked) {
                    asked.put(e.request)
                    host?.answer(e.request.id, answers.poll(30, TimeUnit.SECONDS) ?: RootChoice.Deny)
                }
            })
        val spawner = host.process
        try {
            // The spawner binds its socket a moment after it starts, so give it a few seconds before
            // the first sudo rather than racing it.
            val sudo = b.command(listOf("/usr/bin/sudo", "/usr/bin/id", "-u"))
            answers.put(RootChoice.Once)
            var (c7, sudoId) = inGuest(sudo)
            repeat(20) {
                if (c7 == 0 || !sudoId.contains("Connection refused") || !spawner.isAlive) return@repeat
                Thread.sleep(500)
                inGuest(sudo).let { (c, o) -> c7 = c; sudoId = o }
            }
            log("sudo id -u exit=$c7 output=$sudoId")
            assertEquals("sudo output: $sudoId", 0, c7); assertEquals("0", sudoId.trim().lines().last())
            val first = asked.poll(5, TimeUnit.SECONDS)
            assertNotNull("the spawner didn't ask before running sudo", first)
            assertEquals(listOf("/usr/bin/id", "-u"), first!!.argv)
            assertEquals("sudo", first.via)
            assertEquals("/usr/bin/id", first.program)

            // Refused: nothing runs, and sudo says why.
            answers.put(RootChoice.Deny)
            val (c8, refused) = inGuest(b.command(listOf("/usr/bin/sudo", "/usr/bin/touch", "/e2e-refused")))
            log("refused sudo exit=$c8 output=$refused")
            assertEquals("refused sudo output: $refused", 1, c8)
            assertTrue("refused sudo output: $refused", refused.contains("did not allow"))
            assertFalse(File(b.rootfs, "e2e-refused").exists())
        } finally { stopHost(spawner) }

        // One session bus per container (droidc user-services): Selkie's keeper, which two sessions join.
        val keeper = startHost(b.userServices(), File(env.cacheDir, "e2e-user-services.log"))
        try {
            val joinBus = b.command(listOf("/usr/bin/droidc", "supervise", "--", "/bin/sh", "-c", "echo bus=\$DBUS_SESSION_BUS_ADDRESS"))
                .let { it.copy(env = it.env + ("DROIDC_USER_SERVICES_WAIT" to "60")) }
            val (c8, bus1) = inGuest(joinBus)
            val (c9, bus2) = inGuest(joinBus)
            log("user services: exit=$c8/$c9\n$bus1\n$bus2")
            assertEquals("first session: $bus1", 0, c8); assertEquals("second session: $bus2", 0, c9)
            val addr = { out: String -> out.lines().firstOrNull { it.startsWith("bus=") }?.removePrefix("bus=") }
            assertTrue("no session bus: $bus1", !addr(bus1).isNullOrBlank())
            assertEquals("sessions got different buses", addr(bus1), addr(bus2))
            assertTrue("the keeper ended with a session", keeper.isAlive)
        } finally { stopHost(keeper) }

        // 3. upgrade (no-op is fine)
        val up = client.run(listOf("upgrade"))
        assertTrue("upgrade failed: $up", up is DroidcOutcome.Success)
        val afterUp = status().current!!.manifestDigest

        // 4. second digest, then rollback
        val other = findOtherTag(afterUp)
        assertNotNull("could not find a second centos-bootc stream10 tag with a different digest", other)
        log("second ref: $other")
        runSwitch(other!!)
        val st2 = status()
        assertEquals(other, st2.current!!.imageRef)
        assertNotEquals(afterUp, st2.current!!.manifestDigest)
        assertEquals(afterUp, st2.previous?.manifestDigest)
        val rb = client.run(listOf("rollback"))
        assertTrue("rollback failed: $rb", rb is DroidcOutcome.Success)
        val st3 = status()
        assertEquals(afterUp, st3.current!!.manifestDigest)
        assertEquals(st2.current!!.manifestDigest, st3.previous?.manifestDigest)
        log("lifecycle OK")
    }
}

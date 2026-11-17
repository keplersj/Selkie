package io.github.keplersj.selkie

import android.os.Bundle
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import io.github.keplersj.selkie.engine.*
import io.github.keplersj.selkie.platform.PhantomProcessCheck
import io.github.keplersj.selkie.proot.GuestFiles
import io.github.keplersj.selkie.proot.Invocation
import io.github.keplersj.selkie.proot.ProotCommandBuilder
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

    /** Two different digests of centos-bootc stream10: `stream10` and the newest other tag with a different digest. */
    private fun findOtherTag(): String? {
        val url = "https://quay.io/api/v1/repository/centos-bootc/centos-bootc/tag/?limit=100&onlyActiveTags=true"
        val c = URL(url).openConnection() as HttpURLConnection
        val body = c.inputStream.bufferedReader().readText()
        val tags = SelkieJson.parseToJsonElement(body).jsonObject["tags"]!!.jsonArray.map { it.jsonObject }
        fun name(o: JsonObject) = o["name"]!!.jsonPrimitive.content
        fun digest(o: JsonObject) = o["manifest_digest"]!!.jsonPrimitive.content
        val base = tags.firstOrNull { name(it) == "stream10" } ?: return null
        val cands = tags.filter { name(it).startsWith("stream10") && digest(it) != digest(base) &&
            it["is_manifest_list"]?.jsonPrimitive?.booleanOrNull != false }
        log("tags seen: ${tags.map { name(it) }}")
        return cands.minByOrNull { it["start_ts"]?.jsonPrimitive?.longOrNull ?: Long.MAX_VALUE }
            ?.let { "quay.io/centos-bootc/centos-bootc:${name(it)}" }
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

        // 2. commands inside proot with the same argv builder the terminal uses
        val spec = (client.shellSpec() as DroidcOutcome.Success).shellSpec()
        val fake = GuestFiles.prepare(env, spec)
        val b = ProotCommandBuilder(env, spec, fake)
        val (c1, osr) = inGuest(b.command(listOf("/bin/cat", "/etc/os-release")))
        log("os-release exit=$c1\n$osr")
        assertEquals(0, c1); assertTrue(osr, osr.contains("CentOS Stream"))
        val (c2, st) = inGuest(b.command(listOf("/usr/bin/droidc", "--json", "status")))
        log("guest droidc status exit=$c2\n$st")
        assertEquals(0, c2); assertTrue(st, st.contains("\"ok\":true"))
        val (c3, ls) = inGuest(b.command(listOf("/bin/sh", "-c", "ls /usr/bin | head")))
        assertEquals(0, c3); assertTrue("ls output empty", ls.isNotBlank())
        val (c4, id) = inGuest(b.command(listOf("/usr/bin/id")))
        log("id: $id")
        assertEquals(0, c4)
        val (c5, root) = inGuest(b.command(listOf("/usr/bin/id", "-u"), rootMode = true))
        assertEquals(0, c5); assertEquals("0", root.trim())

        // 3. upgrade (no-op is fine)
        val up = client.run(listOf("upgrade"))
        assertTrue("upgrade failed: $up", up is DroidcOutcome.Success)
        val afterUp = status().current!!.manifestDigest

        // 4. second digest, then rollback
        val other = findOtherTag()
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

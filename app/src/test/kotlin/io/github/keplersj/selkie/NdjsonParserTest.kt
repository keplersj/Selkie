package io.github.keplersj.selkie

import io.github.keplersj.selkie.engine.*
import org.junit.Assert.*
import org.junit.Test

class NdjsonParserTest {
    @Test fun progress() {
        val e = NdjsonParser.parseLine("""{"event":"progress","phase":"download","current":123,"total":456,"unit":"bytes","message":"layer 3/68"}""")
        assertEquals(DroidcEvent.Progress("download", 123, 456, "bytes", "layer 3/68"), e)
    }
    @Test fun progressWithoutTotal() {
        val e = NdjsonParser.parseLine("""{"event":"progress","phase":"resolve","current":0,"total":null}""") as DroidcEvent.Progress
        assertNull(e.total); assertEquals("resolve", e.phase)
    }
    @Test fun log() {
        assertEquals(DroidcEvent.Log("warn", "x"), NdjsonParser.parseLine("""{"event":"log","level":"warn","message":"x"}"""))
    }
    @Test fun resultError() {
        val e = NdjsonParser.parseLine("""{"event":"result","ok":false,"error":{"kind":"signature","message":"bad"}}""") as DroidcEvent.Result
        assertFalse(e.ok); assertEquals(DroidcError("signature", "bad"), e.error); assertNull(e.data)
    }
    @Test fun resultOkStatus() {
        val line = """{"event":"result","ok":true,"data":{"root":"/r","platform":{"os":"linux"},"current":{"id":"d2a12ef344f80bb0","image_ref":"ghcr.io/projectbluefin/bluefin-lts:stable","manifest_digest":"sha256:abcdef0123456789aa","platform":"linux/arm64","session_label":null,"signature":{"verified":true,"mode":"keyless","subject":"s","issuer":"i"},"insecure":false,"future_field":1,"stats":{"download_bytes":1,"files":2,"bytes_on_disk":3,"hardlinks":4,"link_strategy":"link2symlink","unpack_seconds":1.5}},"previous":null}}"""
        val r = NdjsonParser.parseLine(line) as DroidcEvent.Result
        val st = DroidcOutcome.Success(r.data).status()
        assertEquals("abcdef012345", st.current!!.shortDigest)
        assertTrue(st.current!!.signature!!.verified)
        assertEquals(1.5, st.current!!.stats!!.unpackSeconds, 0.0)
        assertNull(st.previous)
    }
    @Test fun shellSpec() {
        val r = NdjsonParser.parseLine("""{"event":"result","ok":true,"data":{"rootfs":"/r/current/rootfs","binds":[{"host":"/r/var","guest":"/var"}],"env":{"A":"b"},"cwd":"/root","session_label":"gnome"}}""") as DroidcEvent.Result
        val s = DroidcOutcome.Success(r.data).shellSpec()
        assertEquals("/var", s.binds.single().guest); assertEquals("gnome", s.sessionLabel)
    }
    @Test fun blankAndGarbageAndUnknown() {
        assertNull(NdjsonParser.parseLine("  "))
        assertTrue(NdjsonParser.parseLine("not json") is DroidcEvent.Log)
        assertTrue(NdjsonParser.parseLine("""{"event":"zzz"}""") is DroidcEvent.Unknown)
    }
    @Test fun globalArgs() {
        val env = TestEnv.env(dns = listOf("1.1.1.1", "9.9.9.9"))
        assertEquals(listOf("/lib/libdroidc.so", "--root", "/files/droidc", "--json", "--dns", "1.1.1.1,9.9.9.9",
            "switch", "r:t", "--insecure-allow-unsigned", "--links", "link2symlink"),
            DroidcArgs.global(env, true) + DroidcArgs.switch("r:t", true))
    }
}

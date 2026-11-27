package io.github.keplersj.selkie

import io.github.keplersj.selkie.service.RootChoice
import io.github.keplersj.selkie.service.RootRequest
import io.github.keplersj.selkie.service.RootRequests
import io.github.keplersj.selkie.service.ServiceState
import io.github.keplersj.selkie.service.SpawnerEvent
import io.github.keplersj.selkie.service.watchRootRequests
import io.github.keplersj.selkie.ui.activityItems
import io.github.keplersj.selkie.watch.WatchProtocol
import io.github.keplersj.selkie.watch.WatchRootRequest
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class RootRequestsTest {
    @Test fun readsTheSpawnersQuestion() {
        val line = """{"ask":{"id":3,"via":"sudo","argv":["dnf","install","-y","htop"],"cwd":"/root","from":"bash"}}"""
        assertEquals(
            SpawnerEvent.Asked(RootRequest(3, "dev", "sudo", listOf("dnf", "install", "-y", "htop"), "/root", "bash")),
            RootRequests.parse(line, "dev"),
        )
    }

    @Test fun readsHowItEnded() {
        assertEquals(SpawnerEvent.Settled(3, true, "host"),
            RootRequests.parse("""{"settled":{"id":3,"allowed":true,"by":"host"}}""", "dev"))
        assertEquals(SpawnerEvent.Settled(4, false, "caller"),
            RootRequests.parse("""{"settled":{"id":4,"allowed":false,"by":"caller"}}""", "dev"))
    }

    @Test fun skipsWhatItCantRead() {
        assertNull(RootRequests.parse("spawn-server: listening", "dev"))
        assertNull(RootRequests.parse("""{"ask":{"id":1,"argv":[]}}""", "dev"))
        assertNull(RootRequests.parse("""{"something":"new"}""", "dev"))
        // An empty caller name is no hint at all.
        val e = RootRequests.parse("""{"ask":{"id":1,"via":"su","argv":["sh"],"cwd":"/","from":""}}""", "dev")
        assertNull((e as SpawnerEvent.Asked).request.from)
    }

    @Test fun answersAsTheSpawnerReadsThem() {
        assertEquals("""{"answer":{"id":7,"allow":true,"remember":false}}""", RootRequests.answer(7, RootChoice.Once))
        assertEquals("""{"answer":{"id":7,"allow":true,"remember":true}}""", RootRequests.answer(7, RootChoice.WhileRunning))
        assertEquals("""{"answer":{"id":7,"allow":false,"remember":false}}""", RootRequests.answer(7, RootChoice.Deny))
    }

    @Test fun findsTheProgramOnTheRootPath() {
        val rootfs = Files.createTempDirectory("rootfs").toFile()
        File(rootfs, "usr/bin").mkdirs(); File(rootfs, "usr/bin/id").writeText("")
        File(rootfs, "usr/sbin").mkdirs(); File(rootfs, "usr/sbin/dnf").writeText("")
        File(rootfs, "usr/bin/dnf").writeText("")
        // Links point at guest paths, so they count without being followed.
        Files.createSymbolicLink(File(rootfs, "usr/bin/vi").toPath(), File("/usr/bin/vim").toPath())
        assertEquals("/usr/bin/id", RootRequests.resolveProgram(rootfs.path, "id"))
        assertEquals("/usr/sbin/dnf", RootRequests.resolveProgram(rootfs.path, "dnf"))
        assertEquals("/usr/bin/vi", RootRequests.resolveProgram(rootfs.path, "vi"))
        assertEquals("/opt/x", RootRequests.resolveProgram(rootfs.path, "/opt/x"))
        assertNull(RootRequests.resolveProgram(rootfs.path, "./x"))
        assertNull(RootRequests.resolveProgram(rootfs.path, "nope"))
        val e = RootRequests.parse("""{"ask":{"id":1,"via":"sudo","argv":["id","-u"],"cwd":"/"}}""", "dev", rootfs.path)
        assertEquals("/usr/bin/id", (e as SpawnerEvent.Asked).request.program)
        rootfs.deleteRecursively()
    }

    @Test fun showsTheCommandQuotedOnlyWhereNeeded() {
        val r = RootRequest(1, "dev", "sudo", listOf("sh", "-c", "echo hi > /etc/motd", "it's"), "/")
        assertEquals("""sh -c 'echo hi > /etc/motd' 'it'\''s'""", r.commandLine)
    }

    @Test fun activityListsWhatWaits() {
        val r = RootRequest(1, "dev", "sudo", listOf("id"), "/")
        val items = activityItems(ServiceState(rootRequests = listOf(r)))
        assertFalse(items.empty)
        assertEquals(listOf(r), items.rootRequests)
    }

    @Test fun theWatchGetsTheCommandOnOneLine() {
        val r = RootRequest(5, "dev", "sudo", listOf("printf", "a\tb\nc"), "/")
        val watch = watchRootRequests(ServiceState(rootRequests = listOf(r)))
        val decoded = WatchProtocol.decodeRoot(WatchProtocol.encodeRoot(watch))
        assertEquals(listOf(WatchRootRequest("dev", 5, "printf 'a b c'")), decoded)
        assertEquals("dev" to 5L, WatchProtocol.readRefusal(WatchProtocol.refusal("dev", 5)))
        assertNull(WatchProtocol.readRefusal("dev".toByteArray()))
        assertEquals(emptyList<WatchRootRequest>(), WatchProtocol.decodeRoot("junk"))
    }
}

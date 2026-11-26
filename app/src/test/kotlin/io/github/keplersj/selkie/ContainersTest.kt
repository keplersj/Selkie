package io.github.keplersj.selkie

import io.github.keplersj.selkie.engine.ContainerInfo
import io.github.keplersj.selkie.engine.DroidcArgs
import io.github.keplersj.selkie.engine.DroidcOutcome
import io.github.keplersj.selkie.engine.EngineEnv
import io.github.keplersj.selkie.engine.NdjsonParser
import io.github.keplersj.selkie.engine.DroidcEvent
import io.github.keplersj.selkie.engine.ShellSpec
import io.github.keplersj.selkie.engine.containers
import io.github.keplersj.selkie.proot.ProotCommandBuilder
import io.github.keplersj.selkie.service.mergeContainers
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ContainersTest {
    private val dev = TestEnv.env().forContainer("dev")

    @Test fun defaultContainerKeepsItsPathsAndPassesNoFlag() {
        val env = TestEnv.env()
        assertTrue(env.isDefaultContainer)
        assertEquals(File("/files/guest/run"), env.guestRun)
        assertEquals(File("/files/guest/generated"), env.guestGenerated)
        assertEquals(File("/files/guest/run/user/10234/wayland-0"), env.waylandSocket)
        assertEquals(File("/cache/droidc.log"), env.droidcLog)
        assertEquals(File("/cache/desktop.log"), env.desktopLog)
        // A droidc from before containers has no --container.
        assertEquals(listOf("/lib/libdroidc.so", "--root", "/files/droidc", "--json"), DroidcArgs.global(env, true))
    }

    @Test fun namedContainerHasItsOwnGuestDirsLogsAndFlag() {
        assertFalse(dev.isDefaultContainer)
        assertEquals(File("/files/c/dev/tmp"), dev.guestTmp)
        assertEquals(File("/files/c/dev/run"), dev.guestRun)
        assertEquals(File("/files/c/dev/shm"), dev.guestShm)
        assertEquals(File("/files/c/dev/cgroup"), dev.guestCgroup)
        assertEquals(File("/files/c/dev/run/user/10234/wayland-0"), dev.waylandSocket)
        assertEquals(File("/cache/droidc-dev.log"), dev.droidcLog)
        assertEquals(File("/cache/desktop-dev.log"), dev.desktopLog)
        // Shared: one droidc root (images are shared) and one proot tmp.
        assertEquals(TestEnv.env().droidcRoot, dev.droidcRoot)
        assertEquals(TestEnv.env().prootTmp, dev.prootTmp)
        assertEquals(listOf("/lib/libdroidc.so", "--root", "/files/droidc", "--container", "dev", "--json", "status"),
            DroidcArgs.global(dev, true) + "status")
    }

    @Test fun prootBindsTheContainersGuestDirs() {
        val spec = ShellSpec("/files/droidc/containers/dev/current/rootfs", cwd = "/root")
        val args = ProotCommandBuilder(dev, spec).prootArgs()
        assertTrue(args.containsAll(listOf("/files/c/dev/tmp:/tmp", "/files/c/dev/run:/run",
            "/files/c/dev/shm:/dev/shm", "/files/c/dev/cgroup:/sys/fs/cgroup",
            "/files/c/dev/generated/resolv.conf:/etc/resolv.conf")))
        assertFalse(args.any { it.startsWith("/files/guest/") })
    }

    @Test fun containerNames() {
        for (ok in listOf("default", "dev", "a", "0x", "fedora-41", "a.b_c", "a".repeat(EngineEnv.MAX_CONTAINER_NAME))) {
            assertTrue(ok, EngineEnv.isValidContainerName(ok))
        }
        for (bad in listOf("", "Dev", "-x", ".x", "_x", "a/b", "..", "a b", "a".repeat(EngineEnv.MAX_CONTAINER_NAME + 1))) {
            assertFalse(bad, EngineEnv.isValidContainerName(bad))
        }
        assertThrows(IllegalArgumentException::class.java) { TestEnv.env().forContainer("../x") }
    }

    @Test fun longestGuestSocketFitsAUnixSocketPath() {
        // Canonical app files dir on a secondary user, the longest name Selkie allows, PipeWire's longest socket.
        val env = EngineEnv(File("/lib"), File("/data/user/10/io.github.keplersj.selkie/files"), File("/cache"),
            1010234, 1010234, container = "a".repeat(EngineEnv.MAX_CONTAINER_NAME))
        val sock = File(env.guestRuntimeDir, "pipewire-0-manager").path
        assertTrue("${sock.length}: $sock", sock.toByteArray().size < 108)
    }

    @Test fun containerArgs() {
        assertEquals(listOf("container", "list"), DroidcArgs.containerList)
        assertEquals(listOf("container", "rm", "dev"), DroidcArgs.containerRm("dev"))
    }

    @Test fun parsesContainerList() {
        val line = """{"event":"result","ok":true,"command":"container","data":{"containers":[""" +
            """{"name":"default","current":{"id":"d1","image_ref":"ghcr.io/a:1"},"previous":null},""" +
            """{"name":"dev","current":{"id":"d2","image_ref":null},"previous":null,"later":1}]}}"""
        val r = NdjsonParser.parseLine(line) as DroidcEvent.Result
        val list = DroidcOutcome.Success(r.data).containers()
        assertEquals(listOf("default", "dev"), list.map { it.name })
        assertEquals("ghcr.io/a:1", list[0].current?.imageRef)
        assertNull(list[0].previous)
        assertEquals("d2", list[1].current?.id)
        assertNull(list[1].current?.imageRef)
    }

    @Test fun mergeKeepsDefaultFirstAndTheSelectedOne() {
        assertEquals(listOf("default"), mergeContainers(emptyList(), "default").map { it.name })
        assertEquals(listOf("default", "new"), mergeContainers(emptyList(), "new").map { it.name })
        val listed = listOf(ContainerInfo("zeta"), ContainerInfo("dev"), ContainerInfo("default"))
        assertEquals(listOf("default", "dev", "zeta"), mergeContainers(listed, "dev").map { it.name })
    }
}

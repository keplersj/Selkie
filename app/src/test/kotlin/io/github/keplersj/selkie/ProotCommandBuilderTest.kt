package io.github.keplersj.selkie

import io.github.keplersj.selkie.engine.Bind
import io.github.keplersj.selkie.engine.ShellSpec
import io.github.keplersj.selkie.platform.PhantomProcessCheck
import io.github.keplersj.selkie.platform.PhantomProcessCheck.State
import io.github.keplersj.selkie.proot.GuestFiles
import io.github.keplersj.selkie.proot.ProotCommandBuilder
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ProotCommandBuilderTest {
    private val spec = ShellSpec("/files/droidc/current/rootfs",
        listOf(Bind("/files/droidc/var", "/var"), Bind("/files/droidc", "/run/droidc/root")),
        mapOf("DROIDC_ROOT" to "/run/droidc/root", "HOME" to "/root", "TERM" to "xterm-256color"), "/root")

    @Test fun normalSessionArgv() {
        val b = ProotCommandBuilder(TestEnv.env(), spec, mapOf("/proc/stat" to File("/files/guest/generated/proc_stat")))
        val inv = b.interactiveShell()
        assertEquals(listOf(
            "/lib/libproot.so", "--kill-on-exit", "--link2symlink", "-i", "10234:10234",
            "-r", "/files/droidc/current/rootfs", "-w", "/root",
            "-b", "/dev:/dev", "-b", "/proc:/proc", "-b", "/sys:/sys",
            "-b", "/lib/libdroidc-guest.so:/usr/bin/droidc",
            "-b", "/lib/libcgshim.so:/usr/lib/droidc/cgshim.so",
            "-b", "/files/guest/tmp:/tmp", "-b", "/files/guest/run:/run", "-b", "/files/guest/shm:/dev/shm",
            "-b", "/files/guest/generated/resolv.conf:/etc/resolv.conf",
            "-b", "/files/guest/generated/proc_stat:/proc/stat",
            "-b", "/files/droidc/var:/var", "-b", "/files/droidc:/run/droidc/root",
            "/usr/bin/droidc", "supervise", "--systemd", "auto", "--", "/bin/bash", "-l"), inv.argv)
        assertEquals("/lib/libproot-loader.so", inv.env["PROOT_LOADER"])
        assertEquals("/cache/proot-tmp", inv.env["PROOT_TMP_DIR"])
        assertEquals("/run/droidc/root", inv.env["DROIDC_ROOT"])
    }

    @Test fun rootModeUsesDashZero() {
        val a = ProotCommandBuilder(TestEnv.env(), spec).prootArgs(rootMode = true)
        assertTrue("-0" in a); assertFalse("-i" in a); assertTrue("--link2symlink" in a)
    }

    @Test fun commandAppended() {
        val inv = ProotCommandBuilder(TestEnv.env(), spec).command(listOf("/bin/sh", "-c", "id"))
        assertEquals(listOf("/bin/sh", "-c", "id"), inv.argv.takeLast(3))
    }

    @Test fun guestFilesFakeOnlyUnreadable() {
        val dir = Files.createTempDirectory("selkie").toFile()
        val env = TestEnv.env().copy(filesDir = File(dir, "files"), cacheDir = File(dir, "cache"), dns = listOf("8.8.4.4"))
        val fake = GuestFiles.prepare(env, spec, readable = { it != "/proc/stat" && it != "/proc/loadavg" }, uptimeSeconds = 100.0, cpus = 2)
        assertEquals(setOf("/proc/stat", "/proc/loadavg"), fake.keys)
        assertTrue(fake.getValue("/proc/stat").readText().startsWith("cpu "))
        assertEquals("nameserver 8.8.4.4\n", File(env.guestGenerated, "resolv.conf").readText())
        assertTrue(File(env.guestRun, "droidc/root").isDirectory)
        dir.deleteRecursively()
    }

    @Test fun identityAddsAppUserOnce() {
        val base = "root:x:0:0:Super User:/root:/bin/bash\n"
        val out = GuestFiles.passwdWithUser(base, 10234, 10234, "/root")
        assertEquals(base + "selkie:x:10234:10234:Selkie:/root:/bin/bash\n", out)
        assertEquals(out, GuestFiles.passwdWithUser(out, 10234, 10234, "/root"))
        assertEquals("root:x:0:\nselkie:x:10234:\n", GuestFiles.groupWithGid("root:x:0:\n", 10234))
        assertEquals("selkie:x:10234:\n", GuestFiles.groupWithGid("", 10234))
    }

    @Test fun selinuxfsIsHiddenFromProcFilesystems() {
        val host = "nodev\tsysfs\nnodev\tselinuxfs\nnodev\tproc\n\text4\n"
        assertEquals("nodev\tsysfs\nnodev\tproc\n\text4\n", GuestFiles.withoutSelinuxfs(host))
    }

    @Test fun selinuxHidingFallsBackWhenHostProcFilesystemsUnreadable() {
        val dir = Files.createTempDirectory("selkie").toFile()
        val env = TestEnv.env().copy(filesDir = File(dir, "files"), cacheDir = File(dir, "cache"))
        val m = GuestFiles.prepareSelinuxHiding(env, read = { throw java.io.IOException("EACCES") })
        val text = m.getValue("/proc/filesystems").readText()
        assertTrue(text.contains("proc")); assertFalse(text.contains("selinuxfs"))
        assertTrue(m.getValue("/sys/fs/selinux").isDirectory)
        dir.deleteRecursively()
    }

    @Test fun identityFilesAreBound() {
        val id = mapOf("/etc/passwd" to File("/files/guest/generated/passwd"), "/etc/group" to File("/files/guest/generated/group"))
        val a = ProotCommandBuilder(TestEnv.env(), spec, overlays = id).prootArgs()
        assertTrue("/files/guest/generated/passwd:/etc/passwd" in a)
        assertTrue("/files/guest/generated/group:/etc/group" in a)
    }

    @Test fun phantomEvaluate() {
        val skip = PhantomProcessCheck.PropRead.Skipped
        fun v(x: String) = PhantomProcessCheck.PropRead.Value(x)
        assertEquals(State.OK, PhantomProcessCheck.evaluate("false", skip).state)
        assertEquals(State.RESTRICTED, PhantomProcessCheck.evaluate("true", v("false")).state) // setting wins
        assertEquals(State.OK, PhantomProcessCheck.evaluate(null, v("false")).state)
        assertEquals(State.RESTRICTED, PhantomProcessCheck.evaluate(null, v("")).state)
        assertEquals(State.RESTRICTED, PhantomProcessCheck.evaluate(null, v("true")).state)
        assertEquals(State.UNVERIFIED, PhantomProcessCheck.evaluate(null, PhantomProcessCheck.PropRead.Unreadable).state)
    }
}

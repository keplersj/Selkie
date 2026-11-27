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
            "-b", "/lib/libifshim.so:/usr/lib/droidc/ifshim.so",
            "-b", "/files/guest/cgroup:/sys/fs/cgroup",
            "-b", "/files/guest/tmp:/tmp", "-b", "/files/guest/run:/run", "-b", "/files/guest/shm:/dev/shm",
            "-b", "/files/guest/generated/resolv.conf:/etc/resolv.conf",
            "-b", "/files/guest/generated/proc_stat:/proc/stat",
            "-b", "/files/droidc/var:/var", "-b", "/files/droidc:/run/droidc/root",
            "/usr/bin/droidc", "supervise", "--", "/bin/bash", "-l"), inv.argv)
        assertEquals("/lib/libproot-loader.so", inv.env["PROOT_LOADER"])
        assertEquals("/cache/proot-tmp", inv.env["PROOT_TMP_DIR"])
        assertEquals("/run/droidc/root", inv.env["DROIDC_ROOT"])
    }

    @Test fun anAppRunsOnItsOwnInTheCompositor() {
        val inv = ProotCommandBuilder(TestEnv.env(), spec).app(listOf("gnome-text-editor", "--new-window"))
        assertEquals(listOf("/usr/bin/droidc", "supervise", "--systemd", "auto", "--", "gnome-text-editor", "--new-window"),
            inv.argv.takeLast(7))
        assertEquals("wayland-0", inv.env["WAYLAND_DISPLAY"])
        assertEquals("wayland", inv.env["GDK_BACKEND"])
        assertEquals("10", inv.env["DROIDC_USER_SERVICES_WAIT"])
        assertEquals("1", inv.env["LIBGL_ALWAYS_SOFTWARE"])
    }

    @Test fun sessionsJoinTheContainersUserServices() {
        val b = ProotCommandBuilder(TestEnv.env(), spec)
        assertEquals("10", b.interactiveShell().env["DROIDC_USER_SERVICES_WAIT"])
        assertEquals("10", b.graphicalSession(800, 600).env["DROIDC_USER_SERVICES_WAIT"])
        val keeper = b.userServices()
        assertEquals(listOf("/usr/bin/droidc", "user-services", "--systemd", "auto", "--stay"), keeper.argv.takeLast(5))
        assertTrue("--kill-on-exit" in keeper.argv)
        assertNull(keeper.env["DROIDC_USER_SERVICES_WAIT"])
        // The user manager starts the desktop's services, so it gets the session's settings.
        assertEquals("1", keeper.env["LIBGL_ALWAYS_SOFTWARE"])
        assertEquals("pipewire-0-manager", keeper.env["PIPEWIRE_REMOTE"])
    }

    @Test fun shimsAreTheGuestDroidcAndAskTheSpawner() {
        val env = TestEnv.env()
        val shims = listOf("/usr/bin/sudo", "/usr/bin/su", "/usr/bin/pkexec")
        val b = ProotCommandBuilder(env, spec.copy(shims = shims))
        val inv = b.interactiveShell()
        for (s in shims) assertTrue("$s not bound", inv.argv.windowed(2).contains(listOf("-b", "/lib/libdroidc-guest.so:$s")))
        assertEquals(env.spawnerSocket, inv.env["DROIDC_SPAWNER"])
        assertTrue(b.hasShims)

        val server = b.spawnServer()
        val launcher = server.argv.dropWhile { it != "--" }.drop(1)
        assertEquals(listOf("/lib/libdroidc.so", "spawn-server", "--socket", env.spawnerSocket, "--uid", "10234", "--"),
            server.argv.take(7))
        assertEquals(b.prootArgs(rootMode = true), launcher)
        assertEquals("/lib/libproot-loader.so", server.env["PROOT_LOADER"])
        assertNull("the server's own environment is the host's, not the guest's", server.env["DROIDC_ROOT"])
    }

    @Test fun noShimsNoSpawner() {
        val b = ProotCommandBuilder(TestEnv.env(), spec)
        assertFalse(b.hasShims)
        assertNull(b.interactiveShell().env["DROIDC_SPAWNER"])
        assertFalse(b.interactiveShell().argv.any { it.endsWith(":/usr/bin/sudo") })
    }

    @Test fun spawnerSocketsDifferByContainer() {
        assertNotEquals(TestEnv.env().spawnerSocket, TestEnv.env().forContainer("work").spawnerSocket)
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

    @Test fun deviceEnvFillsGapsButImageWins() {
        val image = spec.copy(env = spec.env + ("LANG" to "de_DE.UTF-8"))
        val e = ProotCommandBuilder(TestEnv.env(), image,
            guestEnv = mapOf("TZ" to "America/Denver", "LANG" to "en_US.UTF-8")).environment()
        assertEquals("America/Denver", e["TZ"])
        assertEquals("de_DE.UTF-8", e["LANG"])
    }

    @Test fun guestLangNeedsTheLocaleInTheImage() {
        val root = Files.createTempDirectory("rootfs").toFile()
        assertEquals("C.UTF-8", GuestFiles.guestLang(root, "en_US"))
        File(root, "usr/lib/locale/en_US.utf8").mkdirs()
        assertEquals("en_US.UTF-8", GuestFiles.guestLang(root, "en_US"))
        assertEquals("C.UTF-8", GuestFiles.guestLang(root, "fr_FR"))
        File(root, "usr/lib/locale/locale-archive").writeText("")
        assertEquals("fr_FR.UTF-8", GuestFiles.guestLang(root, "fr_FR"))
        assertEquals("C.UTF-8", GuestFiles.guestLang(root, ""))
        assertEquals("C.UTF-8", GuestFiles.guestLang(root, "en_US.UTF-8;rm"))
        root.deleteRecursively()
    }

    @Test fun zoneIdsStayInsideZoneinfo() {
        assertTrue(GuestFiles.isZoneId("America/Denver"))
        assertTrue(GuestFiles.isZoneId("Etc/GMT+7"))
        assertTrue(GuestFiles.isZoneId("UTC"))
        assertFalse(GuestFiles.isZoneId("../../etc/shadow"))
        assertFalse(GuestFiles.isZoneId("/etc/passwd"))
        assertFalse(GuestFiles.isZoneId(""))
    }

    @Test fun identityAddsHostnameAndLocaltime() {
        val dir = Files.createTempDirectory("selkie").toFile()
        val rootfs = File(dir, "rootfs").apply { File(this, "etc").mkdirs() }
        File(rootfs, "usr/share/zoneinfo/America").mkdirs()
        File(rootfs, "usr/share/zoneinfo/America/Denver").writeText("TZif-denver")
        val s = spec.copy(rootfs = rootfs.path)
        val env = TestEnv.env().copy(filesDir = File(dir, "files"), cacheDir = File(dir, "cache"),
            timeZone = "America/Denver")
        val m = GuestFiles.prepareIdentity(env, s)
        assertEquals("selkie\n", m.getValue("/etc/hostname").readText())
        assertEquals("TZif-denver", m.getValue("/etc/localtime").readText())
        val work = GuestFiles.prepareIdentity(env.forContainer("work").copy(timeZone = "Mars/Olympus"), s)
        assertEquals("work\n", work.getValue("/etc/hostname").readText())
        assertFalse("no tzdata for it" , "/etc/localtime" in work)
        assertEquals(mapOf("TZ" to "Mars/Olympus", "LANG" to "C.UTF-8"),
            GuestFiles.guestEnv(env.forContainer("work").copy(timeZone = "Mars/Olympus"), s))
        dir.deleteRecursively()
    }

    @Test fun tmpAndShmGetTheStickyBit() {
        val dir = Files.createTempDirectory("selkie").toFile()
        val env = TestEnv.env().copy(filesDir = File(dir, "files"), cacheDir = File(dir, "cache"))
        val modes = mutableMapOf<File, Int>()
        GuestFiles.prepare(env, spec, readable = { true }, uptimeSeconds = 1.0, cpus = 1,
            chmod = { f, m -> modes[f] = m })
        assertEquals(mapOf(env.guestTmp to "1777".toInt(8), env.guestShm to "1777".toInt(8)), modes)
        dir.deleteRecursively()
    }

    @Test fun procSysDefaultsAndVersionMatchingUname() {
        val dir = Files.createTempDirectory("selkie").toFile()
        val env = TestEnv.env().copy(filesDir = File(dir, "files"), cacheDir = File(dir, "cache")).forContainer("work")
        val k = GuestFiles.Kernel("6.6.102-android15-8", "#1 SMP PREEMPT Tue Oct 7 2026")
        val fake = GuestFiles.prepare(env, spec, readable = { it == "/proc/sys/net/core/somaxconn" },
            uptimeSeconds = 10.0, cpus = 1, chmod = { _, _ -> }, kernel = { k })
        assertFalse("readable entries stay real", "/proc/sys/net/core/somaxconn" in fake)
        assertEquals("65534\n", fake.getValue("/proc/sys/kernel/overflowuid").readText())
        assertEquals("40\n", fake.getValue("/proc/sys/kernel/cap_last_cap").readText())
        assertEquals("0\n", fake.getValue("/proc/sys/user/max_user_namespaces").readText())
        assertEquals("6.6.102-android15-8\n", fake.getValue("/proc/sys/kernel/osrelease").readText())
        assertEquals("work\n", fake.getValue("/proc/sys/kernel/hostname").readText())
        assertEquals("Linux version 6.6.102-android15-8 (selkie@android) #1 SMP PREEMPT Tue Oct 7 2026\n",
            fake.getValue("/proc/version").readText())
        val all = GuestFiles.prepare(env, spec, readable = { false }, uptimeSeconds = 10.0, cpus = 1,
            chmod = { _, _ -> }, kernel = { throw IllegalStateException("no uname") })
        assertTrue(all.getValue("/proc/version").readText().startsWith("Linux version unknown"))
        dir.deleteRecursively()
    }

    @Test fun uptimeAdvancesInPlace() {
        val dir = Files.createTempDirectory("selkie").toFile()
        val env = TestEnv.env().copy(filesDir = File(dir, "files"), cacheDir = File(dir, "cache"))
        GuestFiles.refreshUptime(env, 5.0, 1)
        assertFalse("only containers that fake it", GuestFiles.fakeProcFile(env, "/proc/uptime").exists())
        val fake = GuestFiles.prepare(env, spec, readable = { it != "/proc/uptime" }, uptimeSeconds = 9.5, cpus = 2,
            chmod = { _, _ -> }, kernel = { GuestFiles.Kernel("", "") })
        val f = fake.getValue("/proc/uptime")
        assertEquals("9.50 17.10\n", f.readText())
        GuestFiles.refreshUptime(env, 123.25, 2)
        assertEquals("123.25 221.85\n", f.readText())
        dir.deleteRecursively()
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

package io.github.keplersj.selkie.proot

import io.github.keplersj.selkie.BuildConfig
import io.github.keplersj.selkie.engine.EngineEnv
import io.github.keplersj.selkie.engine.ShellSpec
import java.io.File

/** A fully-resolved process to launch: argv + a *complete* (clean) environment. */
data class Invocation(val argv: List<String>, val env: Map<String, String>) {
    val envArray: Array<String> get() = env.map { "${it.key}=${it.value}" }.toTypedArray()
}

/**
 * Builds the proot command line for entering the current deployment. Pure: no I/O, so the exact
 * argv is unit-tested. [GuestFiles] creates the host files/dirs that the binds refer to.
 *
 * Android binds: /dev, /proc, /sys, libdroidc-guest.so -> /usr/bin/droidc, writable /tmp, /run, /dev/shm,
 * generated resolv.conf, and fake /proc files for entries Android hides. Binds from
 * `droidc shell-spec` come after, so guest paths under /run (e.g. /run/droidc/root) land inside
 * the writable /run dir.
 */
class ProotCommandBuilder(
    private val env: EngineEnv,
    private val spec: ShellSpec,
    /** guest path (e.g. "/proc/stat") -> host file with generated content. */
    private val fakeProc: Map<String, File> = emptyMap(),
    private val resolvConf: File = File(env.guestGenerated, "resolv.conf"),
    /** guest path -> generated host file bound over the image's: /etc/passwd, /etc/group, /proc/filesystems. */
    private val overlays: Map<String, File> = emptyMap(),
    /** Device settings for the guest (`TZ`, `LANG`, from [GuestFiles.guestEnv]); the image's own values win. */
    private val guestEnv: Map<String, String> = emptyMap(),
) {
    fun prootArgs(rootMode: Boolean = false): List<String> = buildList {
        add(env.prootBin.path)
        add("--kill-on-exit")
        add("--link2symlink")
        if (rootMode) add("-0") else { add("-i"); add("${env.uid}:${env.gid}") }
        add("-r"); add(spec.rootfs)
        add("-w"); add(spec.cwd)
        fun bind(host: String, guest: String) { add("-b"); add("$host:$guest") }
        bind("/dev", "/dev")
        bind("/proc", "/proc")
        bind("/sys", "/sys")
        bind(env.droidcGuestBin.path, "/usr/bin/droidc")
        bind(env.cgshimLib.path, CGSHIM_GUEST_PATH)
        bind(env.ifshimLib.path, IFSHIM_GUEST_PATH)
        bind(env.guestCgroup.path, "/sys/fs/cgroup")
        bind(env.guestTmp.path, "/tmp")
        bind(env.guestRun.path, "/run")
        bind(env.guestShm.path, "/dev/shm")
        bind(resolvConf.path, "/etc/resolv.conf")
        for ((guest, host) in (fakeProc + overlays).toSortedMap()) bind(host.path, guest)
        for (b in spec.binds) bind(b.host, b.guest)
        // sudo/su/pkexec: the guest droidc again, which asks the container's spawn-server for root.
        for (shim in spec.shims) bind(env.droidcGuestBin.path, shim)
    }

    /** Environment for the proot process; proot passes it through to the guest command. */
    fun environment(): Map<String, String> = LinkedHashMap<String, String>().apply {
        putAll(spec.env)
        for ((k, v) in guestEnv) putIfAbsent(k, v)
        // Guest journal and unit output to `adb logcat`. Opt-in upstream (logs can hold paths and user
        // names, and logcat lands in bug reports), so only debuggable builds turn it on.
        if (BuildConfig.DEBUG) put("DROIDC_LOGCAT", "auto")
        if (spec.shims.isNotEmpty()) put("DROIDC_SPAWNER", env.spawnerSocket)
        // The guest's adb talks to its server on a socket in /run, never TCP 5037 on the localhost every app
        // shares (as `droidc adb` does). Unless the image sets its own.
        putIfAbsent("ADB_SERVER_SOCKET", ADB_SERVER_SOCKET)
        put("PROOT_LOADER", env.prootLoader.path)
        put("PROOT_TMP_DIR", env.prootTmp.path)
        put("PROOT_L2S_DIR", env.prootL2s.path)
    }

    /** Whether the guest's sudo/su/pkexec need [spawnServer] (droidc lists shims only under proot). */
    val hasShims: Boolean get() = spec.shims.isNotEmpty()

    /** Run an arbitrary guest command (non-interactive; used by tests and tools). */
    fun command(guestCmd: List<String>, rootMode: Boolean = false): Invocation =
        Invocation(prootArgs(rootMode) + guestCmd, environment())

    /**
     * Interactive login shell under the guest supervisor, which joins the container's session bus (or
     * `systemd --user`) from [userServices].
     */
    fun interactiveShell(): Invocation = command(listOf("/usr/bin/droidc", "supervise", "--", "/bin/bash", "-l"))
        .let { it.copy(env = it.env + joinUserServices) }

    /**
     * The container's shared session bus or `systemd --user` (`droidc user-services`), which Selkie runs in
     * a proot of its own: with `--kill-on-exit`, services started from one terminal's proot would end with
     * that terminal. Every session's `droidc supervise` joins it; the first keeper's `--systemd` decides, so
     * `auto` gives the desktop and terminals the image's user manager where it has one. `--stay`: Selkie
     * stops it when the container's last session ends.
     */
    fun userServices(): Invocation {
        val inv = command(listOf("/usr/bin/droidc", "user-services", "--systemd", "auto", "--stay"))
        // The user manager starts the desktop's own services (the shell, PipeWire), so it needs the
        // session's settings, not only the session.
        return inv.copy(env = inv.env + sessionDefaults + systemdDiagnostics)
    }

    /**
     * The guest's sound for Android: `droidc audio-out` under the supervisor (for the session's
     * `XDG_RUNTIME_DIR` and bus), which makes a PipeWire sink the default output and prints what plays
     * there as raw 48 kHz stereo S16LE on stdout. [timeout] is how long it waits for the desktop's PipeWire.
     */
    fun audioOut(timeout: Int = AUDIO_OUT_WAIT_SECONDS): Invocation =
        command(listOf("/usr/bin/droidc", "supervise", "--", "/usr/bin/droidc", "audio-out", "--timeout", timeout.toString()))
            .let { it.copy(env = it.env + sessionDefaults + joinUserServices) }

    /**
     * The desktop's channel to Android: `droidc host-link` under the supervisor (for the session bus and
     * the session's settings), JSON lines both ways. See [io.github.keplersj.selkie.link.GuestLink].
     */
    fun hostLink(): Invocation =
        command(listOf("/usr/bin/droidc", "supervise", "--", "/usr/bin/droidc", "host-link"))
            .let { it.copy(env = it.env + sessionDefaults + joinUserServices) }

    /**
     * `droidc spawn-server` on the host, behind the guest's sudo/su/pkexec: for each request it starts this
     * container's proot again as fake root (`-0`) with the requested command. Only our own uid is served.
     */
    fun spawnServer(): Invocation = Invocation(
        listOf(env.droidcBin.path, "spawn-server", "--socket", env.spawnerSocket, "--uid", env.uid.toString(),
            "--") + prootArgs(rootMode = true),
        linkedMapOf(
            "HOME" to env.filesDir.path, "TMPDIR" to env.cacheDir.path, "PATH" to "/system/bin",
            // The launcher it starts is proot, which needs these as every other proot does.
            "PROOT_LOADER" to env.prootLoader.path, "PROOT_TMP_DIR" to env.prootTmp.path,
            "PROOT_L2S_DIR" to env.prootL2s.path,
        ),
    )

    /**
     * The deployment's graphical session (`droidc supervise --session`), shown by Selkie's compositor on
     * `$XDG_RUNTIME_DIR/wayland-0`. [width]/[height] are the session size in pixels, [scale] the
     * session scale (`{scale}`; a `screencast` session sets its monitor to it, see `DisplayScale`).
     */
    fun graphicalSession(width: Int, height: Int, scale: Float = 1f, sessionId: String? = null): Invocation {
        val inv = command(listOf("/usr/bin/droidc", "supervise", "--session", "--systemd", "auto") +
            (if (sessionId != null) listOf("--session-id", sessionId) else emptyList()) +
            listOf("--width", width.toString(), "--height", height.toString(), "--scale", scale.toString()))
        return inv.copy(env = inv.env + systemdDiagnostics + sessionDefaults + joinUserServices +
            mapOf("WAYLAND_DISPLAY" to EngineEnv.WAYLAND_DISPLAY))
    }

    /**
     * One app of the deployment on its own ([argv] from its desktop entry), shown full screen by Selkie's
     * compositor like the session, with the session's services but no desktop around it.
     */
    fun app(argv: List<String>): Invocation {
        require(argv.isNotEmpty()) { "no command" }
        val inv = command(listOf("/usr/bin/droidc", "supervise", "--systemd", "auto", "--") + argv)
        return inv.copy(env = inv.env + systemdDiagnostics + sessionDefaults + joinUserServices + waylandOnly +
            mapOf("WAYLAND_DISPLAY" to EngineEnv.WAYLAND_DISPLAY))
    }

    /** Toolkits that would try X11 first (there is no X server) go straight to Wayland. */
    private val waylandOnly: Map<String, String> = mapOf(
        "XDG_SESSION_TYPE" to "wayland",
        "GDK_BACKEND" to "wayland",
        "QT_QPA_PLATFORM" to "wayland",
        "SDL_VIDEODRIVER" to "wayland",
        "MOZ_ENABLE_WAYLAND" to "1",
        "ELECTRON_OZONE_PLATFORM_HINT" to "wayland",
    )

    /** droidc's own diagnostics for the user manager (its messages, units' output), in debug builds. */
    private val systemdDiagnostics: Map<String, String> get() = if (BuildConfig.DEBUG) mapOf(
        "DROIDC_JOURNAL" to "stderr", "DROIDC_SYSTEMD_STDIO" to "inherit",
        "DROIDC_SYSTEMD_LOG_LEVEL" to "info",
        "CGSHIM_TRACE" to "1",
    ) else emptyMap()

    private val sessionDefaults: Map<String, String> = mapOf(
        // No GPU is passed through yet: guest GL is Mesa's llvmpipe.
        "LIBGL_ALWAYS_SOFTWARE" to "1",
        // PipeWire's access module runs a Flatpak check on each client through /proc/<pid>/root,
        // which Android hides; the check then fails and every client (Mutter's screen-cast stream,
        // the portal) is restricted to nothing ("No node factory discovered"). The manager socket of
        // PipeWire's default configuration skips that check. Flatpak apps get their own environment
        // and keep using the checked socket.
        "PIPEWIRE_REMOTE" to "pipewire-0-manager",
    )

    companion object {
        /** Where the guest's adb server listens (`droidc adb`'s default too). */
        const val ADB_SERVER_SOCKET = "localfilesystem:/run/droidc-adb.sock"

        /**
         * Seconds a session's `droidc supervise` waits for Selkie's [userServices] keeper before starting
         * one itself (inside its own proot, so it would end with that session).
         */
        const val USER_SERVICES_WAIT_SECONDS = 10
        /** How long `droidc audio-out` waits for the desktop's PipeWire to answer. */
        const val AUDIO_OUT_WAIT_SECONDS = 120
        private val joinUserServices = mapOf("DROIDC_USER_SERVICES_WAIT" to USER_SERVICES_WAIT_SECONDS.toString())

        /** Where `droidc supervise --systemd` looks for the cgroup shim (DROIDC_CGSHIM's default). */
        const val CGSHIM_GUEST_PATH = "/usr/lib/droidc/cgshim.so"
        /** Where `droidc supervise` looks for the network-interface shim (DROIDC_IFSHIM's default). */
        const val IFSHIM_GUEST_PATH = "/usr/lib/droidc/ifshim.so"
    }
}

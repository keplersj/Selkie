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
        bind(env.guestTmp.path, "/tmp")
        bind(env.guestRun.path, "/run")
        bind(env.guestShm.path, "/dev/shm")
        bind(resolvConf.path, "/etc/resolv.conf")
        for ((guest, host) in (fakeProc + overlays).toSortedMap()) bind(host.path, guest)
        for (b in spec.binds) bind(b.host, b.guest)
    }

    /** Environment for the proot process; proot passes it through to the guest command. */
    fun environment(): Map<String, String> = LinkedHashMap<String, String>().apply {
        putAll(spec.env)
        // Guest journal and unit output to `adb logcat`. Opt-in upstream (logs can hold paths and user
        // names, and logcat lands in bug reports), so only debuggable builds turn it on.
        if (BuildConfig.DEBUG) put("DROIDC_LOGCAT", "auto")
        put("PROOT_LOADER", env.prootLoader.path)
        put("PROOT_TMP_DIR", env.prootTmp.path)
    }

    /** Run an arbitrary guest command (non-interactive; used by tests and tools). */
    fun command(guestCmd: List<String>, rootMode: Boolean = false): Invocation =
        Invocation(prootArgs(rootMode) + guestCmd, environment())

    /** Interactive login shell under the guest supervisor (starts dbus etc. first). */
    fun interactiveShell(): Invocation =
        command(listOf("/usr/bin/droidc", "supervise", "--", "/bin/bash", "-l"))

    /**
     * The deployment's graphical session (`droidc supervise --session`), shown by Selkie's compositor on
     * `$XDG_RUNTIME_DIR/wayland-0`. [width]/[height] are the session size in pixels.
     */
    fun graphicalSession(width: Int, height: Int, scale: Float = 1f): Invocation {
        val inv = command(listOf("/usr/bin/droidc", "supervise", "--session", "--systemd", "auto",
            "--width", width.toString(), "--height", height.toString(), "--scale", scale.toString()))
        // droidc's own diagnostics for the user manager (its messages, units' output) in desktop.log.
        val diag = if (BuildConfig.DEBUG) mapOf(
            "DROIDC_JOURNAL" to "stderr", "DROIDC_SYSTEMD_STDIO" to "inherit",
            "DROIDC_SYSTEMD_LOG_LEVEL" to "debug",
        ) else emptyMap()
        return inv.copy(env = inv.env + diag + mapOf(
            "WAYLAND_DISPLAY" to EngineEnv.WAYLAND_DISPLAY,
            // No GPU is passed through yet: guest GL is Mesa's llvmpipe.
            "LIBGL_ALWAYS_SOFTWARE" to "1",
            // PipeWire's access module runs a Flatpak check on each client through /proc/<pid>/root,
            // which Android hides; the check then fails and every client (Mutter's screen-cast stream,
            // the portal) is restricted to nothing ("No node factory discovered"). The manager socket of
            // PipeWire's default configuration skips that check. Flatpak apps get their own environment
            // and keep using the checked socket.
            "PIPEWIRE_REMOTE" to "pipewire-0-manager",
        ))
    }

    companion object {
        /** Where `droidc supervise --systemd` looks for the cgroup shim (DROIDC_CGSHIM's default). */
        const val CGSHIM_GUEST_PATH = "/usr/lib/droidc/cgshim.so"
    }
}

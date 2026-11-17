package io.github.keplersj.selkie.proot

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
        bind(env.guestTmp.path, "/tmp")
        bind(env.guestRun.path, "/run")
        bind(env.guestShm.path, "/dev/shm")
        bind(resolvConf.path, "/etc/resolv.conf")
        for ((guest, host) in fakeProc.toSortedMap()) bind(host.path, guest)
        for (b in spec.binds) bind(b.host, b.guest)
    }

    /** Environment for the proot process; proot passes it through to the guest command. */
    fun environment(): Map<String, String> = LinkedHashMap<String, String>().apply {
        putAll(spec.env)
        put("PROOT_LOADER", env.prootLoader.path)
        put("PROOT_TMP_DIR", env.prootTmp.path)
    }

    /** Run an arbitrary guest command (non-interactive; used by tests and tools). */
    fun command(guestCmd: List<String>, rootMode: Boolean = false): Invocation =
        Invocation(prootArgs(rootMode) + guestCmd, environment())

    /** Interactive login shell under the guest supervisor (starts dbus etc. first). */
    fun interactiveShell(): Invocation =
        command(listOf("/usr/bin/droidc", "supervise", "--", "/bin/bash", "-l"))
}

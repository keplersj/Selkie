package io.github.keplersj.selkie.proot

import io.github.keplersj.selkie.engine.DroidcArgs
import io.github.keplersj.selkie.engine.EngineEnv
import io.github.keplersj.selkie.engine.RootShell
import io.github.keplersj.selkie.engine.ShellSpec
import java.io.File

/**
 * Root mode's counterpart of [ProotCommandBuilder]: `su -c` running `libdroidc.so enter`, which
 * unshares namespaces, mounts a private /proc, /sys, /dev, /run and /tmp, binds the deployment's
 * binds and chroots (droidc design doc, "`enter`"). Pure, so the argv is unit-tested.
 *
 * Selkie adds only what droidc cannot know: the guest droidc binary, the generated resolv.conf, and
 * the SELinux hiding files. The guest runs as root, so no passwd/group overlay for the app uid, and
 * /proc is the real one, so no fake /proc files.
 */
class EnterCommandBuilder(
    private val env: EngineEnv,
    private val spec: ShellSpec,
    private val resolvConf: File = File(env.guestGenerated, "resolv.conf"),
    /** guest path -> generated host file or dir (from [GuestFiles.prepareSelinuxHiding]). */
    private val overlays: Map<String, File> = emptyMap(),
) {
    fun enterArgs(guestCmd: List<String>): List<String> = buildList {
        addAll(DroidcArgs.global(env, json = false))
        add("enter")
        fun bind(host: String, guest: String) { add("--bind"); add("$host:$guest") }
        bind(env.droidcGuestBin.path, "/usr/bin/droidc")
        bind(resolvConf.path, "/etc/resolv.conf")
        for ((guest, host) in overlays.toSortedMap()) bind(host.path, guest)
        add("--hostname"); add("selkie")
        add("--")
        addAll(guestCmd)
    }

    /** As root through su; the root shell writes its pid to [pidFile] so Selkie can stop it. */
    fun command(guestCmd: List<String>, pidFile: File): Invocation {
        val su = env.su ?: error("root mode needs su")
        val droidcEnv = linkedMapOf("HOME" to env.filesDir.path, "TMPDIR" to env.cacheDir.path, "PATH" to "/system/bin")
        return Invocation(
            RootShell.su(su, enterArgs(guestCmd), droidcEnv, RootShell.pidToFile(pidFile)),
            RootShell.suEnv(env.filesDir) + mapOf("TERM" to (spec.env["TERM"] ?: "xterm-256color")),
        )
    }

    /** Interactive login shell under the guest supervisor. */
    fun interactiveShell(pidFile: File): Invocation =
        command(listOf("/usr/bin/droidc", "supervise", "--", "/bin/bash", "-l"), pidFile)
}

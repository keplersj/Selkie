package io.github.keplersj.selkie.engine

import java.io.File

/**
 * Everything about where things live. Pure data so it can be built in JVM tests and in
 * instrumented tests alike (see [fromContext] in AndroidEngineEnv.kt).
 */
data class EngineEnv(
    val nativeLibDir: File,
    val filesDir: File,
    val cacheDir: File,
    val uid: Int,
    val gid: Int,
    val dns: List<String> = emptyList(),
    /** droidc container this env acts on (`droidc --container`). See [forContainer]. */
    val container: String = DEFAULT_CONTAINER,
    /** Root mode for this container: mutating droidc commands and terminals run through [su] (see [RootShell]). */
    val rootMode: Boolean = false,
    /** Path of `su`, when one was found. */
    val su: String? = null,
    /** The device's time zone id (`America/Denver`), passed to the guest as `TZ` and `/etc/localtime`. */
    val timeZone: String = "UTC",
    /** The device's locale as `ll` or `ll_CC` (`en_US`), the base of the guest's `LANG`. Empty: unknown. */
    val locale: String = "",
) {
    init { require(isValidContainerName(container)) { "invalid container name: $container" } }

    val isDefaultContainer: Boolean get() = container == DEFAULT_CONTAINER

    /** Root mode is usable only with an su binary. */
    val useRoot: Boolean get() = rootMode && su != null

    /**
     * The same app, acting on another container. Root mode is per container, so it starts off here;
     * on Android, `engineEnvFrom(context, name)` reads that container's own setting.
     */
    fun forContainer(name: String): EngineEnv = copy(container = name, rootMode = false)

    /** Android (bionic) build of droidc: run by Selkie. Static musl builds hit Android's seccomp allowlist (SIGSYS). */
    val droidcBin: File get() = File(nativeLibDir, "libdroidc.so")
    /** Static musl build of droidc: bound into the guest as /usr/bin/droidc (proot handles that side). */
    val droidcGuestBin: File get() = File(nativeLibDir, "libdroidc-guest.so")
    /** glibc LD_PRELOAD shim that lets the guest's `systemd --user` run without a cgroup filesystem. */
    val cgshimLib: File get() = File(nativeLibDir, "libcgshim.so")
    /**
     * glibc LD_PRELOAD shim that lists network interfaces (getifaddrs, if_nameindex) where Android
     * denies netlink to apps. `droidc supervise` preloads it for everything it starts.
     */
    val ifshimLib: File get() = File(nativeLibDir, "libifshim.so")
    val prootBin: File get() = File(nativeLibDir, "libproot.so")
    val prootLoader: File get() = File(nativeLibDir, "libproot-loader.so")

    /** droidc `--root`. */
    val droidcRoot: File get() = File(filesDir, "droidc")
    val prootTmp: File get() = File(cacheDir, "proot-tmp")
    val droidcLog: File get() = File(cacheDir, logName("droidc"))

    /**
     * Host side of each container's writable guest dirs. The default container keeps the paths it had
     * before containers existed; a named one gets `c/<name>`. Kept short on purpose: guest sockets such
     * as `/run/user/<uid>/pipewire-0-manager` are host paths under here, and a Unix socket path must
     * fit in 108 bytes (hence also [MAX_CONTAINER_NAME]).
     */
    val guestBase: File get() =
        if (isDefaultContainer) File(filesDir, "guest") else File(filesDir, "c/$container")

    // Writable guest dirs bound over /tmp, /run, /dev/shm.
    val guestTmp: File get() = File(guestBase, "tmp")
    val guestRun: File get() = File(guestBase, "run")
    val guestShm: File get() = File(guestBase, "shm")
    /**
     * Bound over the guest's /sys/fs/cgroup. Android's own cgroup2 mount there is read-only to an app,
     * so `systemd --user` fails with "Failed to create /init.scope control group"; a plain directory
     * lets droidc build the fake tree its cgroup shim expects.
     */
    val guestCgroup: File get() = File(guestBase, "cgroup")
    val guestGenerated: File get() = File(guestBase, "generated")
    /** Pid files the root shells of this container's root-mode terminals write (see [RootShell.pidToFile]). */
    val rootSessions: File get() =
        File(cacheDir, if (isDefaultContainer) "root-sessions" else "root-sessions-$container")

    /** The guest's `XDG_RUNTIME_DIR` (`/run/user/<uid>`), where droidc supervise puts its sockets. */
    val guestRuntimeDir: File get() = File(guestRun, "user/$uid")
    /** Selkie's Wayland compositor listens here; guest clients see `$XDG_RUNTIME_DIR/wayland-0`. */
    val waylandSocket: File get() = File(guestRuntimeDir, WAYLAND_DISPLAY)
    /** Output of the proot process running the graphical session. */
    val desktopLog: File get() = File(cacheDir, logName("desktop"))

    /**
     * The guest's `/etc/hostname`. A named container is called by its name; the default one, which has
     * no name the user chose, is `selkie`.
     */
    val hostname: String get() = if (isDefaultContainer) "selkie" else container

    private fun logName(base: String) = if (isDefaultContainer) "$base.log" else "$base-$container.log"

    companion object {
        const val WAYLAND_DISPLAY = "wayland-0"
        /** droidc's own default container: the top of the root. */
        const val DEFAULT_CONTAINER = "default"
        /**
         * Selkie's limit; droidc allows 63. Keeps the longest guest socket path, from the `c/<name>`
         * guest dir, inside the 108-byte Unix socket limit (see [guestBase]).
         */
        const val MAX_CONTAINER_NAME = 20

        /** droidc's rule (1-63 of a-z, 0-9, `-`, `_`, `.`, starting with a letter or digit), within [MAX_CONTAINER_NAME]. */
        fun isValidContainerName(name: String): Boolean =
            name.length in 1..MAX_CONTAINER_NAME &&
                (name[0].isAsciiLowerLetter() || name[0].isAsciiDigit()) &&
                name.all { it.isAsciiLowerLetter() || it.isAsciiDigit() || it in "-_." }

        private fun Char.isAsciiLowerLetter() = this in 'a'..'z'
        private fun Char.isAsciiDigit() = this in '0'..'9'
    }
}

/**
 * `containers.droidc.session.services` JSON that starts PipeWire and WirePlumber for a graphical session.
 *
 * PipeWire's access module runs a Flatpak check on each client through `/proc/<pid>/root`, which
 * Android hides: the check fails and every client (Mutter's screen-cast stream, WirePlumber, the portal)
 * is restricted to nothing. `PIPEWIRE_REMOTE=pipewire-0-manager` was not enough in the emulator, because
 * not every client uses it. PipeWire therefore starts from a copy of its own configuration whose
 * `access.socket` makes the default socket `unrestricted` too.
 */
object PipeWireServices {
    private const val SCRIPT =
        "for f in /etc/pipewire/pipewire.conf /usr/share/pipewire/pipewire.conf; do " +
        "[ -r \"\$f\" ] || continue; c=\"\${XDG_RUNTIME_DIR:-/tmp}/pipewire-selkie.conf\"; " +
        "sed 's/^\\([[:space:]]*\\)#access\\.socket = .*/\\1access.socket = { pipewire-0 = \"unrestricted\", pipewire-0-manager = \"unrestricted\" }/' " +
        "\"\$f\" > \"\$c\" " +
        "&& grep -q '^[[:space:]]*access.socket' \"\$c\" && exec pipewire -c \"\$c\"; done; exec pipewire"

    /** The services value for `containers.droidc.session.services` (JSON argv list). */
    val JSON: String =
        """[["sh","-c","${SCRIPT.replace("\\", "\\\\").replace("\"", "\\\"")}"],["wireplumber"]]"""
}

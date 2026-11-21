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
) {
    /** Android (bionic) build of droidc: run by Selkie. Static musl builds hit Android's seccomp allowlist (SIGSYS). */
    val droidcBin: File get() = File(nativeLibDir, "libdroidc.so")
    /** Static musl build of droidc: bound into the guest as /usr/bin/droidc (proot handles that side). */
    val droidcGuestBin: File get() = File(nativeLibDir, "libdroidc-guest.so")
    /** glibc LD_PRELOAD shim that lets the guest's `systemd --user` run without a cgroup filesystem. */
    val cgshimLib: File get() = File(nativeLibDir, "libcgshim.so")
    val prootBin: File get() = File(nativeLibDir, "libproot.so")
    val prootLoader: File get() = File(nativeLibDir, "libproot-loader.so")

    /** droidc `--root`. */
    val droidcRoot: File get() = File(filesDir, "droidc")
    val prootTmp: File get() = File(cacheDir, "proot-tmp")
    val droidcLog: File get() = File(cacheDir, "droidc.log")

    // Writable guest dirs bound over /tmp, /run, /dev/shm.
    val guestTmp: File get() = File(filesDir, "guest/tmp")
    val guestRun: File get() = File(filesDir, "guest/run")
    val guestShm: File get() = File(filesDir, "guest/shm")
    /**
     * Bound over the guest's /sys/fs/cgroup. Android's own cgroup2 mount there is read-only to an app,
     * so `systemd --user` fails with "Failed to create /init.scope control group"; a plain directory
     * lets droidc build the fake tree its cgroup shim expects.
     */
    val guestCgroup: File get() = File(filesDir, "guest/cgroup")
    val guestGenerated: File get() = File(filesDir, "guest/generated")

    /** The guest's `XDG_RUNTIME_DIR` (`/run/user/<uid>`), where droidc supervise puts its sockets. */
    val guestRuntimeDir: File get() = File(guestRun, "user/$uid")
    /** Selkie's Wayland compositor listens here; guest clients see `$XDG_RUNTIME_DIR/wayland-0`. */
    val waylandSocket: File get() = File(guestRuntimeDir, WAYLAND_DISPLAY)
    /** Output of the proot process running the graphical session. */
    val desktopLog: File get() = File(cacheDir, "desktop.log")

    companion object {
        const val WAYLAND_DISPLAY = "wayland-0"
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

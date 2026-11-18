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
 * PipeWire's access module runs a Flatpak check on every client through `/proc/<pid>/root`, which
 * Android hides, so every client (Mutter's screen-cast stream, WirePlumber) ends up restricted to
 * nothing. The emulator run showed `flatpak check failed` even on the manager socket, so PipeWire
 * starts here from a copy of its own configuration without the access module.
 */
object PipeWireServices {
    private const val SCRIPT =
        "for f in /etc/pipewire/pipewire.conf /usr/share/pipewire/pipewire.conf; do " +
        "[ -r \"\$f\" ] || continue; c=\"\${XDG_RUNTIME_DIR:-/tmp}/pipewire-selkie.conf\"; " +
        "awk 'skip{n+=gsub(/\\{/,\"{\");n-=gsub(/\\}/,\"}\");if(n<=0)skip=0;next} " +
        "/libpipewire-module-access/{n=gsub(/\\{/,\"{\")-gsub(/\\}/,\"}\");skip=(n>0);next} 1' \"\$f\" > \"\$c\" " +
        "&& [ -s \"\$c\" ] && exec pipewire -c \"\$c\"; done; exec pipewire"

    /** The services value for `containers.droidc.session.services` (JSON argv list). */
    val JSON: String =
        """[["sh","-c","${SCRIPT.replace("\\", "\\\\").replace("\"", "\\\"")}"],["wireplumber"]]"""
}

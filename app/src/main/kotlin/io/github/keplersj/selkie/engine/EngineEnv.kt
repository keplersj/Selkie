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
    /** Root mode: mutating droidc commands and terminals run through [su] (see [RootShell]). */
    val rootMode: Boolean = false,
    /** Path of `su`, when one was found. */
    val su: String? = null,
) {
    /** Root mode is usable only with an su binary. */
    val useRoot: Boolean get() = rootMode && su != null

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
    /** Pid files the root shells of root-mode terminals write (see [RootShell.pidToFile]). */
    val rootSessions: File get() = File(cacheDir, "root-sessions")
}

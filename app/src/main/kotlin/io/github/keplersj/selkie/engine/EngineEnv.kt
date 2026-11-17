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
    val droidcBin: File get() = File(nativeLibDir, "libdroidc.so")
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
}

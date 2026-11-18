package io.github.keplersj.selkie.engine

import android.content.Context
import android.net.ConnectivityManager
import android.os.Process

/** Android-specific construction of [EngineEnv]. */
fun engineEnvFrom(context: Context, dns: List<String> = currentDns(context)): EngineEnv {
    val app = context.applicationContext
    val uid = Process.myUid()
    return EngineEnv(
        nativeLibDir = java.io.File(app.applicationInfo.nativeLibraryDir),
        // Canonical: droidc records absolute host paths in link2symlink entries, so the path droidc
        // sees and the path proot gets as -r must be the same (/data/user/0 vs /data/data).
        filesDir = app.filesDir.canonicalFile,
        cacheDir = app.cacheDir,
        uid = uid,
        gid = uid,
        dns = dns,
        rootMode = rootModeEnabled(app),
        su = RootShell.findSu(),
    )
}

private const val PREFS = "selkie"
private const val ROOT_MODE = "root_mode"

fun rootModeEnabled(context: Context): Boolean =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ROOT_MODE, false)

fun setRootModeEnabled(context: Context, on: Boolean) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ROOT_MODE, on).apply()
}

/** DNS servers of the active network (Android has no resolv.conf for static binaries). */
fun currentDns(context: Context): List<String> = try {
    val cm = context.getSystemService(ConnectivityManager::class.java)
    val net = cm.activeNetwork
    cm.getLinkProperties(net)?.dnsServers?.mapNotNull { it.hostAddress?.substringBefore('%') }.orEmpty()
} catch (_: Exception) { emptyList() }

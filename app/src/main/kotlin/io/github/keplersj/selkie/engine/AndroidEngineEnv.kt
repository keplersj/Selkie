package io.github.keplersj.selkie.engine

import android.content.Context
import android.net.ConnectivityManager
import android.os.Process

/** Android-specific construction of [EngineEnv] for [container], with that container's root mode setting. */
fun engineEnvFrom(
    context: Context,
    container: String = EngineEnv.DEFAULT_CONTAINER,
    dns: List<String> = currentDns(context),
): EngineEnv {
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
        container = container,
        rootMode = rootModeEnabled(app, container),
        su = RootShell.findSu(),
        timeZone = java.util.TimeZone.getDefault().id,
        locale = java.util.Locale.getDefault().let { if (it.country.isEmpty()) it.language else "${it.language}_${it.country}" },
    )
}

private const val PREFS = "selkie"
private const val ROOT_MODE = "root_mode"

/** Root mode is set per container: one may be image-owned while another still runs under proot. */
private fun rootModeKey(container: String) =
    if (container == EngineEnv.DEFAULT_CONTAINER) ROOT_MODE else "$ROOT_MODE:$container"

fun rootModeEnabled(context: Context, container: String = EngineEnv.DEFAULT_CONTAINER): Boolean =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(rootModeKey(container), false)

fun setRootModeEnabled(context: Context, container: String, on: Boolean) {
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
    if (on) prefs.putBoolean(rootModeKey(container), true) else prefs.remove(rootModeKey(container))
    prefs.apply()
}

/** DNS servers of the active network (Android has no resolv.conf for static binaries). */
fun currentDns(context: Context): List<String> = try {
    val cm = context.getSystemService(ConnectivityManager::class.java)
    val net = cm.activeNetwork
    cm.getLinkProperties(net)?.dnsServers?.mapNotNull { it.hostAddress?.substringBefore('%') }.orEmpty()
} catch (_: Exception) { emptyList() }

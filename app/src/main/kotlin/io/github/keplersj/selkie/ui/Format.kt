package io.github.keplersj.selkie.ui

import android.os.StatFs
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

/** Free bytes in app storage, read once per composition. */
@Composable
fun freeBytes(): Long {
    val ctx = LocalContext.current
    return remember { runCatching { StatFs(ctx.filesDir.path).availableBytes }.getOrDefault(0L) }
}

fun formatBytes(b: Long): String {
    if (b < 1024) return "$b B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var v = b.toDouble(); var i = -1
    while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
    return String.format(Locale.US, "%.1f %s", v, units[i])
}

object Images {
    const val BLUEFIN = "ghcr.io/projectbluefin/bluefin-lts:stable"
    const val CENTOS = "quay.io/centos-bootc/centos-bootc:stream10"
    fun isBluefin(ref: String?) = ref?.startsWith("ghcr.io/projectbluefin/bluefin-lts") == true
    fun isKnown(ref: String?) = ref == BLUEFIN || ref == CENTOS
}

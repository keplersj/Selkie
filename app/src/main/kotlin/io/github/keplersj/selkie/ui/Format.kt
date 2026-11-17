package io.github.keplersj.selkie.ui

import java.util.Locale

fun formatBytes(b: Long): String {
    if (b < 1024) return "$b B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var v = b.toDouble(); var i = -1
    while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
    return String.format(Locale.US, "%.1f %s", v, units[i])
}

/** Known images offered on the setup screen. */
data class ImageChoice(val id: String, val title: String, val ref: String?, val note: String, val sizeHint: String?)

object Images {
    const val BLUEFIN = "ghcr.io/projectbluefin/bluefin-lts:stable"
    const val CENTOS = "quay.io/centos-bootc/centos-bootc:stream10"
    val choices = listOf(
        ImageChoice("bluefin", "Bluefin LTS (GNOME, recommended)", BLUEFIN,
            "Signed image, verified before install.", "≈ 3.8 GB download (arm64)"),
        ImageChoice("centos", "CentOS Stream 10 bootc (small, for testing)", CENTOS,
            "Not signed in a way droidc can verify; requires \"Allow unsigned image\".", "≈ 0.9 GB download"),
        ImageChoice("custom", "Custom image reference", null,
            "Any bootc image, e.g. registry.example/org/image:tag", null),
    )
    fun isBluefin(ref: String?) = ref?.startsWith("ghcr.io/projectbluefin/bluefin-lts") == true
    fun isKnown(ref: String?) = ref == BLUEFIN || ref == CENTOS
}

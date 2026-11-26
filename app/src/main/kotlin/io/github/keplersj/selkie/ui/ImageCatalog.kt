package io.github.keplersj.selkie.ui

/** The kind of device or window Selkie is showing on, which decides what the picker recommends. */
enum class DeviceClass { Phone, Foldable, Tablet, Desktop, Xr, Tv, Car, Watch }

/**
 * An image the droidc project builds for Selkie. [ref] is null until the image is published; until then the
 * picker shows it as "Coming later" and it can't be chosen.
 */
data class TunedVariant(
    val name: String,
    val summary: String,
    /** Who it's for, as the chip shows it. */
    val forText: String,
    val fits: Set<DeviceClass>,
    val ref: String?,
    val anyDevice: Boolean = false,
) {
    val ready: Boolean get() = ref != null
}

/** Another bootc image Selkie knows by name. [signed] is false where droidc can't verify its signature. */
data class KnownImage(val id: String, val title: String, val ref: String, val chips: List<String>, val signed: Boolean)

/** What the picker has chosen. */
sealed interface ImageSelection {
    data class Tuned(val name: String) : ImageSelection
    data class Known(val id: String) : ImageSelection
    data object Custom : ImageSelection
}

object ImageCatalog {
    // Harbor and Monk are the first two to be built (droidc-dev images factory). None is published yet, so
    // every ref is null; set one when its image ships and the picker offers it.
    val tuned: List<TunedVariant> = listOf(
        TunedVariant("Harbor", "Bluefin LTS with GNOME", "Tablet, desktop window, XR panels, parked car",
            setOf(DeviceClass.Foldable, DeviceClass.Tablet, DeviceClass.Desktop, DeviceClass.Xr, DeviceClass.Car), ref = null),
        TunedVariant("Monk", "CentOS, terminal only", "Any device", emptySet(), ref = null, anyDevice = true),
        TunedVariant("Ringed", "Phosh or Plasma Mobile", "Phone", setOf(DeviceClass.Phone, DeviceClass.Foldable), ref = null),
        TunedVariant("Elephant", "Plasma Bigscreen", "TV", setOf(DeviceClass.Tv), ref = null),
        TunedVariant("Baikal", "A watch-sized system", "Watch", setOf(DeviceClass.Watch), ref = null),
        TunedVariant("Weddell", "Immersive apps", "XR headset", setOf(DeviceClass.Xr), ref = null),
    )

    val known: List<KnownImage> = listOf(
        KnownImage("bluefin", "Bluefin LTS", Images.BLUEFIN, listOf("GNOME desktop", "Signed", "Not tuned"), signed = true),
        KnownImage("centos", "CentOS Stream 10", Images.CENTOS, listOf("Terminal only", "Unsigned", "Not tuned"), signed = false),
    )

    /**
     * The picker's order: variants that fit this device, then ones for any device, then the other ready ones,
     * then the ones still to come.
     */
    fun orderedFor(device: DeviceClass, variants: List<TunedVariant> = tuned): List<TunedVariant> {
        val fit = variants.filter { device in it.fits }
        val rest = variants - fit.toSet()
        val any = rest.filter { it.anyDevice }
        val otherReady = rest.filter { it.ready && !it.anyDevice }
        val notReady = rest.filter { !it.ready && !it.anyDevice }
        return fit + any + otherReady + notReady
    }

    /** The first ready variant in [orderedFor] order (Monk on a phone, once published), else Bluefin LTS. */
    fun defaultSelection(device: DeviceClass, variants: List<TunedVariant> = tuned): ImageSelection =
        orderedFor(device, variants).firstOrNull { it.ready }?.let { ImageSelection.Tuned(it.name) }
            ?: ImageSelection.Known(known.first { it.signed }.id)

    /** The selection that matches an image already in use, or a custom address. */
    fun selectionFor(ref: String?, device: DeviceClass, variants: List<TunedVariant> = tuned): ImageSelection {
        if (ref.isNullOrBlank()) return defaultSelection(device, variants)
        variants.firstOrNull { it.ref == ref }?.let { return ImageSelection.Tuned(it.name) }
        known.firstOrNull { it.ref == ref }?.let { return ImageSelection.Known(it.id) }
        return ImageSelection.Custom
    }

    /**
     * The empty state's suggestion. It names an image made for Selkie only once one is published; until then it
     * leads with the signed image the picker preselects.
     */
    fun emptyStateLead(variants: List<TunedVariant> = tuned): String =
        if (variants.any { it.ready }) "Start with an image made for Selkie, or use any other."
        else "Start with ${known.first { it.signed }.title}, a signed GNOME desktop, or use any other image."

    fun columns(device: DeviceClass): Int = when (device) {
        DeviceClass.Phone, DeviceClass.Watch, DeviceClass.Car -> 1
        DeviceClass.Foldable, DeviceClass.Tablet, DeviceClass.Xr -> 2
        DeviceClass.Desktop, DeviceClass.Tv -> 3
    }

    /** What a selection installs. */
    data class Resolved(val title: String, val ref: String, val signed: Boolean)

    fun resolve(selection: ImageSelection, customRef: String, variants: List<TunedVariant> = tuned): Resolved? = when (selection) {
        is ImageSelection.Tuned -> variants.firstOrNull { it.name == selection.name && it.ready }
            ?.let { Resolved(it.name, it.ref!!, signed = true) }
        is ImageSelection.Known -> known.firstOrNull { it.id == selection.id }?.let { Resolved(it.title, it.ref, it.signed) }
        ImageSelection.Custom -> customRef.trim().takeIf { isPlausibleRef(it) }
            ?.let { Resolved(it.substringAfterLast('/').ifEmpty { it }, it, signed = true) }
    }

    /** A loose check: something like registry/name[:tag] or @digest, with no spaces. droidc does the real one. */
    fun isPlausibleRef(ref: String): Boolean =
        ref.isNotEmpty() && ref.none { it.isWhitespace() } && '/' in ref && !ref.startsWith("/") && !ref.endsWith("/")
}

package io.github.keplersj.selkie.usb

import java.io.File

/**
 * What the guest learns about one USB device the person allowed, read from Android's `UsbDevice` and
 * `UsbDeviceConnection`. Pure data, so the files it becomes are unit-tested.
 */
data class UsbDeviceInfo(
    val bus: Int,
    val dev: Int,
    val vendorId: Int,
    val productId: Int,
    val manufacturer: String?,
    val product: String?,
    /** Only when Android gives it (it needs the permission, which every device here has). */
    val serial: String?,
    /** The device and configuration descriptors, as `read()` on its usbfs file returns them. */
    val descriptors: ByteArray,
    /** The active configuration's `bConfigurationValue`. */
    val configuration: Int,
    /** Interface number to its name string, for the interfaces that have one. */
    val interfaceNames: Map<Int, String> = emptyMap(),
) {
    override fun equals(other: Any?): Boolean = other is UsbDeviceInfo && bus == other.bus && dev == other.dev &&
        vendorId == other.vendorId && productId == other.productId && manufacturer == other.manufacturer &&
        product == other.product && serial == other.serial && descriptors.contentEquals(other.descriptors) &&
        configuration == other.configuration && interfaceNames == other.interfaceNames
    override fun hashCode(): Int = (bus * 1000 + dev) * 31 + descriptors.contentHashCode()
}

/**
 * The placeholder files droidc's usbshim expects (droidc's docs/design/usb.md), in two host
 * directories that Selkie binds into proot at `/dev/bus/usb` and `/sys/bus/usb/devices`. Only devices
 * Android granted get entries, so a scan in the guest finds exactly those. The layout copies the
 * kernel's, which is what fastboot and adb read.
 */
object UsbTree {
    private val DEVICE_NAME = Regex("""/dev/bus/usb/(\d{1,3})/(\d{1,3})""")

    /** Bus and device number from `UsbDevice.getDeviceName()` (`/dev/bus/usb/001/005`), or null. */
    fun busDev(deviceName: String): Pair<Int, Int>? =
        DEVICE_NAME.matchEntire(deviceName)?.destructured?.let { (b, d) -> b.toInt() to d.toInt() }

    /** `001/005`: the node under `/dev/bus/usb`. */
    fun nodeName(bus: Int, dev: Int): String = "%03d/%03d".format(bus, dev)

    /** `1-5`: the sysfs name. It stands in for the port path, which Android doesn't expose. */
    fun sysName(bus: Int, dev: Int): String = "$bus-$dev"

    /** Writes (or rewrites) the entries for [info]. */
    fun write(devDir: File, sysDir: File, info: UsbDeviceInfo) {
        remove(devDir, sysDir, info.bus, info.dev)
        val node = File(devDir, nodeName(info.bus, info.dev))
        node.parentFile?.mkdirs()
        node.writeBytes(ByteArray(0))

        val d = File(sysDir, sysName(info.bus, info.dev)).apply { mkdirs() }
        fun put(name: String, value: String) = File(d, name).writeText("$value\n")
        put("busnum", info.bus.toString())
        put("devnum", info.dev.toString())
        put("idVendor", "%04x".format(info.vendorId))
        put("idProduct", "%04x".format(info.productId))
        info.manufacturer?.let { put("manufacturer", oneLine(it)) }
        info.product?.let { put("product", oneLine(it)) }
        info.serial?.let { put("serial", oneLine(it)) }
        put("bConfigurationValue", info.configuration.toString())
        File(d, "descriptors").writeBytes(info.descriptors)
        for ((number, name) in info.interfaceNames) {
            val i = File(sysDir, "${sysName(info.bus, info.dev)}:${info.configuration}.$number").apply { mkdirs() }
            File(i, "interface").writeText("${oneLine(name)}\n")
        }
    }

    /** Removes the entries for one device, if there are any. */
    fun remove(devDir: File, sysDir: File, bus: Int, dev: Int) {
        File(devDir, nodeName(bus, dev)).delete()
        val name = sysName(bus, dev)
        sysDir.listFiles()?.filter { it.name == name || it.name.startsWith("$name:") }
            ?.forEach { it.deleteRecursively() }
    }

    /** Removes every entry: at start, before writing what Android grants now. */
    fun clear(devDir: File, sysDir: File) {
        devDir.listFiles()?.forEach { it.deleteRecursively() }
        sysDir.listFiles()?.forEach { it.deleteRecursively() }
        devDir.mkdirs(); sysDir.mkdirs()
    }

    private fun oneLine(s: String): String = s.replace('\n', ' ').trim()
}

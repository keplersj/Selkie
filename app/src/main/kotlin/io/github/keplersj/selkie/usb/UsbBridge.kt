package io.github.keplersj.selkie.usb

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import io.github.keplersj.selkie.engine.EngineEnv
import io.github.keplersj.selkie.service.NotificationIds
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

/**
 * Hands the guest the USB devices the person allowed (droidc #100, Selkie #128), for fastboot, adb and
 * other usbfs programs:
 *
 * - keeps [UsbTree]'s placeholder entries in step with Android: written once a device is allowed,
 *   removed when it leaves the bus (a target rebooting into its bootloader leaves and comes back with
 *   a new device number);
 * - asks for each new device with a notification whose tap opens Android's permission prompt (the
 *   device filter in the manifest also has Android offer Selkie when an adb or fastboot device is
 *   plugged in, with an "Always open" choice for a flash that reboots it several times);
 * - answers usbshim's `usb.open` on an abstract socket with a fresh `openDevice()` fd, so every open in
 *   the guest gets its own usbfs file, as on Linux.
 *
 * One per process, for every container: Android grants devices to the app, not to a container.
 */
class UsbBridge(private val context: Context, private val env: EngineEnv) {
    private val usb = context.getSystemService(UsbManager::class.java)
    private val nm = context.getSystemService(NotificationManager::class.java)
    private val devDir: File = env.usbDevDir
    private val sysDir: File = env.usbSysDir
    @Volatile private var server: LocalServerSocket? = null
    @Volatile private var stopped = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            val device = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> device?.let { attached(it) }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> device?.let { detached(it) }
                ACTION_ASK -> device?.let { ask(it) }
                ACTION_PERMISSION -> device?.let {
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) allowed(it)
                }
            }
        }
    }

    fun start() {
        if (usb == null) return // no USB host support
        current = this
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "USB devices", NotificationManager.IMPORTANCE_DEFAULT)
            .apply { description = "A USB device was plugged in that Linux apps could use" })
        UsbTree.clear(devDir, sysDir)
        ContextCompat.registerReceiver(context, receiver, IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }, ContextCompat.RECEIVER_EXPORTED) // the system sends these
        ContextCompat.registerReceiver(context, ownReceiver, IntentFilter().apply {
            addAction(ACTION_ASK)
            addAction(ACTION_PERMISSION)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        for (d in usb.deviceList.values) attached(d)
        Thread({ serve() }, "selkie-usb").apply { isDaemon = true }.start()
    }

    fun stop() {
        stopped = true
        if (current === this) current = null
        try { context.unregisterReceiver(receiver) } catch (_: IllegalArgumentException) {}
        try { context.unregisterReceiver(ownReceiver) } catch (_: IllegalArgumentException) {}
        try { server?.close() } catch (_: Exception) {}
        usb?.deviceList?.values?.forEach { nm.cancel(it.deviceName, NotificationIds.USB_PROMPT) }
        UsbTree.clear(devDir, sysDir)
    }

    /** Selkie's own broadcasts (the notification's tap, the permission result), not exported. */
    private val ownReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) = receiver.onReceive(c, intent)
    }

    /** A device on the bus: written if allowed, else the person is asked. */
    fun attached(device: UsbDevice) {
        if (usb.hasPermission(device)) allowed(device) else prompt(device)
    }

    /** Android opened Selkie for a plugged-in device: accepting that grants it. */
    fun openedFor(device: UsbDevice) { if (usb.hasPermission(device)) allowed(device) }

    private fun allowed(device: UsbDevice) {
        nm.cancel(device.deviceName, NotificationIds.USB_PROMPT)
        val info = read(device) ?: return
        synchronized(this) { UsbTree.write(devDir, sysDir, info) }
        Log.i(TAG, "${device.deviceName} (${"%04x:%04x".format(device.vendorId, device.productId)}) is in the guest")
    }

    private fun detached(device: UsbDevice) {
        nm.cancel(device.deviceName, NotificationIds.USB_PROMPT)
        val (bus, dev) = UsbTree.busDev(device.deviceName) ?: return
        synchronized(this) { UsbTree.remove(devDir, sysDir, bus, dev) }
    }

    private fun read(device: UsbDevice): UsbDeviceInfo? {
        val (bus, dev) = UsbTree.busDev(device.deviceName) ?: return null
        val conn = usb.openDevice(device) ?: return null
        return try {
            val config = if (device.configurationCount > 0) device.getConfiguration(0).id else 1
            val names = buildMap<Int, String> {
                for (i in 0 until device.interfaceCount) device.getInterface(i).let { f -> f.name?.let { put(f.id, it) } }
            }
            UsbDeviceInfo(bus, dev, device.vendorId, device.productId, device.manufacturerName, device.productName,
                try { device.serialNumber } catch (_: SecurityException) { null },
                conn.rawDescriptors ?: ByteArray(0), config, names)
        } finally { conn.close() }
    }

    private fun prompt(device: UsbDevice) {
        val name = device.productName?.takeIf { it.isNotBlank() } ?: "%04x:%04x".format(device.vendorId, device.productId)
        val tap = PendingIntent.getBroadcast(context, device.deviceId,
            Intent(ACTION_ASK).setPackage(context.packageName).putExtra(UsbManager.EXTRA_DEVICE, device),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sdcard_usb)
            .setContentTitle("Use $name in Linux?")
            .setContentText("Tap to let Linux apps such as fastboot and adb reach it")
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        nm.notify(device.deviceName, NotificationIds.USB_PROMPT, n)
    }

    /** Opens Android's own prompt for [device]. */
    private fun ask(device: UsbDevice) {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val result = PendingIntent.getBroadcast(context, device.deviceId,
            Intent(ACTION_PERMISSION).setPackage(context.packageName), flags or PendingIntent.FLAG_UPDATE_CURRENT)
        usb.requestPermission(device, result)
    }

    // ---------------- the socket usbshim asks on ----------------

    private fun serve() {
        val s = try { LocalServerSocket(env.usbSocket) } catch (e: Exception) {
            Log.w(TAG, "can't listen on ${env.usbSocket}: ${e.message}"); return
        }
        server = s
        while (!stopped) {
            val c = try { s.accept() } catch (_: Exception) { break }
            Thread({
                try { c.use { answer(it) } } catch (e: Exception) { Log.w(TAG, "usb.open: ${e.message}") }
            }, "selkie-usb-open").apply { isDaemon = true }.start()
        }
    }

    private fun answer(c: LocalSocket) {
        if (c.peerCredentials.uid != Process.myUid()) return // only this app's own guest processes
        val line = readLine(c.inputStream) ?: return
        val out = c.outputStream
        when (val p = UsbProtocol.parse(line)) {
            is UsbProtocol.Parsed.Bad -> out.write(UsbProtocol.error(p.id, UsbProtocol.BAD_REQUEST, p.message).toByteArray())
            is UsbProtocol.Parsed.Ok -> {
                val o = p.open
                val device = usb.deviceList.values.firstOrNull { UsbTree.busDev(it.deviceName) == (o.bus to o.dev) }
                when {
                    device == null -> out.write(UsbProtocol.error(o.id, UsbProtocol.GONE, "no device ${UsbTree.nodeName(o.bus, o.dev)}").toByteArray())
                    !usb.hasPermission(device) ->
                        out.write(UsbProtocol.error(o.id, UsbProtocol.NOT_ALLOWED, "not allowed in Android").toByteArray())
                    else -> {
                        val conn = usb.openDevice(device)
                        if (conn == null) {
                            out.write(UsbProtocol.error(o.id, UsbProtocol.GONE, "Android couldn't open it").toByteArray())
                        } else try {
                            ParcelFileDescriptor.fromFd(conn.fileDescriptor).use { pfd ->
                                c.setFileDescriptorsForSend(arrayOf(pfd.fileDescriptor))
                                out.write(UsbProtocol.ok(o.id).toByteArray())
                                out.flush()
                            }
                        } finally { conn.close() }
                    }
                }
            }
        }
        out.flush()
    }

    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        while (buf.size() < MAX_LINE) {
            val b = input.read()
            if (b < 0) return null
            if (b == '\n'.code) return buf.toString(Charsets.UTF_8.name())
            buf.write(b)
        }
        return null
    }

    companion object {
        private const val TAG = "SelkieUsb"
        private const val CHANNEL = "usb"
        private const val MAX_LINE = 4096
        private const val ACTION_ASK = "io.github.keplersj.selkie.usb.ASK"
        private const val ACTION_PERMISSION = "io.github.keplersj.selkie.usb.PERMISSION"

        /** The running bridge, for [android.app.Activity]s that Android opens for a device. */
        @Volatile var current: UsbBridge? = null
            private set
    }
}

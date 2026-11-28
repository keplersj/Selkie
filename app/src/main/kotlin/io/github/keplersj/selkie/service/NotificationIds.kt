package io.github.keplersj.selkie.service

/**
 * Every notification id Selkie posts, in one place. Android keys an app's notifications by id, so two
 * features sharing one replace and cancel each other's notifications. Add new ones here.
 */
object NotificationIds {
    /** [SessionService]'s foreground notification. */
    const val SESSION = 1
    /** [SessionService]'s prompt for a guest's root command. */
    const val ROOT_PROMPT = 2
    /** [AdbPairingService]'s foreground notification with the pairing code entry. */
    const val ADB_PAIRING = 3
    /** The desktop's now-playing notification (media controls). */
    const val MEDIA = 4
    /** [io.github.keplersj.selkie.usb.UsbBridge]'s ask to use a plugged-in USB device, tagged with its device name. */
    const val USB_PROMPT = 5

    val ALL = listOf(SESSION, ROOT_PROMPT, ADB_PAIRING, MEDIA, USB_PROMPT)
}

package io.github.keplersj.selkie.display

import android.content.Context

/**
 * The scale a Linux session uses on an Android display: one Linux logical pixel per Android dp.
 *
 * Android's density (`displayMetrics.density`, dp per pixel) is what Android apps are laid out at,
 * so a GNOME logical pixel of the same size looks right next to them, and adaptive apps see about
 * as many logical pixels as an Android app sees dp: a phone is ~360-430 wide, which is where
 * libadwaita's narrow breakpoints are.
 *
 * Used for the desktop session (`droidc bridge` picks the closest scale Mutter supports, and the
 * user's own GNOME display settings win over it) and meant for any other way Selkie shows Linux
 * apps, such as one app in its own Android activity.
 */
object DisplayScale {
    /** The session scale for an Android [density], kept within 1x..4x. */
    fun forDensity(density: Float): Float =
        if (density.isNaN() || density <= 0f) 1f else density.coerceIn(1f, 4f)

    /** The session scale for [context]'s display. */
    fun of(context: Context): Float = forDensity(context.resources.displayMetrics.density)
}

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

    /**
     * Linux session sizes are a multiple of this. Mutter offers a fractional scale only where the
     * logical size comes out in whole pixels on both sides, so a view such as 1920x1432 gets no 150%
     * and 1080x1857 no 200%. With both sides a multiple of 12, GNOME offers 150% and 200% (and about
     * 125% and 175%) on any display. Stays under the compositor's `PAD_MAX`, so the few pixels left
     * over show as a black edge rather than stretching the picture.
     */
    const val SIZE_STEP = 12

    /** The Linux session size for a [width]x[height] view: each side rounded down to [SIZE_STEP]. */
    fun sessionSize(width: Int, height: Int): Pair<Int, Int> = step(width) to step(height)

    private fun step(v: Int) = if (v >= SIZE_STEP) v - v % SIZE_STEP else v
}

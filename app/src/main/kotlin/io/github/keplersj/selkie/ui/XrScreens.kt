package io.github.keplersj.selkie.ui

import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.xr.compose.platform.LocalSession
import androidx.xr.compose.platform.LocalSpatialCapabilities
import androidx.xr.compose.spatial.Subspace
import androidx.xr.compose.subspace.SpatialPanel
import androidx.xr.compose.subspace.SpatialRow
import androidx.xr.compose.subspace.layout.SubspaceModifier
import androidx.xr.compose.subspace.layout.height
import androidx.xr.compose.subspace.layout.movable
import androidx.xr.compose.subspace.layout.resizable
import androidx.xr.compose.subspace.layout.width
import androidx.xr.scenecore.scene

// The canvas's XR artboard. In Home Space Selkie is its tablet layout, unchanged, in one window. In Full Space
// Selkie is a row of panels: the containers, the desktop and a terminal, each moved and resized on its own,
// and the desktop follows its panel's size. Immersive (OpenXR apps through a proxy runtime) comes later.

/** A headset Selkie can ask for Full Space on. False on every other device and in previews. */
@Composable
fun xrAvailable(): Boolean = !LocalInspectionMode.current && LocalSession.current != null

/** Selkie is in Full Space, where its screens are spatial panels. */
@Composable
fun xrFullSpace(): Boolean = !LocalInspectionMode.current && LocalSpatialCapabilities.current.isSpatialUiEnabled

/** Moves Selkie into Full Space (true) or back to one window in Home Space (false). */
@Composable
fun rememberXrSpace(): (Boolean) -> Unit {
    val session = if (LocalInspectionMode.current) null else LocalSession.current
    return { full ->
        session?.let { if (full) it.scene.requestFullSpace() else it.scene.requestHomeSpace() }
    }
}

/** Panel sizes from the artboard: a containers panel beside a 16:10 desktop and a terminal under it. */
internal data class XrPanelSize(val width: Dp, val height: Dp)

internal val XR_MAIN_PANEL = XrPanelSize(720.dp, 680.dp)
internal val XR_TERMINAL_PANEL = XrPanelSize(720.dp, 440.dp)

/**
 * The desktop panel opens at the size the desktop already runs at, fitted to 1280dp wide and 800dp tall,
 * else at 1280 by 800. Resizing the panel afterwards resizes the session.
 */
internal fun xrDesktopPanel(running: Pair<Int, Int>?): XrPanelSize {
    val (w, h) = running?.takeIf { it.first > 0 && it.second > 0 } ?: return XrPanelSize(1280.dp, 800.dp)
    val scale = minOf(1280f / w, 800f / h)
    return XrPanelSize((w * scale).dp, (h * scale).dp)
}

/** Selkie in Full Space: [main] in its own panel, with the desktop and a terminal beside it while they're open. */
@Composable
fun XrPanels(
    desktopSize: Pair<Int, Int>?,
    main: @Composable () -> Unit,
    desktop: (@Composable () -> Unit)?,
    terminal: (@Composable () -> Unit)?,
) {
    Subspace {
        SpatialRow {
            SpatialPanel(SubspaceModifier.width(XR_MAIN_PANEL.width).height(XR_MAIN_PANEL.height).movable().resizable()) {
                main()
            }
            if (desktop != null) {
                val size = xrDesktopPanel(desktopSize)
                SpatialPanel(SubspaceModifier.width(size.width).height(size.height).movable().resizable()) { desktop() }
            }
            if (terminal != null) {
                SpatialPanel(SubspaceModifier.width(XR_TERMINAL_PANEL.width).height(XR_TERMINAL_PANEL.height)
                    .movable().resizable()) { terminal() }
            }
        }
    }
}

/** The rail's way between Home Space and Full Space, on a headset only. */
@Composable
fun XrSpaceRailItem(full: Boolean, onChange: (Boolean) -> Unit) {
    NavigationRailItem(
        selected = false,
        onClick = { onChange(!full) },
        icon = { Icon(if (full) SelkieIcons.Device else SelkieIcons.Open, contentDescription = null) },
        label = { Text(if (full) "Home Space" else "Full Space") },
    )
}

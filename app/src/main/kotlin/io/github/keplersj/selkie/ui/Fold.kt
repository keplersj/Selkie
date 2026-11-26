package io.github.keplersj.selkie.ui

import android.app.Activity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker

/**
 * A foldable's hinge, in window coordinates. [vertical] hinges run top to bottom (a book); the others run
 * side to side (a laptop or tabletop). [start] and [end] are the hinge's edges across that axis, in dp from
 * the window's left (vertical) or top (horizontal) edge; they are equal for a hinge that hides nothing.
 */
@Immutable
data class Fold(val vertical: Boolean, val start: Dp, val end: Dp, val halfOpened: Boolean) {
    /** Open like a book: list on one half, detail on the other. */
    val book: Boolean get() = vertical
    /** Bent like a laptop: the terminal stays on the upright half, keys go on the flat one. */
    val tabletop: Boolean get() = !vertical && halfOpened
}

/** The fold of the window the UI is in, or null. MainActivity provides it; previews pass a fake one. */
val LocalFold = staticCompositionLocalOf<Fold?> { null }

/** Watches the activity's window for a hinge. */
@Composable
fun rememberFold(activity: Activity): State<Fold?> {
    val density = LocalDensity.current
    return produceState<Fold?>(null, activity, density) {
        WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity).collect { info ->
            val f = info.displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull()
            value = f?.let {
                val vertical = it.orientation == FoldingFeature.Orientation.VERTICAL
                val b = it.bounds
                with(density) {
                    Fold(
                        vertical = vertical,
                        start = (if (vertical) b.left else b.top).toDp(),
                        end = (if (vertical) b.right else b.bottom).toDp(),
                        halfOpened = it.state == FoldingFeature.State.HALF_OPENED,
                    )
                }
            }
        }
    }
}

/** Where the first pane ends and the second starts inside a box at [offset] that is [size] long. */
internal fun hingeSplit(fold: Fold, offset: Dp, size: Dp): Pair<Dp, Dp> {
    val start = (fold.start - offset).coerceIn(0.dp, size)
    val end = (fold.end - offset).coerceIn(start, size)
    return start to end
}

/**
 * Lays [first] and [second] out on either side of [fold]'s hinge, side by side for a book and stacked for a
 * tabletop, so nothing sits across it.
 */
@Composable
fun HingePanes(fold: Fold, modifier: Modifier = Modifier, first: @Composable () -> Unit, second: @Composable () -> Unit) {
    val density = LocalDensity.current
    var offset by remember { mutableStateOf(0.dp) }
    BoxWithConstraints(modifier.fillMaxSize().onGloballyPositioned {
        val p = it.positionInWindow()
        offset = with(density) { (if (fold.vertical) p.x else p.y).toDp() }
    }) {
        val (a, b) = hingeSplit(fold, offset, if (fold.vertical) maxWidth else maxHeight)
        if (fold.vertical) Row(Modifier.fillMaxSize()) {
            Box(Modifier.width(a).fillMaxHeight()) { first() }
            Spacer(Modifier.width(b - a))
            Box(Modifier.weight(1f).fillMaxHeight()) { second() }
        } else Column(Modifier.fillMaxSize()) {
            Box(Modifier.height(a).fillMaxWidth()) { first() }
            Spacer(Modifier.height(b - a))
            Box(Modifier.weight(1f).fillMaxWidth()) { second() }
        }
    }
}

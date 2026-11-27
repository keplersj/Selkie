package io.github.keplersj.selkie.wear

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListState
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.OutlinedButton
import androidx.wear.compose.material3.Text
import io.github.keplersj.selkie.watch.WatchContainer
import io.github.keplersj.selkie.watch.WatchDesktop

// The canvas's Watch artboard, for what a watch can do today: a remote for Selkie on the phone. The list
// shows the phone's containers, running ones first; a container's screen stops its desktop or closes its
// terminals, each after a second tap. Containers on the watch itself wait for Baikal.

/** Selkie's sea-teal dark scheme on a watch's black. */
private val SelkieWatchColors = ColorScheme(
    primary = Color(0xFF82D5C8), onPrimary = Color(0xFF003731),
    primaryContainer = Color(0xFF005048), onPrimaryContainer = Color(0xFF9EF2E4),
    tertiary = Color(0xFFA9CAE8), onTertiary = Color(0xFF0E3349),
    // A step lighter than the phone's, so tonal buttons stand off the watch's black as on the artboard.
    surfaceContainerLow = Color(0xFF1A2120), surfaceContainer = Color(0xFF252B2A),
    surfaceContainerHigh = Color(0xFF2F3635),
    onSurface = Color(0xFFDDE4E1), onSurfaceVariant = Color(0xFFBEC9C6),
    outline = Color(0xFF899391), outlineVariant = Color(0xFF3F4947),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    background = Color.Black, onBackground = Color(0xFFDDE4E1),
)

@Composable
fun WatchTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = SelkieWatchColors, content = content)

@Composable
private fun Muted(text: String) = Text(
    text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
)

/** The phone's containers, then the watch's own (coming later). [loaded] is false until the first read. */
/** The lists start under the round screen's top edge, not with their second item centred. */
private val ListPadding = PaddingValues(start = 10.dp, top = 28.dp, end = 10.dp, bottom = 40.dp)

@Composable
fun WatchHome(
    phone: PhoneState?,
    loaded: Boolean,
    onOpen: (String) -> Unit,
    state: ScalingLazyListState = rememberScalingLazyListState(),
) {
    ScalingLazyColumn(
        Modifier.fillMaxSize(), state = state, autoCentering = null, contentPadding = ListPadding,
    ) {
        if (phone == null) {
            item { ListHeader { Text("Selkie") } }
            item {
                Muted(if (loaded) "Open Selkie on your phone or tablet, and its containers show up here."
                else "Looking for your phone")
            }
        } else {
            item { Muted("Selkie · ${phone.device}") }
            item {
                Text(runningHeadline(phone.containers), style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
            for (c in ordered(phone.containers)) {
                item(key = c.name) { ContainerButton(c) { onOpen(c.name) } }
            }
        }
        item { ListHeader { Text("On this watch") } }
        item {
            OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth(),
                secondaryLabel = { Text("Coming later") }, label = { Text("Baikal") })
        }
    }
}

@Composable
private fun ContainerButton(c: WatchContainer, onClick: () -> Unit) {
    if (c.running) {
        FilledTonalButton(onClick = onClick, modifier = Modifier.fillMaxWidth(),
            secondaryLabel = { Text(containerLine(c)) }, label = { Text(c.name) })
    } else {
        OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth(),
            secondaryLabel = { Text(containerLine(c)) }, label = { Text(c.name) })
    }
}

/** The two requests the watch can make, each asked twice before it goes. */
enum class WatchAction { StopDesktop, CloseTerminals }

/** One of the phone's containers: what it runs, and a way to stop it. */
@Composable
fun WatchContainerScreen(
    device: String,
    c: WatchContainer,
    onStopDesktop: () -> Unit,
    onCloseTerminals: () -> Unit,
    /** Shows this container in Selkie on [device]. Null where the phone can't be reached. */
    onOpenOnPhone: (() -> Unit)? = null,
    initialConfirm: WatchAction? = null,
    initialOpened: Boolean = false,
    state: ScalingLazyListState = rememberScalingLazyListState(),
) {
    var confirm by remember(c.name) { mutableStateOf(initialConfirm) }
    var opened by remember(c.name) { mutableStateOf(initialOpened) }
    ScalingLazyColumn(
        Modifier.fillMaxSize(), state = state, autoCentering = null, contentPadding = ListPadding,
    ) {
        item { Muted("On $device") }
        item {
            Text(c.name, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth())
        }
        item { Muted(containerLine(c)) }
        if (c.desktop != WatchDesktop.None) {
            item {
                ConfirmButton(
                    label = "Stop desktop", again = "Tap again to stop",
                    armed = confirm == WatchAction.StopDesktop,
                    onArm = { confirm = WatchAction.StopDesktop },
                    onConfirm = { confirm = null; onStopDesktop() },
                )
            }
        }
        if (c.terminals > 0) {
            item {
                ConfirmButton(
                    label = if (c.terminals == 1) "Close its terminal" else "Close its terminals",
                    again = "Tap again to close",
                    armed = confirm == WatchAction.CloseTerminals,
                    onArm = { confirm = WatchAction.CloseTerminals },
                    onConfirm = { confirm = null; onCloseTerminals() },
                )
            }
        }
        if (!c.running) item { Muted("Nothing is running here. Start it on $device.") }
        if (onOpenOnPhone != null) {
            item {
                OutlinedButton(
                    onClick = { opened = true; onOpenOnPhone() }, modifier = Modifier.fillMaxWidth(),
                    label = { Text("Open on phone") },
                    secondaryLabel = { Text(if (opened) "Check $device" else device) },
                )
            }
        }
    }
}

@Composable
private fun ConfirmButton(label: String, again: String, armed: Boolean, onArm: () -> Unit, onConfirm: () -> Unit) {
    if (armed) {
        Button(
            onClick = onConfirm, modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError,
            ),
            label = { Text(again) },
        )
    } else {
        FilledTonalButton(onClick = onArm, modifier = Modifier.fillMaxWidth(), label = { Text(label) })
    }
}

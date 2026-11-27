package io.github.keplersj.selkie.ui

import android.content.Context
import android.hardware.input.InputManager
import android.view.InputDevice
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.keplersj.selkie.service.DesktopState
import io.github.keplersj.selkie.service.ServiceState

// The ten-foot layout from the canvas's TV artboards: tabs along the top, one featured container, and rows
// that the D-pad walks left to right. Focus is a 3dp ring and a slight lift; selection is a tint.

/** What is plugged in or paired, for the top row's chips and for whether the desktop can be used. */
data class TvInputs(val gamepad: Boolean = false, val keyboard: Boolean = false, val pointer: Boolean = false)

/** One input device, as [tvInputs] needs it: its InputDevice sources and whether it types letters. */
internal data class InputInfo(val sources: Int, val alphabetic: Boolean = false, val virtual: Boolean = false)

private fun Int.has(source: Int) = this and source == source

/**
 * A remote is a D-pad keyboard, so a keyboard only counts when it types letters. A trackpad or mouse is a
 * pointer; a touchscreen is not one here, since a TV has none and the desktop wants a cursor.
 */
internal fun tvInputs(devices: List<InputInfo>): TvInputs {
    val real = devices.filterNot { it.virtual }
    return TvInputs(
        gamepad = real.any { it.sources.has(InputDevice.SOURCE_GAMEPAD) || it.sources.has(InputDevice.SOURCE_JOYSTICK) },
        keyboard = real.any { it.alphabetic && it.sources.has(InputDevice.SOURCE_KEYBOARD) },
        pointer = real.any { it.sources.has(InputDevice.SOURCE_MOUSE) || it.sources.has(InputDevice.SOURCE_TOUCHPAD) ||
            it.sources.has(InputDevice.SOURCE_MOUSE_RELATIVE) },
    )
}

private fun readInputs(): TvInputs = tvInputs(InputDevice.getDeviceIds().toList().mapNotNull { id ->
    InputDevice.getDevice(id)?.let {
        InputInfo(it.sources, it.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC, it.isVirtual)
    }
})

/** The connected inputs, updated as devices come and go. */
@Composable
fun rememberTvInputs(): TvInputs {
    if (LocalInspectionMode.current) return TvInputs()
    val ctx = LocalContext.current
    var inputs by remember { mutableStateOf(readInputs()) }
    DisposableEffect(ctx) {
        val im = ctx.getSystemService(Context.INPUT_SERVICE) as InputManager
        val listener = object : InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(deviceId: Int) { inputs = readInputs() }
            override fun onInputDeviceRemoved(deviceId: Int) { inputs = readInputs() }
            override fun onInputDeviceChanged(deviceId: Int) { inputs = readInputs() }
        }
        im.registerInputDeviceListener(listener, null)
        onDispose { im.unregisterInputDeviceListener(listener) }
    }
    return inputs
}

/** The container TV Home features: the one whose desktop runs, else the selected one, else the first. */
internal fun tvFeatured(state: ServiceState, containers: List<ContainerSummary>): ContainerSummary? =
    containers.firstOrNull { it.desktop } ?: containers.firstOrNull { it.name == state.container } ?: containers.firstOrNull()

/** The line under a container's name on TV: its image and what runs in it. */
internal fun tvStateLine(c: ContainerSummary, starting: Boolean): String {
    val image = imageTitle(c.imageRef)
    val what = when {
        c.desktop && starting -> "desktop starting"
        c.desktop -> "desktop running"
        c.terminals == 1 -> "1 terminal"
        c.terminals > 1 -> "${c.terminals} terminals"
        else -> "stopped"
    }
    return "$image · $what"
}

/** TV overscan: the 5% margin Android TV's guidelines keep clear of the screen's edges. */
private val TV_H = 48.dp
private val TV_V = 27.dp

/** A focusable surface for a remote: tinted when [selected], ringed and lifted when focused. */
@Composable
fun TvFocusable(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    shape: Shape = MaterialTheme.shapes.large,
    color: Color = MaterialTheme.colorScheme.surfaceContainer,
    outlined: Boolean = false,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val border = when {
        focused -> BorderStroke(3.dp, cs.primary)
        outlined -> BorderStroke(1.dp, cs.outlineVariant)
        else -> null
    }
    Surface(
        onClick = onClick,
        modifier = modifier.graphicsLayer { val s = if (focused) 1.05f else 1f; scaleX = s; scaleY = s },
        enabled = enabled,
        shape = shape,
        color = if (selected) cs.secondaryContainer else color,
        contentColor = if (selected) cs.onSecondaryContainer else cs.onSurface,
        border = border,
        interactionSource = source,
    ) { content() }
}

/** A remote-friendly button: a pill with an icon and a label, filled when [primary]. */
@Composable
fun TvButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null,
             primary: Boolean = false, enabled: Boolean = true) {
    val cs = MaterialTheme.colorScheme
    TvFocusable(onClick, modifier, shape = RoundedCornerShape(50), enabled = enabled,
        color = if (primary) cs.primary else cs.surfaceContainerHigh) {
        Row(Modifier.padding(horizontal = 24.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val fg = if (primary) cs.onPrimary else cs.onSurface
            if (icon != null) Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(20.dp))
            Text(text, style = MaterialTheme.typography.labelLarge, color = fg)
        }
    }
}

/** The TV frame: Selkie's name, the sections as focusable tabs and the inputs that are connected, over [content]. */
@Composable
fun TvScaffold(
    section: Section,
    onSection: (Section) -> Unit,
    inputs: TvInputs,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier.fillMaxSize().padding(horizontal = TV_H, vertical = TV_V),
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Selkie", Modifier.padding(end = 16.dp), style = MaterialTheme.typography.headlineSmall)
            for (s in Section.entries) {
                TvFocusable(onClick = { onSection(s) }, selected = s == section, shape = RoundedCornerShape(50),
                    color = Color.Transparent,
                    modifier = Modifier.semantics { selected = s == section; role = Role.Tab }) {
                    Text(s.label, Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.titleSmall)
                }
            }
            Spacer(Modifier.weight(1f))
            if (inputs.gamepad) StatusChip("Gamepad connected", tone = ChipTone.Secondary)
            if (inputs.keyboard) StatusChip("Keyboard connected", tone = ChipTone.Secondary, icon = SelkieIcons.Keyboard)
            if (inputs.pointer) StatusChip("Mouse connected", tone = ChipTone.Secondary)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) { content() }
    }
}

/**
 * TV Home: the featured container with a way back into it, the planned big-screen apps row, and every
 * container as a card. Choosing a card features it; Back leaves Selkie as on any TV app's home.
 */
@Composable
fun TvHome(
    state: ServiceState,
    onSelectContainer: (String) -> Unit,
    onOpenDesktop: () -> Unit,
    onOpenTerminal: () -> Unit,
    onStopDesktop: () -> Unit,
    onChangeImage: () -> Unit,
    onNewContainer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val containers = summaries(state)
    val featured = tvFeatured(state, containers)
    var stopping by remember { mutableStateOf<String?>(null) }
    val first = remember { FocusRequester() }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        if (featured == null) {
            SelkieCard(Modifier.fillMaxWidth()) {
                Text("No containers yet", style = MaterialTheme.typography.headlineSmall)
                MutedText("Install a bootc image to get a Linux system on this TV.")
                TvButton("New container", onNewContainer, Modifier.focusRequester(first), icon = SelkieIcons.Plus, primary = true)
            }
        } else {
            TvFeatured(featured, state, first,
                onResume = {
                    if (featured.name != state.container) onSelectContainer(featured.name)
                    onOpenDesktop()
                },
                onTerminal = {
                    if (featured.name != state.container) onSelectContainer(featured.name)
                    onOpenTerminal()
                },
                onStop = { stopping = featured.name },
                onChangeImage = onChangeImage)
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Big-screen apps", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
            PlannedChip()
            MutedText("Open a container's ten-foot app, like Steam Big Picture or Kodi, straight from here.")
        }

        if (containers.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Containers", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(vertical = 8.dp, horizontal = 4.dp)) {
                items(containers, key = { it.name }) { c ->
                    TvFocusable(onClick = { onSelectContainer(c.name) }, selected = c.name == featured?.name,
                        modifier = Modifier.width(260.dp).semantics { selected = c.name == featured?.name }) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            ContainerMark(c.name, style = if (c.running) MarkStyle.Primary else MarkStyle.Neutral, size = 40.dp)
                            Column {
                                Text(c.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                MutedText(tvStateLine(c, state.desktop is DesktopState.Starting),
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                item(key = "new") {
                    TvFocusable(onClick = onNewContainer, outlined = true, color = Color.Transparent,
                        modifier = Modifier.width(260.dp)) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Mark(style = MarkStyle.Neutral, size = 40.dp) { Icon(SelkieIcons.Plus, null) }
                            Column {
                                Text("New container", style = MaterialTheme.typography.titleMedium)
                                MutedText("Install another image", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    stopping?.let { name ->
        StopDesktopDialog(name, onDismiss = { stopping = null }, onConfirm = { stopping = null; onStopDesktop() })
    }
}

@Composable
private fun TvFeatured(
    c: ContainerSummary,
    state: ServiceState,
    first: FocusRequester,
    onResume: () -> Unit,
    onTerminal: () -> Unit,
    onStop: () -> Unit,
    onChangeImage: () -> Unit,
) {
    val starting = state.desktop is DesktopState.Starting
    // The session's name is only known for the selected container's image.
    val desktopName = state.graphical?.takeIf { it.configured && c.name == state.container }?.name
    val hasDesktop = c.desktop || desktopName != null
    SelkieCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(20.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(if (c.desktop) desktopName?.let { "$it desktop" } ?: "Desktop" else c.name,
                        style = MaterialTheme.typography.headlineSmall)
                    MutedText(if (c.desktop) "In the ${c.name} container · " + (if (starting) "starting" else "running")
                        else tvStateLine(c, starting))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    when {
                        c.desktop -> {
                            TvButton("Resume", onResume, Modifier.focusRequester(first), icon = SelkieIcons.Play, primary = true)
                            TvButton("Stop…", onStop, icon = SelkieIcons.Stop)
                        }
                        hasDesktop -> {
                            TvButton("Open desktop", onResume, Modifier.focusRequester(first), icon = SelkieIcons.Desktop, primary = true)
                            TvButton("Terminal", onTerminal, icon = SelkieIcons.Terminal)
                        }
                        else -> {
                            TvButton("Open terminal", onTerminal, Modifier.focusRequester(first), icon = SelkieIcons.Terminal, primary = true)
                            TvButton("Change image", onChangeImage, icon = SelkieIcons.ChangeImage)
                        }
                    }
                }
            }
            if (c.desktop) SelkieCard(Modifier.width(240.dp).height(135.dp), tone = CardTone.Low) {
                Icon(SelkieIcons.Desktop, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                MutedText("Live preview of the session")
                PlannedChip()
            }
        }
    }
}

/**
 * Shown on TV before a desktop opens with no mouse or trackpad: a remote can't move a cursor. Opening anyway
 * still works with a gamepad that sends mouse events, or a pointer connected later.
 */
@Composable
fun TvPointerGate(
    sessionName: String?,
    inputs: TvInputs,
    onPair: () -> Unit,
    onOpenAnyway: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val first = remember { FocusRequester() }
    Column(modifier.fillMaxSize().padding(horizontal = TV_H, vertical = TV_V), verticalArrangement = Arrangement.Center) {
        Column(Modifier.widthIn(max = 640.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Mark(style = MarkStyle.Tertiary, size = 56.dp) { Icon(SelkieIcons.Desktop, null) }
            Text(sessionName?.let { "The $it desktop needs a pointer" } ?: "This desktop needs a pointer", Modifier.semantics { heading() },
                style = MaterialTheme.typography.headlineMedium)
            MutedText("A remote can't move a mouse cursor. Connect a mouse or trackpad and the desktop opens " +
                "on its own." + if (inputs.keyboard) " Your keyboard works there too." else "")
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TvButton("Pair a Bluetooth mouse or keyboard", onPair, Modifier.focusRequester(first),
                    icon = SelkieIcons.Keyboard, primary = true)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvButton("Use my phone as trackpad and keyboard", {}, icon = SelkieIcons.Device, enabled = false)
                    PlannedChip()
                }
                TvButton("Open anyway", onOpenAnyway)
            }
            MutedText("Inside a desktop, Back shows Selkie's controls for a few seconds. Back again returns to Selkie.",
                style = MaterialTheme.typography.bodySmall)
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}

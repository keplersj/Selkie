package io.github.keplersj.selkie.ui

import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.BackHandler
import androidx.annotation.VisibleForTesting
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import io.github.keplersj.selkie.display.NativeCompositor
import kotlinx.coroutines.delay
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.keplersj.selkie.display.CompositorView
import androidx.compose.ui.platform.LocalConfiguration
import io.github.keplersj.selkie.engine.OnShapeChange
import io.github.keplersj.selkie.engine.SessionChoice
import io.github.keplersj.selkie.engine.SessionEntry
import io.github.keplersj.selkie.engine.ShapeChange
import io.github.keplersj.selkie.service.DesktopState
import io.github.keplersj.selkie.service.SessionService

/**
 * The guest's graphical session, full screen. Starts it at the view's size on first show; leaving the
 * screen keeps it running (Stop ends it). Selkie's own controls float over it for a few seconds at a time.
 */
@Composable
fun DesktopScreen(service: SessionService, onTerminal: () -> Unit = {}, onBack: () -> Unit, immersive: Boolean = true) {
    val state by service.state.collectAsState()
    val desktop = state.desktop
    var viewRef by remember { mutableStateOf<CompositorView?>(null) }
    var size by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val ctx = LocalContext.current
    // In an XR panel the system bars aren't over the desktop, so there is nothing to hide.
    if (immersive) ImmersiveMode()
    val keepAwake by service.keepAwake.collectAsState()
    KeepScreenOn(keepAwake)

    val controls = remember { DesktopControlsState() }
    var stopping by remember { mutableStateOf(false) }
    var switching by remember { mutableStateOf(false) }
    // Another session to switch to, only for the whole desktop and only when the image offers one.
    val canSwitch = state.desktopApp == null && desktop is DesktopState.Running &&
        state.desktopContainer == state.container && state.graphicalSessions.count { it.runnable } > 1
    val poke: () -> Unit = controls::show

    // The window's size now: a fold or a display changes it after the session started at [size].
    var viewSize by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    // A fold opening or closing, or a dock or display, can make another session suit the device better.
    val shape = ShapeChange.shapeOf(LocalConfiguration.current)
    var shownShape by remember { mutableStateOf(shape) }
    var reshape by remember { mutableStateOf<Reshape?>(null) }
    LaunchedEffect(shape) {
        if (shape == shownShape) return@LaunchedEffect
        shownShape = shape
        val s = service.state.value
        val c = s.desktopContainer
        reshape = if (c == null || c != s.container || s.desktopApp != null || s.desktop !is DesktopState.Running ||
            service.onShapeChange(c) != OnShapeChange.Ask) null
        else ShapeChange.offer(s.graphicalSessions, s.graphical?.id, s.desktopSessionId, service.pickFor(c, shape))
            ?.let { Reshape(c, shape, s.desktopSession ?: "This session", it) }
    }

    // The view's surface usually exists before the compositor: hand it over once the session runs.
    LaunchedEffect(desktop is DesktopState.Running || desktop is DesktopState.Starting, viewRef) {
        viewRef?.attach()
    }
    LaunchedEffect(desktop) { if (desktop is DesktopState.Running) viewRef?.attach() }

    LaunchedEffect(size) {
        val s = size ?: return@LaunchedEffect
        if (service.state.value.desktop is DesktopState.Stopped) service.startDesktop(s.first, s.second)
    }

    // Compose, not the embedded view, owns key focus in this window, so hardware keys (and the test's
    // injected ones) are forwarded to the compositor view from here. Back shows the controls (above).
    val keyFocus = remember { FocusRequester() }
    // Asked again once the session runs: the first request can come before the focus target is attached.
    LaunchedEffect(desktop is DesktopState.Running) { runCatching { keyFocus.requestFocus() } }
    Box(Modifier.fillMaxSize().background(Color.Black).onSizeChanged {
        if (it.width > 0 && it.height > 0) {
            if (size == null) size = it.width to it.height
            viewSize = it.width to it.height
        }
    }.focusRequester(keyFocus).focusable().onPreviewKeyEvent { viewRef?.dispatchKeyEvent(it.nativeKeyEvent) ?: false }) {
        AndroidView(
            factory = { c -> CompositorView(c) { service.compositor() }.also { viewRef = it } },
            modifier = Modifier.fillMaxSize(),
        )
        when (desktop) {
            is DesktopState.Starting -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Text(state.desktopApp?.let { "Opening ${it.name}…" } ?: "Starting the session…", color = Color.White, modifier = Modifier.padding(top = 12.dp))
            }
            is DesktopState.Failed -> Card(Modifier.align(Alignment.Center).padding(24.dp).widthIn(max = 640.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(state.desktopApp?.let { "${it.name} stopped" } ?: "The session stopped", style = MaterialTheme.typography.titleMedium)
                    Text(desktop.message)
                    if (desktop.logTail.isNotBlank()) Text(desktop.logTail,
                        Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()),
                        style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            service.dismissDesktopError()
                            size?.let { service.startDesktop(it.first, it.second) }
                        }) { Text("Try again") }
                        TextButton(onClick = { service.dismissDesktopError(); onBack() }) { Text("Back") }
                    }
                }
            }
            else -> {}
        }
        if (desktop !is DesktopState.Failed) {
            DesktopControlsLayer(controls, paused = stopping || switching, onLeave = onBack) {
                DesktopControls(
                    container = state.desktopContainer ?: state.container,
                    app = state.desktopApp?.name,
                    size = (desktop as? DesktopState.Running)?.let { it.width to it.height },
                    onSuper = { poke(); sendKey(service, KEY_LEFTMETA) },
                    onKeyboard = {
                        poke()
                        viewRef?.let { v ->
                            v.requestFocus()
                            ctx.getSystemService(InputMethodManager::class.java).showSoftInput(v, 0)
                        }
                    },
                    onEsc = { poke(); sendKey(service, KEY_ESC) },
                    onTerminal = onTerminal,
                    onBack = onBack,
                    onStop = { stopping = true },
                    onHide = controls::hide,
                    onSwitch = if (canSwitch) ({ switching = true }) else null,
                )
            }
        }
    }

    if (stopping) StopDesktopDialog(state.desktopContainer ?: state.container, app = state.desktopApp?.name,
        onDismiss = { stopping = false; poke() },
        onConfirm = { stopping = false; service.stopDesktop(); onBack() })
    if (switching) SwitchSessionDialog(state.container, state.graphicalSessions, state.sessionPick.formFactor,
        current = state.desktopSessionId ?: service.chosenSession()?.id,
        onDismiss = { switching = false; poke() },
        onSwitch = { id ->
            switching = false
            (viewSize ?: size)?.let { service.switchSession(id, it.first, it.second) }
        })
    reshape?.let { r ->
        ReshapeDialog(r,
            onKeep = { always ->
                reshape = null
                if (always) service.keepSessionOnShapeChange(r.container)
                poke()
            },
            onSwitch = {
                reshape = null
                (viewSize ?: size)?.let { service.switchSession(r.offer.id, it.first, it.second) }
            })
    }
}

/** Whether Selkie's controls show over the desktop. [show] also restarts their time on screen. */
@Stable
class DesktopControlsState(showing: Boolean = true) {
    var showing by mutableStateOf(showing)
        private set
    internal var shownAt by mutableIntStateOf(0)
        private set
    fun show() { showing = true; shownAt++ }
    fun hide() { showing = false }
}

/** Says what the tab over a desktop with its controls hidden does, for TalkBack and the tests. */
internal const val SHOW_DESKTOP_CONTROLS = "Show Selkie's controls"

/**
 * The controls over a desktop, and how they come back. They show when the desktop opens, then go after
 * [DESKTOP_CONTROLS_MILLIS] unless [paused]. While they're hidden a small tab stays at the top edge, and
 * tapping it brings them back: touch is otherwise all the guest's, and Back alone isn't enough on a phone,
 * where the edge swipe in immersive mode first reveals the system bars. Back shows them too, and Back
 * while they show calls [onLeave].
 */
@Composable
fun DesktopControlsLayer(
    state: DesktopControlsState,
    paused: Boolean = false,
    onLeave: () -> Unit,
    controls: @Composable () -> Unit,
) {
    LaunchedEffect(state.showing, state.shownAt, paused) {
        if (state.showing && !paused && desktopControlsAutoHide) { delay(DESKTOP_CONTROLS_MILLIS); state.hide() }
    }
    BackHandler {
        when (desktopBack(controlsShowing = state.showing)) {
            DesktopBack.Leave -> onLeave()
            DesktopBack.ShowControls -> state.show()
        }
    }
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(state.showing, Modifier.fillMaxSize(), enter = fadeIn(), exit = fadeOut()) { controls() }
        // Below a camera cutout at the top of an upright phone, which would otherwise sit over the tab.
        AnimatedVisibility(!state.showing,
            Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Top)),
            enter = fadeIn(), exit = fadeOut()) {
            DesktopControlsTab(onClick = state::show)
        }
    }
}

/** The tab at the top edge of a desktop whose controls are hidden: a small pull-down handle. */
@Composable
internal fun DesktopControlsTab(onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        modifier = Modifier.size(width = 64.dp, height = 28.dp).semantics { contentDescription = SHOW_DESKTOP_CONTROLS },
        shape = RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp),
        color = cs.surfaceContainerHigh.copy(alpha = 0.85f), contentColor = cs.onSurface, shadowElevation = 2.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(SelkieIcons.Chevron, contentDescription = null, modifier = Modifier.size(18.dp).rotate(90f))
        }
    }
}

internal enum class DesktopBack { Leave, ShowControls }

/** Back on the desktop: the first press shows the controls, a press while they show returns to Selkie. */
internal fun desktopBack(controlsShowing: Boolean): DesktopBack =
    if (controlsShowing) DesktopBack.Leave else DesktopBack.ShowControls

/** Evdev key codes the controls press. */
private const val KEY_ESC = 1
private const val KEY_LEFTMETA = 125

private fun sendKey(service: SessionService, code: Int) {
    service.compositor()?.let { c ->
        c.input(NativeCompositor.KEY, code, true)
        c.input(NativeCompositor.KEY, code, false)
    }
}

/** How long the controls stay after the desktop opens, or Back or the tab shows them. */
internal const val DESKTOP_CONTROLS_MILLIS = 4_000L

/** Set false by the GUI test, which presses Super long after the controls would have gone. */
@VisibleForTesting
internal var desktopControlsAutoHide = true

/** From this width the controls name the container; below it they keep to the buttons. */
private val CONTROLS_LABEL_WIDTH = 720.dp

/** Below this width the toolbar takes two rows. */
private val CONTROLS_ONE_ROW_WIDTH = 480.dp

/**
 * Selkie's controls over a running desktop (Tablet-Desktop): a floating toolbar at the bottom and the
 * session's size at the top right. They hold only what touch has no other way to do.
 */
@Composable
fun DesktopControls(
    container: String,
    size: Pair<Int, Int>?,
    /** The app shown on its own, or null for the desktop. */
    app: String? = null,
    onSuper: () -> Unit,
    onKeyboard: () -> Unit,
    onEsc: () -> Unit,
    onTerminal: () -> Unit,
    onBack: () -> Unit,
    onStop: () -> Unit,
    onHide: () -> Unit,
    modifier: Modifier = Modifier,
    /** Opens the session switcher; null when there is no other session to switch to. */
    onSwitch: (() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    BoxWithConstraints(modifier.fillMaxSize()) {
        val labelled = maxWidth >= CONTROLS_LABEL_WIDTH
        Surface(
            Modifier.align(Alignment.TopEnd).padding(top = 52.dp, end = 24.dp),
            shape = MaterialTheme.shapes.small, color = cs.surfaceContainerHigh, contentColor = cs.onSurface,
        ) {
            Text(listOfNotNull(app, container, size?.let { "${it.first} × ${it.second}" }).joinToString(" · "),
                Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelLarge)
        }
        Surface(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp, start = 16.dp, end = 16.dp)
                .semantics { paneTitle = "Selkie desktop controls" },
            shape = RoundedCornerShape(36.dp), color = cs.surfaceContainerHigh, contentColor = cs.onSurface,
            shadowElevation = 6.dp,
        ) {
            val keys: @Composable () -> Unit = {
                FilledTonalButton(onClick = onSuper, Modifier.height(56.dp), shape = RoundedCornerShape(28.dp)) {
                    Icon(SelkieIcons.Apps, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Super")
                }
                IconButton(onClick = onKeyboard, Modifier.size(56.dp)) {
                    Icon(SelkieIcons.Keyboard, contentDescription = "Show keyboard")
                }
                TextButton(onClick = onEsc, Modifier.height(56.dp), colors = ButtonDefaults.textButtonColors(contentColor = cs.onSurface)) {
                    Text("Esc")
                }
                IconButton(onClick = onTerminal, Modifier.size(56.dp)) {
                    Icon(SelkieIcons.Terminal, contentDescription = "Open a terminal, keep the desktop running")
                }
                if (onSwitch != null) IconButton(onClick = onSwitch, Modifier.size(56.dp)) {
                    Icon(SelkieIcons.Desktop, contentDescription = "Switch session")
                }
            }
            val leave: @Composable () -> Unit = {
                IconButton(onClick = onBack, Modifier.size(56.dp)) {
                    Icon(SelkieIcons.Back, contentDescription = "Back to Selkie, keep the desktop running")
                }
                Button(onClick = onStop, Modifier.height(56.dp), shape = RoundedCornerShape(28.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = cs.errorContainer, contentColor = cs.onErrorContainer)) {
                    Icon(SelkieIcons.Stop, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Stop…")
                }
                IconButton(onClick = onHide, Modifier.size(56.dp)) {
                    Icon(SelkieIcons.Close, contentDescription = "Hide these controls")
                }
            }
            val gap = Arrangement.spacedBy(4.dp)
            // A phone held upright can't fit one row, so the keys and the ways out stack rather than scroll away.
            if (maxWidth < CONTROLS_ONE_ROW_WIDTH) Column(Modifier.padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = gap) { keys() }
                Box(Modifier.width(240.dp).height(1.dp).background(cs.outlineVariant))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = gap) { leave() }
            } else Row(Modifier.horizontalScroll(rememberScrollState()).padding(8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = gap) {
                if (labelled) Row(Modifier.padding(start = 8.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(32.dp).clip(CircleShape).background(cs.primary), contentAlignment = Alignment.Center) {
                        Icon(SelkieIcons.Containers, contentDescription = null, tint = cs.onPrimary, modifier = Modifier.size(18.dp))
                    }
                    Text(listOfNotNull("Selkie", container, app).joinToString(" · "), style = MaterialTheme.typography.labelLarge)
                }
                keys()
                Box(Modifier.padding(horizontal = 4.dp).width(1.dp).height(32.dp).background(cs.outlineVariant))
                leave()
            }
        }
    }
}

@Composable
fun StopDesktopDialog(container: String, onDismiss: () -> Unit, onConfirm: () -> Unit, app: String? = null) {
    val cs = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(SelkieIcons.Stop, contentDescription = null) },
        title = { Text(if (app != null) "Close $app?" else "Stop the desktop?") },
        text = { Text(if (app != null) "$app in $container closes, and anything unsaved in it is lost. Its terminals keep running."
            else "Apps open on $container's desktop close, and anything unsaved in them is lost. Its terminals keep running.") },
        confirmButton = {
            Button(onClick = onConfirm, colors = ButtonDefaults.buttonColors(containerColor = cs.error, contentColor = cs.onError)) {
                Text(if (app != null) "Close" else "Stop")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Picks another session for the running desktop: those that fit this device first, then the rest.
 * Switching stops this desktop, so it says what closes, as Stop does.
 */
@Composable
fun SwitchSessionDialog(
    container: String,
    sessions: List<SessionEntry>,
    formFactor: String,
    current: String?,
    onDismiss: () -> Unit,
    onSwitch: (String) -> Unit,
) {
    val (fits, others) = SessionChoice.split(sessions, formFactor)
    var picked by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(SelkieIcons.Desktop, contentDescription = null) },
        title = { Text("Switch session?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Apps open on $container's desktop close, and anything unsaved in them is lost. " +
                    "Its terminals keep running.")
                for (e in fits + others) {
                    val selected = (picked ?: current) == e.id
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .selectable(selected = selected, enabled = e.id != current, role = Role.RadioButton,
                            onClick = { picked = e.id }),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        RadioButton(selected = selected, onClick = null, enabled = e.id != current)
                        Column(Modifier.weight(1f)) {
                            Text(e.title, style = MaterialTheme.typography.bodyLarge)
                            val note = when {
                                e.id == current -> "Running now"
                                e in others -> "Made for " + e.formFactors.joinToString(", ") { shapeName(it) }
                                else -> e.comment
                            }
                            if (note != null) Text(note, style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { picked?.let(onSwitch) }, enabled = picked != null && picked != current) {
                Text(sessions.firstOrNull { it.id == picked }?.let { "Switch to ${it.title}" } ?: "Switch")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** The device changed shape under [container]'s desktop, and [offer] suits the new [shape] where [running] doesn't. */
data class Reshape(val container: String, val shape: String, val running: String, val offer: SessionEntry)

/**
 * Asks whether to keep the running session, which resizes to the new screen, or switch to the one made
 * for the new shape. Keeping is chosen to start with, since switching closes the desktop's apps.
 */
@Composable
fun ReshapeDialog(r: Reshape, onKeep: (always: Boolean) -> Unit, onSwitch: () -> Unit) {
    val shape = shapeName(r.shape)
    var switch by remember { mutableStateOf(false) }
    var always by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { onKeep(false) },
        icon = { Icon(SelkieIcons.Desktop, contentDescription = null) },
        title = { Text("Use a session made for this $shape?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${r.container} is showing ${r.running}, which isn't made for a $shape. ${r.offer.title} is.")
                ReshapeOption("Keep ${r.running}", "It resizes to fit. Nothing closes.", selected = !switch) { switch = false }
                ReshapeOption("Switch to ${r.offer.title}",
                    "Apps open on ${r.container}'s desktop close, and anything unsaved in them is lost.",
                    selected = switch) { switch = true }
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .toggleable(value = always && !switch, enabled = !switch, role = Role.Checkbox,
                        onValueChange = { always = it }),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Checkbox(checked = always && !switch, onCheckedChange = null, enabled = !switch)
                    Text("Always keep the session for ${r.container}", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            Button(onClick = { if (switch) onSwitch() else onKeep(always) }) {
                Text(if (switch) "Switch to ${r.offer.title}" else "Keep ${r.running}")
            }
        },
        dismissButton = { TextButton(onClick = { onKeep(false) }) { Text("Not now") } },
    )
}

@Composable
private fun ReshapeOption(title: String, body: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
        .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Keeps the screen on while [on] and this screen is showing, as a desktop app playing video asks. */
@Composable
private fun KeepScreenOn(on: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, on) {
        view.keepScreenOn = on
        onDispose { view.keepScreenOn = false }
    }
}

@Composable
private fun ImmersiveMode() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.context as? android.app.Activity)?.window
        val c = window?.let { WindowCompat.getInsetsController(it, view) }
        c?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { c?.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

package io.github.keplersj.selkie.ui

import android.view.inputmethod.InputMethodManager
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
import io.github.keplersj.selkie.service.DesktopState
import io.github.keplersj.selkie.service.SessionService

/**
 * The guest's graphical session, full screen. Starts it at the view's size on first show; leaving the
 * screen keeps it running (Stop ends it).
 */
@Composable
fun DesktopScreen(service: SessionService, onBack: () -> Unit) {
    val state by service.state.collectAsState()
    val desktop = state.desktop
    var viewRef by remember { mutableStateOf<CompositorView?>(null) }
    var size by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val ctx = LocalContext.current
    ImmersiveMode()

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
    // injected ones) are forwarded to the compositor view from here. Back is left to the system.
    val keyFocus = remember { FocusRequester() }
    // Asked again once the session runs: the first request can come before the focus target is attached.
    LaunchedEffect(desktop is DesktopState.Running) { runCatching { keyFocus.requestFocus() } }
    Box(Modifier.fillMaxSize().background(Color.Black).onSizeChanged {
        if (it.width > 0 && it.height > 0 && size == null) size = it.width to it.height
    }.focusRequester(keyFocus).focusable().onPreviewKeyEvent { viewRef?.dispatchKeyEvent(it.nativeKeyEvent) ?: false }) {
        AndroidView(
            factory = { c -> CompositorView(c) { service.compositor() }.also { viewRef = it } },
            modifier = Modifier.fillMaxSize(),
        )
        when (desktop) {
            is DesktopState.Starting -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Text("Starting the session…", color = Color.White, modifier = Modifier.padding(top = 12.dp))
            }
            is DesktopState.Failed -> Card(Modifier.align(Alignment.Center).padding(24.dp).widthIn(max = 640.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("The session stopped", style = MaterialTheme.typography.titleMedium)
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
        Row(Modifier.align(Alignment.TopEnd).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            // Super (the Activities key in GNOME, the launcher in most desktops): touch has no other
            // way to press it, and Android may keep a hardware Meta key for itself.
            FilledTonalButton(onClick = {
                service.compositor()?.let { c ->
                    c.input(io.github.keplersj.selkie.display.NativeCompositor.KEY, 125, true)
                    c.input(io.github.keplersj.selkie.display.NativeCompositor.KEY, 125, false)
                }
            }) { Text("Super") }
            FilledTonalButton(onClick = {
                viewRef?.let { v ->
                    v.requestFocus()
                    ctx.getSystemService(InputMethodManager::class.java).showSoftInput(v, 0)
                }
            }) { Text("Keyboard") }
            FilledTonalButton(onClick = { service.stopDesktop(); onBack() }) { Text("Stop") }
            FilledTonalButton(onClick = onBack) { Text("Back") }
        }
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

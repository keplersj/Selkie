package io.github.keplersj.selkie

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.keplersj.selkie.platform.AppShortcuts
import io.github.keplersj.selkie.service.DesktopState
import io.github.keplersj.selkie.service.Operation
import io.github.keplersj.selkie.service.SessionService
import io.github.keplersj.selkie.ui.*

/**
 * One Linux app on its own, opened from a home screen shortcut (canvas: Phone-Launcher). Each app is
 * its own document, so it gets its own task in Recents beside Selkie. It shares Selkie's one
 * compositor: opening a second app while one is showing asks to close the first.
 */
class AppActivity : ComponentActivity() {
    private val serviceState = mutableStateOf<SessionService?>(null)
    private val targetState = mutableStateOf<AppShortcuts.Target?>(null)
    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            serviceState.value = (binder as SessionService.LocalBinder).service
        }
        override fun onServiceDisconnected(name: ComponentName?) { serviceState.value = null }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTarget(intent)
        bindService(SessionService.intent(this), conn, Context.BIND_AUTO_CREATE)
        setContent {
            val fold by rememberFold(this)
            SelkieTheme {
                CompositionLocalProvider(LocalFold provides fold) {
                    Surface(Modifier.fillMaxSize()) {
                        val target = targetState.value
                        val service = serviceState.value
                        when {
                            target == null -> LaunchedEffect(Unit) { finishAndRemoveTask() }
                            service == null -> Starting(target.name, null)
                            else -> key(target) { AppRoot(service, target) }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        setTarget(intent)
    }

    private fun setTarget(intent: Intent?) {
        val t = AppShortcuts.target(intent)
        targetState.value = t
        if (t != null) setTaskDescription(ActivityManager.TaskDescription("${t.name} · ${t.container}"))
    }

    override fun onDestroy() {
        runCatching { unbindService(conn) }
        super.onDestroy()
    }

    private fun openSelkie() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    @Composable
    private fun AppRoot(service: SessionService, target: AppShortcuts.Target) {
        val state by service.state.collectAsState()
        var opened by remember { mutableStateOf(false) }
        var problem by remember { mutableStateOf<String?>(null) }
        var gone by remember { mutableStateOf(false) }
        var wasRunning by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) { service.selectContainer(target.container) }

        // Waits for the container's apps, then opens this one. Reads the service's state itself, since
        // the collected copy can lag the selectContainer call above by a frame.
        LaunchedEffect(state.container, state.statusLoaded, state.status, state.apps, state.appsError, state.operation) {
            if (opened || problem != null) return@LaunchedEffect
            val st = service.state.value
            if (st.container != target.container && st.operation is Operation.Running) {
                problem = "Selkie is busy with another container. Open Selkie to see it."; return@LaunchedEffect
            }
            if (!st.statusLoaded) return@LaunchedEffect
            if (st.containers.none { it.name == target.container }) {
                gone = true; problem = "${target.container} isn't on this device any more."; return@LaunchedEffect
            }
            if (st.container != target.container) return@LaunchedEffect
            st.statusError?.let { problem = it; return@LaunchedEffect }
            if (st.status?.current == null) {
                problem = "${target.container} has no image yet. Open Selkie to set it up."; return@LaunchedEffect
            }
            st.appsError?.let { problem = it; return@LaunchedEffect }
            val list = st.apps?.takeIf { it.container.isEmpty() || it.container == target.container } ?: return@LaunchedEffect
            val app = list.apps.firstOrNull { it.id == target.app }
            if (app == null) {
                gone = true; problem = "${target.name} isn't in ${target.container} any more."; return@LaunchedEffect
            }
            val message = service.prepareApp(app)
            if (message != null) problem = message
            else {
                opened = true
                AppShortcuts.opened(this@AppActivity, target.container, app)
            }
        }

        // The app closing (or being closed from Selkie) ends this window, as closing a phone app does.
        val desktop = state.desktop
        val mine = state.desktopContainer == target.container && state.desktopApp?.id == target.app
        LaunchedEffect(opened, desktop, mine) {
            if (!opened) return@LaunchedEffect
            if (desktop is DesktopState.Running && mine) wasRunning = true
            if (wasRunning && (desktop is DesktopState.Stopped || !mine)) finishAndRemoveTask()
        }

        when {
            problem != null -> {
                BackHandler { finishAndRemoveTask() }
                Problem(target, problem!!,
                    onTurnOff = if (gone) ({
                        AppShortcuts.disable(this, target, problem!!)
                        finishAndRemoveTask()
                    }) else null,
                    onOpenSelkie = { openSelkie(); finishAndRemoveTask() })
            }
            !opened -> {
                BackHandler { finishAndRemoveTask() }
                Starting(target.name, target.container)
            }
            else -> DesktopScreen(service,
                onTerminal = { openSelkie() },
                onBack = { if (state.desktop is DesktopState.Stopped) finishAndRemoveTask() else moveTaskToBack(true) })
        }
    }
}

@Composable
private fun Starting(name: String, container: String?) {
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp), Arrangement.Center, Alignment.CenterHorizontally) {
        Text(name, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        if (container != null) MutedText("Starting $container…", textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        CircularProgressIndicator()
    }
}

@Composable
private fun Problem(target: AppShortcuts.Target, message: String, onTurnOff: (() -> Unit)?, onOpenSelkie: () -> Unit) {
    Box(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp), Alignment.Center) {
        SelkieCard(Modifier.widthIn(max = 480.dp).fillMaxWidth(), tone = CardTone.Low) {
            Text("Can't open ${target.name}", style = MaterialTheme.typography.titleLarge)
            MutedText(message)
            Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp, Alignment.End)) {
                if (onTurnOff != null) TextButton(onTurnOff) { Text("Turn off this icon") }
                Button(onOpenSelkie) { Text("Open Selkie") }
            }
        }
    }
}

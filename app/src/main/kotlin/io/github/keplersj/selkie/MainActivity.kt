package io.github.keplersj.selkie

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.keplersj.selkie.platform.PhantomProcessCheck
import io.github.keplersj.selkie.service.Operation
import io.github.keplersj.selkie.service.ServiceState
import io.github.keplersj.selkie.service.SessionService
import io.github.keplersj.selkie.ui.*

class MainActivity : ComponentActivity() {
    private val serviceState = mutableStateOf<SessionService?>(null)
    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            serviceState.value = (binder as SessionService.LocalBinder).service
        }
        override fun onServiceDisconnected(name: ComponentName?) { serviceState.value = null }
    }

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        bindService(SessionService.intent(this), conn, Context.BIND_AUTO_CREATE)
        setContent { SelkieTheme { Surface(Modifier.fillMaxSize()) { Root() } } }
    }

    override fun onDestroy() {
        // The service keeps running (foreground) when sessions/operations are active.
        runCatching { unbindService(conn) }
        super.onDestroy()
    }

    @Composable
    private fun Root() {
        val service = serviceState.value
        if (service == null) {
            Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            return
        }
        val state by service.state.collectAsState()
        val ctx = LocalContext.current
        val prefs = remember { ctx.getSharedPreferences("selkie", Context.MODE_PRIVATE) }
        val check = remember { PhantomProcessCheck(ctx) }
        var phantom by remember { mutableStateOf(check.check()) }
        var acknowledged by remember { mutableStateOf(prefs.getBoolean("phantom_ack", false)) }
        var screen by rememberSaveable { mutableStateOf("auto") }

        LifecycleResumeEffect(Unit) { phantom = check.check(); onPauseOrDispose {} }

        val op = state.operation
        val needPhantom = (phantom == PhantomProcessCheck.State.RESTRICTED || phantom == PhantomProcessCheck.State.UNVERIFIED) && !acknowledged

        Box(Modifier.fillMaxSize().safeDrawingPadding()) {
            when {
                needPhantom -> PhantomScreen(true, phantom == PhantomProcessCheck.State.UNVERIFIED,
                    onRecheck = { phantom = check.check() },
                    onContinueAnyway = { prefs.edit().putBoolean("phantom_ack", true).apply(); acknowledged = true },
                    onFixWithRoot = if (state.suFound) ({
                        service.disablePhantomKillerWithRoot { phantom = check.check() }
                    }) else null)
                op !is Operation.Idle -> ProgressScreen(op,
                    onCancel = { service.cancelOperation() },
                    onRetry = { f -> service.retry(f, f.insecure) },
                    onRetryInsecure = { f -> service.retry(f, true) },
                    onDismiss = { service.dismissOperation(); screen = "auto" })
                screen == "terminal" -> {
                    BackHandler { screen = "auto" }
                    TerminalScreen(service) { screen = "auto" }
                }
                screen == "desktop" -> {
                    BackHandler { screen = "auto" }
                    DesktopScreen(service) { screen = "auto" }
                }
                screen == "session" -> {
                    BackHandler { screen = "auto" }
                    SessionConfigScreen(state.graphical,
                        onSave = { r, e, s, n, done -> service.configureSession(r, e, s, n, done) },
                        onClear = { service.clearSessionOverride() },
                        onBack = { screen = "auto" })
                }
                screen == "about" -> { BackHandler { screen = "auto" }; AboutScreen { screen = "auto" } }
                screen == "new" -> {
                    BackHandler { screen = "auto" }
                    NewContainerScreen(
                        existing = state.containers.map { it.name },
                        onCreate = { name, ref, insecure -> screen = "auto"; service.createContainer(name, ref, insecure) },
                        onClose = { screen = "auto" })
                }
                screen == "change" -> {
                    BackHandler { screen = "auto" }
                    ChangeImageScreen(state.container, state.status?.current?.imageRef,
                        onChange = { ref, insecure -> screen = "auto"; service.switchTo(ref, insecure) },
                        onBack = { screen = "auto" })
                }
                // The first run: the default container has no image yet and there are no others.
                screen == "auto" && state.statusLoaded && state.status?.current == null && state.statusError == null
                        && state.containers.size == 1 -> NewContainerScreen(
                    existing = emptyList(),
                    fixedName = state.container,
                    onCreate = { _, ref, insecure -> service.switchTo(ref, insecure) },
                    onClose = null)
                !state.statusLoaded -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                else -> HomeScreen(state,
                    onOpenTerminal = { screen = "terminal" },
                    onOpenDesktop = { screen = "desktop" },
                    onConfigureDesktop = { screen = "session" },
                    onUpgrade = { service.upgrade() },
                    onRollback = { service.rollback() },
                    onSwitch = { screen = "change" },
                    onAbout = { screen = "about" },
                    onRefresh = { service.refreshStatus() },
                    onSelectContainer = { service.selectContainer(it) },
                    onNewContainer = { screen = "new" },
                    onRemoveContainer = { name, done -> service.removeContainer(name, done) },
                    onRootMode = { service.setRootMode(it) })
            }
        }
    }
}

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
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.KeyboardShortcutInfo
import android.view.Menu
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
import io.github.keplersj.selkie.engine.AppEntry
import io.github.keplersj.selkie.engine.EngineEnv
import io.github.keplersj.selkie.platform.AppShortcuts
import io.github.keplersj.selkie.platform.PhantomProcessCheck
import io.github.keplersj.selkie.service.DesktopState
import io.github.keplersj.selkie.service.Operation
import io.github.keplersj.selkie.service.ServiceState
import io.github.keplersj.selkie.service.SessionService
import io.github.keplersj.selkie.ui.*
import io.github.keplersj.selkie.watch.WatchProtocol

class MainActivity : ComponentActivity() {
    private val serviceState = mutableStateOf<SessionService?>(null)
    /** A container a selkie://container/NAME link asked to show, such as the watch's Open on phone. */
    private val linkedContainer = mutableStateOf<String?>(null)
    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            serviceState.value = (binder as SessionService.LocalBinder).service
        }
        override fun onServiceDisconnected(name: ComponentName?) { serviceState.value = null }
    }

    /** What Ctrl+T, Ctrl+N and Ctrl+1 to 4 do while a section is showing; null on every other screen. */
    private var homeShortcuts: HomeShortcuts? = null

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (savedInstanceState == null) linkedContainer.value = WatchProtocol.containerFromLink(intent?.dataString)
        bindService(SessionService.intent(this), conn, Context.BIND_AUTO_CREATE)
        setContent {
            val fold by rememberFold(this)
            SelkieTheme { CompositionLocalProvider(LocalFold provides fold) { Surface(Modifier.fillMaxSize()) { Root() } } }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        WatchProtocol.containerFromLink(intent.dataString)?.let { linkedContainer.value = it }
    }

    override fun onKeyShortcut(keyCode: Int, event: KeyEvent): Boolean {
        val home = homeShortcuts
        if (home != null && event.isCtrlPressed) when (shortcutFor(keyCode)) {
            Shortcut.NewTerminal -> { home.newTerminal(); return true }
            Shortcut.NewContainer -> { home.newContainer(); return true }
            null -> sectionFor(keyCode)?.let { home.section(it); return true }
        }
        return super.onKeyShortcut(keyCode, event)
    }

    // Lists the shortcuts in the system's keyboard shortcuts helper (Meta+/ on most keyboards).
    override fun onProvideKeyboardShortcuts(data: MutableList<KeyboardShortcutGroup>, menu: Menu?, deviceId: Int) {
        super.onProvideKeyboardShortcuts(data, menu, deviceId)
        if (homeShortcuts == null) return
        data.add(KeyboardShortcutGroup("Selkie", Shortcut.entries.map {
            KeyboardShortcutInfo(it.label, it.keyCode, KeyEvent.META_CTRL_ON)
        } + Section.entries.mapIndexed { i, s ->
            KeyboardShortcutInfo(s.label, SECTION_KEYS[i], KeyEvent.META_CTRL_ON)
        }))
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
        // After a failed create, choosing another image keeps the name that was typed.
        var retryName by rememberSaveable { mutableStateOf<String?>(null) }
        var section by rememberSaveable { mutableStateOf(Section.Containers) }
        // Images' Container button starts a new container from that image.
        var newRef by rememberSaveable { mutableStateOf<String?>(null) }
        // Activity opens a terminal on the session that was tapped.
        var openSession by remember { mutableStateOf<String?>(null) }
        var homeDetail by remember { mutableStateOf(false) }
        // Bumped to open the selected container's own screen, for a link that named it.
        var detailRequest by remember { mutableIntStateOf(0) }
        val linked = linkedContainer.value
        LaunchedEffect(linked, state.statusLoaded) {
            if (linked == null || !state.statusLoaded) return@LaunchedEffect
            if (summaries(state).any { it.name == linked }) {
                if (linked != state.container) service.selectContainer(linked)
                section = Section.Containers
                screen = "auto"
                detailRequest++
            }
            linkedContainer.value = null
        }
        // On a TV the desktop waits for a mouse or trackpad, which a remote can't stand in for.
        val tv = currentDeviceClass() == DeviceClass.Tv
        val inputs = if (tv) rememberTvInputs() else TvInputs()
        // On a headset in Full Space the desktop and the terminal open in panels of their own, beside the rest.
        val xr = xrAvailable()
        val full = xrFullSpace()
        val setSpace = rememberXrSpace()
        var xrDesktop by remember { mutableStateOf(false) }
        var xrTerminal by remember { mutableStateOf(false) }
        // On TV without a mouse, a desktop or pointer-driven app first explains it needs one; big-screen apps don't.
        val showCompositor: (Boolean) -> Unit = { needsPointer ->
            if (full) xrDesktop = true else screen = if (tv && needsPointer && !inputs.pointer) "tv-pointer" else "desktop"
        }
        // The whole desktop, unless one is already running (an app left prepared but never opened is dropped).
        val openDesktop: () -> Unit = { service.prepareDesktop(); showCompositor(true) }
        val openApp: (AppEntry) -> String? = { app ->
            service.prepareApp(app) ?: run {
                // A whole app (not one of its actions) joins the shortcuts on a long press of Selkie's icon.
                if ('#' !in app.id) AppShortcuts.opened(ctx, state.container, app)
                showCompositor(!bigScreen(app)); null
            }
        }
        val openTerminal: () -> Unit = { if (full) xrTerminal = true else screen = "terminal" }
        // In a car, Selkie is parked-only: while driving one screen stands in for all of it.
        val driving = rememberCarDriving()
        val car = currentDeviceClass() == DeviceClass.Car

        LifecycleResumeEffect(Unit) { phantom = check.check(); onPauseOrDispose {} }

        val op = state.operation
        val needPhantom = (phantom == PhantomProcessCheck.State.RESTRICTED || phantom == PhantomProcessCheck.State.UNVERIFIED) && !acknowledged

        val main: @Composable () -> Unit = { Box(Modifier.fillMaxSize().safeDrawingPadding()) {
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
                    onDismiss = { service.dismissOperation(); screen = "auto" },
                    onChooseImage = { f ->
                        service.dismissOperation()
                        retryName = f.container.takeIf { f.creating }
                        screen = if (f.creating) "new" else "change"
                    },
                    onCleanUp = { done -> service.cleanUpImages(done) })
                screen == "terminal" -> {
                    BackHandler { screen = "auto"; openSession = null }
                    TerminalScreen(service, onBack = { screen = "auto"; openSession = null }, initialSession = openSession)
                }
                // The desktop handles Back itself: the first press shows its controls.
                screen == "desktop" -> DesktopScreen(service,
                    onTerminal = {
                        state.desktopContainer?.let { if (it != state.container) service.selectContainer(it) }
                        openTerminal()
                    },
                    onBack = { screen = "auto" })
                screen == "tv-pointer" -> {
                    BackHandler { screen = "auto" }
                    // Pairing a mouse from the gate carries on into the desktop.
                    LaunchedEffect(inputs.pointer) { if (inputs.pointer) screen = "desktop" }
                    TvPointerGate(state.graphical?.name, inputs, app = state.desktopApp?.name,
                        onPair = {
                            runCatching { ctx.startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)) }
                                .onFailure { runCatching { ctx.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)) } }
                        },
                        onOpenAnyway = { screen = "desktop" })
                }
                screen == "session" -> {
                    BackHandler { screen = "auto" }
                    SessionConfigScreen(state.graphical,
                        onSave = { r, e, s, n, done -> service.configureSession(r, e, s, n, done) },
                        onClear = { service.clearSessionOverride() },
                        onBack = { screen = "auto" })
                }
                screen == "about" -> { BackHandler { screen = "auto" }; AboutScreen { screen = "auto" } }
                screen == "phantom" -> {
                    BackHandler { screen = "auto" }
                    PhantomScreen(phantom == PhantomProcessCheck.State.RESTRICTED,
                        phantom == PhantomProcessCheck.State.UNVERIFIED,
                        onRecheck = { phantom = check.check() },
                        onContinueAnyway = { screen = "auto" },
                        onFixWithRoot = if (state.suFound) ({
                            service.disablePhantomKillerWithRoot { phantom = check.check() }
                        }) else null)
                }
                screen == "new" -> {
                    BackHandler { screen = "auto" }
                    val existing = summaries(state).map { it.name }
                    NewContainerScreen(
                        existing = existing,
                        // The first container takes droidc's default name, which the user can still change.
                        initialName = retryName ?: if (existing.isEmpty()) EngineEnv.DEFAULT_CONTAINER else "",
                        initialRef = newRef,
                        onCreate = { name, ref, insecure ->
                            screen = "auto"; retryName = null; newRef = null; section = Section.Containers
                            service.createContainer(name, ref, insecure)
                        },
                        onClose = { screen = "auto"; retryName = null; newRef = null })
                }
                screen == "change" -> {
                    BackHandler { screen = "auto" }
                    ChangeImageScreen(state.container, state.status?.current?.imageRef,
                        onChange = { ref, insecure -> screen = "auto"; service.switchTo(ref, insecure) },
                        onBack = { screen = "auto" })
                }
                // Only the first read waits here; later container switches load inside Home, which keeps its place.
                !state.statusLoaded && summaries(state).isEmpty() ->
                    Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                else -> {
                    val selected = state.container
                    val newContainer = { newRef = null; screen = "new" }
                    DisposableEffect(selected) {
                        homeShortcuts = HomeShortcuts(
                            newTerminal = { if (selected != null) openTerminal() },
                            newContainer = newContainer,
                            section = { section = it },
                        )
                        onDispose { homeShortcuts = null }
                    }
                    val body: @Composable (Boolean) -> Unit = { rail ->
                        when (section) {
                            Section.Containers -> if (tv) TvHome(state,
                                onSelectContainer = { service.selectContainer(it) },
                                onOpenDesktop = openDesktop,
                                onOpenTerminal = openTerminal,
                                onStopDesktop = { service.stopDesktop() },
                                onChangeImage = { screen = "change" },
                                onNewContainer = newContainer,
                                inputs = inputs,
                                onOpenApp = openApp)
                            else HomeScreen(state,
                                onOpenTerminal = openTerminal,
                                onOpenDesktop = openDesktop,
                                onConfigureDesktop = { screen = "session" },
                                onUpgrade = { service.upgrade() },
                                onRollback = { service.rollback() },
                                onSwitch = { screen = "change" },
                                onRefresh = { service.refreshStatus() },
                                onSelectContainer = { service.selectContainer(it) },
                                onNewContainer = newContainer,
                                onRemoveContainer = { name, done -> service.removeContainer(name, done) },
                                onRootMode = { service.setRootMode(it) },
                                onOpenApp = openApp,
                                newInList = !rail,
                                onDetailShown = { homeDetail = it },
                                openDetail = detailRequest,
                                listNote = if (car) ({ CarParkedNote() }) else null)
                            Section.Images -> ImagesScreen(state, rail,
                                onNewContainer = { ref -> newRef = ref; screen = "new" },
                                onCleanUp = { done -> service.cleanUpImages(done) })
                            Section.Activity -> ActivityScreen(state, rail,
                                onReturnToDesktop = { c -> if (c != state.container) service.selectContainer(c); openDesktop() },
                                onStopDesktop = { service.stopDesktop() },
                                onOpenTerminal = { t ->
                                    if (t.container != state.container) service.selectContainer(t.container)
                                    openSession = t.id; openTerminal()
                                },
                                onStopAll = {
                                    service.stopDesktop()
                                    service.terminalSessions().forEach { service.closeSession(it) }
                                },
                                onDismissDesktopError = { service.dismissDesktopError() })
                            Section.Settings -> SettingsScreen(rail, phantom, state.suFound,
                                onPhantomHelp = { screen = "phantom" },
                                onAbout = { screen = "about" })
                        }
                    }
                    if (tv) {
                        // Back from another tab returns to Containers; from Containers it leaves Selkie.
                        BackHandler(enabled = section != Section.Containers) { section = Section.Containers }
                        TvScaffold(section, onSection = { section = it }, inputs = inputs) { body(true) }
                    } else SectionScaffold(section, onSection = { section = it }, onNewContainer = newContainer,
                        showNavigation = !(section == Section.Containers && homeDetail),
                        railFooter = if (xr) ({ XrSpaceRailItem(full, setSpace) }) else null) { rail -> body(rail) }
                }
            }
        } }

        if (driving) Box(Modifier.fillMaxSize().safeDrawingPadding()) { CarDrivingScreen(state) }
        else if (full) {
            // A screen that went full screen before the switch moves into its panel.
            LaunchedEffect(Unit) {
                if (screen == "desktop") { xrDesktop = true; screen = "auto" }
                if (screen == "terminal") { xrTerminal = true; screen = "auto" }
            }
            XrPanels(
                desktopSize = (state.desktop as? DesktopState.Running)?.let { it.width to it.height },
                main = main,
                desktop = if (xrDesktop) ({
                    DesktopScreen(service, immersive = false,
                        onTerminal = {
                            state.desktopContainer?.let { if (it != state.container) service.selectContainer(it) }
                            xrTerminal = true
                        },
                        onBack = { xrDesktop = false })
                }) else null,
                terminal = if (xrTerminal) ({
                    key(openSession) { TerminalScreen(service, onBack = { xrTerminal = false; openSession = null },
                        initialSession = openSession) }
                }) else null,
            )
        } else main()
    }
}

private class HomeShortcuts(val newTerminal: () -> Unit, val newContainer: () -> Unit, val section: (Section) -> Unit)

/** The app's Ctrl shortcuts, from the desktop window design. */
internal enum class Shortcut(val keyCode: Int, val label: String) {
    NewTerminal(KeyEvent.KEYCODE_T, "New terminal"),
    NewContainer(KeyEvent.KEYCODE_N, "New container"),
}

internal fun shortcutFor(keyCode: Int): Shortcut? = Shortcut.entries.firstOrNull { it.keyCode == keyCode }

/** Ctrl+1 to Ctrl+4 open the sections in the navigation's order. */
internal val SECTION_KEYS = intArrayOf(KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_3, KeyEvent.KEYCODE_4)

internal fun sectionFor(keyCode: Int): Section? = SECTION_KEYS.indexOf(keyCode).takeIf { it >= 0 }?.let { Section.entries[it] }

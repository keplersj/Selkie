package io.github.keplersj.selkie.ui

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.keplersj.selkie.engine.AppAction
import io.github.keplersj.selkie.engine.SessionChoice
import io.github.keplersj.selkie.engine.SessionEntry
import io.github.keplersj.selkie.engine.SessionPick
import io.github.keplersj.selkie.engine.AppEntry
import io.github.keplersj.selkie.engine.AppIcon
import io.github.keplersj.selkie.engine.AppList
import io.github.keplersj.selkie.engine.AppOrigin
import io.github.keplersj.selkie.engine.ContainerDeployment
import io.github.keplersj.selkie.engine.HiddenApp
import io.github.keplersj.selkie.platform.PhantomProcessCheck
import io.github.keplersj.selkie.engine.ContainerInfo
import io.github.keplersj.selkie.engine.DeployStats
import io.github.keplersj.selkie.engine.Deployment
import io.github.keplersj.selkie.engine.DroidcError
import io.github.keplersj.selkie.engine.ErrorKind
import io.github.keplersj.selkie.engine.GraphicalSession
import io.github.keplersj.selkie.engine.Signature
import io.github.keplersj.selkie.engine.StatusData
import io.github.keplersj.selkie.service.DesktopState
import io.github.keplersj.selkie.service.OpKind
import io.github.keplersj.selkie.service.Closed
import io.github.keplersj.selkie.service.ClosedCause
import io.github.keplersj.selkie.service.Operation
import io.github.keplersj.selkie.service.ServiceState
import io.github.keplersj.selkie.service.SessionInfo

// Previews for every screen. Each one is also a Roborazzi screenshot test (./gradlew recordRoborazziDebug),
// so keep them deterministic: fixed data, no dynamic color, no I/O.

@Preview(name = "phone", device = "spec:width=411dp,height=891dp", showBackground = true)
@Preview(name = "phone-dark", device = "spec:width=411dp,height=891dp", showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
annotation class PhonePreviews

/** Galaxy Tab S11, landscape. */
@Preview(name = "tablet", device = "spec:width=1280dp,height=800dp,dpi=240", showBackground = true)
annotation class TabletPreview

@Composable
private fun PreviewFrame(content: @Composable () -> Unit) {
    val dark = (androidx.compose.ui.platform.LocalConfiguration.current.uiMode and
        Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    SelkieTheme(dark = dark, dynamicColor = false) { Surface(Modifier.fillMaxSize()) { content() } }
}

object Fixtures {
    val bluefin = Deployment(
        id = "d2",
        imageRef = Images.BLUEFIN,
        manifestDigest = "sha256:4f9c2a7be1d03c5e8a61f0b2d7c94e3a5b6c7d8e9f0a1b2c3d4e5f60718293a4",
        deployedAt = "2026-11-12T19:04:51Z",
        sessionLabel = "GNOME (headless)",
        signature = Signature(verified = true, mode = "sigstore",
            subject = "https://github.com/projectbluefin/common/.github/workflows/build.yml@refs/heads/main"),
        stats = DeployStats(files = 214_318, bytesOnDisk = 9_412_005_888, hardlinks = 18_204, linkStrategy = "reflink"),
    )
    val bluefinOld = bluefin.copy(id = "d1",
        manifestDigest = "sha256:9a1e77c0d4b2f6a8e3c5d7b9f1a2c4e6d8b0a1c3e5f7a9b1c3d5e7f9a1b3c5d7",
        deployedAt = "2026-10-30T08:11:02Z")
    val centos = Deployment(
        id = "d3", imageRef = Images.CENTOS,
        manifestDigest = "sha256:0c3b5d7f9e1a3c5e7a9b1d3f5e7c9a1b3d5f7e9c1a3b5d7f9e1c3a5b7d9f1e3a",
        deployedAt = "2026-11-17T22:40:13Z", insecure = true, signature = Signature(verified = false),
        stats = DeployStats(files = 61_022, bytesOnDisk = 2_208_374_784, hardlinks = 3_911, linkStrategy = "hardlink"),
    )

    val gnome = GraphicalSession(name = "GNOME", renderer = "screencast",
        exec = listOf("gnome-shell", "--headless", "--virtual-monitor", "{width}x{height}"),
        configured = true, source = "label")
    val noSession = GraphicalSession(configured = false)
    /**
     * What an image with several pocket sessions offers (Hooded has Phosh, Ringed will have Plasma Mobile):
     * phone shells, and desktops for other devices.
     */
    val pocketSessions = listOf(
        SessionEntry("sm.puri.Phosh", "xdg", "Phosh", "Phone shell", listOf("phone"), runnable = true, renderer = "wayland"),
        SessionEntry("plasma-mobile", "xdg", "Plasma Mobile", null, listOf("phone"), runnable = true, renderer = "wayland"),
        SessionEntry("plasma", "xdg", "Plasma Desktop", null, listOf("tablet", "desktop"), runnable = true, renderer = "wayland"),
        SessionEntry("plasma-bigscreen", "xdg", "Plasma Bigscreen", null, listOf("tv"), runnable = true, renderer = "wayland"),
        SessionEntry("gnome-wayland", "xdg", "GNOME", null, runnable = false,
            problem = "made for a display manager: the entry has no X-Droidc-Renderer key"),
    )

    val homeInstalled = ServiceState(
        status = StatusData(current = bluefin, previous = bluefinOld), statusLoaded = true,
        sessions = listOf(SessionInfo("1", "bash", true), SessionInfo("2", "htop", true)),
        graphical = gnome, desktop = DesktopState.Running(2560, 1600), desktopContainer = "default",
    )
    /** A named container selected while the default one runs the desktop. */
    val homeContainers = ServiceState(
        container = "dev",
        containers = listOf(
            ContainerInfo("default", current = ContainerDeployment("d1", bluefin.imageRef)),
            ContainerInfo("dev", current = ContainerDeployment("d3", centos.imageRef)),
            ContainerInfo("scratch"),
        ),
        status = StatusData(current = centos), statusLoaded = true,
        sessions = listOf(SessionInfo("1", "bash", true), SessionInfo("2", "bash", true, container = "dev")),
        graphical = gnome, desktop = DesktopState.Running(2560, 1600), desktopContainer = "default",
    )
    val homeInsecure = ServiceState(status = StatusData(current = centos), statusLoaded = true, graphical = noSession)
    /** Nothing installed anywhere: the first run, or after the last container was removed. */
    val homeEmpty = ServiceState(statusLoaded = true)
    /** Root mode offered, for the Settings tab. */
    val homeSettings = homeInstalled.copy(suFound = true)
    val textEditor = AppEntry(
        id = "org.gnome.TextEditor.desktop", name = "Text Editor", comment = "Edit text files",
        categories = listOf("GNOME", "GTK", "Utility", "TextEditor"), mimeTypes = listOf("text/plain", "text/markdown"),
        exec = "gnome-text-editor %U", argv = listOf("gnome-text-editor"),
        path = "/usr/share/applications/org.gnome.TextEditor.desktop",
        icon = AppIcon("org.gnome.TextEditor"),
        actions = listOf(AppAction("new-window", "New Window", listOf("gnome-text-editor", "--new-window"))),
    )
    val apps = AppList(container = "default", apps = listOf(
        AppEntry(id = "org.gnome.Calculator.desktop", name = "Calculator", comment = "Perform arithmetic, scientific or financial calculations",
            categories = listOf("GNOME", "GTK", "Utility", "Calculator"), origin = AppOrigin.FLATPAK,
            argv = listOf("/usr/bin/flatpak", "run", "org.gnome.Calculator"),
            path = "/var/lib/flatpak/exports/share/applications/org.gnome.Calculator.desktop"),
        AppEntry(id = "org.gnome.Nautilus.desktop", name = "Files", comment = "Access and organize files",
            categories = listOf("GNOME", "GTK", "Utility", "Core", "FileManager"), argv = listOf("nautilus", "--new-window"),
            path = "/usr/share/applications/org.gnome.Nautilus.desktop"),
        AppEntry(id = "htop.desktop", name = "Htop", comment = "Show system processes", terminal = true,
            categories = listOf("System", "Monitor"), argv = listOf("htop"), path = "/usr/share/applications/htop.desktop"),
        AppEntry(id = "org.gnome.Loupe.desktop", name = "Image Viewer", comment = "View images",
            categories = listOf("GNOME", "GTK", "Graphics", "Viewer"), argv = listOf("loupe"),
            path = "/usr/share/applications/org.gnome.Loupe.desktop"),
        AppEntry(id = "org.gnome.Settings.desktop", name = "Settings", categories = listOf("GNOME", "GTK", "Settings"),
            onlyShowIn = listOf("GNOME"), needsDesktop = true, argv = listOf("gnome-control-center"),
            path = "/usr/share/applications/org.gnome.Settings.desktop"),
        AppEntry(id = "org.gnome.SystemMonitor.desktop", name = "System Monitor", comment = "View current processes and monitor system state",
            categories = listOf("GNOME", "GTK", "System", "Monitor"), argv = listOf("gnome-system-monitor"),
            path = "/usr/share/applications/org.gnome.SystemMonitor.desktop"),
        textEditor,
        AppEntry(id = "dev.zed.Zed.desktop", name = "Zed", categories = listOf("Development", "IDE"), origin = AppOrigin.USER,
            argv = listOf("/var/home/kepler/.local/bin/zed"),
            path = "/var/home/kepler/.local/share/applications/dev.zed.Zed.desktop"),
    ), hidden = List(9) { HiddenApp("helper$it.desktop", reason = "no-display") } +
        List(3) { HiddenApp("old$it.desktop", reason = "not-installed") })
    val homeApps = homeInstalled.copy(apps = apps)
    /** A games-and-media container's apps, for the TV row: Steam's Big Picture and Kodi lead. */
    val tvApps = AppList(container = "dev", apps = listOf(
        AppEntry(id = "steam.desktop", name = "Steam", comment = "Application for managing and playing games on Steam",
            categories = listOf("Network", "FileTransfer", "Game"), argv = listOf("steam"),
            path = "/usr/share/applications/steam.desktop",
            actions = listOf(AppAction("Store", "Store", listOf("steam", "steam://store")),
                AppAction("BigPicture", "Big Picture", listOf("steam", "steam://open/bigpicture")))),
        AppEntry(id = "org.xbmc.kodi.desktop", name = "Kodi", comment = "Manage and view your media",
            categories = listOf("AudioVideo", "Video", "Player", "TV"), origin = AppOrigin.FLATPAK,
            argv = listOf("/usr/bin/flatpak", "run", "tv.kodi.Kodi"),
            path = "/var/lib/flatpak/exports/share/applications/tv.kodi.Kodi.desktop"),
    ) + apps.apps)
    val homeError = ServiceState(statusLoaded = true,
        statusError = "droidc exited with code 101: state.json: unexpected end of file")

    val downloading = Operation.Running(OpKind.SWITCH, Images.BLUEFIN, phase = "download",
        current = 2_362_232_012, total = 4_080_218_112, unit = "bytes", message = "layer 23/41 sha256:7e1f…",
        container = "work", creating = true)
    val unpacking = Operation.Running(OpKind.UPGRADE, Images.BLUEFIN, phase = "unpack",
        current = 120_455, unit = "files")
    val ownership = Operation.Running(OpKind.FIX_OWNERSHIP, null, phase = "ownership",
        message = "Applying image ownership", container = "dev")
    val signatureFailed = Operation.Failed(OpKind.SWITCH, "registry.example/team/image:latest", insecure = false,
        error = DroidcError(ErrorKind.SIGNATURE, "no trust policy entry matches this image"),
        stderrTail = "INFO pulling registry.example/team/image:latest\nINFO manifest sha256:0a1b2c…\nERROR verify: no matching signatures",
        container = "work", creating = true)
    val storageFailed = Operation.Failed(OpKind.SWITCH, Images.BLUEFIN, insecure = false,
        error = DroidcError(ErrorKind.STORAGE, "ENOSPC while writing usr/lib64/libLLVM.so.19"), stderrTail = "")
    val networkFailed = Operation.Failed(OpKind.UPGRADE, Images.BLUEFIN, insecure = false,
        error = DroidcError(ErrorKind.NETWORK, "connection reset while reading layer 41 of 68"),
        stderrTail = "INFO download layer 41/68\nERROR connection reset by peer (ghcr.io:443)")
    val done = Operation.Done(OpKind.SWITCH, "Installing finished", container = "work", creating = true)
    val cancelled = Operation.Cancelled(OpKind.SWITCH)
}

private val noop: () -> Unit = {}

// --- Home ---

private const val FREE = 96_636_764_160

@Composable
private fun Home(state: ServiceState, detail: Boolean = false, tab: ContainerTab = ContainerTab.Overview) = PreviewFrame {
    HomeSection(state, detail, tab)
}

/** Home as the app shows it: with the navigation bar or rail, which steps aside for a phone's container screen. */
@Composable
private fun HomeSection(state: ServiceState, detail: Boolean = false, tab: ContainerTab = ContainerTab.Overview) {
    var detailShown by remember { mutableStateOf(false) }
    SectionScaffold(Section.Containers, onSection = {}, onNewContainer = noop, showNavigation = !detailShown) { rail ->
        HomeScreen(state, onOpenTerminal = noop, onOpenDesktop = noop, onConfigureDesktop = noop, onUpgrade = noop,
            onRollback = noop, onSwitch = noop, onRefresh = noop, newInList = !rail, onDetailShown = { detailShown = it },
            initialDetail = detail, initialTab = tab, free = FREE)
    }
}

/** Phone: the list. Tablet: the list beside the selected container. */
@PhonePreviews @TabletPreview
@Composable
fun HomeInstalledPreview() = Home(Fixtures.homeInstalled)

@PhonePreviews @TabletPreview
@Composable
fun HomeContainersPreview() = Home(Fixtures.homeContainers)

/** Android closed a container while Selkie was in the background: what ended, and why it happens. */
@PhonePreviews
@Composable
fun HomeKilledPreview() = PreviewFrame {
    SectionScaffold(Section.Containers, onSection = {}, onNewContainer = noop) { rail ->
        HomeScreen(Fixtures.homeContainers, onOpenTerminal = noop, onOpenDesktop = noop, onConfigureDesktop = noop,
            onUpgrade = noop, onRollback = noop, onSwitch = noop, onRefresh = noop, newInList = !rail, free = FREE,
            listNote = {
                ClosedNotices(listOf(Closed("default", at = 0L, desktop = true, terminals = 2,
                    cause = ClosedCause.ChildProcesses, logTail = "gnome-shell: Exiting\n")),
                    phantomRestricted = true, batteryLimited = true, onDismiss = {}, onStartAgain = {},
                    onFixPhantom = noop, onBattery = noop, time = { "14:02" })
            })
    }
}

/** A foldable open like a book: the list and the container on either side of the canvas's 24dp hinge. */
@Preview(name = "fold-book", device = "spec:width=840dp,height=700dp", showBackground = true)
@Preview(name = "fold-book-dark", device = "spec:width=840dp,height=700dp", showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun HomeBookPreview() = PreviewFrame {
    CompositionLocalProvider(LocalFold provides Fold(vertical = true, start = 408.dp, end = 432.dp, halfOpened = false)) {
        HomeSection(Fixtures.homeContainers)
    }
}

@PhonePreviews
@Composable
fun HomeOverviewPreview() = Home(Fixtures.homeInstalled, detail = true)

@PhonePreviews
@Composable
fun HomeOverviewOtherDesktopPreview() = Home(Fixtures.homeContainers, detail = true)

@PhonePreviews
@Composable
fun HomeUpdatesPreview() = Home(Fixtures.homeInstalled, detail = true, tab = ContainerTab.Updates)

@PhonePreviews @TabletPreview
@Composable
fun HomeAppsPreview() = Home(Fixtures.homeApps, detail = true, tab = ContainerTab.Apps)

@PhonePreviews
@Composable
fun AppSheetPreview() = PreviewFrame { AppSheetContent("default", Fixtures.textEditor, onOpen = {}) }

@PhonePreviews @TabletPreview
@Composable
fun HomeSettingsPreview() = Home(Fixtures.homeSettings, detail = true, tab = ContainerTab.Settings)

@PhonePreviews
@Composable
fun HomeInsecurePreview() = Home(Fixtures.homeInsecure, detail = true)

@PhonePreviews @TabletPreview
@Composable
fun HomeEmptyPreview() = Home(Fixtures.homeEmpty)

@PhonePreviews
@Composable
fun HomeErrorPreview() = Home(Fixtures.homeError)

// --- New container and change image ---

/** The published Pinniped OS images as if droidc couldn't verify them, so Bluefin LTS is preselected. */
private val unsignedVariants = ImageCatalog.tuned.map { it.copy(signed = false) }

@PhonePreviews @TabletPreview
@Composable
fun NewContainerPreview() = PreviewFrame {
    NewContainerScreen(existing = listOf("default"), onCreate = { _, _, _ -> }, onClose = noop, free = 96_636_764_160,
        device = deviceClassForWidth(androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp))
}

@PhonePreviews @TabletPreview
@Composable
fun NewContainerUnsignedPreview() = PreviewFrame {
    NewContainerScreen(existing = listOf("default"), onCreate = { _, _, _ -> }, onClose = noop, free = 96_636_764_160,
        device = deviceClassForWidth(androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp),
        variants = unsignedVariants)
}

@PhonePreviews
@Composable
fun FirstContainerPreview() = PreviewFrame {
    NewContainerScreen(existing = emptyList(), initialName = "default", onCreate = { _, _, _ -> }, onClose = noop,
        free = 48_318_382_080, device = DeviceClass.Phone)
}

@PhonePreviews @TabletPreview
@Composable
fun ChangeImagePreview() = PreviewFrame {
    ChangeImageScreen("default", Images.BLUEFIN, onChange = { _, _ -> }, onBack = noop,
        device = deviceClassForWidth(androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp))
}

@PhonePreviews
@Composable
fun ChangeImageCustomPreview() = PreviewFrame {
    ChangeImageScreen("dev", "ghcr.io/example/my-bootc:latest", onChange = { _, _ -> }, onBack = noop,
        device = DeviceClass.Phone)
}

// --- Desktop session settings (DesktopScreen itself needs a live SessionService) ---

@PhonePreviews
@Composable
fun SessionConfigNonePreview() = PreviewFrame {
    SessionConfigScreen(Fixtures.noSession, onSave = { _, _, _, _, _ -> }, onClear = noop, onBack = noop)
}

@PhonePreviews
@Composable
fun SessionConfigOverridePreview() = PreviewFrame {
    SessionConfigScreen(Fixtures.gnome.copy(source = "override",
        services = listOf(listOf("pipewire"), listOf("wireplumber"))),
        onSave = { _, _, _, _, _ -> }, onClear = noop, onBack = noop)
}

@PhonePreviews
@Composable
fun SessionsPhonePreview() = PreviewFrame {
    val pick = SessionPick(formFactor = "phone")
    SessionsScreen("pocket", Fixtures.pocketSessions, pick,
        current = SessionChoice.choose(Fixtures.pocketSessions, null, pick), running = null,
        onChoose = {}, onChooseByDevice = {}, onAddOwn = noop, onBack = noop)
}

@PhonePreviews
@Composable
fun SessionsChosenRunningPreview() = PreviewFrame {
    val pick = SessionPick(formFactor = "phone", chosen = "plasma")
    SessionsScreen("pocket", Fixtures.pocketSessions, pick,
        current = SessionChoice.choose(Fixtures.pocketSessions, null, pick), running = "Phosh",
        onChoose = {}, onChooseByDevice = {}, onAddOwn = noop, onBack = noop)
}

// --- Progress ---

@Composable
private fun Progress(op: Operation) = PreviewFrame {
    ProgressScreen(op, noop, {}, {}, noop, free = 10_093_173_145, onChooseImage = {}, onCleanUp = {})
}

@PhonePreviews @TabletPreview
@Composable
fun ProgressDownloadingPreview() = Progress(Fixtures.downloading)

@PhonePreviews
@Composable
fun ProgressUnpackingPreview() = Progress(Fixtures.unpacking)

@PhonePreviews
@Composable
fun ProgressOwnershipPreview() = Progress(Fixtures.ownership)

@PhonePreviews
@Composable
fun ProgressSignatureFailedPreview() = Progress(Fixtures.signatureFailed)

@PhonePreviews
@Composable
fun ProgressStorageFailedPreview() = Progress(Fixtures.storageFailed)

@PhonePreviews
@Composable
fun ProgressNetworkFailedPreview() = Progress(Fixtures.networkFailed)

@PhonePreviews
@Composable
fun ProgressDonePreview() = Progress(Fixtures.done)

@PhonePreviews
@Composable
fun ProgressCancelledPreview() = Progress(Fixtures.cancelled)

// --- Phantom process setup ---

@PhonePreviews
@Composable
fun PhantomRestrictedPreview() = PreviewFrame { PhantomScreen(stillRestricted = true, onRecheck = noop, onContinueAnyway = noop) }

@PhonePreviews
@Composable
fun PhantomUnverifiedPreview() = PreviewFrame {
    PhantomScreen(stillRestricted = true, unverified = true, onRecheck = noop, onContinueAnyway = noop)
}

// --- About ---

@PhonePreviews
@Composable
fun AboutPreview() = PreviewFrame {
    AboutContent(droidc = "droidc 0.4.2 (3e1c9ab)", proot = "proot 5.4.0-selkie.2",
        platform = """{"os":"android","arch":"aarch64","kernel":"6.6.30","page_size":4096}""",
        onOpenSource = noop, onBack = noop)
}

// --- Terminal chrome (the TerminalView itself needs a live session, so a placeholder stands in) ---

private val previewTabs = listOf(
    TerminalTab("default", "bash", home = true),
    TerminalTab("default", "htop", home = true),
    TerminalTab("dev", "bash", home = false),
)

@Composable
private fun Terminal(error: String? = null) = TerminalLayout(
    tabs = previewTabs, selected = 0, onSelect = {}, onBack = noop, onNew = noop, onClose = noop, onPaste = noop,
    onShowKeyboard = noop, error = error, ctrlOn = true, altOn = false, onCtrl = noop, onAlt = noop, send = {},
    appCursor = { false },
) { m ->
    val t = SelkieTheme.terminal
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = t.accent)) { append("kepler@default") }
            append(":"); withStyle(SpanStyle(color = t.dim)) { append("~") }; append("$ brew install ripgrep\n")
            withStyle(SpanStyle(color = t.dim)) { append("/home/linuxbrew/.linuxbrew/Cellar/ripgrep/14.1.1: 14 files, 6.2MB\n") }
            withStyle(SpanStyle(color = t.accent)) { append("kepler@default") }
            append(":"); withStyle(SpanStyle(color = t.dim)) { append("~") }; append("$ ▊")
        },
        color = t.foreground, style = MonoStyle, modifier = m.padding(horizontal = 10.dp, vertical = 12.dp),
    )
}

@PhonePreviews @TabletPreview
@Composable
fun TerminalChromePreview() = PreviewFrame { Terminal() }

@Preview(name = "phone", device = "spec:width=411dp,height=891dp", showBackground = true)
@Composable
fun TerminalErrorPreview() = PreviewFrame { Terminal(error = "proot exited with status 1") }

/** A foldable bent like a laptop: the hinge runs across at the canvas's 338 to 362dp. */
@Preview(name = "fold-tabletop", device = "spec:width=840dp,height=700dp", showBackground = true)
@Preview(name = "fold-tabletop-dark", device = "spec:width=840dp,height=700dp", showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun TerminalTabletopPreview() = PreviewFrame {
    CompositionLocalProvider(LocalFold provides Fold(vertical = false, start = 338.dp, end = 362.dp, halfOpened = true)) {
        Terminal()
    }
}

// --- Shared components (the redesign's building blocks) ---

@OptIn(ExperimentalLayoutApi::class)
@PhonePreviews
@Composable
fun ComponentsPreview() = PreviewFrame {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Containers", style = MaterialTheme.typography.displaySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            ContainerMark("default")
            ContainerMark("dev", style = MarkStyle.Tertiary)
            ContainerMark("scratch", style = MarkStyle.Neutral)
            Mark(style = MarkStyle.Tertiary) { androidx.compose.material3.Icon(SelkieIcons.Root, null) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusChip("Running", tone = ChipTone.Ok)
            StatusChip("GNOME desktop", tone = ChipTone.Secondary)
            StatusChip("Unsigned image", tone = ChipTone.Bad, icon = SelkieIcons.Warn)
            StatusChip("Stopped")
            PlannedChip()
        }
        SectionTitle("Versions on this device")
        Group {
            GroupItem("In use", supporting = "Nov 12 · 4f9c2a7be1d0",
                leading = { androidx.compose.material3.Icon(SelkieIcons.Check, null) })
            GroupItem("Previous", supporting = "Oct 30 · 9a1e77c0d4b2", onClick = {},
                trailing = { androidx.compose.material3.Icon(SelkieIcons.Chevron, null) })
            DetailItem("Image", Images.BLUEFIN, mono = true)
        }
        SelkieCard(tone = CardTone.Tertiary) {
            Text("Update available", style = MaterialTheme.typography.titleMedium)
            MutedText("Bluefin LTS has a newer version. Its signature is checked before anything is downloaded.")
        }
        ProgressTrack(0.58f)
    }
}

// --- Desktop controls (Tablet-Desktop), over a stand-in for the guest's desktop ---

@Composable
private fun DesktopControlsFrame(size: Pair<Int, Int>?) = SelkieTheme(dark = true, dynamicColor = false) {
    Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xFF101418))) {
        DesktopControls("default", size, onSuper = noop, onKeyboard = noop, onEsc = noop, onTerminal = noop,
            onBack = noop, onStop = noop, onHide = noop)
    }
}

@TabletPreview
@Composable
fun DesktopControlsTabletPreview() = DesktopControlsFrame(1280 to 800)

@Preview(name = "phone-landscape", device = "spec:width=891dp,height=411dp", showBackground = true)
@Preview(name = "phone-portrait", device = "spec:width=411dp,height=891dp", showBackground = true)
@Composable
fun DesktopControlsPhonePreview() = DesktopControlsFrame(null)

/** The desktop once its controls have gone: only the tab that brings them back. */
@Preview(name = "phone-portrait", device = "spec:width=411dp,height=891dp", showBackground = true)
@Composable
fun DesktopControlsHiddenPreview() = SelkieTheme(dark = true, dynamicColor = false) {
    Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xFF101418))) {
        DesktopControlsLayer(remember { DesktopControlsState(showing = false) }, onLeave = noop) {}
    }
}

@PhonePreviews
@Composable
fun StopDesktopDialogPreview() = PreviewFrame { StopDesktopDialog("default", onDismiss = noop, onConfirm = noop) }

// --- Images, Activity and Settings ---

@Composable
private fun InSection(section: Section, content: @Composable (rail: Boolean) -> Unit) = PreviewFrame {
    SectionScaffold(section, onSection = {}, onNewContainer = noop, content = content)
}

/** Two containers share Bluefin LTS; the selected one runs CentOS, installed without a signature check. */
private val imagesState = Fixtures.homeContainers.copy(containers = Fixtures.homeContainers.containers +
    ContainerInfo("work", current = ContainerDeployment("d4", Images.BLUEFIN)))

@PhonePreviews @TabletPreview
@Composable
fun ImagesPreview() = InSection(Section.Images) { rail -> ImagesScreen(imagesState, rail, onNewContainer = {}, onCleanUp = {}) }

@PhonePreviews
@Composable
fun ImagesEmptyPreview() = InSection(Section.Images) { rail -> ImagesScreen(Fixtures.homeEmpty, rail, onNewContainer = {}, onCleanUp = {}) }

private val activityState = Fixtures.homeInstalled.copy(sessions = listOf(
    SessionInfo("1", "bash", true), SessionInfo("2", "htop", true), SessionInfo("3", "kepler@dev: ~/src", true, container = "dev")))

@Composable
private fun Activity(state: ServiceState) = InSection(Section.Activity) { rail ->
    ActivityScreen(state, rail, onReturnToDesktop = {}, onStopDesktop = noop, onOpenTerminal = {}, onStopAll = noop,
        onDismissDesktopError = noop)
}

@PhonePreviews @TabletPreview
@Composable
fun ActivityPreview() = Activity(activityState)

@PhonePreviews
@Composable
fun ActivityEmptyPreview() = Activity(Fixtures.homeEmpty)

private val sudoRequest = io.github.keplersj.selkie.service.RootRequest(1, "default", "sudo",
    listOf("dnf", "install", "-y", "htop"), "/var/home/kepler", from = "bash", program = "/usr/bin/dnf")

@PhonePreviews
@Composable
fun ActivityRootRequestPreview() = Activity(activityState.copy(rootRequests = listOf(sudoRequest)))

@PhonePreviews @TabletPreview
@Composable
fun RootRequestPreview() = PreviewFrame {
    RootRequestScreen(sudoRequest, image = "Bluefin LTS", waiting = 0, onAnswer = {}, awake = true)
}

@PhonePreviews
@Composable
fun RootRequestUnnamedPreview() = PreviewFrame {
    RootRequestScreen(sudoRequest.copy(via = "pkexec", argv = listOf("/usr/libexec/helper"), from = null,
        program = "/usr/libexec/helper", cwd = "/"), image = null, waiting = 2, onAnswer = {}, awake = true)
}

@PhonePreviews
@Composable
fun ActivityDesktopFailedPreview() = Activity(Fixtures.homeEmpty.copy(
    desktop = DesktopState.Failed("gnome-shell exited with status 1", "")))

@Composable
private fun SettingsSection(phantom: PhantomProcessCheck.State, suFound: Boolean, notifications: Boolean = true) =
    InSection(Section.Settings) { rail ->
        SettingsContent(rail, phantom, notifications, usedBytes = 19_756_849_152, freeBytes = FREE, suFound = suFound,
            debugLogs = false, version = "0.2.0", onPhantomHelp = noop, onNotificationSettings = noop, onAbout = noop)
    }

@PhonePreviews @TabletPreview
@Composable
fun SettingsPreview() = SettingsSection(PhantomProcessCheck.State.OK, suFound = false)

@PhonePreviews
@Composable
fun SettingsRestrictedPreview() = SettingsSection(PhantomProcessCheck.State.RESTRICTED, suFound = true, notifications = false)

// --- TV (TV-Home, TV-New, TV-Session) ---

/** A 1080p TV: 960x540dp at xhdpi, dark as TV launchers are. */
@Preview(name = "tv", device = "spec:width=960dp,height=540dp,dpi=320", showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_TELEVISION)
annotation class TvPreview

private val tvRemote = TvInputs(gamepad = true)

@Composable
private fun TvHomeFrame(state: ServiceState, inputs: TvInputs = tvRemote) = PreviewFrame {
    TvScaffold(Section.Containers, onSection = {}, inputs = inputs) {
        TvHome(state, onSelectContainer = {}, onOpenDesktop = noop, onOpenTerminal = noop, onStopDesktop = noop,
            onChangeImage = noop, onNewContainer = noop, inputs = inputs)
    }
}

@TvPreview
@Composable
fun TvHomePreview() = TvHomeFrame(Fixtures.homeContainers.copy(apps = Fixtures.tvApps), TvInputs(gamepad = true, keyboard = true))

/** Nothing running: the selected container is featured with its terminal. */
@TvPreview
@Composable
fun TvHomeStoppedPreview() = TvHomeFrame(Fixtures.homeContainers.copy(sessions = emptyList(),
    desktop = DesktopState.Stopped, desktopContainer = null, graphical = Fixtures.noSession))

@TvPreview
@Composable
fun TvHomeEmptyPreview() = TvHomeFrame(Fixtures.homeEmpty, TvInputs())

@TvPreview
@Composable
fun TvImagesPreview() = PreviewFrame {
    TvScaffold(Section.Images, onSection = {}, inputs = tvRemote) {
        ImagesScreen(imagesState, rail = true, onNewContainer = {}, onCleanUp = {})
    }
}

@TvPreview
@Composable
fun TvPointerGatePreview() = PreviewFrame {
    TvPointerGate("GNOME", TvInputs(gamepad = true), onPair = noop, onOpenAnyway = noop)
}

/** An app that wants a pointer (Files), opened from the TV row with only a remote. */
@TvPreview
@Composable
fun TvPointerGateAppPreview() = PreviewFrame {
    TvPointerGate("GNOME", TvInputs(gamepad = true), onPair = noop, onOpenAnyway = noop, app = "Files")
}

@TvPreview
@Composable
fun TvNewContainerPreview() = PreviewFrame {
    NewContainerScreen(existing = listOf("default"), onCreate = { _, _, _ -> }, onClose = {}, free = FREE,
        device = DeviceClass.Tv)
}

// --- Car (parked-only) ---

@Preview(name = "car", device = "spec:width=1280dp,height=720dp,dpi=240", showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_CAR)
annotation class CarPreview

/** Parked: the tablet layout with what driving does, above the containers. */
@CarPreview
@Composable
fun CarHomePreview() = PreviewFrame {
    SectionScaffold(Section.Containers, onSection = {}, onNewContainer = noop) { rail ->
        HomeScreen(Fixtures.homeContainers, onOpenTerminal = noop, onOpenDesktop = noop, onConfigureDesktop = noop,
            onUpgrade = noop, onRollback = noop, onSwitch = noop, onRefresh = noop, newInList = !rail, free = FREE,
            listNote = { CarParkedNote() })
    }
}

/** Driving: nothing to press, only what keeps running. */
@CarPreview
@Composable
fun CarDrivingPreview() = PreviewFrame { CarDrivingScreen(Fixtures.homeContainers) }

@CarPreview
@Composable
fun CarDrivingRootRequestPreview() = PreviewFrame {
    CarDrivingScreen(Fixtures.homeContainers.copy(rootRequests = listOf(sudoRequest)))
}

@CarPreview
@Composable
fun CarNewContainerPreview() = PreviewFrame {
    NewContainerScreen(existing = listOf("default"), onCreate = { _, _, _ -> }, onClose = {}, free = FREE,
        device = DeviceClass.Car)
}

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
import io.github.keplersj.selkie.engine.ContainerDeployment
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

/** Harbor and Monk as if published, to show the design's preselection. */
private val publishedVariants = ImageCatalog.tuned.map {
    when (it.name) {
        "Harbor" -> it.copy(ref = "ghcr.io/droidc-dev/harbor:stable")
        "Monk" -> it.copy(ref = "ghcr.io/droidc-dev/monk:stable")
        else -> it
    }
}

@PhonePreviews @TabletPreview
@Composable
fun NewContainerPreview() = PreviewFrame {
    NewContainerScreen(existing = listOf("default"), onCreate = { _, _, _ -> }, onClose = noop, free = 96_636_764_160,
        device = deviceClassForWidth(androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp))
}

@PhonePreviews @TabletPreview
@Composable
fun NewContainerPublishedPreview() = PreviewFrame {
    NewContainerScreen(existing = listOf("default"), onCreate = { _, _, _ -> }, onClose = noop, free = 96_636_764_160,
        device = deviceClassForWidth(androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp),
        variants = publishedVariants)
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

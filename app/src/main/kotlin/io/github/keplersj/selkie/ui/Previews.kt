package io.github.keplersj.selkie.ui

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.keplersj.selkie.engine.ContainerDeployment
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
        graphical = gnome, desktop = DesktopState.Running(2560, 1600),
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
    val homeEmpty = ServiceState(statusLoaded = true)
    val homeError = ServiceState(statusLoaded = true,
        statusError = "droidc exited with code 101: state.json: unexpected end of file")

    val downloading = Operation.Running(OpKind.SWITCH, Images.BLUEFIN, phase = "download",
        current = 1_734_082_560, total = 4_080_218_112, unit = "bytes", message = "layer 23/41 sha256:7e1f…")
    val unpacking = Operation.Running(OpKind.UPGRADE, Images.BLUEFIN, phase = "unpack",
        current = 120_455, unit = "files")
    val signatureFailed = Operation.Failed(OpKind.SWITCH, "ghcr.io/example/my-bootc:latest", insecure = false,
        error = DroidcError(ErrorKind.SIGNATURE, "no matching signatures: policy requires sigstore for ghcr.io/example"),
        stderrTail = "INFO pulling ghcr.io/example/my-bootc:latest\nINFO manifest sha256:0a1b2c…\nERROR verify: no matching signatures")
    val storageFailed = Operation.Failed(OpKind.SWITCH, Images.BLUEFIN, insecure = false,
        error = DroidcError(ErrorKind.STORAGE, "ENOSPC while writing usr/lib64/libLLVM.so.19"), stderrTail = "")
    val networkFailed = Operation.Failed(OpKind.UPGRADE, Images.BLUEFIN, insecure = false,
        error = DroidcError(ErrorKind.NETWORK, "connection reset by peer (ghcr.io:443)"), stderrTail = "")
    val done = Operation.Done(OpKind.SWITCH, "Installed ghcr.io/projectbluefin/bluefin-lts:stable (4f9c2a7be1d0).")
    val cancelled = Operation.Cancelled(OpKind.SWITCH)
}

private val noop: () -> Unit = {}

// --- Home ---

@Composable
private fun Home(state: ServiceState) = PreviewFrame {
    HomeScreen(state, onOpenTerminal = noop, onOpenDesktop = noop, onConfigureDesktop = noop, onUpgrade = noop,
        onRollback = noop, onSwitch = noop, onAbout = noop, onRefresh = noop)
}

@PhonePreviews @TabletPreview
@Composable
fun HomeInstalledPreview() = Home(Fixtures.homeInstalled)

@PhonePreviews @TabletPreview
@Composable
fun HomeContainersPreview() = Home(Fixtures.homeContainers)

@PhonePreviews
@Composable
fun HomeInsecurePreview() = Home(Fixtures.homeInsecure)

@PhonePreviews
@Composable
fun HomeEmptyPreview() = Home(Fixtures.homeEmpty)

@PhonePreviews
@Composable
fun HomeErrorPreview() = Home(Fixtures.homeError)

// --- Setup ---

@PhonePreviews @TabletPreview
@Composable
fun SetupFirstRunPreview() = PreviewFrame {
    SetupScreen("Welcome to Selkie", onStart = { _, _ -> }, onBack = null, free = 48_318_382_080)
}

@PhonePreviews
@Composable
fun SetupCustomPreview() = PreviewFrame {
    SetupScreen("Switch image", initialRef = "ghcr.io/example/my-bootc:latest", onStart = { _, _ -> },
        onBack = noop, free = 6_442_450_944)
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
private fun Progress(op: Operation) = PreviewFrame { ProgressScreen(op, noop, {}, {}, noop, free = 1_288_490_188) }

@PhonePreviews @TabletPreview
@Composable
fun ProgressDownloadingPreview() = Progress(Fixtures.downloading)

@PhonePreviews
@Composable
fun ProgressUnpackingPreview() = Progress(Fixtures.unpacking)

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

@PhonePreviews @TabletPreview
@Composable
fun TerminalChromePreview() = PreviewFrame {
    Column(Modifier.fillMaxSize()) {
        SessionBar(listOf("bash", "htop", "kepler@bluefin: ~/src"), selected = 0,
            onSelect = {}, onBack = noop, onNew = noop, onClose = noop)
        Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black), contentAlignment = Alignment.TopStart) {
            Text("kepler@bluefin:~$ ▊", color = Color(0xFFD0D0D0), fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(8.dp))
        }
        ExtraKeys(ctrlOn = true, altOn = false, onCtrl = noop, onAlt = noop, send = {}, paste = noop, appCursor = { false })
    }
}

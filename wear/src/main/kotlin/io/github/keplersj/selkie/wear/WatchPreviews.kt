package io.github.keplersj.selkie.wear

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import io.github.keplersj.selkie.watch.WatchContainer
import io.github.keplersj.selkie.watch.WatchDesktop
import io.github.keplersj.selkie.watch.WatchRootRequest

// Each @Preview here is a screenshot test (wear/screenshots/). The round mask stands in for a round watch.

@Preview(name = "round", device = "spec:width=227dp,height=227dp,dpi=320", showBackground = true,
    backgroundColor = 0xFF000000)
annotation class WatchPreview

private val tablet = PhoneState(
    "Kepler's tablet",
    listOf(
        WatchContainer("default", WatchDesktop.Running, 0),
        WatchContainer("dev", WatchDesktop.None, 1),
        WatchContainer("scratch"),
    ),
)

@Composable
private fun Round(content: @Composable () -> Unit) = WatchTheme {
    Box(Modifier.fillMaxSize().clip(CircleShape).background(Color.Black)) { content() }
}

@WatchPreview @Composable
fun WatchHomePreview() = Round { WatchHome(tablet, loaded = true, onOpen = {}) }

@WatchPreview @Composable
fun WatchNoPhonePreview() = Round { WatchHome(null, loaded = true, onOpen = {}) }

@WatchPreview @Composable
fun WatchContainerPreview() = Round {
    WatchContainerScreen(tablet.device, tablet.containers[0], onStopDesktop = {}, onCloseTerminals = {},
        onOpenOnPhone = {})
}

/** Scrolled to the end after Open on phone, which says where to look. */
@WatchPreview @Composable
fun WatchOpenedOnPhonePreview() = Round {
    WatchContainerScreen(tablet.device, tablet.containers[2], onStopDesktop = {}, onCloseTerminals = {},
        onOpenOnPhone = {}, initialOpened = true)
}

@WatchPreview @Composable
fun WatchConfirmPreview() = Round {
    WatchContainerScreen(tablet.device, WatchContainer("dev", WatchDesktop.None, 2), onStopDesktop = {},
        onCloseTerminals = {}, initialConfirm = WatchAction.CloseTerminals)
}

private val sudo = WatchRootRequest("default", 1, "dnf install -y htop")

@WatchPreview @Composable
fun WatchRootPreview() = Round { WatchRootScreen(tablet.device, sudo, onRefuse = {}, onLater = {}) }

@WatchPreview @Composable
fun WatchHomeRootPreview() = Round { WatchHome(tablet.copy(rootRequests = listOf(sudo)), loaded = true, onOpen = {}) }

@WatchPreview @Composable
fun WatchIdlePreview() = Round {
    WatchContainerScreen(tablet.device, tablet.containers[2], onStopDesktop = {}, onCloseTerminals = {})
}

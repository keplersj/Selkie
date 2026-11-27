package io.github.keplersj.selkie.wear

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.wear.compose.foundation.BasicSwipeToDismissBox
import androidx.wear.remote.interactions.RemoteActivityHelper
import io.github.keplersj.selkie.watch.WatchProtocol

class WatchActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val link = PhoneLink(this)
        val remote = RemoteActivityHelper(this)
        setContent {
            WatchTheme {
                WatchApp(link) { node, container ->
                    // Opens Selkie on the phone at this container. A browsable VIEW link is what a watch may start
                    // there; if the phone can't take it, the button's line still says where to look.
                    val intent = Intent(Intent.ACTION_VIEW)
                        .addCategory(Intent.CATEGORY_BROWSABLE)
                        .setData(Uri.parse(WatchProtocol.containerLink(container)))
                    runCatching { remote.startRemoteActivity(intent, node) }
                }
            }
        }
    }
}

@Composable
private fun WatchApp(link: PhoneLink, openOnPhone: (node: String, container: String) -> Unit) {
    var phone by remember { mutableStateOf<PhoneState?>(null) }
    var loaded by remember { mutableStateOf(false) }
    DisposableEffect(link) {
        val watching = link.watch { phone = it; loaded = true }
        onDispose { watching.close() }
    }
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    val current = phone
    // A container the phone no longer lists closes its screen.
    val shown = current?.containers?.firstOrNull { it.name == open }
    BackHandler(enabled = shown != null) { open = null }
    BasicSwipeToDismissBox(onDismissed = { open = null }, userSwipeEnabled = shown != null) { isBackground ->
        if (isBackground || shown == null || current == null) {
            WatchHome(current, loaded, onOpen = { open = it })
        } else {
            WatchContainerScreen(
                current.device, shown,
                onStopDesktop = { current.node?.let(link::stopDesktop) },
                onCloseTerminals = { current.node?.let { link.closeTerminals(it, shown.name) } },
                onOpenOnPhone = current.node?.let { node -> { openOnPhone(node, shown.name) } },
            )
        }
    }
}

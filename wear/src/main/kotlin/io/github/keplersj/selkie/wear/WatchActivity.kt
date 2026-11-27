package io.github.keplersj.selkie.wear

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

class WatchActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val link = PhoneLink(this)
        setContent { WatchTheme { WatchApp(link) } }
    }
}

@Composable
private fun WatchApp(link: PhoneLink) {
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
            )
        }
    }
}

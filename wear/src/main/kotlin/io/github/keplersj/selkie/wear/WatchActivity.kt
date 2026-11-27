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
    // Root requests left for the phone with Later, as container and id. They stay listed on Home.
    var later by remember { mutableStateOf(setOf<Pair<String, Long>>()) }
    var openRoot by remember { mutableStateOf<Pair<String, Long>?>(null) }
    val current = phone
    // A container the phone no longer lists closes its screen.
    val shown = current?.containers?.firstOrNull { it.name == open }
    // A new request comes up by itself; one that the phone settled goes away.
    val root = current?.rootRequests?.firstOrNull { (it.container to it.id) == openRoot }
        ?: current?.rootRequests?.firstOrNull { (it.container to it.id) !in later }
    val leaveRoot = { r: io.github.keplersj.selkie.watch.WatchRootRequest -> later = later + (r.container to r.id); openRoot = null }
    BackHandler(enabled = root != null) { root?.let(leaveRoot) }
    BackHandler(enabled = root == null && shown != null) { open = null }
    BasicSwipeToDismissBox(onDismissed = { if (root != null) leaveRoot(root) else open = null },
        userSwipeEnabled = root != null || shown != null) { isBackground ->
        if (!isBackground && root != null && current != null) {
            WatchRootScreen(current.device, root,
                onRefuse = { current.node?.let { link.refuseRoot(it, root.container, root.id) }; leaveRoot(root) },
                onLater = { leaveRoot(root) })
        } else if (isBackground || shown == null || current == null) {
            WatchHome(current, loaded, onOpen = { open = it }, onOpenRoot = { openRoot = it.container to it.id })
        } else {
            WatchContainerScreen(
                current.device, shown,
                onStopDesktop = { current.node?.let(link::stopDesktop) },
                onCloseTerminals = { current.node?.let { link.closeTerminals(it, shown.name) } },
            )
        }
    }
}

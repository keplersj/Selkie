package io.github.keplersj.selkie.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import io.github.keplersj.selkie.engine.Deployment
import io.github.keplersj.selkie.engine.EngineEnv
import io.github.keplersj.selkie.service.DesktopState
import io.github.keplersj.selkie.service.ServiceState

@Composable
fun HomeScreen(
    state: ServiceState,
    onOpenTerminal: () -> Unit,
    onOpenDesktop: () -> Unit,
    onConfigureDesktop: () -> Unit,
    onUpgrade: () -> Unit,
    onRollback: () -> Unit,
    onSwitch: () -> Unit,
    onAbout: () -> Unit,
    onRefresh: () -> Unit,
    onSelectContainer: (String) -> Unit = {},
    /** Opens the new container screen. */
    onNewContainer: () -> Unit = {},
    onRemoveContainer: (name: String, onDone: (String?) -> Unit) -> Unit = { _, _ -> },
    onRootMode: (Boolean) -> Unit = {},
) {
    var confirmRollback by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    var removeError by remember { mutableStateOf<String?>(null) }
    val cur = state.status?.current
    val prev = state.status?.previous
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Selkie", style = MaterialTheme.typography.headlineMedium)
        ContainerRow(state, onSelectContainer, onNew = onNewContainer)
        removeError?.let {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Text("Could not remove the container: $it", Modifier.padding(12.dp))
            }
        }
        if (state.statusError != null) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Text("Could not read status: ${state.statusError}", Modifier.padding(12.dp))
            }
        }
        if (cur != null) DeploymentCard("Current", cur) else if (state.statusLoaded) Text("Nothing installed.")
        if (prev != null) DeploymentCard("Previous", prev)

        Button(onClick = onOpenTerminal, enabled = cur != null, modifier = Modifier.fillMaxWidth()) {
            Text(if (state.sessions.isNotEmpty()) "Open terminal (${state.sessions.size} running)" else "Open terminal")
        }
        val graphical = state.graphical
        if (cur != null && graphical != null) {
            if (graphical.configured) {
                val other = state.desktopContainer?.takeIf { it != state.container }
                Button(onClick = onOpenDesktop, modifier = Modifier.fillMaxWidth()) {
                    Text(when {
                        other != null -> "Return to desktop ($other)"
                        state.desktop is DesktopState.Running || state.desktop is DesktopState.Starting -> "Return to desktop"
                        else -> "Open desktop" + (graphical.name?.let { " ($it)" } ?: "")
                    })
                }
                // One compositor, so one desktop at a time.
                if (other != null) Text("Stop the desktop in $other to open this container's desktop.",
                    style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onConfigureDesktop) { Text("Desktop session settings") }
            } else {
                OutlinedButton(onClick = onConfigureDesktop, modifier = Modifier.fillMaxWidth()) { Text("Set up a desktop session") }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onUpgrade, enabled = cur != null) { Text("Upgrade") }
            OutlinedButton(onClick = { confirmRollback = true }, enabled = prev != null) { Text("Rollback") }
            OutlinedButton(onClick = onSwitch) { Text("Change image") }
        }
        if (state.suFound || state.rootMode) RootModeCard(state, onRootMode)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onRefresh) { Text("Refresh") }
            TextButton(onClick = onAbout) { Text("About") }
            if (state.container != EngineEnv.DEFAULT_CONTAINER) {
                TextButton(onClick = { confirmRemove = true }) { Text("Remove container") }
            }
        }
    }
    if (confirmRemove) {
        val name = state.container
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove $name?") },
            text = { Text("This deletes the container's deployments and its /home and /var, and closes its " +
                "terminals and desktop. Images other containers use are kept.") },
            confirmButton = { TextButton(onClick = {
                confirmRemove = false; removeError = null
                onRemoveContainer(name) { removeError = it }
            }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
        )
    }
    if (confirmRollback) {
        AlertDialog(
            onDismissRequest = { confirmRollback = false },
            title = { Text("Roll back?") },
            text = { Text("Swap back to the previous deployment (${prev?.imageRef} ${prev?.shortDigest}). " +
                "Your files in /home and /var are kept. Close open terminals first.") },
            confirmButton = { TextButton(onClick = { confirmRollback = false; onRollback() }) { Text("Roll back") } },
            dismissButton = { TextButton(onClick = { confirmRollback = false }) { Text("Cancel") } },
        )
    }
}

/** Shown only where an su binary exists (or root mode is already on). */
@Composable
private fun RootModeCard(state: ServiceState, onRootMode: (Boolean) -> Unit) {
    val busy = state.rootProbing || state.operation is io.github.keplersj.selkie.service.Operation.Running
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Root mode", style = MaterialTheme.typography.titleMedium)
                    Text("Runs Linux natively instead of under proot: much faster, real file owners " +
                        "(sudo works), systemd user services. Needs Magisk, KernelSU or APatch. " +
                        "Linux programs then run as root on this device.",
                        style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = state.rootMode, enabled = !busy && state.sessions.none { it.running && it.container == state.container },
                    onCheckedChange = onRootMode)
            }
            if (state.sessions.any { it.running }) Text("Close open terminals to change this.",
                style = MaterialTheme.typography.bodySmall)
            state.rootMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary) }
        }
    }
}

@Composable
private fun DeploymentCard(label: String, d: Deployment) {
    val clipboard = LocalClipboardManager.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(label, style = MaterialTheme.typography.titleMedium)
                if (d.insecure) AssistChip(onClick = {}, label = { Text("INSECURE") },
                    colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.errorContainer))
                if (d.imageOwned) AssistChip(onClick = {}, label = { Text("Image owners (root)") })
            }
            Text(d.imageRef, style = MaterialTheme.typography.bodyLarge)
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("Digest ${d.shortDigest}", style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { clipboard.setText(AnnotatedString(d.manifestDigest)) }) { Text("Copy") }
            }
            val sig = d.signature
            Text("Signature: " + when {
                sig == null -> "unknown"
                sig.verified -> "verified (${sig.mode ?: "?"})" + (sig.subject?.let { ", $it" } ?: "")
                else -> "not verified"
            }, style = MaterialTheme.typography.bodySmall)
            d.sessionLabel?.let { Text("Session: $it", style = MaterialTheme.typography.bodySmall) }
            d.deployedAt?.let { Text("Deployed: $it", style = MaterialTheme.typography.bodySmall) }
            d.stats?.let {
                Text("On disk: ${formatBytes(it.bytesOnDisk)} · ${it.files} files · ${it.hardlinks} hard links " +
                    "(${it.linkStrategy ?: "?"})", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** The containers under the droidc root, as chips: tap one to show it, or make a new one. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ContainerRow(state: ServiceState, onSelect: (String) -> Unit, onNew: () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (c in state.containers) {
            val running = state.sessions.count { it.container == c.name && it.running } +
                (if (state.desktopContainer == c.name) 1 else 0)
            FilterChip(
                selected = c.name == state.container,
                onClick = { onSelect(c.name) },
                label = { Text(if (running > 0) "${c.name} ($running)" else c.name) },
            )
        }
        AssistChip(onClick = onNew, label = { Text("New container") })
    }
}

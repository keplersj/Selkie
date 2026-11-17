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
import io.github.keplersj.selkie.service.ServiceState

@Composable
fun HomeScreen(
    state: ServiceState,
    onOpenTerminal: () -> Unit,
    onUpgrade: () -> Unit,
    onRollback: () -> Unit,
    onSwitch: () -> Unit,
    onAbout: () -> Unit,
    onRefresh: () -> Unit,
) {
    var confirmRollback by remember { mutableStateOf(false) }
    val cur = state.status?.current
    val prev = state.status?.previous
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Selkie", style = MaterialTheme.typography.headlineMedium)
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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onUpgrade, enabled = cur != null) { Text("Upgrade") }
            OutlinedButton(onClick = { confirmRollback = true }, enabled = prev != null) { Text("Rollback") }
            OutlinedButton(onClick = onSwitch) { Text("Switch image") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onRefresh) { Text("Refresh") }
            TextButton(onClick = onAbout) { Text("About") }
        }
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

@Composable
private fun DeploymentCard(label: String, d: Deployment) {
    val clipboard = LocalClipboardManager.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(label, style = MaterialTheme.typography.titleMedium)
                if (d.insecure) AssistChip(onClick = {}, label = { Text("INSECURE") },
                    colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.errorContainer))
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

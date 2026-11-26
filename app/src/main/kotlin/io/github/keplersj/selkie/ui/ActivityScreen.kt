package io.github.keplersj.selkie.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.keplersj.selkie.service.DesktopState
import io.github.keplersj.selkie.service.ServiceState
import io.github.keplersj.selkie.service.SessionInfo

/** What the Activity screen lists: the desktop, if one starts or runs, and the open terminals. */
internal data class ActivityItems(
    /** The container whose desktop is starting or running, or null. */
    val desktop: String?,
    val desktopStarting: Boolean,
    /** The desktop's session name, where Selkie knows it (only for the selected container's). */
    val desktopName: String?,
    val terminals: List<SessionInfo>,
) {
    val empty: Boolean get() = desktop == null && terminals.isEmpty()
}

internal fun activityItems(state: ServiceState): ActivityItems {
    val live = state.desktop is DesktopState.Running || state.desktop is DesktopState.Starting
    val desktop = state.desktopContainer?.takeIf { live }
    return ActivityItems(
        desktop = desktop,
        desktopStarting = state.desktop is DesktopState.Starting,
        desktopName = state.graphical?.name?.takeIf { desktop != null && desktop == state.container },
        terminals = state.sessions.filter { it.running },
    )
}

/** Activity: everything Selkie keeps alive, with a way back to each and a way to stop it. */
@Composable
fun ActivityScreen(
    state: ServiceState,
    rail: Boolean,
    /** Opens the running desktop of this container. */
    onReturnToDesktop: (container: String) -> Unit,
    onStopDesktop: () -> Unit,
    /** Opens the terminal screen on this session's container. */
    onOpenTerminal: (SessionInfo) -> Unit,
    onStopAll: () -> Unit,
    onDismissDesktopError: () -> Unit,
) {
    val items = activityItems(state)
    var stopping by remember { mutableStateOf<String?>(null) }
    var stoppingAll by remember { mutableStateOf(false) }
    val failed = state.desktop as? DesktopState.Failed

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.widthIn(max = 840.dp).fillMaxWidth().align(Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionHeader("Activity", "Everything Selkie is keeping alive", rail, action = {
                if (!items.empty) TextButton(onClick = { stoppingAll = true }) { Text("Stop all…") }
            })

            failed?.let {
                SelkieCard(Modifier.fillMaxWidth(), tone = CardTone.Error) {
                    Text("The desktop stopped", style = MaterialTheme.typography.titleMedium)
                    Text(it.message, Modifier.padding(top = 4.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onDismissDesktopError) { Text("Dismiss") }
                    }
                }
            }

            if (items.empty && failed == null) SelkieCard(Modifier.fillMaxWidth(), tone = CardTone.Outlined) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Mark(style = MarkStyle.Neutral) { Icon(SelkieIcons.Activity, contentDescription = null) }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Nothing is running", style = MaterialTheme.typography.titleMedium)
                        MutedText("Terminals and desktops you open show here, so you can get back to them.")
                    }
                }
            }

            items.desktop?.let { container ->
                SelkieCard(Modifier.fillMaxWidth()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Mark { Icon(SelkieIcons.Desktop, contentDescription = null) }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(listOfNotNull("Desktop", items.desktopName).joinToString(" · "),
                                style = MaterialTheme.typography.titleMedium)
                            MutedText("$container · ${if (items.desktopStarting) "starting" else "running"}")
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                        OutlinedButton(onClick = { stopping = container },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                            Icon(SelkieIcons.Stop, contentDescription = null, Modifier.size(18.dp))
                            Text("Stop…", Modifier.padding(start = 8.dp))
                        }
                        Button(onClick = { onReturnToDesktop(container) }, enabled = !items.desktopStarting) {
                            Text("Return")
                        }
                    }
                }
                MutedText("One desktop runs at a time. Stop this one to open another container's.",
                    Modifier.padding(horizontal = 4.dp))
            }

            if (items.terminals.isNotEmpty()) {
                SectionTitle("Terminals", Modifier.padding(top = 12.dp))
                Group {
                    for (t in items.terminals) GroupItem(t.title,
                        supporting = t.container,
                        leading = { Icon(SelkieIcons.Terminal, contentDescription = null) },
                        trailing = { Icon(SelkieIcons.Chevron, contentDescription = null) },
                        onClick = { onOpenTerminal(t) })
                }
            }
        }
    }

    stopping?.let { container ->
        StopDesktopDialog(container, onDismiss = { stopping = null }, onConfirm = { stopping = null; onStopDesktop() })
    }
    if (stoppingAll) {
        val n = items.terminals.size
        val what = listOfNotNull(
            if (items.desktop != null) "the desktop" else null,
            if (n > 0) "$n terminal" + if (n == 1) "" else "s" else null,
        ).joinToString(" and ")
        AlertDialog(
            onDismissRequest = { stoppingAll = false },
            icon = { Icon(SelkieIcons.Stop, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Stop everything?") },
            text = { Text("Selkie stops ${what}. Anything unsaved in them is lost. Containers and their files stay.") },
            confirmButton = {
                TextButton(onClick = { stoppingAll = false; onStopAll() },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Stop all") }
            },
            dismissButton = { TextButton(onClick = { stoppingAll = false }) { Text("Cancel") } },
        )
    }
}

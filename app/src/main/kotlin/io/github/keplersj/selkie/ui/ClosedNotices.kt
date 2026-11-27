package io.github.keplersj.selkie.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.keplersj.selkie.service.Closed
import io.github.keplersj.selkie.service.closedLine
import io.github.keplersj.selkie.service.closedTitle

/**
 * Under the containers list's heading: a card for each container whose sessions Android closed, then why
 * it happens and what fixes it (Phone-Killed in the redesign).
 */
@Composable
fun ClosedNotices(
    closed: List<Closed>,
    /** Android's child process limit is on now. */
    phantomRestricted: Boolean,
    /** Battery optimisation applies to Selkie. */
    batteryLimited: Boolean,
    onDismiss: (Closed) -> Unit,
    onStartAgain: (Closed) -> Unit,
    onFixPhantom: () -> Unit,
    onBattery: () -> Unit,
    /** Formats [Closed.at] as a time of day for the person. */
    time: (Long) -> String,
) {
    if (closed.isEmpty()) return
    var log by remember { mutableStateOf<Closed?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        for (c in closed) SelkieCard(Modifier.fillMaxWidth(), tone = CardTone.Error,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
                Icon(SelkieIcons.Warn, contentDescription = null)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(closedTitle(c), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
                    Text(closedLine(c, c.at?.let(time)), style = MaterialTheme.typography.bodyMedium)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                TextButton(onClick = { onDismiss(c) }) {
                    Text("Dismiss", color = MaterialTheme.colorScheme.onErrorContainer)
                }
                Button(onClick = { onStartAgain(c) }) { Text("Start again") }
            }
        }

        val logs = closed.filter { it.logTail.isNotBlank() }
        if (phantomRestricted || batteryLimited || logs.isNotEmpty()) {
            SectionTitle("Why this happens", Modifier.padding(top = 4.dp))
            Group {
                if (phantomRestricted) GroupItem("Child process restrictions are back on",
                    supporting = "Turning Developer options off resets it. Fix it to stop this happening.",
                    leading = { Icon(SelkieIcons.Warn, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                    trailing = { Icon(SelkieIcons.Chevron, contentDescription = null) },
                    onClick = onFixPhantom)
                if (batteryLimited) GroupItem("Battery saving may stop Selkie",
                    supporting = "Allow Selkie to run in the background without limits",
                    leading = { Icon(SelkieIcons.Device, contentDescription = null) },
                    trailing = { Icon(SelkieIcons.Open, contentDescription = null) },
                    onClick = onBattery, onClickLabel = "Open battery settings")
                for (c in logs) GroupItem("See what was running",
                    supporting = "The last lines from ${c.container}'s log",
                    leading = { Icon(SelkieIcons.Logs, contentDescription = null) },
                    trailing = { Icon(SelkieIcons.Chevron, contentDescription = null) },
                    onClick = { log = c })
            }
        }
    }

    log?.let { c ->
        AlertDialog(
            onDismissRequest = { log = null },
            icon = { Icon(SelkieIcons.Logs, contentDescription = null) },
            title = { Text("${c.container}'s log") },
            text = {
                Text(c.logTail, Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), style = MonoStyle)
            },
            confirmButton = { TextButton(onClick = { log = null }) { Text("Close") } },
        )
    }
}

/** [at] as a time of day in the person's own 12- or 24-hour clock. */
fun closedTime(context: android.content.Context, at: Long): String =
    android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(at))

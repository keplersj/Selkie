package io.github.keplersj.selkie.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.keplersj.selkie.engine.ErrorKind
import io.github.keplersj.selkie.service.Operation

@Composable
fun ProgressScreen(
    op: Operation,
    onCancel: () -> Unit,
    onRetry: (Operation.Failed) -> Unit,
    onRetryInsecure: (Operation.Failed) -> Unit,
    onDismiss: () -> Unit,
    free: Long = if ((op as? Operation.Failed)?.error?.kind == ErrorKind.STORAGE) freeBytes() else 0L,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (op) {
            is Operation.Running -> {
                Text("${op.kind.label}…", style = MaterialTheme.typography.headlineMedium)
                op.ref?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                Text("Phase: ${op.phase}", style = MaterialTheme.typography.titleMedium)
                val total = op.total
                val unit = op.unit
                if (total != null && total > 0) {
                    LinearProgressIndicator(progress = { (op.current.toFloat() / total).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth())
                    Text(if (unit == "bytes") "${formatBytes(op.current)} / ${formatBytes(total)}"
                         else "${op.current} / $total ${unit ?: ""}")
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (op.current > 0) Text(if (unit == "bytes") formatBytes(op.current) else "${op.current} ${unit ?: ""}")
                }
                op.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Text("You can leave the app; the work continues in a notification.",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = onCancel) { Text("Cancel") }
            }
            is Operation.Failed -> ErrorCard(op, free, onRetry, onRetryInsecure, onDismiss)
            is Operation.Done -> {
                Text("Done", style = MaterialTheme.typography.headlineMedium)
                Text(op.summary)
                Button(onClick = onDismiss) { Text("Continue") }
            }
            is Operation.Cancelled -> {
                Text("Cancelled", style = MaterialTheme.typography.headlineMedium)
                Text("droidc was stopped. It is crash-safe: partial work is cleaned up on the next run.")
                Button(onClick = onDismiss) { Text("OK") }
            }
            Operation.Idle -> {}
        }
    }
}

@Composable
private fun ErrorCard(op: Operation.Failed, free: Long, onRetry: (Operation.Failed) -> Unit,
                      onRetryInsecure: (Operation.Failed) -> Unit, onDismiss: () -> Unit) {
    val e = op.error
    val (title, help) = when (e.kind) {
        ErrorKind.NETWORK -> "Network problem" to
            "Check your connection (and that Wi-Fi or data works), then retry. Downloads resume from cached layers."
        ErrorKind.SIGNATURE -> "Signature verification failed" to
            "The image's signature could not be verified against the built-in trust policy, so it was not installed."
        ErrorKind.STORAGE ->
            "Not enough storage" to "Free space available: ${formatBytes(free)}. Free up space and retry. " +
                "Details from droidc: ${e.message}"
        ErrorKind.NOT_FOUND -> "Not found" to "The image or tag was not found. Check the reference."
        ErrorKind.INVALID -> "Invalid request" to "droidc rejected the request."
        ErrorKind.LOCKED -> "Busy" to "Another droidc operation holds the lock. Wait for it to finish, then retry."
        else -> "Something went wrong" to "An internal error occurred."
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(help)
            Text("[${e.kind}] ${e.message}", style = MaterialTheme.typography.bodySmall)
        }
    }
    if (op.stderrTail.isNotBlank()) {
        Text("Recent engine output", style = MaterialTheme.typography.titleSmall)
        Text(op.stderrTail.takeLast(2000), style = MaterialTheme.typography.bodySmall,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
    }
    Button(onClick = { onRetry(op) }) { Text("Retry") }
    // Only for refs the user typed themselves: never offered for Bluefin or the built-in testing image.
    val offerInsecure = e.kind == ErrorKind.SIGNATURE && !op.insecure && op.ref != null && !Images.isKnown(op.ref)
    if (offerInsecure) {
        OutlinedButton(onClick = { onRetryInsecure(op) }) { Text("Retry without verification (insecure)") }
    }
    TextButton(onClick = onDismiss) { Text("Back") }
}

package io.github.keplersj.selkie.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.keplersj.selkie.engine.ErrorKind
import io.github.keplersj.selkie.service.OpKind
import io.github.keplersj.selkie.service.Operation
import java.util.Locale

/** The four steps the canvas shows for an install or an update, in droidc's phase order. */
internal enum class InstallStep(val title: String) {
    Signature("Check the signature"), Download("Download"), Unpack("Unpack"), Activate("Make it current");

    companion object {
        /** droidc's phases: resolve, verify, download, unpack, etc-merge, deploy, gc (and ownership in root mode). */
        fun of(phase: String): InstallStep = when (phase) {
            "starting", "resolve", "verify" -> Signature
            "download" -> Download
            "unpack" -> Unpack
            else -> Activate
        }
    }
}

private val OpKind.stepped: Boolean get() = this == OpKind.SWITCH || this == OpKind.UPGRADE

/** "Creating dev", "Updating dev", ... */
internal fun runningTitle(kind: OpKind, container: String, creating: Boolean): String = when (kind) {
    OpKind.SWITCH -> if (creating) "Creating $container" else "Changing $container's image"
    OpKind.UPGRADE -> "Updating $container"
    OpKind.ROLLBACK -> "Going back in $container"
    OpKind.FIX_OWNERSHIP -> "Turning on root mode for $container"
    OpKind.UNDO_OWNERSHIP -> "Turning off root mode for $container"
}

internal fun failedTitle(kind: OpKind, container: String, creating: Boolean): String = when (kind) {
    OpKind.SWITCH -> if (creating) "Couldn't create $container" else "Couldn't change $container's image"
    OpKind.UPGRADE -> "Couldn't update $container"
    OpKind.ROLLBACK -> "Couldn't go back in $container"
    OpKind.FIX_OWNERSHIP -> "Couldn't turn on root mode"
    OpKind.UNDO_OWNERSHIP -> "Couldn't turn off root mode"
}

/** An install's progress, its result, or why it failed and what to do next. */
@Composable
fun ProgressScreen(
    op: Operation,
    onCancel: () -> Unit,
    onRetry: (Operation.Failed) -> Unit,
    onRetryInsecure: (Operation.Failed) -> Unit,
    onDismiss: () -> Unit,
    free: Long = if ((op as? Operation.Failed)?.error?.kind == ErrorKind.STORAGE) freeBytes() else 0L,
    /** The container had no image before this operation, so this install creates it. */
    creating: Boolean = when (op) {
        is Operation.Running -> op.creating
        is Operation.Failed -> op.creating
        is Operation.Done -> op.creating
        else -> false
    },
    /** Opens the image picker again after a signature failure. Without it, Back is offered instead. */
    onChooseImage: ((Operation.Failed) -> Unit)? = null,
    /** `droidc gc`; reports an error message, or null. */
    onCleanUp: ((done: (String?) -> Unit) -> Unit)? = null,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (op) {
                is Operation.Running -> Running(op, creating, onCancel)
                is Operation.Failed -> Failed(op, free, creating, onRetry, onRetryInsecure, onDismiss, onChooseImage, onCleanUp)
                is Operation.Done -> Finished(
                    title = when (op.kind) {
                        OpKind.SWITCH -> if (creating) "${op.container} is ready" else "${op.container} has its new image"
                        OpKind.UPGRADE -> "${op.container} is up to date"
                        OpKind.ROLLBACK -> "${op.container} went back"
                        OpKind.FIX_OWNERSHIP -> "Root mode is on"
                        OpKind.UNDO_OWNERSHIP -> "Root mode is off"
                    },
                    body = when {
                        op.kind == OpKind.SWITCH && creating ->
                            "It's on your containers list. Open a terminal or its desktop from there."
                        op.kind in setOf(OpKind.SWITCH, OpKind.UPGRADE, OpKind.ROLLBACK) ->
                            "The previous version is kept, so you can go back from the Updates tab."
                        else -> null
                    },
                    icon = SelkieIcons.Check, button = "Done", onDismiss = onDismiss)
                is Operation.Cancelled -> Finished(
                    title = "Stopped",
                    body = "Nothing changed in the container. What was already downloaded is kept, so trying again picks up from there.",
                    icon = SelkieIcons.Block, mark = MarkStyle.Neutral, button = "Back", onDismiss = onDismiss)
                Operation.Idle -> {}
            }
        }
    }
}

@Composable
private fun Header(title: String, supporting: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineMedium)
        supporting?.let { MutedText(it, style = MaterialTheme.typography.bodyLarge) }
    }
}

@Composable
private fun Running(op: Operation.Running, creating: Boolean, onCancel: () -> Unit) {
    var confirmCancel by remember { mutableStateOf(false) }
    val step = InstallStep.of(op.phase)
    val total = op.total?.takeIf { it > 0 }
    val fraction = total?.let { (op.current.toFloat() / it).coerceIn(0f, 1f) }

    Header(runningTitle(op.kind, op.container, creating),
        if (op.kind.stepped) "Step ${step.ordinal + 1} of ${InstallStep.entries.size} · ${step.title}" else op.message)

    SelkieCard(Modifier.fillMaxWidth()) {
        if (fraction != null) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${(fraction * 100).toInt()}%", style = MaterialTheme.typography.displaySmall)
                MutedText(amount(op.current, total, op.unit), Modifier.padding(bottom = 6.dp))
            }
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().height(8.dp))
        } else {
            if (op.current > 0) Text(amount(op.current, null, op.unit), style = MaterialTheme.typography.titleLarge)
            LinearProgressIndicator(Modifier.fillMaxWidth().height(8.dp))
        }
        op.ref?.let { Text(it, style = MonoStyle, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }

    if (op.kind.stepped) Group {
        for (s in InstallStep.entries) {
            val state = when {
                s.ordinal < step.ordinal -> StepState.Done
                s == step -> StepState.Now
                else -> StepState.Later
            }
            StepItem(s, state, op)
        }
    }

    MutedText("You can leave this screen or lock the device. Progress stays in the notification.")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        OutlinedButton(onClick = { confirmCancel = true }) { Text("Cancel…") }
    }

    if (confirmCancel) AlertDialog(
        onDismissRequest = { confirmCancel = false },
        title = { Text("Stop ${runningTitle(op.kind, op.container, creating).replaceFirstChar { it.lowercase() }}?") },
        text = { Text("Nothing in the container changes. What was already downloaded is kept for next time.") },
        confirmButton = { TextButton(onClick = { confirmCancel = false; onCancel() }) { Text("Stop") } },
        dismissButton = { TextButton(onClick = { confirmCancel = false }) { Text("Keep going") } },
    )
}

private enum class StepState { Done, Now, Later }

@Composable
private fun StepItem(s: InstallStep, state: StepState, op: Operation.Running) {
    val cs = MaterialTheme.colorScheme
    val (headline, supporting) = when (s) {
        InstallStep.Signature -> when {
            op.insecure -> "Signature not checked" to "You chose to install this image without a check"
            state == StepState.Done -> "Signature verified" to "Signed by a publisher Selkie trusts"
            else -> s.title to "Before anything is downloaded"
        }
        InstallStep.Download -> (if (state == StepState.Done) "Downloaded" else "Downloading layers") to
            "Resumes if the connection drops"
        InstallStep.Unpack -> (if (state == StepState.Done) "Unpacked" else s.title) to null
        InstallStep.Activate -> s.title to "Keeps your files, then switches in one step"
    }
    GroupItem(
        headline,
        supporting = supporting,
        muted = state == StepState.Later,
        leading = {
            when {
                s == InstallStep.Signature && op.insecure -> Icon(SelkieIcons.Warn, contentDescription = null, tint = cs.error)
                state == StepState.Done -> Icon(SelkieIcons.Check, contentDescription = "Done", tint = cs.primary)
                state == StepState.Now -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                else -> Box(Modifier.size(24.dp))
            }
        },
    )
}

private fun amount(current: Long, total: Long?, unit: String?): String = when (unit) {
    "bytes" -> if (total != null) "${formatBytes(current)} of ${formatBytes(total)}" else formatBytes(current)
    else -> {
        val n = String.format(Locale.US, "%,d", current)
        val label = unit ?: ""
        if (total != null) "$n of ${String.format(Locale.US, "%,d", total)} $label".trim() else "$n $label".trim()
    }
}

@Composable
private fun Finished(
    title: String, body: String?, icon: ImageVector, button: String, onDismiss: () -> Unit,
    mark: MarkStyle = MarkStyle.Primary,
) {
    Mark(style = mark, size = 72.dp) { Icon(icon, contentDescription = null, Modifier.size(32.dp)) }
    Header(title, body)
    Button(onClick = onDismiss, Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(button) }
}

@Composable
private fun Failed(
    op: Operation.Failed,
    free: Long,
    creating: Boolean,
    onRetry: (Operation.Failed) -> Unit,
    onRetryInsecure: (Operation.Failed) -> Unit,
    onDismiss: () -> Unit,
    onChooseImage: ((Operation.Failed) -> Unit)?,
    onCleanUp: ((done: (String?) -> Unit) -> Unit)?,
) {
    val e = op.error
    val ctx = LocalContext.current
    var showLog by rememberSaveable { mutableStateOf(false) }
    var confirmInsecure by remember { mutableStateOf(false) }
    var cleaning by remember { mutableStateOf(false) }
    var cleanResult by remember { mutableStateOf<String?>(null) }
    // Only for addresses the user typed: never offered for the images Selkie lists.
    val offerInsecure = e.kind == ErrorKind.SIGNATURE && !op.insecure && op.ref != null && !Images.isKnown(op.ref)

    val (reason, help) = when (e.kind) {
        ErrorKind.SIGNATURE -> "This image's signature didn't check out" to
            "Nothing was downloaded or unpacked. Either the image isn't signed by anyone Selkie trusts, or it was changed on the way."
        ErrorKind.NETWORK -> "The download stopped" to
            "Selkie lost the connection. What was downloaded is kept, so trying again picks up from there."
        ErrorKind.STORAGE -> "Not enough space" to
            "This device has ${formatBytes(free)} free, and unpacking needs more. Nothing in the container was changed."
        ErrorKind.NOT_FOUND -> "Selkie couldn't find this image" to
            "Check the address and tag. Nothing was changed."
        ErrorKind.LOCKED -> "Something else is changing this container" to
            "Wait for it to finish, then try again."
        ErrorKind.INVALID -> "droidc refused the request" to "Nothing was changed."
        else -> "Something went wrong" to "Nothing was changed. The log may say why."
    }

    Header(failedTitle(op.kind, op.container, creating), null)
    SelkieCard(Modifier.fillMaxWidth(), tone = CardTone.Error) {
        Text(reason, style = MaterialTheme.typography.titleLarge)
        Text(help)
    }
    Group {
        op.ref?.let { DetailItem("Image", it, mono = true) }
        DetailItem("Details", "${e.kind}: ${e.message}", mono = true)
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val wide = Modifier.fillMaxWidth().heightIn(min = 56.dp)
        when (e.kind) {
            ErrorKind.SIGNATURE -> {
                if (onChooseImage != null) Button(onClick = { onChooseImage(op) }, wide) { Text("Choose another image") }
                else Button(onClick = onDismiss, wide) { Text("Back") }
                if (offerInsecure) {
                    OutlinedButton(onClick = { confirmInsecure = true }, wide) { Text("Use it without checking…") }
                    MutedText("Unchecked images are marked Unsigned everywhere in Selkie.")
                }
            }
            ErrorKind.STORAGE -> {
                Group {
                    if (onCleanUp != null) GroupItem("Clean up images",
                        supporting = cleanResult ?: "Frees layers no container uses",
                        leading = { Icon(SelkieIcons.Storage, contentDescription = null) },
                        trailing = { if (cleaning) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp) },
                        onClick = if (cleaning) null else ({
                            cleaning = true
                            onCleanUp { err -> cleaning = false; cleanResult = err?.let { "Couldn't clean up: $it" } ?: "Cleaned up" }
                        }))
                    GroupItem("Open Android storage settings",
                        leading = { Icon(SelkieIcons.Open, contentDescription = null) },
                        onClick = { runCatching { ctx.startActivity(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)) } })
                }
                Button(onClick = { onRetry(op) }, wide) { Text("Try again") }
            }
            else -> Button(onClick = { onRetry(op) }, wide) { Text("Try again") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            if (op.stderrTail.isNotBlank()) TextButton(onClick = { showLog = !showLog }) {
                Text(if (showLog) "Hide log" else "Show log")
            }
            if (e.kind != ErrorKind.SIGNATURE || onChooseImage != null) TextButton(onClick = onDismiss) { Text("Back") }
        }
    }
    if (showLog) SelkieCard(Modifier.fillMaxWidth(), tone = CardTone.Low) {
        Text(op.stderrTail.takeLast(4000), style = MonoStyle)
    }

    if (confirmInsecure) AlertDialog(
        onDismissRequest = { confirmInsecure = false },
        icon = { Icon(SelkieIcons.Warn, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
        title = { Text("Use this image without checking?") },
        text = { Text("Selkie can't tell who made ${op.ref} or whether it was changed. It will be marked Unsigned.") },
        confirmButton = { TextButton(onClick = { confirmInsecure = false; onRetryInsecure(op) }) { Text("Use it anyway") } },
        dismissButton = { TextButton(onClick = { confirmInsecure = false }) { Text("Cancel") } },
    )
}

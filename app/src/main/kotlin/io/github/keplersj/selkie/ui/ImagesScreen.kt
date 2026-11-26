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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.keplersj.selkie.service.Operation
import io.github.keplersj.selkie.service.ServiceState

/** One image on the Images screen: what it is and which containers use it. */
data class ImageEntry(
    val ref: String,
    val title: String,
    val usedBy: List<String>,
    /** "Signed by projectbluefin" or "Unsigned", where the selected container's status says; else null. */
    val signature: String?,
    val unsigned: Boolean,
)

/** The title Selkie gives an image: its catalog name, else the last part of its address without the tag. */
internal fun imageTitle(ref: String): String =
    ImageCatalog.tuned.firstOrNull { it.ref == ref }?.name
        ?: ImageCatalog.known.firstOrNull { it.ref == ref }?.title
        ?: ref.substringAfterLast('/').substringBefore('@').substringBefore(':').ifEmpty { ref }

/**
 * The images the containers use, one entry per address, in the containers' order. droidc stores layers once
 * however many containers use an image, which is why the screen groups by image and not by container.
 */
fun imageEntries(state: ServiceState): List<ImageEntry> {
    val selected = state.status?.current
    return summaries(state).filter { it.imageRef != null }.groupBy { it.imageRef!! }.map { (ref, users) ->
        // Only the selected container's status carries a signature; show it for the image that container uses.
        val mine = selected?.takeIf { s -> s.imageRef == ref && users.any { it.name == state.container } }
        val unsigned = mine != null && (mine.insecure || mine.signature?.verified == false)
        val signature = when {
            mine == null -> null
            unsigned -> "Unsigned"
            mine.signature?.verified == true -> signer(mine.signature.subject)?.let { "Signed by $it" } ?: "Signed"
            else -> null
        }
        ImageEntry(ref, imageTitle(ref), users.map { it.name }, signature, unsigned)
    }
}

/**
 * Images: every image a container uses, shared on disk by the containers that use it, with a way to make
 * another container from one and to free the layers nothing uses.
 */
@Composable
fun ImagesScreen(
    state: ServiceState,
    rail: Boolean,
    onNewContainer: (ref: String) -> Unit,
    onCleanUp: (done: (String?) -> Unit) -> Unit,
) {
    val entries = imageEntries(state)
    var confirm by remember { mutableStateOf(false) }
    var cleaning by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    val busy = state.operation is Operation.Running

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.widthIn(max = 840.dp).fillMaxWidth().align(Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionHeader("Images", "Downloaded once, shared by every container that uses it", rail)
            if (entries.isEmpty()) SelkieCard(Modifier.fillMaxWidth(), tone = CardTone.Outlined) {
                Text("No images yet", style = MaterialTheme.typography.titleMedium)
                MutedText("A container's image shows here once it is installed. Make one from the Containers section.")
            }
            for (e in entries) ImageCard(e, enabled = !busy, onNewContainer = { onNewContainer(e.ref) })

            SectionTitle("Store", Modifier.padding(top = 12.dp))
            Group {
                GroupItem("Free up space",
                    supporting = result ?: "Removes downloaded layers no container uses",
                    leading = { Icon(SelkieIcons.Storage, contentDescription = null) },
                    trailing = {
                        if (cleaning) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                        else TextButton(onClick = { confirm = true }, enabled = !busy) { Text("Clean up…") }
                    })
            }
        }
    }

    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        icon = { Icon(SelkieIcons.Storage, contentDescription = null) },
        title = { Text("Clean up images?") },
        text = { Text("Selkie removes downloaded layers that no container uses. Every container keeps what it " +
            "needs, and a removed layer downloads again if an image needs it later.") },
        confirmButton = {
            TextButton(onClick = {
                confirm = false; cleaning = true; result = null
                onCleanUp { err -> cleaning = false; result = err?.let { "Couldn't clean up: $it" } ?: "Cleaned up" }
            }) { Text("Clean up") }
        },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
    )
}

@Composable
private fun ImageCard(e: ImageEntry, enabled: Boolean, onNewContainer: () -> Unit) {
    SelkieCard(Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            ContainerMark(e.title, size = 48.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(e.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(e.ref, style = MonoStyle, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            e.signature?.let {
                StatusChip(it, tone = if (e.unsigned) ChipTone.Bad else ChipTone.Ok,
                    icon = if (e.unsigned) SelkieIcons.Warn else SelkieIcons.Shield)
            }
            StatusChip("Used by ${e.usedBy.joinToString(", ")}")
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) {
            FilledTonalButton(onClick = onNewContainer, enabled = enabled,
                modifier = Modifier.semantics { contentDescription = "New container from ${e.title}" }) {
                Icon(SelkieIcons.Plus, contentDescription = null, Modifier.size(18.dp))
                Text("Container", Modifier.padding(start = 8.dp))
            }
        }
    }
}

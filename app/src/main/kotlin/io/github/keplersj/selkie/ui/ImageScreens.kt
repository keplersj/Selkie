package io.github.keplersj.selkie.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import io.github.keplersj.selkie.engine.EngineEnv

/** The canvas's 64dp top bar: one navigation icon and a title. */
@Composable
fun SelkieTopBar(title: String?, navIcon: ImageVector, navLabel: String, onNav: () -> Unit,
                 actions: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onNav) { Icon(navIcon, contentDescription = navLabel) }
        if (title != null) Text(title, Modifier.weight(1f).padding(start = 12.dp).semantics { heading() },
            style = MaterialTheme.typography.titleLarge, maxLines = 1)
        else Spacer(Modifier.weight(1f))
        actions()
    }
}

private val SelectionSaver: Saver<ImageSelection, String> = Saver(
    save = {
        when (it) {
            is ImageSelection.Tuned -> "t:${it.name}"
            is ImageSelection.Known -> "k:${it.id}"
            ImageSelection.Custom -> "c"
        }
    },
    restore = {
        when {
            it.startsWith("t:") -> ImageSelection.Tuned(it.removePrefix("t:"))
            it.startsWith("k:") -> ImageSelection.Known(it.removePrefix("k:"))
            else -> ImageSelection.Custom
        }
    },
)

/** A centred column no wider than a readable line, on every width. */
@Composable
private fun FormColumn(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.widthIn(max = 840.dp).fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) { content() }
    }
}

/** Asks before an image whose signature Selkie can't check. */
@Composable
private fun UnsignedDialog(title: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(SelkieIcons.Warn, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
        title = { Text("Use $title without checking?") },
        text = { Text("Selkie can't check this image's signature, so it can't tell whether the image was changed on " +
            "the way. It's marked Unsigned everywhere.") },
        confirmButton = {
            TextButton(onClick = onConfirm, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                Text("Use without checking")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** The words for a container name that can't be used, or null. */
fun containerNameProblem(name: String, existing: List<String>): String? = when {
    name.isEmpty() -> null
    name in existing -> "A container with this name already exists."
    !EngineEnv.isValidContainerName(name) ->
        "Use up to ${EngineEnv.MAX_CONTAINER_NAME} of a-z, 0-9, - _ and . starting with a letter or digit."
    else -> null
}

/**
 * New container: a name and an image. With [fixedName] (the first container, which is always "default")
 * the name isn't asked for.
 */
@Composable
fun NewContainerScreen(
    existing: List<String>,
    onCreate: (name: String, ref: String, insecure: Boolean) -> Unit,
    onClose: (() -> Unit)?,
    fixedName: String? = null,
    /** Prefilled, still editable: "default" for the first container. */
    initialName: String = "",
    /** An image to start from, as Images' Container button passes; null preselects for this device. */
    initialRef: String? = null,
    free: Long = freeBytes(),
    device: DeviceClass = currentDeviceClass(),
    variants: List<TunedVariant> = ImageCatalog.tuned,
) {
    var name by rememberSaveable { mutableStateOf(fixedName ?: initialName) }
    val preselected = ImageCatalog.defaultSelection(device, variants)
    val initial = if (initialRef != null) ImageCatalog.selectionFor(initialRef, device, variants) else preselected
    var selection by rememberSaveable(stateSaver = SelectionSaver) { mutableStateOf(initial) }
    var custom by rememberSaveable { mutableStateOf(if (initial == ImageSelection.Custom) initialRef.orEmpty() else "") }
    var confirmUnsigned by rememberSaveable { mutableStateOf(false) }
    val trimmed = name.trim()
    val problem = if (fixedName != null) null else containerNameProblem(trimmed, existing)
    val resolved = ImageCatalog.resolve(selection, custom, variants)
    val canCreate = resolved != null && trimmed.isNotEmpty() && problem == null

    Column(Modifier.fillMaxSize()) {
        if (onClose != null) SelkieTopBar("New container", SelkieIcons.Close, "Close", onClose)
        else Spacer(Modifier.height(24.dp))
        FormColumn {
            if (onClose == null) Text("New container", style = MaterialTheme.typography.displaySmall,
                modifier = Modifier.padding(horizontal = 4.dp).semantics { heading() })
            // A parked car: what happens to a download once the car moves, before anything is chosen.
            if (device == DeviceClass.Car) SelkieCard(tone = CardTone.Low, shape = MaterialTheme.shapes.extraLarge) {
                StatusChip("Parked", tone = ChipTone.Ok)
                MutedText("Downloads and setup keep going while you drive. The new container is here when you park.")
            }
            if (fixedName == null) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it.lowercase() }, singleLine = true,
                    label = { Text("Name") }, isError = problem != null, modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.small,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                    supportingText = {
                        Text(problem ?: "Up to ${EngineEnv.MAX_CONTAINER_NAME} of a-z, 0-9, - _ and . It becomes the hostname.")
                    },
                )
            } else {
                MutedText("Your first container is called $fixedName. It's a full Linux system from an image, with its " +
                    "own home folder. You can add more later.", style = MaterialTheme.typography.bodyLarge)
            }

            ImagePicker(selection, { selection = it }, custom, { custom = it }, device = device, variants = variants)

            if (initialRef == null && selection == preselected) PreselectNote(preselected, variants)


            SectionTitle("Space")
            SelkieCard(tone = CardTone.Low, shape = MaterialTheme.shapes.extraLarge) {
                Text("${formatBytes(free)} free", style = MaterialTheme.typography.bodyLarge)
                MutedText("Images are a few gigabytes. Each is downloaded once and shared between containers, and " +
                    "each container gets its own home folder.")
            }

            Button(
                onClick = { if (resolved?.signed == false) confirmUnsigned = true else resolved?.let { onCreate(trimmed, it.ref, false) } },
                enabled = canCreate,
                modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
                shape = MaterialTheme.shapes.extraLarge,
            ) {
                Text(createLabel(resolved), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
    if (confirmUnsigned && resolved != null) {
        UnsignedDialog(resolved.title,
            onConfirm = { confirmUnsigned = false; onCreate(trimmed, resolved.ref, true) },
            onDismiss = { confirmUnsigned = false })
    }
}

/** Every create button reads "Create with <image>", with "…" when a confirmation follows. */
fun createLabel(resolved: ImageCatalog.Resolved?): String =
    if (resolved == null) "Create" else "Create with ${resolved.title}" + if (resolved.signed) "" else "…"

@Composable
private fun PreselectNote(preselected: ImageSelection, variants: List<TunedVariant>) {
    val (title, body) = when (preselected) {
        is ImageSelection.Tuned -> {
            val v = variants.first { it.name == preselected.name }
            "${v.name} is preselected" to "It's built for this kind of device. Every image's signature is checked " +
                "before anything is downloaded."
        }
        else -> "Bluefin LTS is preselected" to "The images built for Selkie aren't published yet. Bluefin LTS is " +
            "signed and has a GNOME desktop. Every image's signature is checked before anything is downloaded."
    }
    SelkieCard(tone = CardTone.Low, shape = MaterialTheme.shapes.extraLarge) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Icon(SelkieIcons.Shield, contentDescription = null)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                MutedText(body)
            }
        }
    }
}

/** A short name for an image address: the catalog's title, or the last part of the address. */
fun imageTitle(ref: String?, variants: List<TunedVariant> = ImageCatalog.tuned): String = when {
    ref == null -> "No image"
    else -> variants.firstOrNull { it.ref == ref }?.name
        ?: ImageCatalog.known.firstOrNull { it.ref == ref }?.title
        ?: ref.substringAfterLast('/')
}

/** Change image: the same container on a different system. Its files and name stay. */
@Composable
fun ChangeImageScreen(
    container: String,
    currentRef: String?,
    onChange: (ref: String, insecure: Boolean) -> Unit,
    onBack: () -> Unit,
    device: DeviceClass = currentDeviceClass(),
    variants: List<TunedVariant> = ImageCatalog.tuned,
) {
    val initial = ImageCatalog.selectionFor(currentRef, device, variants)
    var selection by rememberSaveable(stateSaver = SelectionSaver) { mutableStateOf(initial) }
    var custom by rememberSaveable { mutableStateOf(if (initial == ImageSelection.Custom) currentRef.orEmpty() else "") }
    var confirm by rememberSaveable { mutableStateOf(false) }
    val resolved = ImageCatalog.resolve(selection, custom, variants)
    val same = resolved?.ref == currentRef

    Column(Modifier.fillMaxSize()) {
        SelkieTopBar("Change image for $container", SelkieIcons.Back, "Back", onBack)
        FormColumn {
            SelkieCard(tone = CardTone.Low) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                    Icon(SelkieIcons.ChangeImage, contentDescription = null)
                    Column(Modifier.weight(1f)) {
                        Text("Same container, different system", style = MaterialTheme.typography.titleMedium)
                        MutedText("$container keeps its name and your files. Only the system underneath changes. The " +
                            "version you have now stays on this device, so you can go back.")
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Now", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(imageTitle(currentRef, variants), style = MaterialTheme.typography.bodyMedium)
                }
            }

            ImagePicker(selection, { selection = it }, custom, { custom = it }, device = device, variants = variants)

            Button(
                onClick = { confirm = true },
                enabled = resolved != null && !same,
                modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
                shape = MaterialTheme.shapes.extraLarge,
            ) {
                Text(if (resolved == null || same) "Change image" else "Change to ${resolved.title}…",
                    style = MaterialTheme.typography.titleMedium)
            }
            if (same) MutedText("$container already uses this image. Pick another one to change it.",
                Modifier.padding(horizontal = 4.dp))
        }
    }
    if (confirm && resolved != null) {
        if (!resolved.signed) {
            UnsignedDialog(resolved.title, onConfirm = { confirm = false; onChange(resolved.ref, true) },
                onDismiss = { confirm = false })
        } else {
            AlertDialog(
                onDismissRequest = { confirm = false },
                title = { Text("Change $container to ${resolved.title}?") },
                text = { Text("Your files in /home and /var stay. Close open terminals first. You can go back to " +
                    "the version you have now.") },
                confirmButton = { TextButton(onClick = { confirm = false; onChange(resolved.ref, false) }) { Text("Change image") } },
                dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
            )
        }
    }
}

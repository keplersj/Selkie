package io.github.keplersj.selkie.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

@Composable
fun SetupScreen(
    title: String,
    initialRef: String? = null,
    onStart: (ref: String, insecure: Boolean) -> Unit,
    onBack: (() -> Unit)?,
    free: Long = freeBytes(),
) {
    var choice by rememberSaveable { mutableStateOf(
        when { initialRef == null || initialRef == Images.BLUEFIN -> "bluefin"
               initialRef == Images.CENTOS -> "centos" else -> "custom" }) }
    var custom by rememberSaveable { mutableStateOf(if (Images.isKnown(initialRef)) "" else initialRef.orEmpty()) }
    var insecure by rememberSaveable { mutableStateOf(choice == "centos") }
    val ref = Images.choices.first { it.id == choice }.ref ?: custom.trim()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Text("Pick a bootc image. Selkie downloads it and unpacks it into app storage.")
        Spacer(Modifier.height(4.dp))
        for (c in Images.choices) {
            Row(Modifier.fillMaxWidth().selectable(choice == c.id, role = Role.RadioButton) {
                choice = c.id; if (c.id == "centos") insecure = true else if (c.id == "bluefin") insecure = false
            }.padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
                RadioButton(selected = choice == c.id, onClick = null)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(c.title, style = MaterialTheme.typography.titleMedium)
                    c.ref?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    Text(c.note, style = MaterialTheme.typography.bodySmall)
                    c.sizeHint?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary) }
                }
            }
        }
        if (choice == "custom") {
            OutlinedTextField(custom, { custom = it }, label = { Text("Image reference") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        Row(Modifier.fillMaxWidth().clickable { insecure = !insecure }.padding(top = 8.dp),
            verticalAlignment = Alignment.Top) {
            Checkbox(insecure, onCheckedChange = { insecure = it })
            Column {
                Text("Allow unsigned image (insecure)")
                Text("Skips signature verification. Anyone who can tamper with the download could " +
                    "run code in your Linux environment. Needed for CentOS Stream; leave off for " +
                    "Bluefin, which is verified.", style = MaterialTheme.typography.bodySmall)
            }
        }
        Text("Free space: ${formatBytes(free)}", style = MaterialTheme.typography.bodyMedium)
        Text("Unpacking needs roughly 2.5× the download in addition to the download itself.",
            style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        Button(onClick = { onStart(ref, insecure) }, enabled = ref.isNotBlank()) { Text("Download and install") }
        if (onBack != null) TextButton(onClick = onBack) { Text("Back") }
    }
}

package io.github.keplersj.selkie.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import io.github.keplersj.selkie.engine.GraphicalSession
import io.github.keplersj.selkie.engine.PipeWireServices

/** One filled-in example of the `containers.droidc.session.*` keys, as editable text. */
private data class Example(val label: String, val name: String, val renderer: String, val exec: String, val services: String)

private val EXAMPLES = listOf(
    Example("GNOME (headless)", "GNOME", "screencast",
        """["gnome-shell","--headless"]""", PipeWireServices.JSON),
    Example("Nested compositor", "", "wayland", """["weston","--backend=wayland","--fullscreen"]""", ""),
)

/**
 * Sets the graphical session for images that don't declare one with `containers.droidc.session.*`
 * labels (`droidc session set`). The image's own labels are used when there is no override.
 */
@Composable
fun SessionConfigScreen(
    current: GraphicalSession?,
    onSave: (renderer: String, exec: String, services: String?, name: String?, onDone: (String?) -> Unit) -> Unit,
    onClear: () -> Unit,
    onBack: () -> Unit,
) {
    var name by remember { mutableStateOf(current?.name.orEmpty()) }
    var renderer by remember { mutableStateOf(current?.renderer ?: "screencast") }
    var exec by remember { mutableStateOf(current?.exec?.let { list -> list.joinToString(",", "[", "]") { "\"" + it.replace("\"", "\\\"") + "\"" } }.orEmpty()) }
    var services by remember { mutableStateOf(current?.services?.takeIf { it.isNotEmpty() }?.joinToString(",", "[", "]") { cmd ->
        cmd.joinToString(",", "[", "]") { "\"" + it.replace("\"", "\\\"") + "\"" } }.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val mono = TextStyle(fontFamily = FontFamily.Monospace)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Desktop session", style = MaterialTheme.typography.headlineSmall)
        Text(when (current?.source) {
            "label" -> "This image declares its session with labels. Saving here overrides them."
            "override" -> "Using your settings below."
            else -> "This image doesn't declare a graphical session. Describe how to start it."
        }, style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (e in EXAMPLES) AssistChip(onClick = {
                name = e.name; renderer = e.renderer; exec = e.exec; services = e.services
            }, label = { Text(e.label) })
        }
        OutlinedTextField(name, { name = it }, label = { Text("Name (XDG_CURRENT_DESKTOP)") }, singleLine = true,
            modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(renderer == "wayland", { renderer = "wayland" }, label = { Text("wayland: nested") })
            FilterChip(renderer == "screencast", { renderer = "screencast" }, label = { Text("screencast: headless") })
        }
        OutlinedTextField(exec, { exec = it }, label = { Text("Command (JSON array; {width} {height} {scale} {wayland_display})") },
            textStyle = mono, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(services, { services = it }, label = { Text("Services started first (JSON array of arrays, optional)") },
            textStyle = mono, modifier = Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !saving && exec.isNotBlank(), onClick = {
                saving = true; error = null
                onSave(renderer, exec.trim(), services.trim().ifBlank { null }, name.trim().ifBlank { null }) { e ->
                    saving = false
                    if (e == null) onBack() else error = e
                }
            }) { Text("Save") }
            if (current?.source == "override") OutlinedButton(onClick = { onClear(); onBack() }) { Text("Use the image's labels") }
            TextButton(onClick = onBack) { Text("Cancel") }
        }
    }
}

package io.github.keplersj.selkie.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.keplersj.selkie.engine.OnShapeChange
import io.github.keplersj.selkie.service.SessionService

/**
 * When the device changes shape (Phone-ShapeSettings): what happens to a running desktop when the device
 * turns, folds or gets a display. Only what Selkie does is listed: the session always resizes, and on a
 * new shape Selkie can offer the session made for it.
 */
@Composable
fun ShapeSettingsContent(
    choice: OnShapeChange,
    /** Containers told to keep their session. */
    kept: List<String>,
    onChoose: (OnShapeChange) -> Unit,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.widthIn(max = 840.dp).fillMaxWidth().align(Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("When the device changes shape", style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f))
                TextButton(onClick = onBack) { Text("Done") }
            }
            MutedText("Selkie's own screens always follow the device. A running desktop resizes, " +
                "and Selkie never switches its session without asking.")

            SectionTitle("Turning or folding the device", Modifier.padding(top = 8.dp))
            Group {
                GroupItem("Resize the session to fit", supporting = "The desktop takes the new size. Nothing closes.",
                    leading = { Icon(SelkieIcons.Desktop, contentDescription = null) })
            }

            SectionTitle("When another session suits the new shape", Modifier.padding(top = 8.dp))
            MutedText("Opening a fold, or a dock or display, can make the device a tablet or a desktop " +
                "while a session made for a phone runs. Turning the device doesn't count.")
            Group(Modifier.selectableGroup()) {
                ShapeChoiceRow("Ask what to do", "Offer the session made for the new shape. Keeping yours is the default.",
                    selected = choice == OnShapeChange.Ask) { onChoose(OnShapeChange.Ask) }
                ShapeChoiceRow("Always keep the running session", "It resizes, and Selkie doesn't ask.",
                    selected = choice == OnShapeChange.Keep) { onChoose(OnShapeChange.Keep) }
            }
            if (choice == OnShapeChange.Ask && kept.isNotEmpty()) {
                MutedText("Always keeping theirs: ${kept.joinToString(", ")}.")
                TextButton(onClick = { onChoose(OnShapeChange.Ask) }) { Text("Ask for these too") }
            }

            SectionTitle("Starting a desktop", Modifier.padding(top = 8.dp))
            MutedText("Which session starts is set for each container, under Sessions.")
        }
    }
}

@Composable
private fun ShapeChoiceRow(title: String, body: String, selected: Boolean, onSelect: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp))
            .background(if (selected) cs.secondaryContainer else cs.surfaceContainer)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge,
                color = if (selected) cs.onSecondaryContainer else cs.onSurface)
            Text(body, style = MaterialTheme.typography.bodyMedium,
                color = if (selected) cs.onSecondaryContainer else cs.onSurfaceVariant)
        }
    }
}

/** [ShapeSettingsContent] read from and saved to [service]. */
@Composable
fun ShapeSettingsScreen(service: SessionService, onBack: () -> Unit) {
    var now by remember { mutableStateOf(service.shapeChangeChoice()) }
    ShapeSettingsContent(now.first, now.second,
        onChoose = { service.setShapeChange(it); now = service.shapeChangeChoice() },
        onBack = onBack)
}

package io.github.keplersj.selkie.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.keplersj.selkie.engine.SessionChoice
import io.github.keplersj.selkie.engine.SessionEntry
import io.github.keplersj.selkie.engine.SessionPick

/** How the picker names a device shape: "Fits this phone". */
internal fun shapeName(formFactor: String): String = when (formFactor) {
    "tv" -> "TV"
    "xr" -> "headset"
    else -> formFactor
}

/**
 * The sessions a container's image offers (Phone-Sessions): those that fit this device, picked one at a
 * time, those made for other devices behind "Use anyway", and the user's own. A running desktop keeps
 * its session; the choice applies the next time it starts.
 */
@Composable
fun SessionsScreen(
    container: String,
    sessions: List<SessionEntry>,
    pick: SessionPick,
    /** What starts now, from [SessionChoice.choose]. */
    current: SessionEntry?,
    /** The session running right now in this container, if any. */
    running: String?,
    onChoose: (String?) -> Unit,
    onChooseByDevice: (Boolean) -> Unit,
    onAddOwn: () -> Unit,
    onBack: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val shape = shapeName(pick.formFactor)
    val (fits, others) = SessionChoice.split(sessions, pick.formFactor)
    val cannot = sessions.filter { !it.runnable }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Sessions in $container", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onBack) { Text("Done") }
        }
        MutedText("Sessions come from the image. Each one is a whole desktop or shell.")
        if (running != null) MutedText("$running keeps running. A new choice starts the next time you open it.")

        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(cs.surfaceContainer)
                .toggleable(value = pick.byDevice, role = Role.Switch, onValueChange = onChooseByDevice)
                .heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text("Choose by device", style = MaterialTheme.typography.bodyLarge)
                Text(if (pick.byDevice) "Right now: $shape. Each kind of device remembers its own session."
                    else "One session on every device.",
                    style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            }
            Switch(checked = pick.byDevice, onCheckedChange = null)
        }

        if (sessions.isEmpty()) {
            MutedText("This image lists no sessions. Selkie starts the one its labels or your settings describe.")
        }

        val list = if (pick.byDevice) fits else fits + others
        if (list.isNotEmpty()) {
            SectionTitle(if (pick.byDevice) "Fits this $shape" else "Sessions")
            Group(Modifier.selectableGroup()) {
                for (e in list) SessionRow(e, selected = e.id == current?.id,
                    note = when {
                        pick.byDevice && e.id == current?.id && pick.chosen == null -> "Starts by default here"
                        e.formFactors.isEmpty() -> "Works on every device"
                        else -> null
                    },
                    onSelect = { onChoose(e.id) })
            }
        }
        if (pick.byDevice && others.isNotEmpty()) {
            SectionTitle("For other devices")
            Group {
                for (e in others) {
                    val selected = e.id == current?.id
                    GroupItem(e.title, supporting = listOfNotNull(e.comment,
                        "Made for " + e.formFactors.joinToString(", ") { shapeName(it) }).joinToString(". "),
                        highlighted = selected,
                        trailing = {
                            if (selected) Text("Starts here", style = MaterialTheme.typography.labelLarge)
                            else OutlinedButton(onClick = { onChoose(e.id) }) { Text("Use anyway") }
                        })
                }
            }
        }
        if (pick.chosen != null) TextButton(onClick = { onChoose(null) }) {
            Text(if (pick.byDevice) "Go back to the one made for this $shape" else "Go back to the image's default")
        }
        if (cannot.isNotEmpty()) {
            SectionTitle("Can't start in Selkie")
            Group {
                for (e in cannot) GroupItem(e.title, supporting = e.problem, muted = true)
            }
        }
        SectionTitle("Your own")
        Group {
            GroupItem("Add your own session", supporting = "A command, and how Selkie shows it",
                onClick = onAddOwn, onClickLabel = "Describe a session")
        }
    }
}

@Composable
private fun SessionRow(e: SessionEntry, selected: Boolean, note: String?, onSelect: () -> Unit) {
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
            Text(e.title, style = MaterialTheme.typography.bodyLarge,
                color = if (selected) cs.onSecondaryContainer else cs.onSurface)
            val sub = listOfNotNull(note, e.comment).joinToString(". ").ifEmpty { null }
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodyMedium,
                color = if (selected) cs.onSecondaryContainer else cs.onSurfaceVariant)
        }
    }
}

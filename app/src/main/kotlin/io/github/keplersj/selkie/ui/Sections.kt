package io.github.keplersj.selkie.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Selkie's top-level destinations, in the navigation bar's and rail's order. Ctrl+1 to Ctrl+4 open them. */
enum class Section(val label: String, val icon: ImageVector) {
    Containers("Containers", SelkieIcons.Containers),
    Images("Images", SelkieIcons.Images),
    Activity("Activity", SelkieIcons.Activity),
    Settings("Settings", SelkieIcons.Settings),
}

/** From this width the sections move from the bottom bar to a rail at the side (M3's medium window class). */
val RAIL_WIDTH: Dp = 600.dp

/** The rail sits at the side from [RAIL_WIDTH] up, except open like a book, where the bar keeps the hinge clear. */
internal fun useRail(width: Dp, book: Boolean): Boolean = width >= RAIL_WIDTH && !book

/**
 * A section with its navigation: a bar under [content] on a phone, or a rail beside it from [RAIL_WIDTH] up.
 * The rail carries [onNewContainer] as its button, so the containers list doesn't repeat it. [showNavigation]
 * is false while a phone shows one container on its own screen, which has its own back arrow.
 */
@Composable
fun SectionScaffold(
    section: Section,
    onSection: (Section) -> Unit,
    onNewContainer: () -> Unit,
    modifier: Modifier = Modifier,
    showNavigation: Boolean = true,
    content: @Composable (rail: Boolean) -> Unit,
) {
    val book = LocalFold.current?.book == true
    BoxWithConstraints(modifier.fillMaxSize()) {
        val rail = useRail(maxWidth, book)
        when {
            !showNavigation -> content(rail)
            rail -> Row(Modifier.fillMaxSize()) {
                SectionRail(section, onSection, onNewContainer)
                Box(Modifier.weight(1f).fillMaxHeight()) { content(true) }
            }
            else -> Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) { content(false) }
                SectionBar(section, onSection)
            }
        }
    }
}

@Composable
fun SectionBar(section: Section, onSection: (Section) -> Unit) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        for (s in Section.entries) NavigationBarItem(
            selected = s == section,
            onClick = { onSection(s) },
            icon = { Icon(s.icon, contentDescription = null) },
            label = { Text(s.label) },
        )
    }
}

@Composable
fun SectionRail(section: Section, onSection: (Section) -> Unit, onNewContainer: () -> Unit) {
    NavigationRail(
        Modifier.fillMaxHeight().padding(vertical = 12.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        header = {
            FloatingActionButton(onClick = onNewContainer, Modifier.padding(bottom = 24.dp),
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp)) {
                Icon(SelkieIcons.Plus, contentDescription = "New container")
            }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            for (s in Section.entries) NavigationRailItem(
                selected = s == section,
                onClick = { onSection(s) },
                icon = { Icon(s.icon, contentDescription = null) },
                label = { Text(s.label) },
            )
        }
    }
}

/** A section's title and one line under it, as on Images and Activity. */
@Composable
fun SectionHeader(title: String, subtitle: String?, rail: Boolean, modifier: Modifier = Modifier,
                  action: (@Composable () -> Unit)? = null) {
    Row(modifier.padding(start = 4.dp, top = 8.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, Modifier.semantics { heading() },
                style = if (rail) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.displaySmall)
            subtitle?.let { MutedText(it) }
        }
        action?.invoke()
    }
}

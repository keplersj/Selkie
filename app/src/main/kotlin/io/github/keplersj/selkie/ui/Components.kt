package io.github.keplersj.selkie.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.progressSemantics
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Shared building blocks from the redesign canvas (m3.css): marks, status chips, grouped lists, cards and
// section titles. Screens move onto these batch by batch.

/** The three mark shapes: a container's own (A), a session or helper (B), and a neutral one (C). */
enum class MarkStyle { Primary, Tertiary, Neutral }

/** A 48dp avatar holding a container's initial or an icon. */
@Composable
fun Mark(
    modifier: Modifier = Modifier,
    style: MarkStyle = MarkStyle.Primary,
    size: Dp = 48.dp,
    content: @Composable () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val (bg, fg) = when (style) {
        MarkStyle.Primary -> cs.primaryContainer to cs.onPrimaryContainer
        MarkStyle.Tertiary -> cs.tertiaryContainer to cs.onTertiaryContainer
        MarkStyle.Neutral -> cs.surfaceContainerHighest to cs.onSurface
    }
    val scale = size.value / 48f
    val shape: Shape = when (style) {
        MarkStyle.Primary -> RoundedCornerShape((16 * scale).dp)
        MarkStyle.Tertiary -> RoundedCornerShape(50)
        MarkStyle.Neutral -> RoundedCornerShape(
            topStart = (24 * scale).dp, topEnd = (8 * scale).dp, bottomEnd = (24 * scale).dp, bottomStart = (8 * scale).dp)
    }
    Box(modifier.size(size).clip(shape).background(bg), contentAlignment = Alignment.Center) {
        CompositionLocalProvider(LocalContentColor provides fg) {
            ProvideTextStyle(MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)) { content() }
        }
    }
}

/** A container's mark: the first letter of its name. */
@Composable
fun ContainerMark(name: String, modifier: Modifier = Modifier, style: MarkStyle = MarkStyle.Primary, size: Dp = 48.dp) =
    Mark(modifier, style, size) { Text(name.take(1).uppercase()) }

enum class ChipTone { Neutral, Ok, Secondary, Bad, Planned }

/**
 * A status label, not a control: it has no click and reads as plain text to TalkBack. Planned features use
 * [ChipTone.Planned], a dashed tertiary outline.
 */
@Composable
fun StatusChip(text: String, modifier: Modifier = Modifier, tone: ChipTone = ChipTone.Neutral, icon: ImageVector? = null) {
    val cs = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.extraSmall
    val (bg, fg) = when (tone) {
        ChipTone.Neutral -> Color.Transparent to cs.onSurfaceVariant
        ChipTone.Ok -> cs.primaryContainer to cs.onPrimaryContainer
        ChipTone.Secondary -> cs.secondaryContainer to cs.onSecondaryContainer
        ChipTone.Bad -> cs.errorContainer to cs.onErrorContainer
        ChipTone.Planned -> Color.Transparent to cs.tertiary
    }
    val outlined = when (tone) {
        ChipTone.Neutral -> modifier.border(1.dp, cs.outline, shape)
        ChipTone.Planned -> modifier.drawBehind {
            val w = 1.5.dp.toPx()
            drawRoundRect(
                color = cs.tertiary,
                topLeft = androidx.compose.ui.geometry.Offset(w / 2, w / 2),
                size = androidx.compose.ui.geometry.Size(size.width - w, size.height - w),
                cornerRadius = CornerRadius(8.dp.toPx()),
                style = Stroke(width = w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
            )
        }
        else -> modifier
    }
    Row(
        outlined.clip(shape).background(bg).defaultMinSize(minHeight = 28.dp).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(16.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = fg)
    }
}

/** "Planned", or a longer note about what isn't built yet. */
@Composable
fun PlannedChip(text: String = "Planned", modifier: Modifier = Modifier) = StatusChip(text, modifier, ChipTone.Planned)

/** A section heading inside a screen: 14sp medium in primary. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.padding(horizontal = 4.dp).semantics { heading() },
        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
}

/** A grouped list: items 2dp apart with 4dp corners, inside a 24dp rounded group. */
@Composable
fun Group(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().clip(MaterialTheme.shapes.large), verticalArrangement = Arrangement.spacedBy(2.dp),
        content = content)
}

/**
 * One row of a [Group]. Pass [onClick] only when the row does something, so TalkBack doesn't announce
 * rows that only show information as buttons.
 */
@Composable
fun GroupItem(
    headline: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    highlighted: Boolean = false,
    muted: Boolean = false,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
) {
    val cs = MaterialTheme.colorScheme
    val bg = if (highlighted) cs.secondaryContainer else cs.surfaceContainer
    val fg = if (highlighted) cs.onSecondaryContainer else cs.onSurface
    val sub = if (highlighted) cs.onSecondaryContainer else cs.onSurfaceVariant
    val clickable = if (onClick != null) Modifier.clickable(onClickLabel = onClickLabel, onClick = onClick) else Modifier
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(bg).then(clickable)
            .defaultMinSize(minHeight = 64.dp).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (leading != null) CompositionLocalProvider(LocalContentColor provides if (highlighted) fg else cs.onSurfaceVariant) { leading() }
        Column(Modifier.weight(1f)) {
            Text(headline, style = MaterialTheme.typography.bodyLarge, color = if (muted) sub else fg)
            if (supporting != null) Text(supporting, style = MaterialTheme.typography.bodyMedium, color = sub)
        }
        if (trailing != null) CompositionLocalProvider(LocalContentColor provides sub) { trailing() }
    }
}

/** A label over a value, for read-only details such as an image address or a digest. */
@Composable
fun DetailItem(label: String, value: String, modifier: Modifier = Modifier, mono: Boolean = false,
               trailing: (@Composable RowScope.() -> Unit)? = null) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(cs.surfaceContainer)
            .defaultMinSize(minHeight = 64.dp).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
            Text(value, style = if (mono) MonoStyle else MaterialTheme.typography.bodyLarge, color = cs.onSurface)
        }
        if (trailing != null) CompositionLocalProvider(LocalContentColor provides cs.onSurfaceVariant) { trailing() }
    }
}

enum class CardTone { Default, Low, Selected, Tertiary, Error, Outlined }

/** The canvas's cards: 28dp corners, 16dp padding, 12dp between children. */
@Composable
fun SelkieCard(
    modifier: Modifier = Modifier,
    tone: CardTone = CardTone.Default,
    shape: Shape = MaterialTheme.shapes.extraLarge,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val (bg, fg) = when (tone) {
        CardTone.Default -> cs.surfaceContainer to cs.onSurface
        CardTone.Low -> cs.surfaceContainerLow to cs.onSurface
        CardTone.Selected -> cs.secondaryContainer to cs.onSecondaryContainer
        CardTone.Tertiary -> cs.tertiaryContainer to cs.onTertiaryContainer
        CardTone.Error -> cs.errorContainer to cs.onErrorContainer
        CardTone.Outlined -> Color.Transparent to cs.onSurface
    }
    val edge = when (tone) {
        CardTone.Selected -> Modifier.border(2.dp, cs.primary, shape)
        CardTone.Outlined -> Modifier.border(1.dp, cs.outlineVariant, shape)
        else -> Modifier
    }
    Column(modifier.clip(shape).background(bg).then(edge).padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CompositionLocalProvider(LocalContentColor provides fg) { content() }
    }
}

/** Secondary text that takes its colour from the card it sits in (the canvas's .muted). */
@Composable
fun MutedText(text: String, modifier: Modifier = Modifier, style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyMedium) {
    val inCard = LocalContentColor.current
    val cs = MaterialTheme.colorScheme
    val color = if (inCard == cs.onSurface) cs.onSurfaceVariant else inCard
    Text(text, modifier, style = style, color = color)
}

/** An 8dp progress track, as under a download. */
@Composable
fun ProgressTrack(fraction: Float, modifier: Modifier = Modifier, height: Dp = 8.dp) {
    val cs = MaterialTheme.colorScheme
    Box(modifier.fillMaxWidth().height(height).progressSemantics(fraction.coerceIn(0f, 1f))
        .clip(RoundedCornerShape(height / 2)).background(cs.secondaryContainer)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(height).clip(RoundedCornerShape(height / 2))
            .background(cs.primary))
    }
}

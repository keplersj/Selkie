package io.github.keplersj.selkie.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/** The device class of the current window, from its size and UI mode. */
@Composable
@ReadOnlyComposable
fun currentDeviceClass(): DeviceClass {
    val c = LocalConfiguration.current
    return when (c.uiMode and Configuration.UI_MODE_TYPE_MASK) {
        Configuration.UI_MODE_TYPE_TELEVISION -> DeviceClass.Tv
        Configuration.UI_MODE_TYPE_CAR -> DeviceClass.Car
        Configuration.UI_MODE_TYPE_WATCH -> DeviceClass.Watch
        Configuration.UI_MODE_TYPE_VR_HEADSET -> DeviceClass.Xr
        else -> deviceClassForWidth(c.screenWidthDp)
    }
}

/** Compact width is a phone, medium a fold or small tablet, expanded a tablet, and very wide a desktop window. */
fun deviceClassForWidth(widthDp: Int): DeviceClass = when {
    widthDp < 600 -> DeviceClass.Phone
    widthDp < 840 -> DeviceClass.Foldable
    widthDp < 1440 -> DeviceClass.Tablet
    else -> DeviceClass.Desktop
}

/**
 * The image picker: droidc-tuned variants, other bootc images Selkie knows, and any other address. The
 * cards are one radio group, so TalkBack reads each as "radio button, selected/not selected, n of m".
 */
@Composable
fun ImagePicker(
    selection: ImageSelection,
    onSelect: (ImageSelection) -> Unit,
    customRef: String,
    onCustomRef: (String) -> Unit,
    modifier: Modifier = Modifier,
    device: DeviceClass = currentDeviceClass(),
    variants: List<TunedVariant> = ImageCatalog.tuned,
) {
    val cols = ImageCatalog.columns(device)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            SectionTitle("droidc-tuned", Modifier.padding(end = 8.dp))
            Spacer(Modifier.weight(1f))
            Text("Built by the droidc project for Selkie", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val ordered = ImageCatalog.orderedFor(device, variants)
        Grid(cols, ordered) { v ->
            val selected = selection == ImageSelection.Tuned(v.name)
            PickerCard(
                title = v.name, subtitle = v.summary, mono = false, selected = selected, enabled = v.ready,
                onClick = { onSelect(ImageSelection.Tuned(v.name)) },
                chips = {
                    if (device in v.fits) StatusChip("Fits this device", tone = ChipTone.Ok)
                    StatusChip(v.forText)
                    if (!v.ready) PlannedChip("Coming later")
                },
            )
        }

        SectionTitle("Other bootc images", Modifier.padding(top = 8.dp))
        Grid(cols, ImageCatalog.known) { k ->
            PickerCard(
                title = k.title, subtitle = k.ref, mono = true, selected = selection == ImageSelection.Known(k.id),
                enabled = true, onClick = { onSelect(ImageSelection.Known(k.id)) },
                chips = { for (c in k.chips) StatusChip(c, tone = if (c == "Unsigned") ChipTone.Bad else ChipTone.Neutral) },
            )
        }

        SectionTitle("Any other image", Modifier.padding(top = 8.dp))
        val customSelected = selection == ImageSelection.Custom
        Column {
            OutlinedTextField(
                value = customRef,
                onValueChange = { onCustomRef(it); if (it.isNotBlank()) onSelect(ImageSelection.Custom) },
                modifier = Modifier.fillMaxWidth().onFocusChanged {
                    if (it.isFocused && customRef.isNotBlank()) onSelect(ImageSelection.Custom)
                },
                label = { Text("Container image address") },
                placeholder = { Text("registry.example/name:tag", style = MonoStyle) },
                textStyle = MonoStyle.copy(fontSize = MaterialTheme.typography.bodyLarge.fontSize),
                singleLine = true,
                shape = MaterialTheme.shapes.small,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                supportingText = {
                    Text(if (customSelected) "Selected. Its signature is checked before anything is downloaded."
                        else "Any bootc image. Its signature is checked before anything is downloaded.")
                },
            )
        }
    }
}

@Composable
private fun <T> Grid(cols: Int, items: List<T>, cell: @Composable (T) -> Unit) {
    Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (row in items.chunked(cols)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (item in row) Column(Modifier.weight(1f)) { cell(item) }
                repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PickerCard(
    title: String, subtitle: String, mono: Boolean, selected: Boolean, enabled: Boolean,
    onClick: () -> Unit, chips: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    SelkieCard(
        Modifier.fillMaxWidth().selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
        tone = when { selected -> CardTone.Selected; enabled -> CardTone.Default; else -> CardTone.Outlined },
        shape = shape,
    ) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // The card is the radio button; this one only draws its state.
            RadioButton(selected = selected, onClick = null, enabled = enabled)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                MutedText(subtitle, style = if (mono) MonoStyle else MaterialTheme.typography.bodyMedium)
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { chips() }
    }
}

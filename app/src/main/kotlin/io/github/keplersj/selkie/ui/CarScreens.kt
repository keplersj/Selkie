package io.github.keplersj.selkie.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.keplersj.selkie.platform.CarDriving
import io.github.keplersj.selkie.service.DesktopState
import io.github.keplersj.selkie.service.ServiceState

// The canvas's car artboards. Parked, Selkie is its tablet layout with a note on what driving does. Driving,
// nothing is interactive: one screen says what keeps running, and everything comes back when the car parks.
// Google Play doesn't take container managers for cars, so this is for sideloaded and OEM builds.

/** True while the car is moving. Always false off a car and in previews. */
@Composable
fun rememberCarDriving(): Boolean {
    val ctx = LocalContext.current
    val car = !LocalInspectionMode.current && remember(ctx) { CarDriving.isCar(ctx) }
    var driving by remember { mutableStateOf(car) }
    if (car) DisposableEffect(ctx) {
        val watch = CarDriving.watch(ctx) { driving = it }
        onDispose { watch.close() }
    }
    return car && driving
}

/** What the driving screen says keeps running, so the driver knows nothing was closed. */
internal fun carDrivingLine(state: ServiceState): String {
    val running = summaries(state).filter { it.running }.map { it.name }
    val desktop = state.desktop is DesktopState.Running || state.desktop is DesktopState.Starting
    val what = when (running.size) {
        0 -> return "Nothing is running. Selkie comes back when you park."
        1 -> "${running[0]} keeps running"
        else -> running.dropLast(1).joinToString(", ") + " and ${running.last()} keep running"
    }
    val back = when {
        desktop -> "The desktop comes back when you park."
        running.size > 1 -> "Their terminals come back when you park."
        else -> "Its terminals come back when you park."
    }
    return "$what in the background. $back"
}

/** Selkie while the car moves: nothing to press, only what keeps running. */
@Composable
fun CarDrivingScreen(state: ServiceState) {
    Box(Modifier.fillMaxSize().padding(48.dp), Alignment.Center) {
        Column(Modifier.widthIn(max = 720.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Selkie is hidden while you drive", Modifier.semantics { heading() },
                style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
            Text(carDrivingLine(state), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Above the parked car's containers: what changes once the car moves. */
@Composable
fun CarParkedNote(modifier: Modifier = Modifier) {
    SelkieCard(modifier.fillMaxWidth(), tone = CardTone.Low) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusChip("Parked", tone = ChipTone.Ok)
        }
        MutedText("You can create a container here while parked. When you start driving, the desktop and terminal " +
            "are hidden and the containers keep running. They come back when you park.")
    }
}

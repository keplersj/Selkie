package io.github.keplersj.selkie.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** First-run screen: Android kills proot's children unless child process restrictions are off. */
@Composable
fun PhantomScreen(stillRestricted: Boolean, unverified: Boolean = false, onRecheck: () -> Unit, onContinueAnyway: () -> Unit) {
    val ctx = LocalContext.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("One-time Android setup", style = MaterialTheme.typography.headlineMedium)
        Text("Android 12+ kills the child processes of apps (\"phantom processes\"). Linux programs " +
            "running under Selkie are child processes, so without this change they will be killed " +
            "at random, often within minutes.")
        Text("1. Enable Developer options", style = MaterialTheme.typography.titleMedium)
        Text("Settings → About tablet → Software information → tap Build number 7 times " +
            "(Samsung One UI). Enter your PIN if asked.")
        Text("2. Disable child process restrictions", style = MaterialTheme.typography.titleMedium)
        Text("Settings → Developer options → turn on \"Disable child process restrictions\".")
        if (stillRestricted) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Text(if (unverified) "Couldn't verify the child process setting on this device. If you already turned it on, continue anyway." else "Child process restrictions look enabled on this device.",
                    Modifier.padding(12.dp))
            }
        }
        Button(onClick = {
            ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }) { Text("Open developer settings") }
        OutlinedButton(onClick = onRecheck) { Text("Check again") }
        TextButton(onClick = onContinueAnyway) { Text("Continue anyway") }
    }
}

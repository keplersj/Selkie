package io.github.keplersj.selkie.ui

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import io.github.keplersj.selkie.BuildConfig
import io.github.keplersj.selkie.engine.DroidcClient
import io.github.keplersj.selkie.engine.DroidcOutcome
import io.github.keplersj.selkie.engine.engineEnvFrom
import io.github.keplersj.selkie.engine.SelkieJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val uri = LocalUriHandler.current
    var droidc by remember { mutableStateOf("…") }
    var proot by remember { mutableStateOf("…") }
    var platform by remember { mutableStateOf("…") }
    LaunchedEffect(Unit) {
        val c = DroidcClient(engineEnvFrom(ctx))
        droidc = c.version(); proot = c.prootVersion()
        platform = withContext(Dispatchers.IO) {
            when (val o = c.platform()) {
                is DroidcOutcome.Success -> o.data?.let { SelkieJson.encodeToString(
                    kotlinx.serialization.json.JsonElement.serializer(), it) } ?: "?"
                is DroidcOutcome.Failure -> "unavailable (${o.error.message})"
            }
        }
    }
    AboutContent(droidc, proot, platform, onOpenSource = { uri.openUri(BuildConfig.PROOT_SOURCE_URL) }, onBack)
}

@Composable
fun AboutContent(droidc: String, proot: String, platform: String, onOpenSource: () -> Unit, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("About Selkie", style = MaterialTheme.typography.headlineMedium)
        Text("Selkie ${BuildConfig.VERSION_NAME} (MIT license)")
        Text("droidc: $droidc")
        Text("proot: $proot")
        HorizontalDivider()
        Text("Device", style = MaterialTheme.typography.titleMedium)
        Text("Model: ${Build.MANUFACTURER} ${Build.MODEL}")
        if (Build.VERSION.SDK_INT >= 31) Text("SoC: ${Build.SOC_MODEL}")
        Text("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ABIs ${Build.SUPPORTED_ABIS.joinToString()}")
        Text("droidc platform: $platform", style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        Text("Licenses", style = MaterialTheme.typography.titleMedium)
        Text("Selkie: MIT.")
        Text("Termux terminal-view / terminal-emulator: Apache-2.0 (Termux terminal library exception).")
        Text("proot: GPL-2.0-or-later. Corresponding source is attached to the Selkie GitHub release.")
        TextButton(onClick = onOpenSource) { Text("proot source (GPL-2, on this release)") }
        Text("Terminal font: JetBrains Mono Nerd Font Mono (no ligatures), SIL Open Font License 1.1. Copyright 2020 The JetBrains Mono Project Authors; patched by Nerd Fonts.")
        Text("kotlinx.serialization, kotlinx.coroutines, AndroidX, Jetpack Compose: Apache-2.0.")
        Spacer(Modifier.height(8.dp))
        Button(onClick = onBack) { Text("Back") }
    }
}

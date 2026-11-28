package io.github.keplersj.selkie.ui

import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Process
import android.os.StatFs
import android.os.storage.StorageManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.keplersj.selkie.BuildConfig
import io.github.keplersj.selkie.platform.PhantomProcessCheck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The phantom process row's two lines, from what [PhantomProcessCheck] found. */
internal fun phantomText(state: PhantomProcessCheck.State): Pair<String, String> = when (state) {
    PhantomProcessCheck.State.OK -> "Child process restrictions are off" to
        "Android won't kill container processes. Checked each time Selkie opens."
    PhantomProcessCheck.State.RESTRICTED -> "Child process restrictions are on" to
        "Android may kill container processes when too many run. Turn the restriction off in developer options."
    PhantomProcessCheck.State.UNVERIFIED -> "Child process restrictions unknown" to
        "Selkie couldn't read the setting on this device. Check it in developer options."
    PhantomProcessCheck.State.NOT_APPLICABLE -> "No child process setting" to
        "This Android version has no restriction to turn off."
}

/**
 * Settings: this device's state as it bears on Selkie, and what Selkie does about the desktop and logs.
 * Only what Selkie actually reads or does is listed; the design's planned rows wait for their features.
 */
@Composable
fun SettingsContent(
    rail: Boolean,
    phantom: PhantomProcessCheck.State,
    notificationsOn: Boolean,
    /** Bytes Selkie's app and data take, or null while it is read. */
    usedBytes: Long?,
    freeBytes: Long,
    suFound: Boolean,
    debugLogs: Boolean,
    version: String,
    onPhantomHelp: () -> Unit,
    onNotificationSettings: () -> Unit,
    onAbout: () -> Unit,
    onShape: () -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.widthIn(max = 840.dp).fillMaxWidth().align(Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionHeader("Settings", null, rail)

            SectionTitle("This device", Modifier.padding(top = 8.dp))
            Group {
                val (head, body) = phantomText(phantom)
                val ok = phantom == PhantomProcessCheck.State.OK || phantom == PhantomProcessCheck.State.NOT_APPLICABLE
                GroupItem(head, supporting = body,
                    leading = { Icon(if (ok) SelkieIcons.Check else SelkieIcons.Warn, contentDescription = null,
                        tint = if (ok) cs.primary else cs.error) },
                    trailing = {
                        if (phantom != PhantomProcessCheck.State.NOT_APPLICABLE)
                            TextButton(onClick = onPhantomHelp) { Text("How to") }
                    })
                GroupItem(if (notificationsOn) "Notifications allowed" else "Notifications off",
                    supporting = if (notificationsOn) "Shows progress and what is running"
                        else "Selkie can't show progress or what is running",
                    leading = { Icon(SelkieIcons.Bell, contentDescription = null) },
                    trailing = { TextButton(onClick = onNotificationSettings) { Text(if (notificationsOn) "Change" else "Turn on") } })
                GroupItem(usedBytes?.let { "${formatBytes(it)} used by Selkie" } ?: "Storage",
                    supporting = "${formatBytes(freeBytes)} free on this device",
                    leading = { Icon(SelkieIcons.Storage, contentDescription = null) })
                GroupItem(if (suFound) "Root available" else "Not rooted",
                    supporting = if (suFound) "Turn root mode on for a container in its Settings tab"
                        else "Root mode needs Magisk, KernelSU or APatch",
                    leading = { Icon(SelkieIcons.Root, contentDescription = null) })
            }

            SectionTitle("Desktop", Modifier.padding(top = 8.dp))
            Group {
                GroupItem("When the device changes shape", supporting = "Resize, or offer the session made for it",
                    leading = { Icon(SelkieIcons.Desktop, contentDescription = null) },
                    trailing = { Icon(SelkieIcons.Chevron, contentDescription = null) },
                    onClick = onShape)
                GroupItem("Keyboard layout", supporting = "US only for now",
                    leading = { Icon(SelkieIcons.Keyboard, contentDescription = null) })
                GroupItem("User services", supporting = "Uses the image's own systemd user services when it has them",
                    leading = { Icon(SelkieIcons.Desktop, contentDescription = null) })
            }

            SectionTitle("Advanced", Modifier.padding(top = 8.dp))
            Group {
                GroupItem("Container logs in logcat",
                    supporting = if (debugLogs) "On in this debug build. Logs can include paths and user names."
                        else "Only debug builds send them",
                    leading = { Icon(SelkieIcons.Logs, contentDescription = null) })
                GroupItem("About Selkie", supporting = "$version · droidc, licences and this device",
                    leading = { Icon(SelkieIcons.Info, contentDescription = null) },
                    trailing = { Icon(SelkieIcons.Chevron, contentDescription = null) },
                    onClick = onAbout)
            }
        }
    }
}

/** [SettingsContent] for this device. */
@Composable
fun SettingsScreen(rail: Boolean, phantom: PhantomProcessCheck.State, suFound: Boolean,
                   onPhantomHelp: () -> Unit, onAbout: () -> Unit, onShape: () -> Unit = {}) {
    val ctx = LocalContext.current
    var notificationsOn by remember { mutableStateOf(NotificationManagerCompat.from(ctx).areNotificationsEnabled()) }
    LifecycleResumeEffect(Unit) {
        notificationsOn = NotificationManagerCompat.from(ctx).areNotificationsEnabled()
        onPauseOrDispose {}
    }
    val used by produceState<Long?>(null) { value = withContext(Dispatchers.IO) { usedBySelkie(ctx) } }
    val free by produceState(0L) {
        value = withContext(Dispatchers.IO) { runCatching { StatFs(ctx.filesDir.path).availableBytes }.getOrDefault(0L) }
    }
    SettingsContent(rail, phantom, notificationsOn, used, free, suFound, BuildConfig.DEBUG, BuildConfig.VERSION_NAME,
        onPhantomHelp = onPhantomHelp,
        onNotificationSettings = {
            runCatching {
                ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName))
            }
        },
        onAbout = onAbout, onShape = onShape)
}

/** App, data and cache bytes, as Android's storage settings count them; null if Android won't say. */
private fun usedBySelkie(ctx: Context): Long? = runCatching {
    val stats = ctx.getSystemService(StorageStatsManager::class.java)
        .queryStatsForUid(StorageManager.UUID_DEFAULT, Process.myUid())
    stats.appBytes + stats.dataBytes // dataBytes already counts the cache
}.getOrNull()

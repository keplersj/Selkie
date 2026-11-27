package io.github.keplersj.selkie.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.keplersj.selkie.engine.AppEntry
import io.github.keplersj.selkie.engine.AppList
import io.github.keplersj.selkie.engine.AppOrigin
import io.github.keplersj.selkie.engine.HiddenApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// The container's Apps tab (canvas: Phone-Apps, Phone-AppSheet): its applications, read by `droidc apps`
// from their desktop entries. An app opens on its own, full screen in Selkie's compositor; one whose
// entry names desktops (OnlyShowIn) opens the whole desktop instead.

/** Freedesktop main categories, as the filter chips name them. */
private val CATEGORY_NAMES = linkedMapOf(
    "Utility" to "Utilities",
    "Development" to "Development",
    "Office" to "Office",
    "Graphics" to "Graphics",
    "Network" to "Internet",
    "AudioVideo" to "Media",
    "Game" to "Games",
    "Education" to "Education",
    "Science" to "Science",
    "System" to "System",
    "Settings" to "Settings",
)

/** The friendly name of [app]'s first main category, if it has one. */
fun mainCategory(app: AppEntry): String? = app.categories.firstNotNullOfOrNull { CATEGORY_NAMES[it] }

/** Filter chips after "All": the main categories the apps use, most used first, at most [max]. */
fun categoryFilters(apps: List<AppEntry>, max: Int = 4): List<String> =
    apps.mapNotNull(::mainCategory).groupingBy { it }.eachCount().entries
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        .take(max).map { it.key }

/** How the Apps tab groups a container's apps: the image's first, then Flatpaks, then the user's own. */
enum class AppGroup(val title: String?) { Image(null), Flatpak("Flatpaks"), User("Installed by you") }

fun appGroup(app: AppEntry): AppGroup = when (app.origin) {
    AppOrigin.IMAGE -> AppGroup.Image
    AppOrigin.FLATPAK, AppOrigin.USER_FLATPAK -> AppGroup.Flatpak
    AppOrigin.USER -> AppGroup.User
}

/** What Open does for [app]: run it on its own, open the desktop it needs, or nothing (a terminal program). */
enum class AppOpen { Alone, Desktop, None }

fun appOpen(app: AppEntry): AppOpen = when {
    app.terminal || app.argv.isEmpty() -> AppOpen.None
    app.needsDesktop -> AppOpen.Desktop
    else -> AppOpen.Alone
}

/** The line under an app's name. */
fun appSupporting(app: AppEntry): String = when {
    app.terminal -> "Runs in a terminal"
    app.needsDesktop -> "Needs the full desktop"
    app.origin == AppOrigin.USER -> "From " + app.path.replace(Regex("^/(var/)?home/[^/]+/"), "~/").substringBeforeLast('/')
    else -> app.comment ?: app.genericName ?: mainCategory(app) ?: app.id.removeSuffix(".desktop")
}

/** The note under the list about entries left out; null when none are. */
fun hiddenNote(hidden: List<HiddenApp>): String? {
    if (hidden.isEmpty()) return null
    val reasons = hidden.map { it.reason }.toSet()
    val why = buildList {
        if ("no-display" in reasons || "hidden" in reasons) add("marked not for display")
        if ("not-installed" in reasons) add("program not installed")
        if ("invalid" in reasons) add("not an app Selkie can open")
    }
    val n = hidden.size
    val head = if (n == 1) "1 entry is hidden" else "$n entries are hidden"
    return if (why.isEmpty()) "$head." else "$head: " + when (why.size) {
        1 -> why[0]
        2 -> "${why[0]}, or ${why[1]}"
        else -> "${why[0]}, ${why[1]}, or ${why[2]}"
    } + "."
}

@Composable
internal fun AppsTab(
    container: String,
    apps: AppList?,
    error: String?,
    installed: Boolean,
    /** Opens an app (or its action); returns why it can't, or null. */
    onOpen: (AppEntry) -> String?,
    onOpenDesktop: () -> Unit,
    initialSheet: String? = null,
) {
    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    var sheet by rememberSaveable { mutableStateOf(initialSheet) }
    var message by remember { mutableStateOf<String?>(null) }
    val open: (AppEntry) -> Unit = { app ->
        message = when (appOpen(app)) {
            AppOpen.Desktop -> { onOpenDesktop(); null }
            else -> onOpen(app)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        when {
            !installed -> MutedText("Install an image in this container to see its apps.")
            error != null -> SelkieCard(Modifier.fillMaxWidth(), tone = CardTone.Outlined) {
                Text("Selkie couldn't read this container's apps", style = MaterialTheme.typography.titleMedium)
                MutedText(error)
            }
            apps == null -> Box(Modifier.fillMaxWidth().padding(32.dp), Alignment.Center) { CircularProgressIndicator() }
            else -> {
                MutedText("Read from the container's app entries. An app opens on its own, full screen; " +
                    "one made for a desktop opens inside it.")
                val chips = categoryFilters(apps.apps)
                if (chips.isNotEmpty()) Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(selected = filter == null, onClick = { filter = null }, label = { Text("All") })
                    for (c in chips) FilterChip(selected = filter == c, onClick = { filter = if (filter == c) null else c },
                        label = { Text(c) })
                }
                message?.let { m ->
                    SelkieCard(Modifier.fillMaxWidth(), tone = CardTone.Tertiary) { Text(m) }
                }
                val shown = apps.apps.filter { filter == null || mainCategory(it) == filter }
                if (apps.apps.isEmpty()) MutedText("This container has no apps with desktop entries.")
                else if (shown.isEmpty()) MutedText("No apps in $filter.")
                for (g in AppGroup.entries) {
                    val inGroup = shown.filter { appGroup(it) == g }
                    if (inGroup.isEmpty()) continue
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        g.title?.let { SectionTitle(it) }
                        Group {
                            for (app in inGroup) AppRow(app, onOpen = { open(app) }, onDetails = { sheet = app.id })
                        }
                    }
                }
                hiddenNote(apps.hidden)?.let { MutedText(it) }
            }
        }
    }
    val sheetApp = apps?.apps?.firstOrNull { it.id == sheet }
    if (sheetApp != null) AppSheet(container, sheetApp, onDismiss = { sheet = null },
        onOpen = { a -> sheet = null; open(a) })
}

@Composable
private fun AppRow(app: AppEntry, onOpen: () -> Unit, onDetails: () -> Unit) {
    GroupItem(
        headline = app.name,
        supporting = appSupporting(app),
        leading = { AppIconView(app) },
        trailing = {
            when (appOpen(app)) {
                AppOpen.Alone -> FilledTonalButton(onClick = onOpen) { Text("Open") }
                AppOpen.Desktop -> OutlinedButton(onClick = onOpen) { Text("Desktop") }
                AppOpen.None -> {}
            }
        },
        onClick = onDetails,
        onClickLabel = "Show options for ${app.name}",
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppSheet(container: String, app: AppEntry, onDismiss: () -> Unit, onOpen: (AppEntry) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        AppSheetContent(container, app, onOpen)
    }
}

@Composable
internal fun AppSheetContent(container: String, app: AppEntry, onOpen: (AppEntry) -> Unit) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            AppIconView(app, size = 56.dp)
            Column(Modifier.weight(1f)) {
                Text(app.name, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                MutedText("In $container · ${app.id.removeSuffix(".desktop")}")
            }
        }
        when (appOpen(app)) {
            AppOpen.Alone -> Button(onClick = { onOpen(app) }, Modifier.fillMaxWidth()) { Text("Open") }
            AppOpen.Desktop -> Button(onClick = { onOpen(app) }, Modifier.fillMaxWidth()) { Text("Open the desktop") }
            AppOpen.None -> {}
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle("Opens as")
            Group {
                when (appOpen(app)) {
                    AppOpen.Alone -> GroupItem("Its own window", supporting = "Full screen, with nothing around it. " +
                        "Back shows Selkie's controls.")
                    AppOpen.Desktop -> GroupItem("Inside the desktop", supporting = "Its entry is for " +
                        app.onlyShowIn.joinToString(", ") + ", so Selkie starts the desktop and you open it there.")
                    AppOpen.None -> GroupItem("A terminal program", supporting = "Run it from a terminal in $container.")
                }
            }
        }
        val actions = app.actions.takeIf { appOpen(app) == AppOpen.Alone }.orEmpty()
        if (actions.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle("Actions from the app")
            Group {
                for (a in actions) GroupItem(a.name, trailing = {
                    OutlinedButton(onClick = {
                        onOpen(app.copy(id = app.id + "#" + a.id, name = "${app.name}: ${a.name}", argv = a.argv, actions = emptyList()))
                    }) { Text("Run") }
                })
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle("Details")
            Group {
                if (app.exec.isNotBlank()) DetailItem("Command", app.exec, mono = true)
                DetailItem("Entry", app.path, mono = true)
                if (app.mimeTypes.isNotEmpty()) DetailItem("Opens", app.mimeTypes.take(4).joinToString(", ") +
                    if (app.mimeTypes.size > 4) " and ${app.mimeTypes.size - 4} more" else "")
            }
        }
    }
}

// ---------------------------------------------------------------- icons

private val iconCache = LruCache<String, Bitmap>(64)

/** Draws [path] (PNG or SVG) at [px] pixels square; null if it can't be read. */
internal fun loadAppIcon(path: String, format: String?, px: Int): Bitmap? {
    val key = "$px:$path"
    iconCache.get(key)?.let { return it }
    val bmp = runCatching {
        when (format) {
            "png" -> BitmapFactory.decodeFile(path)?.let { Bitmap.createScaledBitmap(it, px, px, true) }
            "svg" -> File(path).inputStream().use { input ->
                val svg = com.caverock.androidsvg.SVG.getFromInputStream(input)
                svg.setDocumentWidth(px.toFloat())
                svg.setDocumentHeight(px.toFloat())
                Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888).also { svg.renderToCanvas(Canvas(it)) }
            }
            else -> null
        }
    }.getOrNull()
    if (bmp != null) iconCache.put(key, bmp)
    return bmp
}

/** The app's own icon from the container's icon theme, or its initial in a mark. */
@Composable
fun AppIconView(app: AppEntry, size: Dp = 40.dp) {
    val icon = app.icon
    val px = with(LocalDensity.current) { size.roundToPx() }
    val image by produceState<ImageBitmap?>(null, icon?.hostPath, px) {
        val ic = icon ?: return@produceState
        val path = ic.hostPath ?: return@produceState
        value = withContext(Dispatchers.IO) { loadAppIcon(path, ic.format, px) }?.asImageBitmap()
    }
    val img = image
    if (img != null) Image(img, contentDescription = null, Modifier.size(size))
    else Mark(size = size, style = MarkStyle.Neutral) { Text(app.name.take(1).uppercase()) }
}

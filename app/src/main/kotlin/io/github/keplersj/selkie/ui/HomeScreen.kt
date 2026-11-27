package io.github.keplersj.selkie.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.keplersj.selkie.engine.Deployment
import io.github.keplersj.selkie.engine.AppEntry
import io.github.keplersj.selkie.service.DesktopState
import io.github.keplersj.selkie.service.Operation
import io.github.keplersj.selkie.service.ServiceState
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Test tag on every control that opens the desktop. */
const val OPEN_DESKTOP_TAG = "open-desktop"

/** Matches the picker's "checked before anything is downloaded": droidc verifies the manifest before pulling layers. */
internal const val SIGNATURE_TIMING = "checked before it was downloaded"

/** Below this width the list and a container are separate screens; from it up they sit side by side. */
private val LIST_DETAIL_WIDTH = 840.dp
/** The detail pane puts Overview in two columns from this width. */
private val DETAIL_COLUMNS_WIDTH = 600.dp

/** A container's tabs. */
enum class ContainerTab(val label: String) { Overview("Overview"), Apps("Apps"), Updates("Updates"), Settings("Settings") }

/** What the list shows about one container. */
data class ContainerSummary(
    val name: String,
    val imageRef: String?,
    val terminals: Int,
    val desktop: Boolean,
    /** Only known for the selected container. */
    val insecure: Boolean = false,
) {
    val running: Boolean get() = terminals > 0 || desktop
}

/** Containers with an image, in droidc's order. The empty default one Selkie keeps for selection is left out. */
fun summaries(state: ServiceState): List<ContainerSummary> = state.containers.mapNotNull { c ->
    val selected = c.name == state.container
    val ref = c.current?.imageRef ?: if (selected) state.status?.current?.imageRef else null
    if (ref == null) return@mapNotNull null
    ContainerSummary(
        name = c.name,
        imageRef = ref,
        terminals = state.sessions.count { it.container == c.name && it.running },
        desktop = state.desktopContainer == c.name &&
            (state.desktop is DesktopState.Running || state.desktop is DesktopState.Starting),
        insecure = selected && state.status?.current?.insecure == true,
    )
}

/** "18 Nov" from an RFC 3339 time; the raw text if it doesn't parse. */
fun shortDate(iso: String?): String? = iso?.let {
    runCatching { OffsetDateTime.parse(it).format(DateTimeFormatter.ofPattern("d MMM", Locale.US)) }.getOrDefault(it)
}

/** `https://github.com/projectbluefin/common/...` -> `projectbluefin`. */
internal fun signer(subject: String?): String? = subject?.let {
    Regex("^https://github\\.com/([^/]+)/").find(it)?.groupValues?.get(1) ?: it.takeIf { s -> s.length <= 40 }
}

private fun plural(n: Int, one: String) = "$n $one" + if (n == 1) "" else "s"

/**
 * Home: the containers. On a phone it is the list, and a container opens on its own screen with tabs.
 * From [LIST_DETAIL_WIDTH] up, the list and the selected container sit side by side.
 */
@Composable
fun HomeScreen(
    state: ServiceState,
    onOpenTerminal: () -> Unit,
    onOpenDesktop: () -> Unit,
    onConfigureDesktop: () -> Unit,
    onUpgrade: () -> Unit,
    onRollback: () -> Unit,
    onSwitch: () -> Unit,
    onRefresh: () -> Unit,
    onSelectContainer: (String) -> Unit = {},
    /** Opens the new container screen. */
    onNewContainer: () -> Unit = {},
    onRemoveContainer: (name: String, onDone: (String?) -> Unit) -> Unit = { _, _ -> },
    onRootMode: (Boolean) -> Unit = {},
    /** Opens one of the selected container's apps on its own; returns why it can't, or null. */
    onOpenApp: (AppEntry) -> String? = { null },
    /** False where the navigation rail carries the New container button instead. */
    newInList: Boolean = true,
    /** Told when a phone shows one container on its own screen, so the navigation bar steps aside. */
    onDetailShown: (Boolean) -> Unit = {},
    /** Previews open a container or a tab with these; the app starts on the list. */
    initialDetail: Boolean = false,
    initialTab: ContainerTab = ContainerTab.Overview,
    /** Each new value opens the selected container's own screen, for a link that named it. */
    openDetail: Int = 0,
    free: Long = freeBytes(),
    /** Under the list's heading, such as what driving does in a parked car. */
    listNote: (@Composable () -> Unit)? = null,
) {
    var detail by rememberSaveable { mutableStateOf(initialDetail) }
    LaunchedEffect(openDetail) { if (openDetail > 0) detail = true }
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    var removing by remember { mutableStateOf<String?>(null) }
    var removeError by remember { mutableStateOf<String?>(null) }
    val list = summaries(state)

    val open: (String) -> Unit = { name ->
        if (name != state.container) { onSelectContainer(name); tab = ContainerTab.Overview }
        detail = true
    }
    val terminal: (String) -> Unit = { name -> if (name != state.container) onSelectContainer(name); onOpenTerminal() }
    val actions = DetailActions(
        onOpenTerminal = onOpenTerminal, onOpenDesktop = onOpenDesktop, onConfigureDesktop = onConfigureDesktop,
        onUpgrade = onUpgrade, onRollback = onRollback, onChangeImage = onSwitch,
        onRemove = { removing = state.container }, onRootMode = onRootMode, onRefresh = onRefresh,
        onOpenApp = onOpenApp,
    )

    val remove: (String) -> Unit = { name -> removing = name }
    val fold = LocalFold.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= LIST_DETAIL_WIDTH
        val listPane: @Composable (Modifier, Boolean) -> Unit = { m, w ->
            ContainerList(list, state, wide = w, free = free, error = removeError,
                onOpen = open, onTerminal = terminal, onDesktop = onOpenDesktop, onNew = onNewContainer,
                onRemove = remove, newButton = newInList, note = listNote, modifier = m)
        }
        val detailPanel: @Composable (Modifier) -> Unit = { m ->
            Surface(m, shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                BoxWithConstraints {
                    ContainerDetail(state, tab, { tab = it }, actions, wide = true, onBack = null,
                        columns = maxWidth >= DETAIL_COLUMNS_WIDTH)
                }
            }
        }
        when {
            list.isEmpty() && state.statusError == null -> EmptyContainers(free, onNewContainer)
            // Open like a book: the list on one half and the container on the other, nothing across the hinge.
            fold?.book == true -> HingePanes(fold,
                first = { listPane(Modifier.fillMaxSize(), true) },
                second = { detailPanel(Modifier.fillMaxSize().padding(top = 16.dp, start = 12.dp, end = 16.dp, bottom = 16.dp)) })
            wide -> Row(Modifier.fillMaxSize()) {
                listPane(Modifier.width(392.dp).fillMaxHeight(), true)
                detailPanel(Modifier.weight(1f).fillMaxHeight().padding(top = 16.dp, end = 16.dp, bottom = 16.dp))
            }
            detail -> {
                DisposableEffect(Unit) { onDetailShown(true); onDispose { onDetailShown(false) } }
                ContainerDetail(state, tab, { tab = it }, actions, wide = false, onBack = { detail = false })
            }
            else -> listPane(Modifier.fillMaxSize(), false)
        }
    }

    removing?.let { name ->
        val summary = list.firstOrNull { it.name == name }
        RemoveDialog(
            name = name,
            summary = summary,
            sharedWith = list.filter { it.name != name && summary?.imageRef != null && it.imageRef == summary.imageRef }
                .map { it.name },
            busy = state.operation is Operation.Running,
            onDismiss = { removing = null },
            onConfirm = {
                removing = null; removeError = null; detail = false
                onRemoveContainer(name) { removeError = it }
            },
        )
    }
}

private class DetailActions(
    val onOpenTerminal: () -> Unit,
    val onOpenDesktop: () -> Unit,
    val onConfigureDesktop: () -> Unit,
    val onUpgrade: () -> Unit,
    val onRollback: () -> Unit,
    val onChangeImage: () -> Unit,
    val onRemove: () -> Unit,
    val onRootMode: (Boolean) -> Unit,
    val onRefresh: () -> Unit,
    val onOpenApp: (AppEntry) -> String?,
)

/** The desktop belongs to another container. Selkie runs one desktop at a time. */
private fun desktopElsewhere(state: ServiceState): String? =
    state.desktopContainer?.takeIf { it != state.container && state.desktop !is DesktopState.Stopped }

// ---------------------------------------------------------------- list

@Composable
private fun ContainerList(
    list: List<ContainerSummary>,
    state: ServiceState,
    wide: Boolean,
    free: Long,
    error: String?,
    onOpen: (String) -> Unit,
    onTerminal: (String) -> Unit,
    onDesktop: () -> Unit,
    onNew: () -> Unit,
    onRemove: (String) -> Unit,
    newButton: Boolean,
    note: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val running = list.count { it.running }
    Box(modifier) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = if (wide) 12.dp else 16.dp, top = 8.dp, bottom = if (wide) 24.dp else 104.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.padding(start = 4.dp, top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Containers", Modifier.semantics { heading() },
                    style = if (wide) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.displaySmall)
                MutedText(listOfNotNull(if (running > 0) "$running running" else null, "${formatBytes(free)} free")
                    .joinToString(" · "))
            }
            if (wide && newButton) {
                Button(onClick = onNew, Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Icon(SelkieIcons.Plus, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("New container")
                }
            }
            note?.invoke()
            error?.let { SelkieCard(Modifier.fillMaxWidth(), tone = CardTone.Error) { Text("Could not remove the container: $it") } }
            state.statusError?.let { SelkieCard(Modifier.fillMaxWidth(), tone = CardTone.Error) { Text("Could not read status: $it") } }
            for (c in list) {
                ContainerCard(c, state, highlighted = wide && c.name == state.container,
                    onOpen = { onOpen(c.name) }, onTerminal = { onTerminal(c.name) }, onDesktop = onDesktop,
                    onRemove = { onRemove(c.name) })
            }
        }
        if (!wide && newButton) {
            ExtendedFloatingActionButton(
                onClick = onNew,
                icon = { Icon(SelkieIcons.Plus, contentDescription = null) },
                text = { Text("New container") },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                shape = RoundedCornerShape(20.dp),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ContainerCard(
    c: ContainerSummary,
    state: ServiceState,
    highlighted: Boolean,
    onOpen: () -> Unit,
    onTerminal: () -> Unit,
    onDesktop: () -> Unit,
    onRemove: () -> Unit,
) {
    // Desktop details are only known for the selected container, so only its card offers the desktop.
    val graphical = state.graphical.takeIf { c.name == state.container && state.statusLoaded }
    val canDesktop = graphical?.configured == true && desktopElsewhere(state) == null
    val shape = MaterialTheme.shapes.extraLarge
    var menu by remember { mutableStateOf(false) }
    // The same choices as the right-click menu, for screen readers and switch access.
    val menuActions = buildList {
        add(CustomAccessibilityAction("Open a terminal") { onTerminal(); true })
        if (canDesktop) add(CustomAccessibilityAction("Open the desktop") { onDesktop(); true })
        add(CustomAccessibilityAction("Remove ${c.name}") { onRemove(); true })
    }
    Box {
    SelkieCard(
        Modifier.fillMaxWidth().clip(shape)
            .onSecondaryClick { menu = true }
            .clickable(onClickLabel = "Open ${c.name}", onClick = onOpen)
            .semantics { customActions = menuActions },
        tone = if (highlighted) CardTone.Selected else CardTone.Default,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ContainerMark(c.name)
            Column(Modifier.weight(1f)) {
                Text(c.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                MutedText(imageTitle(c.imageRef))
            }
            if (c.running) StatusChip("Running", tone = ChipTone.Ok) else StatusChip("Stopped")
        }
        val chips = buildList {
            if (c.desktop) add(Triple(graphical?.name ?: "Desktop", SelkieIcons.Desktop, ChipTone.Neutral))
            if (c.terminals > 0) add(Triple(plural(c.terminals, "terminal"), SelkieIcons.Terminal, ChipTone.Neutral))
            if (c.insecure) add(Triple("Unsigned image", SelkieIcons.Warn, ChipTone.Bad))
        }
        if (chips.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((text, icon, tone) in chips) StatusChip(text, tone = tone, icon = icon)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (canDesktop) {
                Button(onClick = onDesktop, Modifier.weight(1f).testTag(OPEN_DESKTOP_TAG),
                    shape = RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp, topEnd = 8.dp, bottomEnd = 8.dp)) {
                    Icon(SelkieIcons.Desktop, contentDescription = null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp)); Text("Desktop")
                }
            }
            val terminalLabel: @Composable RowScope.() -> Unit = {
                Icon(SelkieIcons.Terminal, contentDescription = null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp)); Text("Terminal")
            }
            // Terminal is the card's main action when there's no desktop to open, so it gets the filled style;
            // a tonal button would melt into a selected card.
            if (canDesktop) FilledTonalButton(onClick = onTerminal, Modifier.weight(1f),
                shape = RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp, topEnd = 24.dp, bottomEnd = 24.dp),
                content = terminalLabel)
            else Button(onClick = onTerminal, Modifier.weight(1f), shape = RoundedCornerShape(24.dp), content = terminalLabel)
        }
    }
    // Right-click (mouse or trackpad) opens the container's menu, like on a desktop.
    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
        DropdownMenuItem(text = { Text("Open") }, onClick = { menu = false; onOpen() })
        DropdownMenuItem(text = { Text("Open a terminal") }, onClick = { menu = false; onTerminal() },
            leadingIcon = { Icon(SelkieIcons.Terminal, null) })
        if (canDesktop) DropdownMenuItem(text = { Text("Open the desktop") }, onClick = { menu = false; onDesktop() },
            leadingIcon = { Icon(SelkieIcons.Desktop, null) })
        HorizontalDivider()
        DropdownMenuItem(text = { Text("Remove…") }, onClick = { menu = false; onRemove() },
            leadingIcon = { Icon(SelkieIcons.Trash, null) })
    }
    }
}

/** Calls [onClick] on a secondary (right) button press and keeps it from also counting as a tap. */
private fun Modifier.onSecondaryClick(onClick: () -> Unit): Modifier = composed {
    val current by rememberUpdatedState(onClick)
    pointerInput(Unit) {
        awaitEachGesture {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                event.changes.forEach { it.consume() }
                current()
            }
        }
    }
}

/** No container has an image yet: say what a container is, and make one. */
@Composable
private fun EmptyContainers(free: Long, onNew: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text("Containers", Modifier.padding(start = 4.dp, top = 8.dp).semantics { heading() },
            style = MaterialTheme.typography.displaySmall)
        Column(
            Modifier.widthIn(max = 480.dp).fillMaxWidth().align(Alignment.CenterHorizontally)
                .padding(horizontal = 16.dp, vertical = 64.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Mark(size = 96.dp) { Icon(SelkieIcons.Containers, contentDescription = null, Modifier.size(44.dp)) }
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("No containers yet", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                MutedText("A container is a full Linux system from an image, with its own home folder. " +
                    ImageCatalog.emptyStateLead(),
                    style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
            }
            Button(onClick = onNew, Modifier.fillMaxWidth().heightIn(min = 72.dp), shape = MaterialTheme.shapes.extraLarge) {
                Icon(SelkieIcons.Plus, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("New container", style = MaterialTheme.typography.titleMedium)
            }
            MutedText("Images are a few gigabytes. You have ${formatBytes(free)} free.", textAlign = TextAlign.Center)
        }
    }
}

// ---------------------------------------------------------------- one container

@Composable
private fun ContainerDetail(
    state: ServiceState,
    tab: ContainerTab,
    onTab: (ContainerTab) -> Unit,
    actions: DetailActions,
    wide: Boolean,
    /** Null where the list is beside it. */
    onBack: (() -> Unit)?,
    /** Status and "What works here" side by side; off when the pane is too narrow, as on half a fold. */
    columns: Boolean = wide,
) {
    if (onBack != null) BackHandler(onBack = onBack)
    val cur = state.status?.current
    val running = state.sessions.any { it.container == state.container && it.running } ||
        (state.desktopContainer == state.container && state.desktop !is DesktopState.Stopped)
    Column(Modifier.fillMaxSize()) {
        if (onBack != null) SelkieTopBar(title = null, navIcon = SelkieIcons.Back, navLabel = "Back to containers", onNav = onBack)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = if (wide) 24.dp else 16.dp, vertical = if (wide) 24.dp else 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ContainerMark(state.container, size = 64.dp)
                Column(Modifier.weight(1f)) {
                    Text(state.container, Modifier.semantics { heading() },
                        style = if (wide) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.displaySmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (wide && cur != null) Text(cur.imageRef, style = MonoStyle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else MutedText(imageTitle(cur?.imageRef) + " · " + if (running) "running" else "stopped")
                }
                if (wide) OutlinedButton(onClick = actions.onRemove, enabled = state.operation !is Operation.Running,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Icon(SelkieIcons.Trash, contentDescription = null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp)); Text("Remove…")
                }
            }
            Tabs(tab, onTab)
            when {
                !state.statusLoaded -> Box(Modifier.fillMaxWidth().padding(32.dp), Alignment.Center) { CircularProgressIndicator() }
                tab == ContainerTab.Overview -> Overview(state, actions, columns)
                tab == ContainerTab.Apps -> AppsTab(state.container, state.apps, state.appsError,
                    installed = cur != null, onOpen = actions.onOpenApp, onOpenDesktop = actions.onOpenDesktop)
                tab == ContainerTab.Updates -> Updates(state, actions)
                else -> Settings(state, actions)
            }
        }
    }
}

/** The canvas's pill tabs, read as tabs by TalkBack. */
@Composable
private fun Tabs(tab: ContainerTab, onTab: (ContainerTab) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (t in ContainerTab.entries) {
            val on = t == tab
            Box(
                Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(24.dp))
                    .background(if (on) cs.secondaryContainer else Color.Transparent)
                    .selectable(selected = on, role = Role.Tab, onClick = { onTab(t) })
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(t.label, style = MaterialTheme.typography.labelLarge,
                    color = if (on) cs.onSecondaryContainer else cs.onSurfaceVariant)
            }
        }
    }
}

/** A 72dp button with a title, a second line and a chevron, as on the container overview. */
@Composable
private fun BigAction(
    title: String,
    supporting: String,
    icon: ImageVector,
    primary: Boolean,
    shape: RoundedCornerShape,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val content: @Composable RowScope.() -> Unit = {
        Icon(icon, contentDescription = null)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(supporting, style = MaterialTheme.typography.labelMedium)
        }
        Icon(SelkieIcons.Chevron, contentDescription = null)
    }
    val m = modifier.fillMaxWidth().heightIn(min = 72.dp)
    val pad = PaddingValues(horizontal = 24.dp, vertical = 12.dp)
    if (primary) Button(onClick = onClick, modifier = m, shape = shape, contentPadding = pad, content = content)
    else FilledTonalButton(onClick = onClick, modifier = m, shape = shape, contentPadding = pad, content = content)
}

@Composable
private fun Overview(state: ServiceState, a: DetailActions, wide: Boolean) {
    val cur = state.status?.current
    if (cur == null) {
        SelkieCard(Modifier.fillMaxWidth(), tone = CardTone.Outlined) {
            Text("Nothing is installed in this container", style = MaterialTheme.typography.titleMedium)
            MutedText("Choose an image for it, or remove it.")
            Button(onClick = a.onChangeImage) { Text("Choose image") }
        }
        return
    }
    val graphical = state.graphical
    val terminals = state.sessions.count { it.container == state.container && it.running }
    val other = desktopElsewhere(state)
    val desktopHere = state.desktopContainer == state.container &&
        (state.desktop is DesktopState.Running || state.desktop is DesktopState.Starting)
    val top = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp, bottomStart = 12.dp, bottomEnd = 12.dp)
    val bottom = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp, bottomStart = 28.dp, bottomEnd = 28.dp)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            graphical == null -> {}
            graphical.configured && other == null -> BigAction(
                title = if (desktopHere) "Return to desktop" else "Open desktop",
                supporting = (graphical.name ?: "Desktop") + if (desktopHere) " · running" else "",
                icon = SelkieIcons.Desktop, primary = true, shape = top, onClick = a.onOpenDesktop,
                modifier = Modifier.testTag(OPEN_DESKTOP_TAG))
            graphical.configured -> SelkieCard(Modifier.fillMaxWidth(), tone = CardTone.Outlined, shape = top) {
                Text("The desktop is open in $other", style = MaterialTheme.typography.titleMedium)
                MutedText("Selkie shows one desktop at a time. Stop it there to open this one.")
            }
            else -> BigAction("Set up a desktop", "This image doesn't say how to start one",
                SelkieIcons.Desktop, primary = false, shape = top, onClick = a.onConfigureDesktop)
        }
        BigAction(
            title = "Terminals",
            supporting = if (terminals > 0) "$terminals open · return or start another" else "Open a terminal",
            icon = SelkieIcons.Terminal, primary = graphical?.configured != true,
            shape = if (graphical == null) RoundedCornerShape(28.dp) else bottom,
            onClick = a.onOpenTerminal)
    }

    val status: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle("Status")
            Group {
                SignatureItem(cur)
                cur.stats?.let {
                    GroupItem("${formatBytes(it.bytesOnDisk)} on this device",
                        supporting = "System image · " + String.format(Locale.US, "%,d", it.files) + " files",
                        leading = { Icon(SelkieIcons.Storage, contentDescription = null) })
                }
            }
        }
    }
    val works: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle("What works here")
            Group {
                GroupItem("Homebrew, networking and the session bus",
                    leading = { Icon(SelkieIcons.Check, contentDescription = null) })
                GroupItem("Podman and Flatpak don't work yet",
                    supporting = "They need a sandbox that droidc is still adding. Use Homebrew for now.",
                    leading = { Icon(SelkieIcons.Block, contentDescription = null) })
            }
        }
    }
    if (wide) Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(Modifier.weight(1f)) { status() }
        Box(Modifier.weight(1f)) { works() }
    } else { status(); works() }
}

@Composable
private fun SignatureItem(d: Deployment) {
    val sig = d.signature
    val cs = MaterialTheme.colorScheme
    when {
        d.insecure || sig?.verified == false -> GroupItem("Unsigned image",
            supporting = "Installed without a signature check, so Selkie can't tell who made it",
            leading = { Icon(SelkieIcons.Warn, contentDescription = null, tint = cs.error) })
        sig?.verified == true -> GroupItem("Trusted image",
            supporting = (signer(sig.subject)?.let { "Signed by $it, " } ?: "Signature ") + SIGNATURE_TIMING,
            leading = { Icon(SelkieIcons.Shield, contentDescription = null, tint = cs.primary) })
        else -> GroupItem("Signature not recorded", supporting = "An older Selkie installed this version",
            leading = { Icon(SelkieIcons.Shield, contentDescription = null) })
    }
}

@Composable
private fun Updates(state: ServiceState, a: DetailActions) {
    val cur = state.status?.current
    val prev = state.status?.previous
    val clipboard = LocalClipboardManager.current
    var confirmRollback by remember { mutableStateOf(false) }
    val busy = state.operation is Operation.Running

    SectionTitle("Versions on this device")
    Group {
        if (cur == null) GroupItem("Nothing installed", muted = true)
        else GroupItem("In use" + (shortDate(cur.deployedAt)?.let { " · installed $it" } ?: ""),
            supporting = cur.shortDigest,
            leading = { Icon(SelkieIcons.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            trailing = {
                IconButton(onClick = { clipboard.setText(AnnotatedString(cur.manifestDigest)) }) {
                    Icon(SelkieIcons.Copy, contentDescription = "Copy version id")
                }
            })
        if (prev != null) GroupItem("Previous" + (shortDate(prev.deployedAt)?.let { " · installed $it" } ?: ""),
            supporting = prev.shortDigest + " · kept so you can go back",
            leading = { Icon(SelkieIcons.Rollback, contentDescription = null) },
            trailing = { OutlinedButton(onClick = { confirmRollback = true }, enabled = !busy) { Text("Go back…") } })
    }

    SectionTitle("Image")
    Group {
        if (cur != null) {
            DetailItem("Follows", cur.imageRef, mono = true)
            GroupItem("Update",
                supporting = "Get the newest version of this image. Your files are kept, and you can go back.",
                leading = { Icon(SelkieIcons.Upgrade, contentDescription = null) },
                trailing = { FilledTonalButton(onClick = a.onUpgrade, enabled = !busy) { Text("Update") } })
        }
        GroupItem("Change image",
            supporting = "Move this container to a different image and keep your files",
            leading = { Icon(SelkieIcons.ChangeImage, contentDescription = null) },
            trailing = { Icon(SelkieIcons.Chevron, contentDescription = null) },
            onClick = if (busy) null else a.onChangeImage)
    }

    if (confirmRollback && prev != null) AlertDialog(
        onDismissRequest = { confirmRollback = false },
        icon = { Icon(SelkieIcons.Rollback, contentDescription = null) },
        title = { Text("Go back to ${prev.shortDigest}?") },
        text = { Text("The container moves back to the version installed" +
            (shortDate(prev.deployedAt)?.let { " on $it" } ?: " before this one") +
            ". Your files in /home and /var are kept. Close its terminals first.") },
        confirmButton = { TextButton(onClick = { confirmRollback = false; a.onRollback() }) { Text("Go back") } },
        dismissButton = { TextButton(onClick = { confirmRollback = false }) { Text("Cancel") } },
    )
}

@Composable
private fun Settings(state: ServiceState, a: DetailActions) {
    val graphical = state.graphical
    val cs = MaterialTheme.colorScheme
    val busy = state.operation is Operation.Running
    SectionTitle("Desktop")
    Group {
        GroupItem("Desktop session",
            supporting = when {
                graphical == null -> "Install an image first"
                graphical.configured -> (graphical.name ?: "Set up") +
                    if (graphical.source == "override") " · chosen by you" else " · from the image"
                else -> "Not set up"
            },
            leading = { Icon(SelkieIcons.Desktop, contentDescription = null) },
            trailing = { Icon(SelkieIcons.Chevron, contentDescription = null) },
            onClick = if (graphical != null) a.onConfigureDesktop else null)
    }
    if (state.suFound || state.rootMode) {
        SectionTitle("Administrator (root)")
        RootModeItem(state, a.onRootMode)
    }
    SectionTitle("Remove")
    Group {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(cs.surfaceContainer)
                .clickable(enabled = !busy, onClick = a.onRemove)
                .heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(SelkieIcons.Trash, contentDescription = null, tint = cs.error)
            Column(Modifier.weight(1f)) {
                Text("Remove container…", style = MaterialTheme.typography.bodyLarge, color = cs.error)
                Text("Deletes its system and your files in it. Asks you to confirm.",
                    style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            }
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = a.onRefresh) { Text("Refresh status") }
    }
}

/** Shown only where an su binary exists (or root mode is already on). The whole row is the switch. */
@Composable
private fun RootModeItem(state: ServiceState, onRootMode: (Boolean) -> Unit) {
    val busy = state.rootProbing || state.operation is Operation.Running
    val blocked = state.sessions.any { it.running && it.container == state.container }
    val enabled = !busy && !blocked
    val cs = MaterialTheme.colorScheme
    Group {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(cs.surfaceContainer)
                .toggleable(value = state.rootMode, enabled = enabled, role = Role.Switch, onValueChange = onRootMode)
                .heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text("Root mode", style = MaterialTheme.typography.bodyLarge)
                Text("Runs this container as real root through Magisk, KernelSU or APatch, without proot: much " +
                    "faster, real file owners and systemd user services. Linux programs then run as root on this device.",
                    style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                if (blocked) Text("Close this container's terminals to change this.",
                    style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                state.rootMessage?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = cs.primary) }
            }
            Switch(checked = state.rootMode, onCheckedChange = null, enabled = enabled)
        }
    }
}

/** Confirms removing a container, the default one included, and says what goes with it. */
@Composable
private fun RemoveDialog(
    name: String,
    summary: ContainerSummary?,
    sharedWith: List<String>,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(SelkieIcons.Trash, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
        title = { Text("Remove $name?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("This can't be undone. It deletes:")
                RemoveLine(SelkieIcons.Storage, "Everything in its home folder and /var")
                if (summary?.running == true) {
                    val what = listOfNotNull(
                        if (summary.desktop) "its desktop" else null,
                        if (summary.terminals > 0) "its " + plural(summary.terminals, "terminal") else null,
                    ).joinToString(" and ")
                    RemoveLine(SelkieIcons.Stop, what.replaceFirstChar { it.uppercase() } + ", which close now")
                }
                val image = imageTitle(summary?.imageRef)
                Text(
                    if (sharedWith.isNotEmpty()) "The $image image stays, because ${sharedWith.joinToString()} uses it."
                    else "The $image image is deleted too, unless another container still uses it.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = !busy,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError)) { Text("Remove $name") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun RemoveLine(icon: ImageVector, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, contentDescription = null, Modifier.size(20.dp))
        Text(text, Modifier.weight(1f))
    }
}

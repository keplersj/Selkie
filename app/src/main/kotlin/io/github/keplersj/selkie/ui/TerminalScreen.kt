package io.github.keplersj.selkie.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import io.github.keplersj.selkie.R
import io.github.keplersj.selkie.service.SessionService
import kotlinx.coroutines.launch

/** Termux terminal-view client: keyboard state, pinch zoom, copy/paste. */
class SelkieTerminalClient(private val context: Context) : TerminalViewClient, TerminalSessionClient {
    var view: TerminalView? = null
    @Volatile var ctrl = false
    @Volatile var alt = false
    var onToggleChange: () -> Unit = {}
    private var textSizePx = (14 * context.resources.displayMetrics.scaledDensity).toInt()

    fun initialSize() = textSizePx

    // --- TerminalViewClient ---
    override fun onScale(scale: Float): Float {
        if (scale < 0.9f || scale > 1.1f) {
            val d = context.resources.displayMetrics.scaledDensity
            textSizePx = (textSizePx + if (scale > 1f) d else -d).toInt()
                .coerceIn((8 * d).toInt(), (40 * d).toInt())
            view?.setTextSize(textSizePx)
            return 1.0f
        }
        return scale
    }
    override fun onSingleTapUp(e: MotionEvent) {
        view?.let {
            it.requestFocus()
            context.getSystemService(InputMethodManager::class.java).showSoftInput(it, 0)
        }
    }
    override fun shouldBackButtonBeMappedToEscape() = false
    override fun shouldEnforceCharBasedInput() = true
    override fun shouldUseCtrlSpaceWorkaround() = false
    override fun isTerminalViewSelected() = true
    override fun copyModeChanged(copyMode: Boolean) {}
    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession) = false
    override fun onKeyUp(keyCode: Int, e: KeyEvent) = false
    override fun onLongPress(event: MotionEvent) = false
    // Ctrl/Alt from the extra-keys row are one-shot: consumed when read.
    override fun readControlKey(): Boolean = ctrl.also { if (it) { ctrl = false; onToggleChange() } }
    override fun readAltKey(): Boolean = alt.also { if (it) { alt = false; onToggleChange() } }
    override fun readShiftKey() = false
    override fun readFnKey() = false
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession) = false
    override fun onEmulatorSet() {}

    // --- TerminalSessionClient ---
    override fun onTextChanged(s: TerminalSession) { view?.onScreenUpdated() }
    override fun onTitleChanged(s: TerminalSession) {}
    override fun onSessionFinished(s: TerminalSession) {}
    override fun onCopyTextToClipboard(s: TerminalSession, text: String?) {
        context.getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("terminal", text ?: ""))
    }
    override fun onPasteTextFromClipboard(s: TerminalSession?) { paste(s) }
    fun paste(s: TerminalSession?) {
        val cm = context.getSystemService(ClipboardManager::class.java)
        val t = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
        if (!t.isNullOrEmpty()) (s ?: view?.currentSession)?.emulator?.paste(t)
    }
    override fun onBell(s: TerminalSession) {}
    override fun onColorsChanged(s: TerminalSession) { view?.invalidate() }
    override fun onTerminalCursorStateChange(state: Boolean) {}
    override fun getTerminalCursorStyle(): Int? = null
    override fun logError(tag: String?, message: String?) {}
    override fun logWarn(tag: String?, message: String?) {}
    override fun logInfo(tag: String?, message: String?) {}
    override fun logDebug(tag: String?, message: String?) {}
    override fun logVerbose(tag: String?, message: String?) {}
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
    override fun logStackTrace(tag: String?, e: Exception?) {}
}

/** One terminal tab: the container it runs in and its title. [home] is the container Home is showing. */
@Immutable
data class TerminalTab(val container: String, val title: String, val home: Boolean)

@Composable
fun TerminalScreen(service: SessionService, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val client = remember { SelkieTerminalClient(ctx) }
    var sessions by remember { mutableStateOf(service.terminalSessions()) }
    var current by remember { mutableStateOf<TerminalSession?>(sessions.firstOrNull()) }
    var ctrlOn by remember { mutableStateOf(false) }
    var altOn by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    client.onToggleChange = { ctrlOn = client.ctrl; altOn = client.alt }
    val homeContainer = service.state.collectAsState().value.container

    DisposableEffect(service) {
        service.uiSessionClient = client
        onDispose { service.uiSessionClient = null; client.view = null }
    }
    fun open() = scope.launch {
        try {
            val s = service.openSession()
            sessions = service.terminalSessions(); current = s
        } catch (e: Exception) { error = e.message }
    }
    LaunchedEffect(Unit) { if (current == null) open() }

    TerminalLayout(
        tabs = sessions.map { s ->
            val c = service.containerOf(s) ?: homeContainer
            TerminalTab(c, s.title ?: s.mSessionName ?: "bash", home = c == homeContainer)
        },
        selected = sessions.indexOf(current),
        onSelect = { current = sessions[it] },
        onBack = onBack,
        onNew = { open() },
        onClose = {
            current?.let { service.closeSession(it) }
            sessions = service.terminalSessions(); current = sessions.firstOrNull()
            if (current == null) onBack()
        },
        onPaste = { client.paste(current) },
        onShowKeyboard = {
            client.view?.let { v -> v.requestFocus(); ctx.getSystemService(InputMethodManager::class.java).showSoftInput(v, 0) }
        },
        error = error,
        ctrlOn = ctrlOn, altOn = altOn,
        onCtrl = { client.ctrl = !client.ctrl; ctrlOn = client.ctrl },
        onAlt = { client.alt = !client.alt; altOn = client.alt },
        send = { seq -> current?.write(seq) },
        appCursor = { current?.emulator?.isCursorKeysApplicationMode == true },
    ) { m ->
        AndroidView(
            modifier = m,
            factory = { c ->
                TerminalView(c, null).also { v ->
                    client.view = v
                    v.setTerminalViewClient(client)
                    v.setTextSize(client.initialSize())
                    v.setTypeface(terminalTypeface(c))
                    v.isFocusable = true; v.isFocusableInTouchMode = true
                }
            },
            update = { v ->
                val s = current
                if (s != null && v.currentSession !== s) { v.attachSession(s); v.requestFocus() }
                v.onScreenUpdated()
            },
        )
    }
}

/** Width from which the keys row has room for Home, End, PgUp, PgDn and all four arrows. */
private val FULL_KEYS_WIDTH = 600.dp

/**
 * The terminal's chrome around [terminal]. On a foldable bent like a laptop the terminal fills the upright
 * half and the tabs, keys and keyboard share the flat one; elsewhere the tabs sit on top and the keys below.
 */
@Composable
internal fun TerminalLayout(
    tabs: List<TerminalTab>,
    selected: Int,
    onSelect: (Int) -> Unit,
    onBack: () -> Unit,
    onNew: () -> Unit,
    onClose: () -> Unit,
    onPaste: () -> Unit,
    onShowKeyboard: () -> Unit,
    error: String?,
    ctrlOn: Boolean,
    altOn: Boolean,
    onCtrl: () -> Unit,
    onAlt: () -> Unit,
    send: (String) -> Unit,
    appCursor: () -> Boolean,
    terminal: @Composable (Modifier) -> Unit,
) {
    val fold = LocalFold.current
    val colors = SelkieTheme.terminal
    val term: @Composable (Modifier) -> Unit = { m ->
        Box(m.background(colors.background).padding(horizontal = 4.dp)) { terminal(Modifier.fillMaxSize()) }
    }
    val bar: @Composable () -> Unit = {
        TerminalBar(tabs, selected, onSelect, onBack, onNew, onPaste, onClose)
    }
    val keys: @Composable (Boolean) -> Unit = { full ->
        TerminalKeys(full, ctrlOn, altOn, onCtrl, onAlt, send, appCursor)
    }
    val err: @Composable () -> Unit = {
        error?.let {
            Text("Couldn't start a terminal: $it", color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        }
    }

    if (fold?.tabletop == true) {
        HingePanes(fold,
            first = { term(Modifier.fillMaxSize()) },
            second = {
                Column(
                    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    bar(); err(); keys(true)
                    // The system keyboard opens over the rest of this half.
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        FilledTonalButton(onClick = onShowKeyboard) {
                            Icon(SelkieIcons.Keyboard, contentDescription = null, Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp)); Text("Show keyboard")
                        }
                    }
                }
            })
    } else BoxWithConstraints(Modifier.fillMaxSize()) {
        val full = maxWidth >= FULL_KEYS_WIDTH
        Column(Modifier.fillMaxSize().imePadding()) {
            Box(Modifier.padding(horizontal = 4.dp)) { bar() }
            err()
            term(Modifier.weight(1f).fillMaxWidth())
            Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(8.dp)) { keys(full) }
        }
    }
}

@Composable
internal fun TerminalBar(
    tabs: List<TerminalTab>, selected: Int, onSelect: (Int) -> Unit,
    onBack: () -> Unit, onNew: () -> Unit, onPaste: () -> Unit, onClose: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(SelkieIcons.Back, contentDescription = "Back, keep terminals running") }
        LazyRow(Modifier.weight(1f).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            itemsIndexed(tabs) { i, t -> TerminalTabChip(t, i == selected) { onSelect(i) } }
        }
        IconButton(onClick = onNew) { Icon(SelkieIcons.Plus, contentDescription = "New terminal") }
        Box {
            IconButton(onClick = { menu = true }) { Icon(SelkieIcons.More, contentDescription = "Terminal options") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Paste") }, onClick = { menu = false; onPaste() })
                DropdownMenuItem(text = { Text("Close this terminal") }, onClick = { menu = false; onClose() },
                    leadingIcon = { Icon(SelkieIcons.Close, contentDescription = null) })
            }
        }
    }
}

@Composable
private fun TerminalTabChip(tab: TerminalTab, selected: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.height(48.dp).clip(RoundedCornerShape(24.dp))
            .background(if (selected) cs.secondaryContainer else Color.Transparent)
            .selectable(selected = selected, onClick = onClick, role = Role.Tab)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (tab.home) cs.primary else cs.tertiary))
        Text("${tab.container} · ${tab.title}".take(32), style = MaterialTheme.typography.labelLarge, maxLines = 1,
            color = if (selected) cs.onSecondaryContainer else cs.onSurfaceVariant)
    }
}

private fun TerminalSession.write(s: String) { val b = s.toByteArray(); write(b, 0, b.size) }

/** What a key sends. Arrows, Home and End follow the program's cursor-key mode. */
internal fun keySequence(key: String, appCursor: Boolean): String? {
    fun cursor(c: Char) = if (appCursor) "\u001bO$c" else "\u001b[$c"
    return when (key) {
        "Esc" -> "\u001b"
        "Tab" -> "\t"
        "Up" -> cursor('A')
        "Down" -> cursor('B')
        "Right" -> cursor('C')
        "Left" -> cursor('D')
        "Home" -> cursor('H')
        "End" -> cursor('F')
        "PgUp" -> "\u001b[5~"
        "PgDn" -> "\u001b[6~"
        else -> null
    }
}

private data class Key(val id: String, val label: String, val description: String? = null)

private val ARROWS = mapOf("Up" to "↑", "Down" to "↓", "Left" to "←", "Right" to "→")
private fun key(id: String) = Key(id, ARROWS[id] ?: id, ARROWS[id]?.let { id })

/**
 * The extra keys, all the same width as on the canvas. [full] rows fit Home, End, PgUp, PgDn and every arrow;
 * the narrow row keeps Esc, Tab, Ctrl, Alt, up and down and puts the rest behind "More keys".
 */
@Composable
internal fun TerminalKeys(
    full: Boolean, ctrlOn: Boolean, altOn: Boolean, onCtrl: () -> Unit, onAlt: () -> Unit,
    send: (String) -> Unit, appCursor: () -> Boolean,
) {
    var more by rememberSaveable { mutableStateOf(false) }
    val press: (String) -> Unit = { id -> keySequence(id, appCursor())?.let(send) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            KeyButton(key("Esc"), press)
            KeyButton(key("Tab"), press)
            ToggleKey("Ctrl", ctrlOn, onCtrl)
            ToggleKey("Alt", altOn, onAlt)
            if (full) {
                for (k in listOf("Home", "End", "PgUp", "PgDn", "Left", "Up", "Down", "Right")) KeyButton(key(k), press)
            } else {
                KeyButton(key("Up"), press)
                KeyButton(key("Down"), press)
                ToggleKey("···", more, { more = !more }, description = "More keys")
            }
        }
        if (!full && more) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (k in listOf("Left", "Right", "Home", "End", "PgUp", "PgDn")) KeyButton(key(k), press)
        }
    }
}

@Composable
private fun RowScope.KeyButton(k: Key, press: (String) -> Unit) {
    KeySurface(on = false, modifier = Modifier.weight(1f).clickable(role = Role.Button) { press(k.id) }
        .then(k.description?.let { d -> Modifier.semantics { contentDescription = d } } ?: Modifier)) {
        // Arrow glyphs draw small at the label size, so they get a larger one.
        Text(k.label, style = if (k.id in ARROWS) LocalTextStyle.current.copy(fontSize = 20.sp) else LocalTextStyle.current)
    }
}

@Composable
private fun RowScope.ToggleKey(label: String, on: Boolean, onToggle: () -> Unit, description: String? = null) {
    KeySurface(on = on, modifier = Modifier.weight(1f).toggleable(value = on, role = Role.Switch, onValueChange = { onToggle() })
        .then(description?.let { d -> Modifier.semantics { contentDescription = d } } ?: Modifier)) {
        Text(label)
    }
}

@Composable
private fun KeySurface(on: Boolean, modifier: Modifier, content: @Composable () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier.height(48.dp).clip(MaterialTheme.shapes.small)
            .background(if (on) cs.primary else cs.surfaceContainerHigh).then(modifier).padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(
            LocalContentColor provides (if (on) cs.onPrimary else cs.onSurface),
            LocalTextStyle provides MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp),
        ) { content() }
    }
}

/**
 * JetBrains Mono with Nerd Font symbols (OFL-1.1): programs such as `fastfetch` and prompts print icons from
 * the Private Use Area that Android's stock monospace font lacks and draws as tofu. Falls back to the stock
 * font if the resource cannot be loaded.
 */
internal fun terminalTypeface(context: Context): Typeface =
    runCatching { context.resources.getFont(R.font.jetbrains_mono_nl_nerd_mono_regular) }
        .getOrDefault(Typeface.MONOSPACE)

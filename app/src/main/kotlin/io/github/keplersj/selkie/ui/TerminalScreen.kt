package io.github.keplersj.selkie.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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

    Column(Modifier.fillMaxSize().imePadding().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("Back") }
            LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                items(sessions) { s ->
                    FilterChip(selected = s === current, onClick = { current = s },
                        label = { Text((s.title ?: s.mSessionName ?: "Session").take(24)) })
                }
            }
            TextButton(onClick = { open() }) { Text("+") }
            TextButton(onClick = {
                current?.let { service.closeSession(it) }
                sessions = service.terminalSessions(); current = sessions.firstOrNull()
                if (current == null) onBack()
            }) { Text("Close") }
        }
        error?.let { Text("Cannot start session: $it", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(8.dp)) }
        AndroidView(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            factory = { c ->
                TerminalView(c, null).also { v ->
                    client.view = v
                    v.setTerminalViewClient(client)
                    v.setTextSize(client.initialSize())
                    v.setTypeface(Typeface.MONOSPACE)
                    v.isFocusable = true; v.isFocusableInTouchMode = true
                }
            },
            update = { v ->
                val s = current
                if (s != null && v.currentSession !== s) { v.attachSession(s); v.requestFocus() }
                v.onScreenUpdated()
            },
        )
        ExtraKeys(
            ctrlOn, altOn,
            onCtrl = { client.ctrl = !client.ctrl; ctrlOn = client.ctrl },
            onAlt = { client.alt = !client.alt; altOn = client.alt },
            send = { seq -> current?.write(seq) },
            paste = { client.paste(current) },
            appCursor = { current?.emulator?.isCursorKeysApplicationMode == true },
        )
    }
}

private fun TerminalSession.write(s: String) { val b = s.toByteArray(); write(b, 0, b.size) }

@Composable
private fun ExtraKeys(ctrlOn: Boolean, altOn: Boolean, onCtrl: () -> Unit, onAlt: () -> Unit,
                      send: (String) -> Unit, paste: () -> Unit, appCursor: () -> Boolean) {
    fun arrow(c: Char) = if (appCursor()) "\u001bO$c" else "\u001b[$c"
    val keys: List<Pair<String, () -> Unit>> = listOf(
        "Esc" to { send("\u001b") },
        "Tab" to { send("\t") },
        "↑" to { send(arrow('A')) }, "↓" to { send(arrow('B')) },
        "←" to { send(arrow('D')) }, "→" to { send(arrow('C')) },
        "-" to { send("-") }, "/" to { send("/") }, "|" to { send("|") },
        "Paste" to paste,
    )
    Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        FilterChip(selected = ctrlOn, onClick = onCtrl, label = { Text("Ctrl") })
        FilterChip(selected = altOn, onClick = onAlt, label = { Text("Alt") })
        LazyRow(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            items(keys) { (label, act) -> AssistChip(onClick = act, label = { Text(label) }) }
        }
    }
}

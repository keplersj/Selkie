package io.github.keplersj.selkie.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import io.github.keplersj.selkie.MainActivity
import io.github.keplersj.selkie.engine.DroidcClient
import io.github.keplersj.selkie.engine.DroidcError
import io.github.keplersj.selkie.engine.DroidcEvent
import io.github.keplersj.selkie.engine.DroidcOutcome
import io.github.keplersj.selkie.engine.EngineEnv
import io.github.keplersj.selkie.engine.ErrorKind
import io.github.keplersj.selkie.display.NativeCompositor
import io.github.keplersj.selkie.engine.engineEnvFrom
import io.github.keplersj.selkie.engine.shellSpec
import io.github.keplersj.selkie.engine.status
import io.github.keplersj.selkie.proot.GuestFiles
import io.github.keplersj.selkie.proot.ProotCommandBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Foreground service that owns droidc operations and terminal sessions so both survive the
 * activity going away. The UI binds and observes [state].
 */
class SessionService : Service() {
    inner class LocalBinder : Binder() { val service: SessionService get() = this@SessionService }

    private val binder = LocalBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(ServiceState())
    val state: StateFlow<ServiceState> = _state.asStateFlow()

    private var opJob: Job? = null
    private var foreground = false
    private var lastNotify = 0L

    private val terminals = LinkedHashMap<String, TerminalSession>()
    /** UI plugs its client in here; null while the activity is gone. */
    @Volatile var uiSessionClient: TerminalSessionClient? = null

    private val sessionClient = object : TerminalSessionClient {
        override fun onTextChanged(s: TerminalSession) { uiSessionClient?.onTextChanged(s) }
        override fun onTitleChanged(s: TerminalSession) { uiSessionClient?.onTitleChanged(s); publishSessions() }
        override fun onSessionFinished(s: TerminalSession) { uiSessionClient?.onSessionFinished(s); publishSessions() }
        override fun onCopyTextToClipboard(s: TerminalSession, text: String?) { uiSessionClient?.onCopyTextToClipboard(s, text) }
        override fun onPasteTextFromClipboard(s: TerminalSession?) { uiSessionClient?.onPasteTextFromClipboard(s) }
        override fun onBell(s: TerminalSession) { uiSessionClient?.onBell(s) }
        override fun onColorsChanged(s: TerminalSession) { uiSessionClient?.onColorsChanged(s) }
        override fun onTerminalCursorStateChange(state: Boolean) { uiSessionClient?.onTerminalCursorStateChange(state) }
        override fun getTerminalCursorStyle(): Int? = uiSessionClient?.terminalCursorStyle
        override fun logError(tag: String?, message: String?) {}
        override fun logWarn(tag: String?, message: String?) {}
        override fun logInfo(tag: String?, message: String?) {}
        override fun logDebug(tag: String?, message: String?) {}
        override fun logVerbose(tag: String?, message: String?) {}
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
        override fun logStackTrace(tag: String?, e: Exception?) {}
    }

    private fun newEnv(): EngineEnv = engineEnvFrom(this)
    private fun client(): DroidcClient = DroidcClient(newEnv())

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        refreshStatus()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopEverything(); return START_NOT_STICKY }
        // Entering the foreground promptly satisfies startForegroundService().
        updateForeground(force = true)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopDesktop()
        terminals.values.forEach { it.finishIfRunning() }
        scope.cancel()
        super.onDestroy()
    }

    // ---------------- status ----------------

    fun refreshStatus() {
        scope.launch {
            val c = client()
            val outcome = withContext(Dispatchers.IO) { c.status() }
            _state.update {
                when (outcome) {
                    is DroidcOutcome.Success -> it.copy(
                        status = runCatching { outcome.status() }.getOrNull(),
                        statusError = null, statusLoaded = true)
                    is DroidcOutcome.Failure -> it.copy(
                        statusError = outcome.error.message, statusLoaded = true)
                }
            }
            if (_state.value.status?.current != null) {
                val spec = withContext(Dispatchers.IO) { c.shellSpec() }
                val session = (spec as? DroidcOutcome.Success)?.let { runCatching { it.shellSpec().session }.getOrNull() }
                _state.update { it.copy(graphical = session) }
            } else {
                _state.update { it.copy(graphical = null) }
            }
        }
    }

    // ---------------- operations ----------------

    fun switchTo(ref: String, insecure: Boolean) =
        start(OpKind.SWITCH, ref, insecure) { c -> c.switch(ref, insecure) }

    fun upgrade() = start(OpKind.UPGRADE, _state.value.status?.current?.imageRef, false) { it.upgrade() }
    fun rollback() = start(OpKind.ROLLBACK, null, false) { it.rollback() }

    private fun start(
        kind: OpKind, ref: String?, insecure: Boolean,
        flow: (DroidcClient) -> kotlinx.coroutines.flow.Flow<DroidcEvent>,
    ) {
        if (_state.value.operation is Operation.Running) return
        _state.update { it.copy(operation = Operation.Running(kind, ref)) }
        startForegroundService(Intent(this, SessionService::class.java))
        updateForeground(force = true)
        val c = client()
        opJob = scope.launch {
            var result: DroidcEvent.Result? = null
            try {
                flow(c).collect { ev ->
                    when (ev) {
                        is DroidcEvent.Progress -> _state.update { st ->
                            st.copy(operation = Operation.Running(kind, ref, ev.phase, ev.current,
                                ev.total, ev.unit, ev.message))
                        }
                        is DroidcEvent.Result -> result = ev
                        else -> {}
                    }
                    updateForeground()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                _state.update { it.copy(operation = Operation.Cancelled(kind)) }
                updateForeground(force = true)
                refreshStatus()
                throw e
            }
            val r = result
            _state.update {
                it.copy(operation = if (r?.ok == true) Operation.Done(kind, "${kind.label} finished")
                else Operation.Failed(kind, ref, insecure,
                    r?.error ?: DroidcError(ErrorKind.INTERNAL, "no result"), c.stderrTail()))
            }
            updateForeground(force = true)
            refreshStatus()
        }
    }

    /** Kills droidc; it is crash-safe, the next command recovers staging. */
    fun cancelOperation() { opJob?.cancel() }

    fun dismissOperation() {
        if (_state.value.operation !is Operation.Running) _state.update { it.copy(operation = Operation.Idle) }
    }

    // ---------------- terminal sessions ----------------

    /** Starts a new proot shell session. Runs `shell-spec` first, so call off the main thread's critical path. */
    suspend fun openSession(): TerminalSession {
        val env = newEnv()
        val outcome = withContext(Dispatchers.IO) { DroidcClient(env).shellSpec() }
        val spec = (outcome as? DroidcOutcome.Success)?.shellSpec()
            ?: error("shell-spec failed: ${(outcome as? DroidcOutcome.Failure)?.error?.message}")
        val inv = withContext(Dispatchers.IO) {
            val fake = GuestFiles.prepare(env, spec)
            ProotCommandBuilder(env, spec, fake, overlays = GuestFiles.prepareIdentity(env, spec) + GuestFiles.prepareSelinuxHiding(env)).interactiveShell()
        }
        val session = TerminalSession(inv.argv.first(), spec.cwd.let { env.filesDir.path },
            inv.argv.drop(1).toTypedArray(), inv.envArray, 4000, sessionClient)
        val id = UUID.randomUUID().toString()
        terminals[id] = session
        session.mSessionName = "Session ${terminals.size}"
        startForegroundService(Intent(this, SessionService::class.java))
        publishSessions()
        updateForeground(force = true)
        return session
    }

    fun terminalSessions(): List<TerminalSession> = terminals.values.toList()

    fun closeSession(s: TerminalSession) {
        s.finishIfRunning()
        terminals.values.remove(s)
        publishSessions()
        updateForeground(force = true)
    }

    private fun publishSessions() {
        val infos = terminals.entries.map { (id, s) ->
            SessionInfo(id, s.title ?: s.mSessionName ?: "Session", s.isRunning)
        }
        _state.update { it.copy(sessions = infos) }
        updateForeground()
    }

    private fun runningSessions() = terminals.values.count { it.isRunning }

    // ---------------- graphical session ----------------

    @Volatile private var compositor: NativeCompositor? = null
    @Volatile private var desktop: Process? = null
    private var desktopJob: Job? = null

    /** The running compositor, for the view that shows it. */
    fun compositor(): NativeCompositor? = compositor

    /**
     * Starts the compositor and the deployment's graphical session at [width]x[height] pixels (the
     * view size). The session keeps running without the activity; the compositor holds frames while
     * nothing shows them.
     */
    fun startDesktop(width: Int, height: Int) {
        val st = _state.value.desktop
        if (st is DesktopState.Starting || st is DesktopState.Running) return
        _state.update { it.copy(desktop = DesktopState.Starting) }
        startForegroundService(Intent(this, SessionService::class.java))
        updateForeground(force = true)
        val env = newEnv()
        desktopJob = scope.launch {
            val p = try {
                withContext(Dispatchers.IO) {
                    val outcome = DroidcClient(env).shellSpec()
                    val spec = (outcome as? DroidcOutcome.Success)?.shellSpec()
                        ?: error("shell-spec failed: ${(outcome as? DroidcOutcome.Failure)?.error?.message}")
                    if (spec.session?.configured != true) error("This image declares no graphical session. Configure one first.")
                    val fake = GuestFiles.prepare(env, spec)
                    val inv = ProotCommandBuilder(env, spec, fake,
                        overlays = GuestFiles.prepareIdentity(env, spec) + GuestFiles.prepareSelinuxHiding(env))
                        .graphicalSession(width, height)
                    compositor = compositor ?: NativeCompositor.start(env.waylandSocket, width, height)
                    val pb = ProcessBuilder(inv.argv).redirectErrorStream(true)
                        .redirectOutput(env.desktopLog)
                    pb.environment().apply { clear(); putAll(inv.env) }
                    pb.start()
                }
            } catch (e: Throwable) {
                // Throwable: a missing native library is an Error, and it must end up on screen too.
                if (e is kotlinx.coroutines.CancellationException) throw e
                android.util.Log.w(TAG, "desktop failed to start", e)
                stopCompositor()
                _state.update { it.copy(desktop = DesktopState.Failed(e.message ?: e.toString(), logTail(env))) }
                updateForeground(force = true)
                return@launch
            }
            desktop = p
            android.util.Log.i(TAG, "desktop session started at ${width}x$height")
            _state.update { it.copy(desktop = DesktopState.Running(width, height)) }
            updateForeground(force = true)
            val code = withContext(Dispatchers.IO) { p.waitFor() }
            android.util.Log.i(TAG, "desktop session exited with $code")
            if (desktop === p) {
                desktop = null
                stopCompositor()
                _state.update { it.copy(desktop =
                    if (code == 0) DesktopState.Stopped
                    else DesktopState.Failed("The session exited with status $code", logTail(env))) }
                updateForeground(force = true)
            }
        }
    }

    /** Stops the session (proot `--kill-on-exit` takes the guest processes with it) and the compositor. */
    fun stopDesktop() {
        val p = desktop
        desktop = null
        desktopJob?.cancel()
        desktopJob = null
        if (p != null) {
            p.destroy()
            Thread { if (!p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly() }.start()
        }
        stopCompositor()
        _state.update { it.copy(desktop = DesktopState.Stopped) }
        updateForeground(force = true)
    }

    fun dismissDesktopError() {
        if (_state.value.desktop is DesktopState.Failed) _state.update { it.copy(desktop = DesktopState.Stopped) }
    }

    private fun stopCompositor() {
        compositor?.stop()
        compositor = null
    }

    private fun logTail(env: EngineEnv): String = try {
        env.desktopLog.readLines().takeLast(40).joinToString("\n")
    } catch (_: Exception) { "" }

    /** Writes the session override (`droidc session set`) and refreshes. */
    fun configureSession(renderer: String, exec: String, services: String?, name: String?, onDone: (String?) -> Unit) {
        scope.launch {
            val outcome = withContext(Dispatchers.IO) { client().setSession(renderer, exec, services, name) }
            onDone((outcome as? DroidcOutcome.Failure)?.error?.message)
            refreshStatus()
        }
    }

    fun clearSessionOverride() {
        scope.launch { withContext(Dispatchers.IO) { client().clearSession() }; refreshStatus() }
    }

    private fun desktopActive() = _state.value.desktop.let { it is DesktopState.Starting || it is DesktopState.Running }

    // ---------------- foreground + notification ----------------

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Selkie sessions",
            NotificationManager.IMPORTANCE_LOW))
    }

    private fun stopEverything() {
        opJob?.cancel()
        stopDesktop()
        terminals.values.forEach { it.finishIfRunning() }
        terminals.clear()
        publishSessions()
        foreground = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun updateForeground(force: Boolean = false) {
        val op = _state.value.operation
        val active = op is Operation.Running || runningSessions() > 0 || desktopActive()
        if (!active) {
            if (foreground) { foreground = false; stopForeground(STOP_FOREGROUND_REMOVE) }
            if (_state.value.sessions.isEmpty() && op !is Operation.Running) {
                // Idle: keep service object alive only while bound; nothing else to do.
            }
            return
        }
        val now = System.currentTimeMillis()
        if (!force && now - lastNotify < 1000) return
        lastNotify = now
        val n = buildNotification(op)
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIF_ID, n, type)
        foreground = true
    }

    private fun buildNotification(op: Operation): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1,
            Intent(this, SessionService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open)
            .addAction(0, "Stop", stop)
        if (op is Operation.Running) {
            b.setContentTitle("${op.kind.label} ${op.ref ?: ""}".trim())
            b.setContentText(op.message ?: op.phase)
            val total = op.total
            if (total != null && total > 0) b.setProgress(100, (op.current * 100 / total).toInt().coerceIn(0, 100), false)
            else b.setProgress(0, 0, true)
        } else {
            val n = runningSessions()
            val text = listOfNotNull(
                "Desktop running".takeIf { desktopActive() },
                "$n terminal${if (n == 1) "" else "s"} running".takeIf { n > 0 },
            ).joinToString(" · ")
            b.setContentTitle("Selkie").setContentText(text)
        }
        return b.build()
    }

    companion object {
        const val ACTION_STOP = "io.github.keplersj.selkie.STOP"
        private const val TAG = "SelkieDesktop"
        private const val CHANNEL = "sessions"
        private const val NOTIF_ID = 1

        fun intent(context: Context) = Intent(context, SessionService::class.java)
    }
}

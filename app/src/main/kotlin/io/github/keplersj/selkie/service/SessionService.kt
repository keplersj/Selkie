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
import io.github.keplersj.selkie.engine.RootShell
import io.github.keplersj.selkie.engine.engineEnvFrom
import io.github.keplersj.selkie.engine.setRootModeEnabled
import io.github.keplersj.selkie.engine.shellSpec
import io.github.keplersj.selkie.engine.status
import io.github.keplersj.selkie.platform.RootAccess
import io.github.keplersj.selkie.proot.EnterCommandBuilder
import io.github.keplersj.selkie.proot.GuestFiles
import io.github.keplersj.selkie.proot.ProotCommandBuilder
import java.io.File
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
    /** Root-mode sessions: session id -> pid file their root shell wrote. */
    private val rootPidFiles = HashMap<String, File>()
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
        val env = newEnv()
        _state.update { it.copy(rootMode = env.rootMode, suFound = env.su != null) }
        refreshStatus()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopEverything(); return START_NOT_STICKY }
        // Entering the foreground promptly satisfies startForegroundService().
        updateForeground(force = true)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
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
        }
    }

    // ---------------- operations ----------------

    fun switchTo(ref: String, insecure: Boolean) =
        start(OpKind.SWITCH, ref, insecure) { c -> c.switch(ref, insecure) }

    fun upgrade() = start(OpKind.UPGRADE, _state.value.status?.current?.imageRef, false) { it.upgrade() }
    fun rollback() = start(OpKind.ROLLBACK, null, false) { it.rollback() }
    fun fixOwnership() = start(OpKind.FIX_OWNERSHIP, null, false) { it.fixOwnership(undo = false) }

    // ---------------- root mode ----------------

    /**
     * On: ask su (the root manager prompts), then apply the image's ownership to the current
     * deployment. Off: give every file back to the app (`fix-ownership --undo`) so proot works
     * again, then clear the setting. Refused while terminals run.
     */
    fun setRootMode(on: Boolean) {
        val st = _state.value
        if (st.operation is Operation.Running || st.rootProbing || on == st.rootMode) return
        if (runningSessions() > 0) {
            _state.update { it.copy(rootMessage = "Close open terminals before switching root mode.") }
            return
        }
        if (on) scope.launch {
            _state.update { it.copy(rootProbing = true, rootMessage = "Waiting for root permission...") }
            val (probe, msg) = withContext(Dispatchers.IO) { RootAccess().probe() }
            val granted = probe == RootAccess.Probe.GRANTED
            if (granted) setRootModeEnabled(this@SessionService, true)
            _state.update { it.copy(rootProbing = false, rootMode = granted, rootMessage = msg) }
            val cur = _state.value.status?.current
            if (granted && cur != null && !cur.imageOwned) fixOwnership()
        } else {
            val s = _state.value.status
            val imageOwned = listOfNotNull(s?.current, s?.previous).any { it.imageOwned }
            val off = {
                setRootModeEnabled(this, false)
                _state.update { it.copy(rootMode = false, rootMessage = "Root mode is off.") }
            }
            if (imageOwned) start(OpKind.UNDO_OWNERSHIP, null, false, onSuccess = off) { it.fixOwnership(undo = true) }
            else off()
        }
    }

    /** Root alternative to the Developer options toggle. */
    fun disablePhantomKillerWithRoot(onDone: (Boolean) -> Unit) {
        scope.launch { onDone(withContext(Dispatchers.IO) { RootAccess().disablePhantomKiller() }) }
    }

    private fun start(
        kind: OpKind, ref: String?, insecure: Boolean,
        onSuccess: () -> Unit = {},
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
            var r = result
            // Root mode: the deployment now current must carry the image's owners (a rollback to
            // one made before root mode, or a switch that reused an older deployment).
            if (r?.ok == true && c.useRoot && kind in setOf(OpKind.SWITCH, OpKind.UPGRADE, OpKind.ROLLBACK)) {
                val cur = (withContext(Dispatchers.IO) { c.status() } as? DroidcOutcome.Success)
                    ?.let { runCatching { it.status() }.getOrNull() }?.current
                if (cur != null && !cur.imageOwned) {
                    _state.update { it.copy(operation = Operation.Running(kind, ref, "ownership", message = "Applying image ownership")) }
                    c.fixOwnership(undo = false).collect { ev -> if (ev is DroidcEvent.Result) r = ev }
                }
            }
            if (r?.ok == true) onSuccess()
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
        val id = UUID.randomUUID().toString()
        val pidFile = File(env.rootSessions, "$id.pid")
        val inv = withContext(Dispatchers.IO) {
            val fake = GuestFiles.prepare(env, spec)
            if (env.useRoot) {
                env.rootSessions.mkdirs()
                EnterCommandBuilder(env, spec, overlays = GuestFiles.prepareSelinuxHiding(env)).interactiveShell(pidFile)
            } else {
                ProotCommandBuilder(env, spec, fake, overlays = GuestFiles.prepareIdentity(env, spec) + GuestFiles.prepareSelinuxHiding(env)).interactiveShell()
            }
        }
        if (env.useRoot) rootPidFiles[id] = pidFile
        val session = TerminalSession(inv.argv.first(), spec.cwd.let { env.filesDir.path },
            inv.argv.drop(1).toTypedArray(), inv.envArray, 4000, sessionClient)
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
        terminals.entries.firstOrNull { it.value === s }?.key?.let { stopRoot(it) }
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

    // ---------------- foreground + notification ----------------

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Selkie sessions",
            NotificationManager.IMPORTANCE_LOW))
    }

    /**
     * Killing our `su` may not reach the root shell (with Magisk it is magiskd's child), so stop it by
     * the pid it wrote; droidc enter then tears the whole namespace down.
     */
    private fun stopRoot(id: String) {
        val f = rootPidFiles.remove(id) ?: return
        val su = newEnv().su ?: return
        Thread { runCatching { ProcessBuilder(RootShell.killFromFile(su, f)).start().waitFor() } }.start()
    }

    private fun stopEverything() {
        opJob?.cancel()
        terminals.keys.toList().forEach { stopRoot(it) }
        terminals.values.forEach { it.finishIfRunning() }
        terminals.clear()
        publishSessions()
        foreground = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun updateForeground(force: Boolean = false) {
        val op = _state.value.operation
        val active = op is Operation.Running || runningSessions() > 0
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
            b.setContentTitle("Selkie").setContentText("$n session${if (n == 1) "" else "s"} running")
        }
        return b.build()
    }

    companion object {
        const val ACTION_STOP = "io.github.keplersj.selkie.STOP"
        private const val CHANNEL = "sessions"
        private const val NOTIF_ID = 1

        fun intent(context: Context) = Intent(context, SessionService::class.java)
    }
}

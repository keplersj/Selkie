package io.github.keplersj.selkie.service

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import io.github.keplersj.selkie.MainActivity
import io.github.keplersj.selkie.audio.GuestAudio
import io.github.keplersj.selkie.engine.AppEntry
import io.github.keplersj.selkie.engine.DroidcClient
import io.github.keplersj.selkie.engine.DroidcError
import io.github.keplersj.selkie.engine.DroidcEvent
import io.github.keplersj.selkie.engine.DroidcOutcome
import io.github.keplersj.selkie.engine.EngineEnv
import io.github.keplersj.selkie.engine.ErrorKind
import io.github.keplersj.selkie.display.DisplayScale
import io.github.keplersj.selkie.display.NativeCompositor
import io.github.keplersj.selkie.engine.RootShell
import io.github.keplersj.selkie.engine.apps
import io.github.keplersj.selkie.engine.containers
import io.github.keplersj.selkie.engine.engineEnvFrom
import io.github.keplersj.selkie.engine.setRootModeEnabled
import io.github.keplersj.selkie.engine.shellSpec
import io.github.keplersj.selkie.link.AdbRequests
import io.github.keplersj.selkie.link.Appearance
import io.github.keplersj.selkie.link.AppearanceWatcher
import io.github.keplersj.selkie.link.BatteryWatcher
import io.github.keplersj.selkie.link.GuestLink
import io.github.keplersj.selkie.link.GuestLinkMessage
import io.github.keplersj.selkie.link.GuestNotification
import io.github.keplersj.selkie.link.GuestNotifier
import io.github.keplersj.selkie.link.OpenUri
import io.github.keplersj.selkie.engine.status
import io.github.keplersj.selkie.platform.PhantomProcessCheck
import io.github.keplersj.selkie.platform.RootAccess
import io.github.keplersj.selkie.proot.EnterCommandBuilder
import io.github.keplersj.selkie.proot.GuestFiles
import io.github.keplersj.selkie.proot.Invocation
import io.github.keplersj.selkie.proot.ProotCommandBuilder
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
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

    private val _keepAwake = MutableStateFlow(false)
    /** A desktop app asks the screen to stay on (an idle inhibitor, through `droidc host-link`). */
    val keepAwake: StateFlow<Boolean> = _keepAwake.asStateFlow()

    private var opJob: Job? = null
    /** The watch remote: a no-op in builds without Play services. */
    private var watchLink: AutoCloseable? = null
    private var foreground = false
    private var lastNotify = 0L

    /** A terminal and the container it runs in. */
    private class Term(val container: String, val session: TerminalSession)
    private val terminals = LinkedHashMap<String, Term>()
    /** Root-mode sessions: session id -> pid file their root shell wrote. */
    private val rootPidFiles = HashMap<String, File>()
    /** UI plugs its client in here; null while the activity is gone. */
    @Volatile var uiSessionClient: TerminalSessionClient? = null

    private val sessionClient = object : TerminalSessionClient {
        override fun onTextChanged(s: TerminalSession) { uiSessionClient?.onTextChanged(s) }
        override fun onTitleChanged(s: TerminalSession) { uiSessionClient?.onTitleChanged(s); publishSessions() }
        override fun onSessionFinished(s: TerminalSession) {
            noticeKilledTerminal(s)
            uiSessionClient?.onSessionFinished(s); publishSessions()
        }
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

    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }

    private fun newEnv(container: String = _state.value.container): EngineEnv =
        engineEnvFrom(this, container)
    private fun client(container: String = _state.value.container): DroidcClient = DroidcClient(newEnv(container))

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        val saved = prefs.getString(KEY_CONTAINER, null)?.takeIf { EngineEnv.isValidContainerName(it) }
        if (saved != null) _state.update { it.copy(container = saved) }
        val env = newEnv()
        _state.update { it.copy(rootMode = env.rootMode, suFound = env.su != null) }
        noticeRestart()
        refreshStatus()
        watchLink = WatchLink.start(this, state, scope,
            onStopDesktop = { stopDesktop() }, onCloseTerminals = { closeTerminals(it) })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopEverything(); return START_NOT_STICKY }
        if (intent?.action == GuestNotifier.ACTION_BUTTON || intent?.action == GuestNotifier.ACTION_DISMISSED) {
            onGuestNotificationIntent(intent)
            return START_NOT_STICKY
        }
        // Entering the foreground promptly satisfies startForegroundService().
        updateForeground(force = true)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        destroying = true
        stopDesktop()
        terminals.values.forEach { it.session.finishIfRunning() }
        // Selkie ended these itself, so the next start has nothing to explain.
        prefs.edit().remove(KEY_RUNNING).apply()
        stopAllServices()
        watchLink?.close()
        scope.cancel()
        super.onDestroy()
    }

    // ---------------- status ----------------

    fun refreshStatus() {
        scope.launch {
            val container = _state.value.container
            val c = client(container)
            val listed = withContext(Dispatchers.IO) { c.listContainers() }
            // A droidc from before containers has no `container list`: then only the selected one is known.
            val containers = (listed as? DroidcOutcome.Success)?.let { runCatching { it.containers() }.getOrNull() }
            if (containers != null && containers.none { it.name == container } && _state.value.operation !is Operation.Running) {
                // Removed (the default one too), or its first install failed before anything was deployed:
                // show the first container droidc lists. With none left, stay put and Home shows its empty state.
                val next = containers.firstOrNull()?.name
                    ?: EngineEnv.DEFAULT_CONTAINER.takeIf { container != EngineEnv.DEFAULT_CONTAINER }
                if (next != null) {
                    selectContainer(next)
                    return@launch
                }
            }
            _state.update { st ->
                st.copy(containers = mergeContainers(containers ?: st.containers.filter { it.name == container }, container))
            }
            val outcome = withContext(Dispatchers.IO) { c.status() }
            if (_state.value.container != container) return@launch
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
                if (_state.value.container != container) return@launch
                _state.update { it.copy(graphical = session) }
                val apps = withContext(Dispatchers.IO) { c.apps() }
                if (_state.value.container != container) return@launch
                _state.update {
                    when (apps) {
                        is DroidcOutcome.Success -> runCatching { apps.apps() }.fold(
                            { l -> it.copy(apps = l, appsError = null) },
                            { e -> it.copy(apps = null, appsError = e.message ?: "unreadable app list") })
                        is DroidcOutcome.Failure -> it.copy(apps = null, appsError = apps.error.message)
                    }
                }
            } else {
                _state.update { it.copy(graphical = null, apps = null, appsError = null) }
            }
        }
    }

    // ---------------- containers ----------------

    /** Shows [name] on Home; new operations, terminals and the desktop act on it. Not while an operation runs. */
    fun selectContainer(name: String) {
        if (!EngineEnv.isValidContainerName(name) || _state.value.operation is Operation.Running) return
        if (_state.value.container == name && _state.value.statusLoaded) return
        showContainer(name)
        refreshStatus()
    }

    private fun showContainer(name: String) {
        prefs.edit().putString(KEY_CONTAINER, name).apply()
        val root = newEnv(name).rootMode
        _state.update { it.copy(container = name, status = null, statusError = null, statusLoaded = false, graphical = null,
            apps = null, appsError = null, rootMode = root, rootMessage = null) }
    }

    /**
     * Makes container [name] by installing [ref] into it. It shares downloaded images with the others,
     * and gets its own deployments, /var, /home and desktop session. If the first install fails the
     * container never appears, and Home goes back to the default one.
     */
    fun createContainer(name: String, ref: String, insecure: Boolean) {
        if (!EngineEnv.isValidContainerName(name) || _state.value.operation is Operation.Running) return
        showContainer(name)
        switchTo(ref, insecure)
    }

    /**
     * Deletes named container [name] after closing its terminals and desktop. Images no other container
     * uses are freed. [onDone] gets an error message, or null.
     */
    fun removeContainer(name: String, onDone: (String?) -> Unit = {}) {
        if (!EngineEnv.isValidContainerName(name) || _state.value.operation is Operation.Running) return
        // Taken first, so closing its sessions doesn't stop them in the background while droidc deletes.
        val left = takeServices(name)
        terminals.values.filter { it.container == name }.forEach { closeSession(it.session) }
        if (_state.value.desktopContainer == name) stopDesktop()
        // A root-mode container's files belong to the image's owners, so only root can delete them.
        val asRoot = newEnv(name).useRoot
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                left?.let { stopServices(it, wait = true) }
                client(EngineEnv.DEFAULT_CONTAINER).removeContainer(name, asRoot)
            }
            if (outcome is DroidcOutcome.Success) setRootModeEnabled(this@SessionService, name, false)
            onDone((outcome as? DroidcOutcome.Failure)?.error?.message)
            // refreshStatus moves Home to the next listed container when the shown one has gone.
            refreshStatus()
        }
    }

    /** The container a terminal runs in. */
    fun containerOf(s: TerminalSession): String? = terminals.values.firstOrNull { it.session === s }?.container

    // ---------------- operations ----------------

    fun switchTo(ref: String, insecure: Boolean) =
        start(OpKind.SWITCH, ref, insecure) { c -> c.switch(ref, insecure) }

    fun upgrade() = start(OpKind.UPGRADE, _state.value.status?.current?.imageRef, false) { it.upgrade() }
    fun rollback() = start(OpKind.ROLLBACK, null, false) { it.rollback() }
    fun fixOwnership() = start(OpKind.FIX_OWNERSHIP, null, false) { it.fixOwnership(undo = false) }

    // ---------------- root mode ----------------

    /**
     * For the selected container only. On: ask su (the root manager prompts), then apply the image's
     * ownership to its current deployment. Off: give every file back to the app
     * (`fix-ownership --undo`) so proot works again, then clear the setting. Refused while that
     * container's terminals or desktop run.
     */
    fun setRootMode(on: Boolean) {
        val st = _state.value
        val container = st.container
        if (st.operation is Operation.Running || st.rootProbing || on == st.rootMode) return
        if (terminals.values.any { it.container == container && it.session.isRunning } ||
            (desktopActive() && st.desktopContainer == container)) {
            _state.update { it.copy(rootMessage = "Close this container's terminals and desktop before switching root mode.") }
            return
        }
        if (on) scope.launch {
            _state.update { it.copy(rootProbing = true, rootMessage = "Waiting for root permission...") }
            val (probe, msg) = withContext(Dispatchers.IO) { RootAccess().probe() }
            val granted = probe == RootAccess.Probe.GRANTED
            if (granted) setRootModeEnabled(this@SessionService, container, true)
            _state.update { it.copy(rootProbing = false, rootMessage = msg) }
            if (_state.value.container != container) return@launch
            _state.update { it.copy(rootMode = granted) }
            val cur = _state.value.status?.current
            if (granted && cur != null && !cur.imageOwned) fixOwnership()
        } else {
            val s = _state.value.status
            val imageOwned = listOfNotNull(s?.current, s?.previous).any { it.imageOwned }
            val off = {
                setRootModeEnabled(this, container, false)
                _state.update { if (it.container == container) it.copy(rootMode = false, rootMessage = "Root mode is off.") else it }
            }
            if (imageOwned) start(OpKind.UNDO_OWNERSHIP, null, false, onSuccess = off) { it.fixOwnership(undo = true) }
            else off()
        }
    }

    /** Root alternative to the Developer options toggle. */
    fun disablePhantomKillerWithRoot(onDone: (Boolean) -> Unit) {
        scope.launch { onDone(withContext(Dispatchers.IO) { RootAccess().disablePhantomKiller() }) }
    }

    /** Runs a failed operation again, on the container it failed on. */
    fun retry(f: Operation.Failed, insecure: Boolean) {
        if (_state.value.operation is Operation.Running) return
        _state.update { it.copy(operation = Operation.Idle) }
        if (_state.value.container != f.container) showContainer(f.container)
        when (f.kind) {
            OpKind.SWITCH -> switchTo(f.ref ?: return, insecure)
            OpKind.UPGRADE -> start(OpKind.UPGRADE, f.ref, false) { it.upgrade() }
            OpKind.ROLLBACK -> rollback()
            OpKind.FIX_OWNERSHIP -> fixOwnership()
            OpKind.UNDO_OWNERSHIP -> setRootMode(false)
        }
    }

    private fun start(
        kind: OpKind, ref: String?, insecure: Boolean,
        onSuccess: () -> Unit = {},
        flow: (DroidcClient) -> kotlinx.coroutines.flow.Flow<DroidcEvent>,
    ) {
        if (_state.value.operation is Operation.Running) return
        val container = _state.value.container
        val creating = kind == OpKind.SWITCH && _state.value.status?.current == null
        _state.update { it.copy(operation = Operation.Running(kind, ref, container = container, insecure = insecure,
            creating = creating)) }
        startForegroundService(Intent(this, SessionService::class.java))
        updateForeground(force = true)
        val c = client(container)
        opJob = scope.launch {
            var result: DroidcEvent.Result? = null
            try {
                flow(c).collect { ev ->
                    when (ev) {
                        is DroidcEvent.Progress -> _state.update { st ->
                            st.copy(operation = Operation.Running(kind, ref, ev.phase, ev.current,
                                ev.total, ev.unit, ev.message, container, insecure, creating))
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
                    _state.update { it.copy(operation = Operation.Running(kind, ref, "ownership",
                        message = "Applying image ownership", container = container, insecure = insecure,
                        creating = creating)) }
                    c.fixOwnership(undo = false).collect { ev -> if (ev is DroidcEvent.Result) r = ev }
                }
            }
            if (r?.ok == true) onSuccess()
            _state.update {
                it.copy(operation = if (r?.ok == true) Operation.Done(kind, "${kind.label} finished", container, creating)
                else Operation.Failed(kind, ref, insecure,
                    r?.error ?: DroidcError(ErrorKind.INTERNAL, "no result"), c.stderrTail(), container, creating))
            }
            updateForeground(force = true)
            refreshStatus()
        }
    }

    /**
     * Frees downloaded layers and old deployments no container uses (`droidc gc`), as the "Not enough
     * space" screen offers. [onDone] gets an error message, or null.
     */
    fun cleanUpImages(onDone: (String?) -> Unit = {}) {
        if (_state.value.operation is Operation.Running) return
        scope.launch {
            val outcome = withContext(Dispatchers.IO) { client(_state.value.container).gc() }
            onDone((outcome as? DroidcOutcome.Failure)?.error?.message)
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
        val container = env.container
        // Until the terminal is in [terminals], keep this container's services from looking idle.
        starting[container] = (starting[container] ?: 0) + 1
        try {
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
                    val b = ProotCommandBuilder(env, spec, fake,
                        overlays = GuestFiles.prepareIdentity(env, spec) + GuestFiles.prepareSelinuxHiding(env),
                        guestEnv = GuestFiles.guestEnv(env, spec))
                    ensureServices(env, b)
                    b.interactiveShell()
                }
            }
            if (env.useRoot) rootPidFiles[id] = pidFile
            val session = TerminalSession(inv.argv.first(), spec.cwd.let { env.filesDir.path },
                inv.argv.drop(1).toTypedArray(), inv.envArray, 4000, sessionClient)
            terminals[id] = Term(container, session)
            _state.update { it.copy(closed = it.closed.filterNot { c -> c.container == container }) }
            val n = terminals.values.count { it.container == container }
            session.mSessionName = if (env.isDefaultContainer) "Session $n" else "$container $n"
            startForegroundService(Intent(this, SessionService::class.java))
            publishSessions()
            updateForeground(force = true)
            keepUptimeCurrent()
            return session
        } finally {
            val n = (starting[container] ?: 1) - 1
            if (n > 0) starting[container] = n else starting.remove(container)
            stopIdleServices()
        }
    }

    fun terminalSessions(): List<TerminalSession> = terminals.values.map { it.session }

    /** The session that [SessionInfo.id] names, while it is open. */
    fun terminalSession(id: String): TerminalSession? = terminals[id]?.session

    /** Closes every terminal open in [container]. */
    fun closeTerminals(container: String) {
        terminals.values.filter { it.container == container }.map { it.session }.forEach(::closeSession)
    }

    fun closeSession(s: TerminalSession) {
        s.finishIfRunning()
        terminals.entries.firstOrNull { it.value.session === s }?.key?.let { stopRoot(it) }
        terminals.values.removeAll { it.session === s }
        publishSessions()
        updateForeground(force = true)
    }

    private fun publishSessions() {
        val infos = terminals.entries.map { (id, t) ->
            val s = t.session
            SessionInfo(id, s.title ?: s.mSessionName ?: "Session", s.isRunning, t.container)
        }
        _state.update { it.copy(sessions = infos) }
        updateForeground()
        stopIdleServices()
    }

    private fun runningSessions() = terminals.values.count { it.session.isRunning }

    private var uptimeJob: Job? = null

    /**
     * Keeps each running container's fake `/proc/uptime` current (Selkie#15): Android hides the real
     * one, and a file written once per launch made `uptime` stand still. Stops when nothing runs.
     */
    private fun keepUptimeCurrent() {
        if (uptimeJob?.isActive == true) return
        // On the main dispatcher, like every other use of [terminals]; only the writes go to IO.
        uptimeJob = scope.launch {
            val cpus = Runtime.getRuntime().availableProcessors()
            while (isActive) {
                val containers = terminals.values.filter { it.session.isRunning }.map { it.container }.toMutableSet()
                if (desktopActive()) _state.value.desktopContainer?.let { containers += it }
                if (containers.isEmpty()) break
                val envs = containers.map { newEnv(it) }
                withContext(Dispatchers.IO) {
                    val up = SystemClock.elapsedRealtime() / 1000.0
                    for (e in envs) GuestFiles.refreshUptime(e, up, cpus)
                }
                delay(UPTIME_REFRESH_MS)
            }
        }
    }

    // ---------------- noticing that Android closed something ----------------

    @Volatile private var destroying = false
    private var lastRecord: String? = null

    /** Keeps what runs in preferences, so a start after Android ended the process can tell (see [noticeRestart]). */
    private fun recordRunning() {
        val st = _state.value
        val desktopC = st.desktopContainer.takeIf { desktopActive() }
        val recs = runRecords(desktopC, st.desktopApp?.id, st.desktopApp?.name,
            terminals.values.filter { it.session.isRunning }.map { it.container }, bootCount())
        val enc = encodeRunRecords(recs)
        if (enc == lastRecord) return
        lastRecord = enc
        prefs.edit().apply { if (recs.isEmpty()) remove(KEY_RUNNING) else putString(KEY_RUNNING, enc) }.apply()
    }

    /** A new process that finds sessions recorded as running: the previous one ended without stopping them. */
    private fun noticeRestart() {
        // Only a new process: the service can be created again in the same one, which ended nothing.
        if (restartChecked) return
        restartChecked = true
        val left = decodeRunRecords(prefs.getString(KEY_RUNNING, null))
        if (left.isEmpty()) return
        prefs.edit().remove(KEY_RUNNING).apply()
        val (kind, at) = lastExit()
        val closed = closedAfterRestart(left, kind, at, bootCount()) { c -> logTail(newEnv(c)) }
        android.util.Log.i(TAG, "previous run ended ($kind) with ${left.size} container(s) running")
        if (closed.isNotEmpty()) _state.update { it.copy(closed = closed) }
    }

    @android.annotation.SuppressLint("InlinedApi")
    private fun lastExit(): Pair<ExitKind, Long?> {
        if (Build.VERSION.SDK_INT < 30) return ExitKind.Unknown to null
        val am = getSystemService(ActivityManager::class.java) ?: return ExitKind.Unknown to null
        val info = runCatching { am.getHistoricalProcessExitReasons(packageName, 0, 1).firstOrNull() }.getOrNull()
            ?: return ExitKind.Unknown to null
        val kind = when (info.reason) {
            ApplicationExitInfo.REASON_LOW_MEMORY -> ExitKind.LowMemory
            ApplicationExitInfo.REASON_USER_REQUESTED, ApplicationExitInfo.REASON_USER_STOPPED,
            // An update or a disabled package isn't Android closing it. The constants are plain ints, so they
            // compare fine on older devices, which never report them.
            ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE,
            ApplicationExitInfo.REASON_PACKAGE_UPDATED -> ExitKind.UserStopped
            ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE,
            ApplicationExitInfo.REASON_ANR -> ExitKind.Crash
            ApplicationExitInfo.REASON_EXIT_SELF -> ExitKind.ExitSelf
            else -> ExitKind.Other
        }
        return kind to info.timestamp
    }

    private fun bootCount(): Int? =
        Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT, -1).takeIf { it >= 0 }

    private fun killedCause(): ClosedCause =
        if (PhantomProcessCheck(this).check() == PhantomProcessCheck.State.RESTRICTED) ClosedCause.ChildProcesses
        else ClosedCause.Other

    /** Selkie's screen is in front, rather than only its foreground service running. */
    private fun onScreen(): Boolean = ActivityManager.RunningAppProcessInfo().also {
        ActivityManager.getMyMemoryState(it)
    }.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND

    private fun noteKilled(k: Closed) {
        android.util.Log.w(TAG, "Android closed ${k.container}: desktop=${k.desktop} terminals=${k.terminals} cause=${k.cause}")
        _state.update { it.copy(closed = addKilled(it.closed, k)) }
    }

    /** A terminal Selkie didn't close (still listed) that ended with SIGKILL. */
    private fun noticeKilledTerminal(s: TerminalSession) {
        if (destroying) return
        val t = terminals.values.firstOrNull { it.session === s } ?: return
        if (!isSigkill(s.exitStatus)) return
        noteKilled(Closed(t.container, System.currentTimeMillis(), desktop = false, terminals = 1,
            cause = killedCause(), background = !onScreen()))
    }

    fun dismissClosed(container: String) {
        _state.update { it.copy(closed = it.closed.filterNot { c -> c.container == container }) }
    }

    // ---------------- graphical session ----------------

    @Volatile private var compositor: NativeCompositor? = null
    /** Whether [compositor] runs in app mode (one app at the display's scale) or session mode. */
    @Volatile private var compositorAppMode = false
    @Volatile private var desktop: Process? = null
    /** The desktop's sound on Android, while the desktop runs. */
    @Volatile private var audio: GuestAudio? = null
    /** Follows Android's look while any container's host-link runs. */
    private var appearanceWatcher: AppearanceWatcher? = null
    /** The guest's notifications in Android's shade (and so on a paired watch). */
    private val guestNotifier by lazy { GuestNotifier(this) }
    private var batteryWatcher: BatteryWatcher? = null
    private var desktopJob: Job? = null

    /** The running compositor, for the view that shows it. */
    fun compositor(): NativeCompositor? = compositor

    /** The app [startDesktop] opens on its own instead of the session; see [prepareApp]. */
    @Volatile private var pendingApp: AppEntry? = null

    /**
     * Has the next [startDesktop] open [app] on its own, full screen, instead of the whole desktop.
     * Returns why it can't, or null. The compositor shows one thing at a time, so an app waits until
     * the desktop or another app is closed; the same app already showing is fine.
     */
    fun prepareApp(app: AppEntry): String? {
        val st = _state.value
        if (st.desktop is DesktopState.Starting || st.desktop is DesktopState.Running) {
            if (st.desktopContainer == st.container && st.desktopApp?.id == app.id) return null
            val what = st.desktopApp?.name ?: "the desktop"
            return "Close $what in ${st.desktopContainer} first. Selkie shows one at a time."
        }
        if (st.rootMode) return "Apps don't open in root mode yet. Turn root mode off for this container to use them."
        pendingApp = app
        _state.update { it.copy(desktop = DesktopState.Stopped, desktopApp = app) }
        return null
    }

    /** The next [startDesktop] starts the whole desktop (undoes [prepareApp] if it never ran). */
    fun prepareDesktop() {
        val st = _state.value
        if (st.desktop is DesktopState.Starting || st.desktop is DesktopState.Running) return
        pendingApp = null
        _state.update { it.copy(desktop = DesktopState.Stopped, desktopApp = null) }
    }

    /**
     * Starts the compositor at [width]x[height] pixels (the view size) and the deployment's graphical
     * session at about that size ([DisplayScale.sessionSize]) and [scale] (by default from the
     * display's density; see [DisplayScale]). After [prepareApp], the app runs instead, on its own.
     * The session keeps running without the activity; the compositor holds frames while nothing
     * shows them.
     */
    fun startDesktop(width: Int, height: Int, scale: Float = DisplayScale.of(this)) {
        val st = _state.value.desktop
        if (st is DesktopState.Starting || st is DesktopState.Running) return
        val env = newEnv()
        val app = pendingApp
        _state.update { it.copy(desktop = DesktopState.Starting, desktopContainer = env.container, desktopApp = app,
            closed = it.closed.filterNot { c -> c.container == env.container }) }
        startForegroundService(Intent(this, SessionService::class.java))
        updateForeground(force = true)
        desktopJob = scope.launch {
            val p = try {
                withContext(Dispatchers.IO) {
                    val outcome = DroidcClient(env).shellSpec()
                    val spec = (outcome as? DroidcOutcome.Success)?.shellSpec()
                        ?: error("shell-spec failed: ${(outcome as? DroidcOutcome.Failure)?.error?.message}")
                    if (app == null && spec.session?.configured != true) error("This image declares no graphical session. Configure one first.")
                    // Root mode leaves the files image-owned, which the app-uid proot below can't write to.
                    if (env.useRoot) error(if (app != null) "Apps don't open in root mode yet. Turn root mode off for this container to use them."
                        else "The desktop doesn't run in root mode yet. Turn root mode off for this container to use it.")
                    val fake = GuestFiles.prepare(env, spec)
                    val b = ProotCommandBuilder(env, spec, fake,
                        overlays = GuestFiles.prepareIdentity(env, spec) + GuestFiles.prepareSelinuxHiding(env),
                        guestEnv = GuestFiles.guestEnv(env, spec))
                    ensureServices(env, b)
                    val (sw, sh) = DisplayScale.sessionSize(width, height)
                    val inv = if (app != null) b.app(app.argv) else b.graphicalSession(sw, sh, scale)
                    // An app runs in app mode at the display's scale, so adaptive apps get about the
                    // width an Android app has, and its menus and popovers show. The session's own
                    // scale is [scale], applied by GNOME; its compositor stays 1x.
                    val appMode = app != null
                    if (compositor != null && compositorAppMode != appMode) stopCompositor()
                    compositor = compositor ?: (if (appMode)
                        NativeCompositor.startApp(env.waylandSocket, width, height, DisplayScale.of(this@SessionService))
                    else NativeCompositor.start(env.waylandSocket, width, height)).also { compositorAppMode = appMode }
                    val pb = ProcessBuilder(inv.argv).redirectErrorStream(true)
                        .redirectOutput(env.desktopLog)
                    pb.environment().apply { clear(); putAll(inv.env) }
                    pb.start().also { audio = GuestAudio(b.audioOut(), env.audioLog).apply { start() } }
                }
            } catch (e: Throwable) {
                // Throwable: a missing native library is an Error, and it must end up on screen too.
                if (e is kotlinx.coroutines.CancellationException) throw e
                android.util.Log.w(TAG, "desktop failed to start", e)
                _keepAwake.value = false
                stopCompositor()
                _state.update { it.copy(desktop = DesktopState.Failed(e.message ?: e.toString(), logTail(env)),
                    desktopContainer = null) }
                updateForeground(force = true)
                stopIdleServices()
                return@launch
            }
            desktop = p
            android.util.Log.i(TAG, "${app?.id ?: "desktop session"} started at ${width}x$height")
            _state.update { it.copy(desktop = DesktopState.Running(width, height)) }
            updateForeground(force = true)
            keepUptimeCurrent()
            val code = withContext(Dispatchers.IO) { p.waitFor() }
            android.util.Log.i(TAG, "desktop session exited with $code")
            if (desktop === p) {
                desktop = null
                stopAudio()
                // The link stays for the container's terminals; the desktop's idle inhibitor went with it.
                _keepAwake.value = false
                stopCompositor()
                // SIGKILL that Selkie didn't send: Android closed it, which the containers list explains.
                val killed = code != 0 && isSigkill(code)
                if (killed) noteKilled(Closed(env.container, System.currentTimeMillis(), desktop = true,
                    appId = app?.id, appName = app?.name, cause = killedCause(), background = !onScreen(),
                    logTail = logTail(env)))
                // A failed app stays prepared, so Try again opens it again.
                if (code == 0 || killed) pendingApp = null
                _state.update { it.copy(desktop =
                    if (code == 0 || killed) DesktopState.Stopped
                    else DesktopState.Failed("${app?.name ?: "The session"} exited with status $code", logTail(env)),
                    desktopContainer = null, desktopApp = if (code == 0 || killed) null else app) }
                updateForeground(force = true)
                stopIdleServices()
            }
        }
    }

    /** Stops the session (proot `--kill-on-exit` takes the guest processes with it) and the compositor. */
    fun stopDesktop() {
        val p = desktop
        desktop = null
        desktopJob?.cancel()
        desktopJob = null
        stopAudio()
        _keepAwake.value = false
        if (p != null) {
            p.destroy()
            Thread { if (!p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly() }.start()
        }
        stopCompositor()
        pendingApp = null
        _state.update { it.copy(desktop = DesktopState.Stopped, desktopContainer = null, desktopApp = null) }
        updateForeground(force = true)
        stopIdleServices()
    }

    /** Bytes of the desktop's sound received from the guest and played on Android so far, or null without a desktop. */
    fun audioBytes(): Pair<Long, Long>? = audio?.let { it.received to it.played }

    /**
     * Starts `droidc host-link` for a container's sessions, terminals and desktop alike: the desktop follows
     * Android's look through it, and `droidc adb` in any session reaches Android through it.
     */
    private fun startLink(b: ProotCommandBuilder, env: EngineEnv): GuestLink {
        val container = env.container
        val link = GuestLink(b.hostLink(), env.hostLinkLog) { m -> onLinkMessage(container, m) }.apply { start() }
        scope.launch(Dispatchers.Main) {
            if (appearanceWatcher == null)
                appearanceWatcher = AppearanceWatcher(this@SessionService) { sendAppearance() }.apply { start() }
            if (batteryWatcher == null)
                batteryWatcher = BatteryWatcher(this@SessionService) { line -> sendToLinks(line) }.apply { start() }
        }
        return link
    }

    private fun linkOf(container: String): GuestLink? = synchronized(services) { services[container]?.link }

    private fun onLinkMessage(container: String, m: GuestLinkMessage) {
        when (m.kind) {
            "ready" -> {
                // A restarted link knows none of this container's notifications already up, so they can't be acted on.
                guestNotifier.cancelContainer(container)
                linkOf(container)?.let { l ->
                    l.send(Appearance.read(this).toLine())
                    // Android locks and blanks the device; GNOME's own lock would be a second one.
                    l.send("""{"kind":"screen-lock","by_android":true}""")
                    batteryWatcher?.current?.let { l.send(it) }
                }
            }
            "keep-awake" -> _keepAwake.value = m.body["on"]?.jsonPrimitive?.booleanOrNull == true
            "open-uri" -> OpenUri.uriFrom(m)?.let { uri ->
                scope.launch(Dispatchers.Main) { OpenUri.open(this@SessionService, uri) }
            }
            "notify" -> GuestNotification.parse(m.body)?.let {
                guestNotifier.show(it.copy(key = GuestNotification.scopedKey(container, it.key)))
            }
            "close-notification" -> (m.body["key"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                ?.let { guestNotifier.cancel(GuestNotification.scopedKey(container, it)) }
            else -> AdbRequests.handle(this, container, m) { line -> linkOf(container)?.send(line) }
        }
    }

    /** The user tapped a guest notification: the activity opens the desktop, the app hears of it. */
    fun openedGuestNotification(scoped: String) {
        val (container, key) = GuestNotification.splitScopedKey(scoped) ?: return
        linkOf(container)?.send(GuestNotification.actionLine(key, null, null))
    }

    /** A button or a dismissal on a guest notification, from its pending intent. */
    private fun onGuestNotificationIntent(intent: Intent) {
        val scoped = intent.getStringExtra(GuestNotifier.EXTRA_KEY) ?: return
        val (container, key) = GuestNotification.splitScopedKey(scoped) ?: return
        val l = linkOf(container)
        when (intent.action) {
            GuestNotifier.ACTION_BUTTON -> {
                val text = GuestNotifier.replyText(intent)
                l?.send(GuestNotification.actionLine(key, intent.getStringExtra(GuestNotifier.EXTRA_BUTTON), text))
                // Done with it here; the app updates or withdraws it in the guest as it sees fit.
                guestNotifier.cancel(scoped)
            }
            GuestNotifier.ACTION_DISMISSED -> l?.send(GuestNotification.closedLine(key))
        }
    }

    private fun sendAppearance() {
        if (synchronized(services) { services.values.none { it.link != null } }) return
        sendToLinks(Appearance.read(this).toLine())
    }

    /** Sends [line] to every container's link: Android's look and battery are the same for all of them. */
    private fun sendToLinks(line: String) {
        val links = synchronized(services) { services.values.mapNotNull { it.link } }
        links.forEach { it.send(line) }
    }

    /** Night mode, font size and the wallpaper's accent arrive here; the desktop follows them. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        sendAppearance()
    }

    private fun stopAudio() {
        audio?.stop()
        audio = null
    }

    // ---------------- per-container host services ----------------

    /**
     * Host processes a container's sessions share, started with its first terminal or desktop and stopped
     * once it has neither: the `droidc user-services` keeper (one session bus or `systemd --user` for all
     * of them, which no single session's proot can hold, since `--kill-on-exit` takes it along) and the
     * `droidc spawn-server` behind the guest's sudo/su/pkexec. Root-mode terminals use neither.
     */
    /** A container's background processes; [link] restarts itself, so it doesn't count toward [alive]. */
    private class ContainerServices(
        val container: String,
        val keeper: Process?, val spawner: Process?, val link: GuestLink?) {
        val alive: Boolean get() = keeper?.isAlive != false && spawner?.isAlive != false
    }
    /** By container; guarded by itself, since sessions start on IO threads. */
    private val services = HashMap<String, ContainerServices>()
    /** Containers with a terminal still being set up (main thread), which [stopIdleServices] leaves alone. */
    private val starting = HashMap<String, Int>()

    /** Starts [env]'s container services unless they run. Blocking: call on IO, before the session starts. */
    private fun ensureServices(env: EngineEnv, b: ProotCommandBuilder) {
        synchronized(services) {
            val cur = services[env.container]
            if (cur != null && cur.alive) return
            // One of them died (an image without a bus, say): start both again, so the next session has them.
            cur?.let { stopServices(it, wait = true) }
            services[env.container] = ContainerServices(
                container = env.container,
                keeper = startService(b.userServices(), env.userServicesLog),
                spawner = if (b.hasShims) startService(b.spawnServer(), env.spawnerLog) else null,
                link = startLink(b, env),
            )
        }
    }

    private fun startService(inv: Invocation, log: File): Process? = try {
        val pb = ProcessBuilder(inv.argv).redirectErrorStream(true).redirectOutput(log)
        pb.environment().apply { clear(); putAll(inv.env) }
        pb.start()
    } catch (e: Exception) {
        // Sessions still work: supervise starts its own keeper after the wait, and sudo says why it can't.
        android.util.Log.w(TAG, "cannot start ${inv.argv.getOrNull(inv.argv.indexOf("--") + 1)}", e)
        null
    }

    private fun takeServices(container: String): ContainerServices? = synchronized(services) { services.remove(container) }

    /** Stops the services of containers with no running terminal, no desktop and no terminal on its way. */
    private fun stopIdleServices() {
        val busy = terminals.values.filter { it.session.isRunning }.mapTo(HashSet()) { it.container }
        _state.value.desktopContainer?.let { busy += it }
        busy += starting.keys
        val idle = synchronized(services) { services.keys.filter { it !in busy }.map { services.remove(it)!! } }
        idle.forEach { stopServices(it, wait = false) }
        stopAppearanceWatcherIfIdle()
    }

    private fun stopAllServices() {
        val all = synchronized(services) { services.values.toList().also { services.clear() } }
        all.forEach { stopServices(it, wait = false) }
        stopAppearanceWatcherIfIdle()
    }

    /** Ends both processes; proot's `--kill-on-exit` takes the keeper's bus and user manager with it. */
    private fun stopAppearanceWatcherIfIdle() {
        if (synchronized(services) { services.isEmpty() })
            scope.launch(Dispatchers.Main) {
                appearanceWatcher?.stop(); appearanceWatcher = null
                batteryWatcher?.stop(); batteryWatcher = null
            }
    }

    private fun stopServices(s: ContainerServices, wait: Boolean) {
        s.link?.stop()
        guestNotifier.cancelContainer(s.container)
        val procs = listOfNotNull(s.keeper, s.spawner)
        procs.forEach { it.destroy() }
        val reap = { procs.forEach { if (!it.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) it.destroyForcibly() } }
        if (wait) reap() else Thread(reap).start()
    }

    fun dismissDesktopError() {
        if (_state.value.desktop is DesktopState.Failed) {
            pendingApp = null
            _state.update { it.copy(desktop = DesktopState.Stopped, desktopApp = null) }
        }
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
        stopDesktop()
        terminals.keys.toList().forEach { stopRoot(it) }
        terminals.values.forEach { it.session.finishIfRunning() }
        terminals.clear()
        publishSessions()
        foreground = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        prefs.edit().remove(KEY_RUNNING).apply()
        stopSelf()
    }

    private fun updateForeground(force: Boolean = false) {
        recordRunning()
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
        private const val UPTIME_REFRESH_MS = 1000L
        private const val TAG = "SelkieDesktop"
        private const val CHANNEL = "sessions"
        private const val NOTIF_ID = 1
        private const val PREFS = "selkie"
        private const val KEY_CONTAINER = "container"
        /** What ran, as [encodeRunRecords] writes it; left behind when Android ends the process. */
        private const val KEY_RUNNING = "running"
        /** [noticeRestart] ran in this process. */
        @Volatile private var restartChecked = false

        fun intent(context: Context) = Intent(context, SessionService::class.java)
    }
}

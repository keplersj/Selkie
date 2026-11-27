package io.github.keplersj.selkie.platform.adb

import android.content.Context
import android.os.Build
import android.util.Log
import io.github.keplersj.selkie.engine.DroidcClient
import io.github.keplersj.selkie.engine.DroidcOutcome
import io.github.keplersj.selkie.engine.EngineEnv
import io.github.keplersj.selkie.engine.engineEnvFrom
import io.github.keplersj.selkie.engine.shellSpec
import io.github.keplersj.selkie.proot.GuestFiles
import io.github.keplersj.selkie.proot.Invocation
import io.github.keplersj.selkie.proot.ProotCommandBuilder
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** What Settings shows about the guest's adb and this device. */
data class AdbStatus(
    /** The container whose adb is paired with this device, or null. */
    val pairedContainer: String? = null,
    /** The Wireless debugging port the guest's adb is connected to, or null. */
    val connectedPort: Int? = null,
    /** Why the last pair or connect failed, in words for the user, or null. */
    val problem: String? = null,
)

/**
 * Keeps the paired container's adb server running and connected to this device. One per app process.
 *
 * The server runs in a proot of its own ([GuestAdb.server]): one that `adb pair` or a terminal started
 * would end with that proot (`--kill-on-exit`), taking the connection with it. While the server runs,
 * [AdbMdns] watches for Wireless debugging and connects whenever it is on, so turning it off and on, or a
 * reboot, needs nothing more than turning it on again. The pairing itself lasts: the device keeps the
 * guest's key until the user revokes it in Settings.
 */
class AdbBridge private constructor(context: Context) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val lock = Mutex()

    private val _status = MutableStateFlow(AdbStatus(pairedContainer = prefs.getString(KEY_CONTAINER, null)))
    val status: StateFlow<AdbStatus> = _status.asStateFlow()

    private var server: Process? = null
    private var serverContainer: String? = null
    private var connectWatch: AdbMdns? = null

    /** A container Selkie can't run adb in, with why. */
    class Unavailable(message: String) : Exception(message)

    /** The proot builder for [container], set up as a terminal's is. Blocking. */
    private suspend fun builder(container: String): Pair<EngineEnv, ProotCommandBuilder> {
        val env = engineEnvFrom(app, container)
        // Root mode's files are image-owned, and the app-uid proot below can't write to them.
        if (env.useRoot) throw Unavailable("Pairing doesn't work in root mode yet. Turn root mode off for $container.")
        val spec = (DroidcClient(env).shellSpec() as? DroidcOutcome.Success)?.let { runCatching { it.shellSpec() }.getOrNull() }
            ?: throw Unavailable("$container has no image installed yet.")
        val fake = GuestFiles.prepare(env, spec)
        return env to ProotCommandBuilder(env, spec, fake,
            overlays = GuestFiles.prepareIdentity(env, spec) + GuestFiles.prepareSelinuxHiding(env),
            guestEnv = GuestFiles.guestEnv(env, spec))
    }

    /** Runs [argv] in the guest and returns its exit status and output; [stdin] is written first. */
    private fun run(inv: Invocation, stdin: String? = null, timeoutSec: Long = 60): Pair<Int, String> {
        val pb = ProcessBuilder(inv.argv).redirectErrorStream(true)
        pb.environment().apply { clear(); putAll(inv.env) }
        val p = pb.start()
        p.outputStream.use { out -> if (stdin != null) out.write(stdin.toByteArray()) }
        val text = StringBuilder()
        val reader = Thread { runCatching { text.append(p.inputStream.bufferedReader().readText()) } }.also { it.start() }
        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            reader.join(2000)
            return -1 to "timed out after ${timeoutSec}s\n$text"
        }
        reader.join(5000)
        return p.exitValue() to text.toString()
    }

    /** Starts [container]'s adb server unless it runs. Returns why it can't, or null. */
    private suspend fun ensureServer(container: String, env: EngineEnv, b: ProotCommandBuilder): String? {
        if (server?.isAlive == true && serverContainer == container) return null
        stopServer()
        // A server some terminal started on the socket would stop ours from listening; ours replaces it.
        run(b.command(GuestAdb.killServer()), timeoutSec = 20)
        val socket = File(env.guestRun, GuestAdb.SERVER_SOCKET.removePrefix("/run/"))
        socket.delete()
        val log = File(env.cacheDir, if (env.isDefaultContainer) "adb-server.log" else "adb-server-$container.log")
        val inv = b.command(GuestAdb.server())
        val p = ProcessBuilder(inv.argv).redirectErrorStream(true).redirectOutput(log)
            .apply { environment().apply { clear(); putAll(inv.env) } }.start()
        repeat(SERVER_WAIT_STEPS) {
            if (socket.exists()) { server = p; serverContainer = container; return null }
            if (!p.isAlive) {
                val out = runCatching { log.readText() }.getOrDefault("")
                return if (p.exitValue() == GuestAdb.NO_ADB) noAdb(container)
                    else "adb's server in $container exited: ${out.lines().lastOrNull { it.isNotBlank() } ?: p.exitValue()}"
            }
            delay(100)
        }
        p.destroy()
        return "adb's server in $container didn't start"
    }

    private fun stopServer() {
        server?.let { p -> p.destroy(); if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroyForcibly() }
        server = null
        serverContainer = null
    }

    /**
     * Pairs [container]'s adb with this device's pairing service on [port] using [code], then keeps it
     * connected. Returns the result; on [GuestAdb.PairResult.Paired] the container is remembered.
     */
    suspend fun pair(container: String, port: Int, code: String): GuestAdb.PairResult = lock.withLock {
        withContext(Dispatchers.IO) {
            val (env, b) = try { builder(container) } catch (e: Unavailable) {
                return@withContext GuestAdb.PairResult.Failed(e.message ?: "unavailable")
            }
            ensureServer(container, env, b)?.let { why ->
                return@withContext if (why == noAdb(container)) GuestAdb.PairResult.NoAdb else GuestAdb.PairResult.Failed(why)
            }
            val (exit, out) = run(b.command(GuestAdb.pair(port)), stdin = code + "\n")
            Log.i(TAG, "adb pair in $container: exit $exit")
            GuestAdb.pairResult(exit, out).also { r ->
                if (r == GuestAdb.PairResult.Paired) {
                    prefs.edit().putString(KEY_CONTAINER, container).apply()
                    _status.update { AdbStatus(pairedContainer = container) }
                }
            }
        }
    }.also { if (it == GuestAdb.PairResult.Paired) keepConnected() }

    /**
     * Starts the paired container's server and connects whenever Wireless debugging is on. Does nothing if
     * no container is paired or Android is older than 11. Safe to call again.
     */
    fun keepConnected() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val container = _status.value.pairedContainer ?: return
        scope.launch {
            val why = lock.withLock {
                try {
                    val (env, b) = builder(container)
                    ensureServer(container, env, b)
                } catch (e: Unavailable) { e.message }
            }
            _status.update { it.copy(problem = why) }
            if (why != null) return@launch
            withContext(Dispatchers.Main) {
                if (connectWatch == null) connectWatch = AdbMdns(app, AdbMdns.TLS_CONNECT) { port ->
                    if (port == null) _status.update { it.copy(connectedPort = null) }
                    else scope.launch { connect(container, port) }
                }.apply { start() }
            }
        }
    }

    private suspend fun connect(container: String, port: Int) = lock.withLock {
        val (_, b) = try { builder(container) } catch (e: Unavailable) {
            _status.update { it.copy(problem = e.message) }; return@withLock
        }
        // The port may come back before the server notices the old one has gone.
        var result: GuestAdb.ConnectResult = GuestAdb.ConnectResult.Failed("not tried")
        for (attempt in 1..CONNECT_TRIES) {
            val (exit, out) = run(b.command(GuestAdb.connect(port)), timeoutSec = 30)
            result = GuestAdb.connectResult(exit, out)
            if (result !is GuestAdb.ConnectResult.Failed) break
            delay(1000L * attempt)
        }
        Log.i(TAG, "adb connect $port in $container: $result")
        _status.update {
            when (val r = result) {
                GuestAdb.ConnectResult.Connected -> it.copy(connectedPort = port, problem = null)
                GuestAdb.ConnectResult.NoAdb -> it.copy(connectedPort = null, problem = noAdb(container))
                is GuestAdb.ConnectResult.Failed -> it.copy(connectedPort = null, problem = "Couldn't connect: ${r.message}")
            }
        }
    }

    /** Stops the server and the watch; the pairing is kept. */
    fun stop() {
        scope.launch {
            withContext(Dispatchers.Main) { connectWatch?.stop(); connectWatch = null }
            lock.withLock { stopServer() }
            _status.update { it.copy(connectedPort = null) }
        }
    }

    companion object {
        private const val TAG = "SelkieAdb"
        private const val PREFS = "selkie-adb"
        private const val KEY_CONTAINER = "paired-container"
        private const val SERVER_WAIT_STEPS = 100
        private const val CONNECT_TRIES = 3

        fun noAdb(container: String) = "There's no adb in $container. Install it there, then pair again."

        @Volatile private var instance: AdbBridge? = null
        fun get(context: Context): AdbBridge =
            instance ?: synchronized(this) { instance ?: AdbBridge(context).also { instance = it } }
    }
}

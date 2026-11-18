package io.github.keplersj.selkie.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

/** Keeps the last [capacity] lines for error display. */
class RingBuffer(private val capacity: Int = 60) {
    private val lines = ArrayDeque<String>()
    @Synchronized fun add(line: String) {
        if (lines.size == capacity) lines.removeFirst()
        lines.addLast(line)
    }
    @Synchronized fun snapshot(): List<String> = lines.toList()
    @Synchronized fun text(): String = lines.joinToString("\n")
}

/** Builds droidc argv. Separate from the process plumbing so it is trivially unit-testable. */
object DroidcArgs {
    fun global(env: EngineEnv, json: Boolean): List<String> = buildList {
        add(env.droidcBin.path)
        add("--root"); add(env.droidcRoot.path)
        if (json) add("--json")
        if (env.dns.isNotEmpty()) { add("--dns"); add(env.dns.joinToString(",")) }
    }

    /**
     * Root mode lets droidc make real hard links (`auto`; link2symlink only where link(2) fails)
     * and apply the image's owners/setuid to the new deployment (`--apply-ownership`).
     */
    fun switch(ref: String, insecure: Boolean, root: Boolean = false): List<String> = buildList {
        add("switch"); add(ref)
        if (insecure) add("--insecure-allow-unsigned")
        add("--links"); add(if (root) "auto" else "link2symlink")
        if (root) add("--apply-ownership")
    }

    fun fixOwnership(undo: Boolean): List<String> =
        if (undo) listOf("fix-ownership", "--undo") else listOf("fix-ownership")
}

/**
 * Runs droidc as a subprocess. stdout is NDJSON (parsed into [DroidcEvent]); stderr goes to
 * [EngineEnv.droidcLog] and its tail is exposed via [stderrTail] for error cards.
 */
class DroidcClient(private val env: EngineEnv) {
    /** Whether mutating commands run as root (root mode with an su binary). */
    val useRoot: Boolean get() = env.useRoot

    private val tail = RingBuffer()

    /** Last stderr/log lines of the most recent run. */
    fun stderrTail(): String = tail.text()

    /** droidc's own environment (also what root mode passes through `env -i`). */
    private fun droidcEnv(): Map<String, String> = linkedMapOf(
        "HOME" to env.filesDir.path, "TMPDIR" to env.cacheDir.path, "PATH" to "/system/bin",
    )

    /**
     * Streams events for `droidc --json <args>`; [asRoot] runs it through `su` (root mode).
     * Cancelling the collector kills the process, and as root also the root pid (with Magisk the
     * root process is not our child).
     */
    fun events(args: List<String>, asRoot: Boolean = false): Flow<DroidcEvent> = callbackFlow {
        // Created by the app, so the root droidc hands what it makes back to the app uid.
        env.droidcRoot.mkdirs()
        env.cacheDir.mkdirs()
        tail.snapshot() // keep previous run until new lines arrive
        val su = env.su
        if (asRoot && su == null) {
            trySend(DroidcEvent.Result(false, null, DroidcError(ErrorKind.INTERNAL, "root mode: no su binary")))
            close(); return@callbackFlow
        }
        val argv = DroidcArgs.global(env, json = true) + args
        val pb = if (asRoot && su != null) ProcessBuilder(RootShell.su(su, argv, droidcEnv(), RootShell.PID_ON_STDOUT))
            .also { it.environment().apply { clear(); putAll(RootShell.suEnv(env.filesDir)) } }
        else ProcessBuilder(argv).also { it.environment().apply { clear(); putAll(droidcEnv()) } }
        pb.redirectError(ProcessBuilder.Redirect.appendTo(env.droidcLog))
        val proc = try { pb.start() } catch (e: Exception) {
            trySend(DroidcEvent.Result(false, null, DroidcError(ErrorKind.INTERNAL,
                "cannot start ${env.droidcBin}: ${e.message}")))
            close(); return@callbackFlow
        }
        val sawResult = AtomicReference(false)
        val rootPid = AtomicReference<Int?>(null)
        val reader = Thread {
            try {
                proc.inputStream.bufferedReader().useLines { seq ->
                    for (line in seq) {
                        if (asRoot && rootPid.get() == null) RootShell.parsePid(line)?.let { rootPid.set(it); continue }
                        val ev = NdjsonParser.parseLine(line) ?: continue
                        when (ev) {
                            is DroidcEvent.Log -> tail.add("[${ev.level}] ${ev.message}")
                            is DroidcEvent.Result -> sawResult.set(true)
                            else -> {}
                        }
                        trySend(ev)
                    }
                }
                val code = proc.waitFor()
                tail.add("exit status $code")
                if (!sawResult.get()) {
                    trySend(DroidcEvent.Result(false, null, DroidcError(ErrorKind.INTERNAL,
                        "droidc exited with status $code without a result" + readLogTail())))
                }
            } catch (_: Exception) {
            } finally { close() }
        }
        reader.isDaemon = true
        reader.start()
        awaitClose {
            val pid = rootPid.get()
            if (su != null && pid != null && !sawResult.get()) {
                // Cancelled while running as root: droidc is crash-safe and recovers next time.
                Thread { runCatching { ProcessBuilder(RootShell.kill(su, pid)).start().waitFor() } }.start()
            }
            proc.destroyForcibly()
        }
    }.flowOn(Dispatchers.IO)

    private fun readLogTail(): String = try {
        val lines = env.droidcLog.readLines().takeLast(15)
        lines.forEach { tail.add(it) }
        if (lines.isEmpty()) "" else "\n" + lines.joinToString("\n")
    } catch (_: Exception) { "" }

    /** Runs to completion, forwarding events to [onEvent]. */
    suspend fun run(args: List<String>, onEvent: suspend (DroidcEvent) -> Unit = {}): DroidcOutcome {
        var result: DroidcEvent.Result? = null
        events(args).collect { ev ->
            onEvent(ev)
            if (ev is DroidcEvent.Result) result = ev
        }
        val r = result ?: DroidcEvent.Result(false, null, DroidcError(ErrorKind.INTERNAL, "no result"))
        return if (r.ok) DroidcOutcome.Success(r.data)
        else DroidcOutcome.Failure(r.error ?: DroidcError(), readLogTailSafe())
    }

    private fun readLogTailSafe(): String = tail.text().ifBlank { readLogTail() }

    suspend fun status(): DroidcOutcome = run(listOf("status"))
    suspend fun shellSpec(): DroidcOutcome = run(listOf("shell-spec"))
    suspend fun platform(): DroidcOutcome = run(listOf("platform"))

    // Read-only commands (status, shell-spec) never need root: droidc hands records back to the app.
    fun switch(ref: String, insecure: Boolean): Flow<DroidcEvent> =
        events(DroidcArgs.switch(ref, insecure, root = env.useRoot), asRoot = env.useRoot)
    fun upgrade(): Flow<DroidcEvent> = events(listOf("upgrade"), asRoot = env.useRoot)
    fun rollback(): Flow<DroidcEvent> = events(listOf("rollback"), asRoot = env.useRoot)
    /** Always as root: apply the image's ownership, or ([undo]) give everything back to the app. */
    fun fixOwnership(undo: Boolean): Flow<DroidcEvent> = events(DroidcArgs.fixOwnership(undo), asRoot = true)

    /** `droidc --version` (human text, no --json). */
    suspend fun version(): String = withContext(Dispatchers.IO) {
        runPlain(listOf(env.droidcBin.path, "--version"))
    }

    /** `libproot.so --version`, first lines. */
    suspend fun prootVersion(): String = withContext(Dispatchers.IO) {
        runPlain(listOf(env.prootBin.path, "--version"), mapOf(
            "PROOT_LOADER" to env.prootLoader.path, "PROOT_TMP_DIR" to env.prootTmp.path))
    }

    private fun runPlain(cmd: List<String>, extraEnv: Map<String, String> = emptyMap()): String = try {
        env.prootTmp.mkdirs()
        val pb = ProcessBuilder(cmd).redirectErrorStream(true)
        pb.environment().apply { clear(); putAll(extraEnv); put("PATH", "/system/bin") }
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText().trim()
        p.waitFor()
        out.lineSequence().firstOrNull { it.isNotBlank() } ?: "(no output)"
    } catch (e: Exception) { "unavailable (${e.message})" }
}


package io.github.keplersj.selkie.service

import io.github.keplersj.selkie.engine.SelkieJson
import io.github.keplersj.selkie.proot.Invocation
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.io.Writer
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * A guest command waiting to run as root: the container's sudo, su or pkexec asked `droidc spawn-server`,
 * which asked Selkie and waits for the answer.
 */
data class RootRequest(
    /** The spawner's number for it, unique while that spawner runs. */
    val id: Long,
    val container: String,
    /** The shim that asked: `sudo`, `su`, `pkexec`. */
    val via: String,
    val argv: List<String>,
    val cwd: String,
    /** The calling program's name, from the guest. It can say anything, so it is only a hint. */
    val from: String? = null,
    /** Where [argv]'s program is in the container (`/usr/bin/id` for `id`), or null if Selkie can't tell. */
    val program: String? = null,
) {
    /** The command as one line, for lists and notifications. */
    val commandLine: String get() = argv.joinToString(" ") { quoteArg(it) }
}

/** How the person answered a [RootRequest]. */
enum class RootChoice {
    /** Runs this command once. */
    Once,
    /** Runs this one and any later root command in the container, until its sessions stop. */
    WhileRunning,
    Deny,
}

/** What `droidc spawn-server` says on its stdout in ask mode. */
sealed interface SpawnerEvent {
    data class Asked(val request: RootRequest) : SpawnerEvent
    /** [by] is `host` (Selkie answered), `caller` (the guest gave up) or `nobody`. */
    data class Settled(val id: Long, val allowed: Boolean, val by: String) : SpawnerEvent
}

object RootRequests {
    /** Reads one line of the spawner's stdout; null for anything else, so a newer droidc can't break Selkie. */
    fun parse(line: String, container: String, rootfs: String? = null): SpawnerEvent? =
        runCatching { read(line, container, rootfs) }.getOrNull()

    private fun read(line: String, container: String, rootfs: String?): SpawnerEvent? {
        val o = SelkieJson.parseToJsonElement(line) as? JsonObject ?: return null
        (o["ask"] as? JsonObject)?.let { a ->
            val id = a["id"]?.jsonPrimitive?.longOrNull ?: return null
            val argv = (a["argv"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: return null
            if (argv.isEmpty()) return null
            return SpawnerEvent.Asked(RootRequest(
                id = id,
                container = container,
                via = a["via"]?.jsonPrimitive?.contentOrNull ?: "sudo",
                argv = argv,
                cwd = a["cwd"]?.jsonPrimitive?.contentOrNull ?: "/",
                from = a["from"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
                program = rootfs?.let { resolveProgram(it, argv[0]) },
            ))
        }
        (o["settled"] as? JsonObject)?.let { s ->
            val id = s["id"]?.jsonPrimitive?.longOrNull ?: return null
            return SpawnerEvent.Settled(id, s["allowed"]?.jsonPrimitive?.booleanOrNull ?: false,
                s["by"]?.jsonPrimitive?.contentOrNull ?: "nobody")
        }
        return null
    }

    /** The line that answers request [id] on the spawner's stdin. */
    fun answer(id: Long, choice: RootChoice): String = buildJsonObject {
        put("answer", buildJsonObject {
            put("id", id)
            put("allow", choice != RootChoice.Deny)
            put("remember", choice == RootChoice.WhileRunning)
        })
    }.toString()

    /** Ends a [RootChoice.WhileRunning] answer early: the spawner asks again from the next command on. */
    const val FORGET = """{"forget":true}"""

    /**
     * Where the spawner's commands find [program]: its own path if it names one, else the first match on
     * droidc's fixed root PATH (`status::DEFAULT_PATH`) inside [rootfs]. Symlinks are not followed: they
     * point at guest paths.
     */
    fun resolveProgram(rootfs: String, program: String): String? {
        if (program.isEmpty()) return null
        if ('/' in program) return program.takeIf { it.startsWith("/") }
        for (dir in ROOT_PATH) {
            val guest = "$dir/$program"
            if (Files.exists(File(rootfs + guest).toPath(), LinkOption.NOFOLLOW_LINKS)) return guest
        }
        return null
    }

    /** droidc's `status::DEFAULT_PATH`, which the spawner sets for every root command. */
    val ROOT_PATH = listOf("/usr/local/sbin", "/usr/local/bin", "/usr/sbin", "/usr/bin", "/sbin", "/bin")
}

/**
 * A running `droidc spawn-server` in ask mode: reads its questions from stdout on a thread of its own and
 * writes answers to its stdin. Its stderr (the log) goes to [log]. [onEvent] runs on the reader thread;
 * [onEnd] runs there once the spawner's stdout closes, as it does when it stops.
 */
class RootAskHost private constructor(
    val process: Process,
    private val container: String,
    private val rootfs: String?,
    private val onEvent: (SpawnerEvent) -> Unit,
    private val onEnd: () -> Unit,
) {
    private val input: Writer = process.outputStream.bufferedWriter()

    private val reader = Thread({
        try {
            process.inputStream.bufferedReader().forEachLine { line ->
                RootRequests.parse(line, container, rootfs)?.let(onEvent)
            }
        } catch (_: Exception) {
            // Stopped while reading.
        } finally {
            onEnd()
        }
    }, "root-asks-$container").apply { isDaemon = true }

    /** Answers [id]; false if the spawner has gone, which refuses everything it was asking. */
    fun answer(id: Long, choice: RootChoice): Boolean = send(RootRequests.answer(id, choice))

    fun forget(): Boolean = send(RootRequests.FORGET)

    private fun send(line: String): Boolean = synchronized(input) {
        try {
            input.write(line); input.write("\n"); input.flush(); true
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        fun start(
            inv: Invocation,
            log: File,
            container: String,
            rootfs: String?,
            onEvent: (SpawnerEvent) -> Unit,
            onEnd: () -> Unit = {},
        ): RootAskHost {
            val pb = ProcessBuilder(inv.argv).redirectError(log)
            pb.environment().apply { clear(); putAll(inv.env) }
            return RootAskHost(pb.start(), container, rootfs, onEvent, onEnd).also { it.reader.start() }
        }
    }
}

/** "default wants to run a command as root", as the phone, the watch and the notification say it. */
fun rootRequestTitle(r: RootRequest): String = "${r.container} wants to run a command as root"

/** Shell-style quoting for showing a command: plain words stay as they are. */
internal fun quoteArg(s: String): String =
    if (s.isNotEmpty() && s.all { it.isLetterOrDigit() || it in "-_./=:,+@%" }) s
    else "'" + s.replace("'", "'\\''") + "'"

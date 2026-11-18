package io.github.keplersj.selkie.engine

import java.io.File

/**
 * Command lines for root mode: everything that runs as root goes through `su -c '<script>'`, and the
 * script is built here so its quoting is unit-tested. Image refs typed by the user end up in these
 * scripts, so every word is single-quoted unless it is plainly safe.
 */
object RootShell {
    /** Where su lives with Magisk, KernelSU, APatch and older root solutions. */
    val SU_CANDIDATES = listOf(
        "/system/bin/su", "/system/xbin/su", "/sbin/su", "/debug_ramdisk/su",
        "/data/adb/ksu/bin/su", "/data/adb/ap/bin/su", "/su/bin/su",
    )

    fun findSu(exists: (String) -> Boolean = { File(it).exists() }): String? =
        SU_CANDIDATES.firstOrNull { runCatching { exists(it) }.getOrDefault(false) }

    private val SAFE = Regex("^[A-Za-z0-9@%+=:,./_-]+$")
    private val ENV_KEY = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

    /** POSIX single-quoting: `'` becomes `'\''`. */
    fun quote(s: String): String = if (SAFE.matches(s)) s else "'" + s.replace("'", "'\\''") + "'"

    /**
     * `su -c` running [cmd] as root with exactly [env] (`env -i`), after an optional [preamble]. The
     * root shell `exec`s the command, so its pid (`$$`, what [PID_ON_STDOUT] and [pidToFile] record)
     * stays the command's: with Magisk the root process is not a child of our `su`, and killing `su`
     * does not reach it.
     */
    fun su(su: String, cmd: List<String>, env: Map<String, String>, preamble: String? = null): List<String> {
        require(cmd.isNotEmpty()) { "empty command" }
        val script = buildString {
            preamble?.let { append(it).append("; ") }
            append("exec /system/bin/env -i")
            for ((k, v) in env) {
                require(ENV_KEY.matches(k)) { "bad environment name: $k" }
                append(' ').append(quote("$k=$v"))
            }
            for (w in cmd) append(' ').append(quote(w))
        }
        return listOf(su, "-c", script)
    }

    /** NDJSON-shaped first line with the root pid; [DroidcClient] reads and drops it. */
    const val PID_EVENT = "selkie-pid"
    const val PID_ON_STDOUT = "printf '{\"event\":\"$PID_EVENT\",\"pid\":%d}\\n' $$"

    /** Pid from a [PID_ON_STDOUT] line, or null for any other line. */
    fun parsePid(line: String): Int? =
        Regex("""^\{"event":"$PID_EVENT","pid":(\d+)}$""").find(line.trim())?.groupValues?.get(1)?.toIntOrNull()

    /** Preamble writing the root pid to [f] (terminal sessions, whose stdout is the terminal). */
    fun pidToFile(f: File) = "echo $$ > ${quote(f.path)}"

    fun kill(su: String, pid: Int) = listOf(su, "-c", "kill -TERM $pid")

    /** Kill whatever pid [f] holds, then remove [f] (root wrote it; the app may not be able to). */
    fun killFromFile(su: String, f: File): List<String> {
        val q = quote(f.path)
        return listOf(su, "-c", "[ -s $q ] && kill -TERM \"\$(cat $q)\"; rm -f $q")
    }

    /** Clean environment for the `su` client itself. */
    fun suEnv(home: File): Map<String, String> = linkedMapOf(
        "PATH" to "/system/bin:/system/xbin", "HOME" to home.path,
    )
}

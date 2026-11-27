package io.github.keplersj.selkie.service

/**
 * Noticing that Android closed a container's sessions, and saying so (Phone-Killed in the redesign).
 *
 * Two ways it happens. Android ends Selkie's whole process (low memory, battery limits, a restart): the
 * next start finds [RunRecord]s that were never cleared. Or Android's child process limit kills a
 * session while Selkie keeps running: the desktop or a terminal ends with SIGKILL that Selkie didn't send.
 */

/** What ran in one container, kept in preferences while anything runs and cleared when it all stops. */
data class RunRecord(
    val container: String,
    val desktop: Boolean,
    /** The app shown on its own in place of the desktop: its desktop entry id and name. */
    val appId: String? = null,
    val appName: String? = null,
    val terminals: Int = 0,
    /** Settings.Global.BOOT_COUNT when written, so a restart can be told apart. */
    val boot: Int? = null,
)

enum class ClosedCause {
    /** Android's child process limit ("Disable child process restrictions" is off). */
    ChildProcesses,
    LowMemory,
    /** The device restarted while sessions ran. */
    Restart,
    /** Selkie itself crashed. */
    Crash,
    /** Android closed Selkie for a reason it doesn't explain, such as battery limits. */
    Other,
}

/** A container whose sessions Android ended, until the person dismisses it. */
data class Closed(
    val container: String,
    /** When, in milliseconds since the epoch; null when Android didn't say. */
    val at: Long?,
    val desktop: Boolean,
    val appId: String? = null,
    val appName: String? = null,
    val terminals: Int = 0,
    val cause: ClosedCause = ClosedCause.Other,
    /** Selkie wasn't on screen, as when its whole process was ended. */
    val background: Boolean = true,
    /** The last lines of the container's desktop log, or empty. */
    val logTail: String = "",
)

private const val SEP = '\t'

/** One line per container: name, desktop flag, app id, app name, terminals, boot count. */
fun encodeRunRecords(list: List<RunRecord>): String = list.joinToString("\n") { r ->
    listOf(r.container, if (r.desktop) "1" else "0", r.appId.orEmpty(), r.appName.orEmpty().replace(SEP, ' '),
        r.terminals.toString(), r.boot?.toString().orEmpty()).joinToString(SEP.toString())
}

fun decodeRunRecords(s: String?): List<RunRecord> = s.orEmpty().lines().mapNotNull { line ->
    val f = line.split(SEP)
    if (f.size < 5 || f[0].isEmpty()) return@mapNotNull null
    RunRecord(f[0], f[1] == "1", f[2].ifEmpty { null }, f[3].ifEmpty { null }, f[4].toIntOrNull() ?: 0,
        f.getOrNull(5)?.toIntOrNull())
}

/** Records for what runs now: a container appears when its desktop or one of its terminals runs. */
fun runRecords(
    desktopContainer: String?,
    appId: String?,
    appName: String?,
    terminalContainers: List<String>,
    boot: Int?,
): List<RunRecord> {
    val names = (listOfNotNull(desktopContainer) + terminalContainers).distinct()
    return names.map { c ->
        val d = c == desktopContainer
        RunRecord(c, d, if (d) appId else null, if (d) appName else null, terminalContainers.count { it == c }, boot)
    }
}

/** Why the previous process ended, as Android's ApplicationExitInfo reports it. */
enum class ExitKind { LowMemory, UserStopped, Crash, ExitSelf, Other, Unknown }

/**
 * What the previous run left behind, turned into [Closed] notices. Nothing when the person stopped Selkie
 * themselves (force stop, or the Task Manager's Stop) or Selkie exited on its own.
 */
fun closedAfterRestart(
    records: List<RunRecord>,
    exit: ExitKind,
    exitAt: Long?,
    bootNow: Int?,
    logTail: (String) -> String = { "" },
): List<Closed> {
    if (exit == ExitKind.UserStopped || exit == ExitKind.ExitSelf) return emptyList()
    return records.map { r ->
        val restarted = r.boot != null && bootNow != null && r.boot != bootNow
        val cause = when {
            restarted -> ClosedCause.Restart
            exit == ExitKind.LowMemory -> ClosedCause.LowMemory
            exit == ExitKind.Crash -> ClosedCause.Crash
            else -> ClosedCause.Other
        }
        Closed(r.container, if (restarted) null else exitAt, r.desktop, r.appId, r.appName, r.terminals, cause,
            background = true, logTail = if (r.desktop) logTail(r.container) else "")
    }
}

/** A session ended by SIGKILL that Selkie didn't send, folded into the notice for its container. */
fun addKilled(list: List<Closed>, k: Closed, windowMs: Long = 30_000): List<Closed> {
    val i = list.indexOfFirst { it.container == k.container && it.at != null && k.at != null && k.at - it.at <= windowMs }
    if (i < 0) return list.filterNot { it.container == k.container } + k
    val o = list[i]
    val merged = o.copy(
        desktop = o.desktop || k.desktop,
        appId = o.appId ?: k.appId, appName = o.appName ?: k.appName,
        terminals = o.terminals + k.terminals,
        logTail = o.logTail.ifEmpty { k.logTail },
        background = o.background && k.background,
    )
    return list.toMutableList().also { it[i] = merged }
}

/** A Java or terminal exit status that means SIGKILL: 137 from a process, -9 from a terminal, or 9. */
fun isSigkill(status: Int): Boolean = status == 137 || status == -9 || status == 9

private fun plural(n: Int, one: String) = "$n $one" + if (n == 1) "" else "s"

/** "The desktop and 2 terminals", "Text Editor", "1 terminal". */
fun closedWhat(c: Closed): String {
    val parts = listOfNotNull(
        if (c.desktop) c.appName ?: "The desktop" else null,
        if (c.terminals > 0) plural(c.terminals, "terminal").let { if (c.desktop) it else it.replaceFirstChar(Char::uppercase) } else null,
    )
    return parts.joinToString(" and ").ifEmpty { "Its sessions" }
}

fun closedTitle(c: Closed): String =
    if (c.cause == ClosedCause.Crash) "Selkie stopped, and ${c.container} with it" else "Android closed ${c.container}"

/** The card's text; [time] is [Closed.at] formatted for the person, or null. */
fun closedLine(c: Closed, time: String?): String {
    val first = when (c.cause) {
        ClosedCause.Restart -> "The device restarted while it ran."
        ClosedCause.Crash -> if (time != null) "Selkie stopped unexpectedly at $time." else "Selkie stopped unexpectedly."
        else -> listOfNotNull(
            "It happened",
            time?.let { "at $it" },
            if (c.background) "while Selkie was in the background" else null,
        ).joinToString(" ") + when (c.cause) {
            ClosedCause.LowMemory -> ", when the device ran low on memory."
            else -> "."
        }
    }
    return "$first ${closedWhat(c)} ended. Your files are safe; unsaved work in open apps is lost."
}

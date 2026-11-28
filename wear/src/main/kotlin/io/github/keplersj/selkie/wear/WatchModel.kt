package io.github.keplersj.selkie.wear

import io.github.keplersj.selkie.watch.WatchContainer
import io.github.keplersj.selkie.watch.WatchDesktop
import io.github.keplersj.selkie.watch.WatchRootRequest

/**
 * What the phone last published: its name, its containers, the root requests waiting there, and the node to
 * send requests to.
 */
data class PhoneState(
    val device: String,
    val containers: List<WatchContainer>,
    val node: String? = null,
    val rootRequests: List<WatchRootRequest> = emptyList(),
)

/** The big line at the top of the list. */
internal fun runningHeadline(containers: List<WatchContainer>): String {
    val n = containers.count { it.running }
    return if (n == 0) "Nothing running" else "$n running"
}

/** What one container is doing, under its name. */
internal fun containerLine(c: WatchContainer): String {
    val parts = buildList {
        when (c.desktop) {
            WatchDesktop.Running -> add("Desktop")
            WatchDesktop.Starting -> add("Desktop starting")
            WatchDesktop.None -> Unit
        }
        if (c.terminals == 1) add("1 terminal") else if (c.terminals > 1) add("${c.terminals} terminals")
    }
    return parts.joinToString(" · ").ifEmpty { "Not running" }
}

/** Running containers first, each group in the phone's order. */
internal fun ordered(containers: List<WatchContainer>): List<WatchContainer> =
    containers.filter { it.running } + containers.filterNot { it.running }

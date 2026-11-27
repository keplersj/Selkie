package io.github.keplersj.selkie.watch

import java.net.URI

// Shared by the phone app and the watch app (both add shared/watch/kotlin as a source folder), so the two
// sides can't drift apart. Plain Kotlin only: the Data Layer calls live in each app.

/** What a container on the phone is doing, as the watch shows it. */
enum class WatchDesktop { None, Starting, Running }

/** One of the phone's containers, as its watch sees it. */
data class WatchContainer(val name: String, val desktop: WatchDesktop = WatchDesktop.None, val terminals: Int = 0) {
    val running: Boolean get() = desktop != WatchDesktop.None || terminals > 0
}

/**
 * The phone publishes one DataItem at [STATE_PATH] with the device's name and its containers. The watch asks
 * for changes with messages to the node that published it.
 */
object WatchProtocol {
    const val STATE_PATH = "/selkie/state"
    const val KEY_DEVICE = "device"
    const val KEY_CONTAINERS = "containers"
    /** No payload: stops the phone's desktop (there is one at a time). */
    const val STOP_DESKTOP_PATH = "/selkie/stop-desktop"
    /** Payload: the container's name in UTF-8. Closes its terminals. */
    const val CLOSE_TERMINALS_PATH = "/selkie/close-terminals"

    /**
     * The link the watch opens on the phone (Open on phone) to show one container: selkie://container/NAME.
     * The phone's MainActivity takes it as a browsable VIEW intent, which is what the watch may start remotely.
     */
    fun containerLink(name: String): String = URI(LINK_SCHEME, LINK_HOST, "/$name", null).toASCIIString()

    /** The container a [containerLink] names, or null for any other link. */
    fun containerFromLink(link: String?): String? {
        val uri = link?.let { runCatching { URI(it) }.getOrNull() } ?: return null
        if (uri.scheme != LINK_SCHEME || uri.host != LINK_HOST) return null
        return uri.path?.removePrefix("/")?.takeIf { it.isNotBlank() && '/' !in it }
    }

    const val LINK_SCHEME = "selkie"
    const val LINK_HOST = "container"

    /** One line per container: name, desktop, terminals, separated by tabs. Container names hold neither. */
    fun encode(containers: List<WatchContainer>): String =
        containers.joinToString("\n") { "${it.name}\t${it.desktop.name}\t${it.terminals}" }

    /** Skips lines it can't read, so a newer phone app can't crash an older watch app. */
    fun decode(text: String?): List<WatchContainer> =
        text.orEmpty().lineSequence().mapNotNull { line ->
            val parts = line.split('\t')
            val name = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val desktop = WatchDesktop.entries.firstOrNull { it.name == parts.getOrNull(1) } ?: WatchDesktop.None
            val terminals = parts.getOrNull(2)?.toIntOrNull()?.coerceAtLeast(0) ?: 0
            WatchContainer(name, desktop, terminals)
        }.toList()
}

package io.github.keplersj.selkie.watch

// Shared by the phone app and the watch app (both add shared/watch/kotlin as a source folder), so the two
// sides can't drift apart. Plain Kotlin only: the Data Layer calls live in each app.

/** What a container on the phone is doing, as the watch shows it. */
enum class WatchDesktop { None, Starting, Running }

/** One of the phone's containers, as its watch sees it. */
data class WatchContainer(val name: String, val desktop: WatchDesktop = WatchDesktop.None, val terminals: Int = 0) {
    val running: Boolean get() = desktop != WatchDesktop.None || terminals > 0
}

/**
 * A guest command waiting for root on the phone. The watch can only refuse it or leave it for the phone,
 * which shows the whole command; it never allows one.
 */
data class WatchRootRequest(val container: String, val id: Long, val command: String)

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
    /** Root requests waiting on the phone, as [encodeRoot] writes them. */
    const val KEY_ROOT = "root"
    /** Payload: [refusal]. Refuses one root request. */
    const val REFUSE_ROOT_PATH = "/selkie/refuse-root"

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

    /** One line per request: container, id, command, separated by tabs; tabs and newlines in the command become spaces. */
    fun encodeRoot(requests: List<WatchRootRequest>): String =
        requests.joinToString("\n") { "${it.container}\t${it.id}\t${it.command.replace(Regex("[\t\r\n]"), " ")}" }

    fun decodeRoot(text: String?): List<WatchRootRequest> =
        text.orEmpty().lineSequence().mapNotNull { line ->
            val parts = line.split('\t', limit = 3)
            val container = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val id = parts.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
            WatchRootRequest(container, id, parts.getOrNull(2).orEmpty())
        }.toList()

    /** The payload of [REFUSE_ROOT_PATH]: the request's container and id. */
    fun refusal(container: String, id: Long): ByteArray = "$container\t$id".toByteArray(Charsets.UTF_8)

    fun readRefusal(payload: ByteArray): Pair<String, Long>? {
        val parts = String(payload, Charsets.UTF_8).split('\t')
        val container = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return null
        return container to (parts.getOrNull(1)?.toLongOrNull() ?: return null)
    }
}

package io.github.keplersj.selkie.service

import io.github.keplersj.selkie.watch.WatchContainer
import io.github.keplersj.selkie.watch.WatchDesktop
import io.github.keplersj.selkie.watch.WatchRootRequest

/** The containers as the watch remote shows them: what each runs right now. */
internal fun watchContainers(state: ServiceState): List<WatchContainer> = state.containers.map { c ->
    val desktop = if (state.desktopContainer != c.name) WatchDesktop.None else when (state.desktop) {
        DesktopState.Starting -> WatchDesktop.Starting
        is DesktopState.Running -> WatchDesktop.Running
        else -> WatchDesktop.None
    }
    WatchContainer(c.name, desktop, state.sessions.count { it.container == c.name && it.running })
}

/** Root requests waiting on the phone, for the watch to refuse or leave. */
internal fun watchRootRequests(state: ServiceState): List<WatchRootRequest> =
    state.rootRequests.map { WatchRootRequest(it.container, it.id, it.commandLine) }

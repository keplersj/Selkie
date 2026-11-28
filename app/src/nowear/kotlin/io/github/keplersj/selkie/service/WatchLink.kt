package io.github.keplersj.selkie.service

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/** Builds without Google Play services (-Pselkie.wear=false) have no watch remote. */
object WatchLink {
    @Suppress("UNUSED_PARAMETER")
    fun start(
        context: Context,
        state: StateFlow<ServiceState>,
        scope: CoroutineScope,
        onStopDesktop: () -> Unit,
        onCloseTerminals: (String) -> Unit,
        onRefuseRoot: (container: String, id: Long) -> Unit,
    ): AutoCloseable = AutoCloseable {}
}

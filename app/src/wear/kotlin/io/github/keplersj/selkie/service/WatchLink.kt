package io.github.keplersj.selkie.service

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import io.github.keplersj.selkie.watch.WatchContainer
import io.github.keplersj.selkie.watch.WatchProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The watch remote's phone side, over the Wear Data Layer: publishes what each container runs and takes the
 * watch's requests to stop the desktop or close a container's terminals. Does nothing without Play services.
 */
object WatchLink {
    fun start(
        context: Context,
        state: StateFlow<ServiceState>,
        scope: CoroutineScope,
        onStopDesktop: () -> Unit,
        onCloseTerminals: (String) -> Unit,
    ): AutoCloseable {
        val app = context.applicationContext
        val available = runCatching {
            GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(app) == ConnectionResult.SUCCESS
        }.getOrDefault(false)
        if (!available) return AutoCloseable {}
        val data = Wearable.getDataClient(app)
        val messages = Wearable.getMessageClient(app)
        val device = deviceName(app)
        fun publish(containers: List<WatchContainer>) {
            val request = PutDataMapRequest.create(WatchProtocol.STATE_PATH).apply {
                dataMap.putString(WatchProtocol.KEY_DEVICE, device)
                dataMap.putString(WatchProtocol.KEY_CONTAINERS, WatchProtocol.encode(containers))
            }.asPutDataRequest().setUrgent()
            // No watch, or no Wear OS app on the phone: the call fails and that's fine.
            runCatching { data.putDataItem(request) }
        }
        val listener = MessageClient.OnMessageReceivedListener { event ->
            when (event.path) {
                WatchProtocol.STOP_DESKTOP_PATH -> onStopDesktop()
                WatchProtocol.CLOSE_TERMINALS_PATH -> onCloseTerminals(String(event.data, Charsets.UTF_8))
            }
        }
        runCatching { messages.addListener(listener) }
        val job = scope.launch {
            state.map { watchContainers(it) }.distinctUntilChanged().collect { publish(it) }
        }
        return AutoCloseable {
            job.cancel()
            runCatching { messages.removeListener(listener) }
            // Selkie has stopped, so nothing runs: say so rather than leave the watch on the last state.
            publish(watchContainers(state.value).map { WatchContainer(it.name) })
        }
    }

    private fun deviceName(context: Context): String =
        runCatching { Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) }
            .getOrNull()?.takeIf { it.isNotBlank() } ?: Build.MODEL
}

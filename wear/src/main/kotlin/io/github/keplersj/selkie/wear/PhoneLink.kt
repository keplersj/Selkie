package io.github.keplersj.selkie.wear

import android.content.Context
import android.net.Uri
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataItem
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import io.github.keplersj.selkie.watch.WatchProtocol

/** The watch's side of the Wear Data Layer: reads what the phone publishes and sends it requests. */
class PhoneLink(context: Context) {
    private val data = Wearable.getDataClient(context.applicationContext)
    private val messages = Wearable.getMessageClient(context.applicationContext)

    /** Calls [onState] with what the phone published (null when nothing has been), then on every change. */
    fun watch(onState: (PhoneState?) -> Unit): AutoCloseable {
        val uri = Uri.Builder().scheme(PutDataRequest.WEAR_URI_SCHEME).path(WatchProtocol.STATE_PATH).build()
        data.getDataItems(uri)
            .addOnSuccessListener { buffer ->
                val state = buffer.firstNotNullOfOrNull { read(it) }
                buffer.release()
                onState(state)
            }
            .addOnFailureListener { onState(null) }
        val listener = DataClient.OnDataChangedListener { events ->
            events.forEach { e ->
                if (e.type == DataEvent.TYPE_CHANGED && e.dataItem.uri.path == WatchProtocol.STATE_PATH) {
                    onState(read(e.dataItem))
                }
            }
        }
        data.addListener(listener)
        return AutoCloseable { data.removeListener(listener) }
    }

    fun stopDesktop(node: String) {
        messages.sendMessage(node, WatchProtocol.STOP_DESKTOP_PATH, ByteArray(0))
    }

    fun closeTerminals(node: String, container: String) {
        messages.sendMessage(node, WatchProtocol.CLOSE_TERMINALS_PATH, container.toByteArray(Charsets.UTF_8))
    }

    private fun read(item: DataItem): PhoneState? {
        if (item.uri.path != WatchProtocol.STATE_PATH) return null
        val map = DataMapItem.fromDataItem(item).dataMap
        return PhoneState(
            device = map.getString(WatchProtocol.KEY_DEVICE)?.takeIf { it.isNotBlank() } ?: "your phone",
            containers = WatchProtocol.decode(map.getString(WatchProtocol.KEY_CONTAINERS)),
            node = item.uri.host,
        )
    }
}

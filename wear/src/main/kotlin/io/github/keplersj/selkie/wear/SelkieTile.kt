package io.github.keplersj.selkie.wear

import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.DimensionBuilders.wrap
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.WearableListenerService
import com.google.common.util.concurrent.ListenableFuture
import io.github.keplersj.selkie.watch.WatchProtocol

// The Watch artboard's tile: which device Selkie runs on, how many containers run there and which, and a
// button into the list. It reads what the phone last published, and the phone's next publish refreshes it.

/** What the tile says, worked out apart from the layout so it can be tested. */
data class TileContent(val top: String, val headline: String, val names: String?, val button: String)

internal fun tileContent(phone: PhoneState?): TileContent =
    if (phone == null) {
        TileContent("Selkie", "No phone yet", "Open Selkie on your phone", "Open")
    } else {
        TileContent(
            top = "Selkie · ${phone.device}",
            headline = runningHeadline(phone.containers),
            names = ordered(phone.containers).filter { it.running }.joinToString(" · ") { it.name }.ifEmpty { null },
            button = "Open list",
        )
    }

// The watch app's sea-teal dark colours (WatchScreens.kt), as tiles take them.
private const val PRIMARY = 0xFF82D5C8.toInt()
private const val ON_SURFACE = 0xFFDDE4E1.toInt()
private const val ON_SURFACE_VARIANT = 0xFFBEC9C6.toInt()
private const val SURFACE_HIGH = 0xFF2F3635.toInt()

private fun text(value: String, size: Float, color: Int, weight: Int = LayoutElementBuilders.FONT_WEIGHT_NORMAL) =
    LayoutElementBuilders.Text.Builder()
        .setText(value)
        .setMaxLines(1)
        .setOverflow(LayoutElementBuilders.TEXT_OVERFLOW_ELLIPSIZE)
        .setFontStyle(
            LayoutElementBuilders.FontStyle.Builder()
                .setSize(sp(size)).setColor(argb(color)).setWeight(weight).build(),
        )
        .build()

private fun spacer(height: Float) = LayoutElementBuilders.Spacer.Builder().setHeight(dp(height)).build()

/** Opens the watch app's list. */
private fun openList(packageName: String) = ModifiersBuilders.Clickable.Builder()
    .setId("open-list")
    .setOnClick(
        ActionBuilders.LaunchAction.Builder()
            .setAndroidActivity(
                ActionBuilders.AndroidActivity.Builder()
                    .setPackageName(packageName)
                    .setClassName(WatchActivity::class.java.name)
                    .build(),
            )
            .build(),
    )
    .build()

/** The tile's layout for [content]; [packageName] is the watch app's, for the button. */
fun tileLayout(packageName: String, content: TileContent): LayoutElement {
    val button = LayoutElementBuilders.Box.Builder()
        .setWidth(wrap()).setHeight(dp(48f))
        .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
        .setModifiers(
            ModifiersBuilders.Modifiers.Builder()
                .setClickable(openList(packageName))
                .setBackground(
                    ModifiersBuilders.Background.Builder()
                        .setColor(argb(SURFACE_HIGH))
                        .setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(24f)).build())
                        .build(),
                )
                .setPadding(ModifiersBuilders.Padding.Builder().setStart(dp(20f)).setEnd(dp(20f)).build())
                .setSemantics(ModifiersBuilders.Semantics.Builder().setContentDescription(content.button).build())
                .build(),
        )
        .addContent(text(content.button, 14f, ON_SURFACE, LayoutElementBuilders.FONT_WEIGHT_MEDIUM))
        .build()
    val column = LayoutElementBuilders.Column.Builder()
        .setWidth(expand())
        .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
        .addContent(text(content.top, 12f, ON_SURFACE_VARIANT))
        .addContent(spacer(2f))
        .addContent(text(content.headline, 24f, ON_SURFACE, LayoutElementBuilders.FONT_WEIGHT_MEDIUM))
        .apply { content.names?.let { addContent(spacer(2f)); addContent(text(it, 13f, PRIMARY)) } }
        .addContent(spacer(10f))
        .addContent(button)
        .build()
    return LayoutElementBuilders.Box.Builder()
        .setWidth(expand()).setHeight(expand())
        .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
        .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
        .setModifiers(
            ModifiersBuilders.Modifiers.Builder()
                // A round screen's corners are cut off: keep text inside it.
                .setPadding(ModifiersBuilders.Padding.Builder().setStart(dp(22f)).setEnd(dp(22f)).build())
                .build(),
        )
        .addContent(column)
        .build()
}

const val TILE_RESOURCES_VERSION = "1"

/** The tile, built from what the phone last published. */
class SelkieTileService : TileService() {
    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> =
        CallbackToFutureAdapter.getFuture { completer ->
            PhoneLink(this).readOnce { phone ->
                completer.set(
                    TileBuilders.Tile.Builder()
                        .setResourcesVersion(TILE_RESOURCES_VERSION)
                        .setTileTimeline(
                            TimelineBuilders.Timeline.fromLayoutElement(tileLayout(packageName, tileContent(phone))),
                        )
                        .build(),
                )
            }
            "Selkie tile"
        }

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest,
    ): ListenableFuture<ResourceBuilders.Resources> =
        CallbackToFutureAdapter.getFuture { completer ->
            completer.set(ResourceBuilders.Resources.Builder().setVersion(TILE_RESOURCES_VERSION).build())
            "Selkie tile resources"
        }
}

/** Refreshes the tile whenever the phone publishes, so it never waits for the watch to ask. */
class PhoneStateListener : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        val changed = events.any { it.type == DataEvent.TYPE_CHANGED && it.dataItem.uri.path == WatchProtocol.STATE_PATH }
        if (changed) TileService.getUpdater(this).requestUpdate(SelkieTileService::class.java)
    }
}

package io.github.keplersj.selkie.wear

import android.os.Looper
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.tiles.renderer.TileRenderer
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.keplersj.selkie.watch.WatchContainer
import io.github.keplersj.selkie.watch.WatchDesktop
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w227dp-h227dp-round-xhdpi")
class SelkieTileTest {
    private val tablet = PhoneState(
        "Kepler's tablet",
        listOf(
            WatchContainer("scratch"),
            WatchContainer("default", WatchDesktop.Running, 0),
            WatchContainer("dev", WatchDesktop.None, 1),
        ),
    )

    @Test fun content() {
        val c = tileContent(tablet)
        assertEquals("Selkie · Kepler's tablet", c.top)
        assertEquals("2 running", c.headline)
        assertEquals("default · dev", c.names)
        assertEquals("Open list", c.button)
        assertNull(tileContent(tablet.copy(containers = listOf(WatchContainer("a")))).names)
        assertEquals("No phone yet", tileContent(null).headline)
    }

    /** The tile as a watch draws it, for wear/screenshots/ like the app's screens. */
    @Test fun tileScreenshots() {
        shoot("SelkieTile.running", tileContent(tablet))
        shoot("SelkieTile.noPhone", tileContent(null))
    }

    private fun shoot(name: String, content: TileContent) {
        val context = RuntimeEnvironment.getApplication()
        val frame = FrameLayout(context)
        val renderer = TileRenderer(context, { it.run() }, {})
        val layout = LayoutElementBuilders.Layout.Builder().setRoot(tileLayout(context.packageName, content)).build()
        val inflated = renderer.inflateAsync(layout, ResourceBuilders.Resources.Builder().setVersion("1").build(), frame)
        shadowOf(Looper.getMainLooper()).idle()
        inflated.get(10, TimeUnit.SECONDS)
        // Drawn in a round 227dp watch face, as the previews are.
        captureRoboImage("screenshots/$name.png") {
            Box(Modifier.size(227.dp).clip(CircleShape).background(Color.Black)) {
                AndroidView(factory = { frame }, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

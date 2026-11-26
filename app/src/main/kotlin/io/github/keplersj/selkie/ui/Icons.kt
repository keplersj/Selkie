package io.github.keplersj.selkie.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The redesign canvas's line icons (Icon.dc.html): 24dp, 1.8 stroke, round caps and joins. Each path is
 * drawn as a stroke, so `Icon(tint = ...)` colours it like any other vector.
 */
object SelkieIcons {
    private fun line(name: String, d: String): ImageVector =
        ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .addPath(pathData = addPathNodes(d), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            .build()

    val Containers by lazy { line("containers", "M3 8l9-5 9 5v8l-9 5-9-5V8zM3 8l9 5 9-5M12 13v8") }
    val Images by lazy { line("images", "M12 3l9 5-9 5-9-5 9-5zM3 12.5l9 5 9-5M3 17l9 5 9-5") }
    val Activity by lazy { line("activity", "M3 12h4l3-8 4 16 3-8h4") }
    val Settings by lazy { line("settings", "M4 7h10M18 7h2M4 17h2M10 17h10M16 4.5v5M8 14.5v5") }
    val Terminal by lazy { line("terminal", "M4 5h16v14H4zM7.5 9.5l3 2.5-3 2.5M12.5 15h4") }
    val Desktop by lazy { line("desktop", "M3 5h18v11H3zM9 20h6M12 16v4") }
    val Plus by lazy { line("plus", "M12 5v14M5 12h14") }
    val More by lazy { line("more", "M12 5.5v.01M12 12v.01M12 18.5v.01") }
    val Back by lazy { line("back", "M19 12H5M11 6l-6 6 6 6") }
    val Check by lazy { line("check", "M5 12.5l4.5 4.5L19 7.5") }
    val Shield by lazy { line("shield", "M12 3l8 3v6c0 4.5-3.2 7.8-8 9-4.8-1.2-8-4.5-8-9V6l8-3zM8.5 12l2.5 2.5 4.5-5") }
    val Warn by lazy { line("warn", "M12 4l9 16H3L12 4zM12 10v4.5M12 17.5v.01") }
    val Download by lazy { line("download", "M12 4v11M7 11l5 5 5-5M5 20h14") }
    val Upgrade by lazy { line("upgrade", "M12 21a9 9 0 100-18 9 9 0 000 18zM12 16V8M8.5 11.5L12 8l3.5 3.5") }
    val Rollback by lazy { line("rollback", "M4 12a8 8 0 108-8 8 8 0 00-6 2.7M4 4v4h4M12 8v4l3 2") }
    val Stop by lazy { line("stop", "M7 7h10v10H7z") }
    val Play by lazy { line("play", "M8 5l11 7-11 7V5z") }
    val Copy by lazy { line("copy", "M9 9h11v11H9zM5 15V4h11") }
    val Trash by lazy { line("trash", "M5 7h14M10 7V4h4v3M7 7l1 13h8l1-13") }
    val Chevron by lazy { line("chevron", "M9 6l6 6-6 6") }
    val Close by lazy { line("close", "M6 6l12 12M18 6L6 18") }
    val Keyboard by lazy { line("keyboard", "M3 6h18v12H3zM7 10v.01M11 10v.01M15 10v.01M8 14h8") }
    val Root by lazy { line("root", "M5 9h14M5 15h14M9.5 4l-1.5 16M16 4l-1.5 16") }
    val Apps by lazy { line("apps", "M4 4h6v6H4zM14 4h6v6h-6zM4 14h6v6H4zM14 14h6v6h-6z") }
    val Logs by lazy { line("logs", "M5 6h14M5 10h14M5 14h9M5 18h6") }
    val Storage by lazy {
        line("storage", "M4 6c0-1.7 3.6-3 8-3s8 1.3 8 3v12c0 1.7-3.6 3-8 3s-8-1.3-8-3V6zM4 6c0 1.7 3.6 3 8 3s8-1.3 8-3" +
            "M4 12c0 1.7 3.6 3 8 3s8-1.3 8-3")
    }
    val Info by lazy { line("info", "M12 21a9 9 0 100-18 9 9 0 000 18zM12 11v6M12 7.5v.01") }
    val Block by lazy { line("block", "M12 21a9 9 0 100-18 9 9 0 000 18zM5.6 5.6l12.8 12.8") }
    val Search by lazy { line("search", "M11 18a7 7 0 100-14 7 7 0 000 14zM16 16l4.5 4.5") }
    val Edit by lazy { line("edit", "M4 20l4-1L19 8l-3-3L5 16l-1 4z") }
    val Open by lazy { line("open", "M14 4h6v6M20 4l-9 9M18 14v6H4V6h6") }
    val Bell by lazy { line("bell", "M6 16v-5a6 6 0 1112 0v5l2 2H4l2-2zM10 21h4") }
    val Pause by lazy { line("pause", "M8 5v14M16 5v14") }

    // The canvas used one "swap" icon for both changing image and the install's last step, and one "device"
    // icon for both a phone and Developer options. Each meaning gets its own here.
    /** Change the image under a container. */
    val ChangeImage by lazy { line("change-image", "M4 8h14M14 4l4 4-4 4M20 16H6M10 12l-4 4 4 4") }
    /** The install's last step: the new version becomes the one in use. */
    val MakeCurrent by lazy { line("make-current", "M12 21a9 9 0 100-18 9 9 0 000 18zM8 12.5l3 3 5-6") }
    /** A phone or tablet: this device. */
    val Device by lazy { line("device", "M7 3h10v18H7zM11 18h2") }
    /** Developer options and other Android system settings. */
    val DeveloperOptions by lazy { line("developer-options", "M8 8l-4 4 4 4M16 8l4 4-4 4M13.5 5l-3 14") }
}

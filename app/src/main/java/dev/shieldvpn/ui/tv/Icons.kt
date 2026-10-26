package dev.shieldvpn.ui.tv

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** The design's line icons: 24-unit paths, drawn with round caps. */
enum class Ic(vararg val paths: String) {
    Logo("M4 20v-9a8 8 0 0 1 16 0v9", "M8.5 20v-8a3.5 3.5 0 0 1 7 0v8"),
    Power("M12 3v8", "M6.6 6.6a7.5 7.5 0 1 0 10.8 0"),
    Pause("M9 5v14", "M15 5v14"),
    Play("M8 5l11 7-11 7z"),
    Info(circle(12f, 12f, 9f), "M12 11v5", "M12 8h.01"),
    Pencil("M4 20h4L19 9l-4-4L4 16z"),
    Log("M5 6h14", "M5 10h14", "M5 14h9", "M5 18h6"),
    Swap("M4 8h13", "M14 4l4 4-4 4", "M20 16H7", "M10 12l-4 4 4 4"),
    Plus("M12 5v14", "M5 12h14"),
    Chev("M9 6l6 6-6 6"),
    Phone(rect(7f, 2.5f, 10f, 19f, 2.5f), "M11 18h2"),
    Usb("M9 2.5h6v5H9z", "M7 7.5h10v8a5 5 0 0 1-10 0z"),
    Link("M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1", "M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1"),
    Archive("M4 8h16v11a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1z", "M3 4h18v4H3z", "M10 12h4"),
    Cert(rect(4f, 4f, 16f, 12f, 2f), "M8 9h8", "M8 12h5", "M15 16v4l2-1 2 1v-4"),
    File("M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z", "M14 3v5h5"),
    Check("M5 12.5l4.5 4.5L19 7.5"),
    Bang("M12 6v8", "M12 18h.01"),
    Shield("M12 3l7.5 3v5.5c0 4.6-3.1 8.2-7.5 9.5-4.4-1.3-7.5-4.9-7.5-9.5V6z", "M8.8 12.2l2.2 2.2 4.3-4.6"),
    Lock(rect(5f, 11f, 14f, 9f, 2f), "M8 11V8a4 4 0 0 1 8 0v3"),
    Rocket("M12 3c3 2 5 5.5 5 9l-2.5 3h-5L7 12c0-3.5 2-7 5-9z", circle(12f, 10f, 1.6f), "M9.5 18.5L8 21", "M14.5 18.5L16 21"),
    Apps(rect(3.5f, 5f, 17f, 11f, 2f), "M8 20h8"),
    Pulse("M3 12h4l2-5 4 10 2-5h6"),
    Gauge("M4.5 17a8.5 8.5 0 1 1 15 0", "M12 13l3.5-4"),
    Gear(circle(12f, 12f, 3f), "M12 2.5v3M12 18.5v3M2.5 12h3M18.5 12h3M5.3 5.3l2.1 2.1M16.6 16.6l2.1 2.1M5.3 18.7l2.1-2.1M16.6 7.4l2.1-2.1"),
    Globe(circle(12f, 12f, 9f), "M3 12h18", "M12 3a14 14 0 0 1 0 18a14 14 0 0 1 0-18z"),
    Bolt("M13 3L5 14h6l-1 7 8-11h-6z"),
    Eye("M2.5 12S6 5.5 12 5.5 21.5 12 21.5 12 18 18.5 12 18.5 2.5 12 2.5 12z", circle(12f, 12f, 3f)),
    Trash("M4 7h16", "M10 11v6", "M14 11v6", "M6 7l1 12a2 2 0 0 0 2 2h6a2 2 0 0 0 2-2l1-12", "M9 7V4h6v3"),
    Copy(rect(8f, 8f, 12f, 12f, 2f), "M16 8V5a1 1 0 0 0-1-1H5a1 1 0 0 0-1 1v10a1 1 0 0 0 1 1h3"),
    Pin("M9 4h6l-1 6 3 3H7l3-3z", "M12 16v4"),
    Download("M12 4v11", "M7 10.5l5 5 5-5", "M5 20h14"),
}

private val vectors = HashMap<Pair<Ic, Float>, ImageVector>()

private fun vector(icon: Ic, stroke: Float): ImageVector = vectors.getOrPut(icon to stroke) {
    ImageVector.Builder(icon.name, 24.dp, 24.dp, 24f, 24f).apply {
        icon.paths.forEach { d ->
            addPath(
                pathData = addPathNodes(d),
                stroke = SolidColor(Color.White),
                strokeLineWidth = stroke,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
    }.build()
}

/** Draws a design icon at [size] design pixels, tinted [color]. */
@Composable
fun TvIcon(icon: Ic, size: Float = 22f, color: Color = Tv.Ink, stroke: Float = 2.1f, modifier: Modifier = Modifier) {
    VectorIcon(vector(icon, stroke), color, modifier.size(size.dp))
}

private fun circle(cx: Float, cy: Float, r: Float): String =
    "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0"

private fun rect(x: Float, y: Float, w: Float, h: Float, r: Float): String =
    "M${x + r} ${y}H${x + w - r}A$r $r 0 0 1 ${x + w} ${y + r}V${y + h - r}A$r $r 0 0 1 ${x + w - r} ${y + h}" +
        "H${x + r}A$r $r 0 0 1 $x ${y + h - r}V${y + r}A$r $r 0 0 1 ${x + r} ${y}Z"

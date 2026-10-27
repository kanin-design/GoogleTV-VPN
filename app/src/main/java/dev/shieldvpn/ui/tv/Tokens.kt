package dev.shieldvpn.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlin.math.min

/** How the tunnel is doing, as the screens show it. */
enum class Mode { On, Busy, Off, Paused, Error }

/** The design's palette (tunnel-tv-app.html, :root and .stage). */
object Tv {
    val Page = Color(0xFF05070A)
    val Ink = Color(0xFFF3F6F8)
    val Mint = Color(0xFF4BE3A1)
    val Blue = Color(0xFF7DB4FF)
    val Amber = Color(0xFFFFB547)
    val Red = Color(0xFFFF8A7A)
    val DarkRed = Color(0xFFB42318)
    val Focus = Color(0xFFF4F7FA)
    val OnFocus = Color(0xFF071018)

    /** rgba(14,20,29,.76): tabs and chips. Denser than glass, since nothing behind it is blurred. */
    val Glass = Color(0xC20E141D)

    /** rgba(10,14,21,.88): columns, panels and sheets. */
    val GlassDeep = Color(0xE00A0E15)
    val GlassEdge = Color.White.copy(alpha = 0.1f)

    /** The light text tone at a given strength: rgba(235,242,248,a). */
    fun soft(a: Float) = Color(0xFFEBF2F8).copy(alpha = a)

    /** The dark text tone on a focused, white item: rgba(7,16,24,a). */
    fun dark(a: Float) = OnFocus.copy(alpha = a)

    fun white(a: Float) = Color.White.copy(alpha = a)

    /** A state's colour, the background's: green through the VPN, amber on the way, red outside it. */
    fun tone(mode: Mode): Color = when (mode) {
        Mode.On -> Mint
        Mode.Busy, Mode.Paused -> Amber
        Mode.Off, Mode.Error -> Red
    }
}

/**
 * Corners: [Small] for icons and keys, [Medium] for rows, cards and fields, [Large] for panels
 * and sheets. Buttons, tabs and chips are pills.
 */
object TvRadius {
    const val Small = 12f
    const val Medium = 16f
    const val Large = 24f
}

/**
 * Type from the design. Sizes are the design's pixels on its 1280 × 720 canvas; [TvStage]
 * scales them with everything else. Android's own sans (Roboto) and monospace, in three
 * weights: Normal for reading, Medium for quiet labels and big names, SemiBold for what is
 * pressed or titled. Focus never changes a weight, so nothing shifts as it moves.
 */
object TvType {
    val Display: FontFamily = FontFamily.SansSerif
    val Ui: FontFamily = FontFamily.SansSerif
    val Mono: FontFamily = FontFamily.Monospace

    fun ui(size: Float, weight: FontWeight = FontWeight.Normal, color: Color = Tv.Ink, tracking: Float = 0f) =
        TextStyle(fontFamily = Ui, fontSize = size.sp, fontWeight = weight, color = color, letterSpacing = tracking.em)

    fun display(size: Float, weight: FontWeight = FontWeight.SemiBold, tracking: Float = -0.02f) =
        TextStyle(fontFamily = Display, fontSize = size.sp, fontWeight = weight, color = Tv.Ink, letterSpacing = tracking.em, lineHeight = (size * 1.08f).sp)

    fun mono(size: Float, weight: FontWeight = FontWeight.Medium, color: Color = Tv.Ink) =
        TextStyle(fontFamily = Mono, fontSize = size.sp, fontWeight = weight, color = color)

    /** .ttl: the huge profile name on Connect and Profiles. */
    fun title(size: Float) = TextStyle(
        fontFamily = Display,
        fontSize = size.sp,
        fontWeight = FontWeight.Medium,
        color = Tv.Ink,
        letterSpacing = (-0.035f).em,
        lineHeight = (size * 0.98f).sp,
        shadow = Shadow(Color.Black.copy(alpha = 0.35f), Offset(0f, 2f), 40f),
    )

    /** Small labels over a group or a panel, such as "Quick connect": quiet, in sentence case. */
    fun eyebrow(size: Float, alpha: Float, tracking: Float = 0.01f) =
        TextStyle(fontFamily = Ui, fontSize = size.sp, fontWeight = FontWeight.Medium, color = Tv.soft(alpha), letterSpacing = tracking.em)
}

/**
 * Measures the layout in the design's units: 1280 × 720 of them fill the screen, and inside,
 * 1.dp is one unit. They are units, not pixels. Text, icons and the tunnel are drawn at the
 * window's own resolution: 1.5 pixels a unit in the 1920 × 1080 window Android TV gives every
 * app, even on a 4K screen, and 3 a unit in a 4K window. Nothing is drawn small and scaled up.
 */
@Composable
fun TvStage(content: @Composable BoxScope.() -> Unit) {
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize().background(Tv.Page),
        contentAlignment = Alignment.Center,
    ) {
        val unit = min(constraints.maxWidth / 1280f, constraints.maxHeight / 720f)
        CompositionLocalProvider(LocalDensity provides Density(unit, 1f)) {
            Box(modifier = Modifier.size(1280.dp, 720.dp).clipToBounds(), content = content)
        }
    }
}

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

    fun tone(mode: Mode): Color = when (mode) {
        Mode.On -> Mint
        Mode.Busy -> Blue
        Mode.Off, Mode.Paused -> Amber
        Mode.Error -> Red
    }
}

/**
 * Type from the design. Sizes are the design's pixels on its 1280 × 720 canvas; [TvStage]
 * scales them with everything else. The design's Bricolage Grotesque and Manrope are not
 * bundled yet, so the system sans stands in.
 */
object TvType {
    val Display: FontFamily = FontFamily.SansSerif
    val Ui: FontFamily = FontFamily.SansSerif
    val Mono: FontFamily = FontFamily.Monospace

    fun ui(size: Float, weight: FontWeight = FontWeight.Normal, color: Color = Tv.Ink, tracking: Float = 0f) =
        TextStyle(fontFamily = Ui, fontSize = size.sp, fontWeight = weight, color = color, letterSpacing = tracking.em)

    fun display(size: Float, weight: FontWeight = FontWeight.Bold, tracking: Float = -0.025f) =
        TextStyle(fontFamily = Display, fontSize = size.sp, fontWeight = weight, color = Tv.Ink, letterSpacing = tracking.em, lineHeight = (size * 1.08f).sp)

    fun mono(size: Float, weight: FontWeight = FontWeight.Medium, color: Color = Tv.Ink) =
        TextStyle(fontFamily = Mono, fontSize = size.sp, fontWeight = weight, color = color)

    /** .ttl: the huge profile name on Connect and Profiles. */
    fun title(size: Float) = TextStyle(
        fontFamily = Display,
        fontSize = size.sp,
        fontWeight = FontWeight.Bold,
        color = Tv.Ink,
        letterSpacing = (-0.04f).em,
        lineHeight = (size * 0.98f).sp,
        shadow = Shadow(Color.Black.copy(alpha = 0.35f), Offset(0f, 2f), 40f),
    )

    /** Uppercase labels such as .label, .sect and .p-eyebrow. */
    fun eyebrow(size: Float, alpha: Float, tracking: Float = 0.14f) =
        TextStyle(fontFamily = Ui, fontSize = size.sp, fontWeight = FontWeight.ExtraBold, color = Tv.soft(alpha), letterSpacing = tracking.em)
}

/**
 * Lays the app out on the design's 1280 × 720 canvas and scales it to the screen, so one
 * design renders sharp at 4K (3 pixels per design pixel) and shrinks cleanly to 1080p and
 * 720p. Inside, 1.dp is one design pixel.
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

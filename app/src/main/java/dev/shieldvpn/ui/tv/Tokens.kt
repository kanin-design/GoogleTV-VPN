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

    /** The shade over the screen behind every sheet, one strength for all of them. */
    val Scrim = Color(0xFF020407).copy(alpha = 0.58f)

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
 * Corners: [Large] for panels and sheets, [Medium] for cards on the screen itself (Quick connect),
 * and [Small] for everything inside a panel or a sheet: rows, fields, boxes, codes, icons and keys.
 * A corner inside another is smaller than the outer one, by about the gap between them. Buttons,
 * tabs, chips and switches are pills.
 */
object TvRadius {
    const val Small = 12f
    const val Medium = 16f
    const val Large = 24f
}

/**
 * Sizes, in design units (0.75 dp each, so 52 is Google's 40 dp TV button). Spacing is on a grid
 * of 4: 4, 8, 12, 16, 24, 32, and 64 for the screen's sides. Only a title and its subtitle sit
 * closer, 2 apart.
 */
object TvSize {
    /** The buttons on a screen and in a sheet. */
    const val Button = 52f

    /**
     * The one small size, for what sits inside a panel, a row or the bar: small buttons, the tabs
     * and the Settings categories.
     */
    const val Small = 40f
    const val ButtonSmall = Small
    const val Chip = 32f
    const val Icon = 40f

    /** A row of words, and a row with an icon or a profile's picture. */
    const val Row = 60f
    const val RowTall = 72f

    /** From a panel's side to its words; the top and bottom take [PanelInsetY]. */
    const val PanelInset = 32f
    const val PanelInsetY = 24f

    /**
     * The box every panel fills, under the tabs: from 124 to 36 above the screen's bottom, 560
     * high. Sheets go in it too, centred; one with a keyboard, taller, is centred on the screen.
     */
    const val PanelTop = 124f
    const val PanelBottom = 36f
    const val PanelHeight = 560f

    /** Panel widths: the Settings categories, and a list beside the panel it opens. */
    const val NavWidth = 320f
    const val ListWidth = 440f

    /**
     * Sheet widths: small (a menu, the PIN), side (Details, at the screen's right), medium (a
     * file, a question, a phone's code), large (the speed test, a form) and a form with the
     * phone's code beside it.
     */
    const val SheetSmall = 460f
    const val SheetSide = 540f
    const val SheetMedium = 760f
    const val SheetLarge = 820f
    const val SheetForm = 1100f
}

/**
 * Focus: what has it turns white and grows, by how wide it is. Buttons, tabs and cards grow by
 * [Grow], the Settings categories by [GrowNav], and rows across a panel by [GrowRow], which keeps
 * them inside their 16 inset. Each takes [Ms] with [FocusEase]; not the 300 ms Google suggests,
 * so focus keeps up with a held arrow key.
 */
object TvFocus {
    const val Grow = 1.06f
    const val GrowNav = 1.03f
    const val GrowRow = 1.02f
    const val Ms = 240
}

/**
 * Type from the design. Sizes are the design's pixels on its 1280 × 720 canvas; [TvStage]
 * scales them with everything else. One scale: 15 for notes and labels (the smallest, about
 * 11 dp), 17 for rows and reading, 19 for buttons and the live line, 21 for a lede, [Heading] for
 * the app's name and a narrow column's title, 32 for a panel's title, and the big names below.
 * Monospace goes at 17 for data, [MonoUrl] for an address and [MonoCode] for a code to type.
 * Android's own sans (Roboto) and monospace, in three weights: Normal for reading, Medium for
 * quiet labels and big names, SemiBold for what is pressed or titled. Focus never changes a
 * weight, so nothing shifts as it moves.
 */
object TvType {
    const val Heading = 24f
    const val MonoUrl = 20f
    const val MonoCode = 24f

    /** A profile's name on Connect. */
    const val Hero = 104f

    /** A long name on Connect, a name on Profiles, and the first run's title. */
    const val HeroCompact = 88f

    /** The speed test's number. */
    const val Gauge = 58f

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

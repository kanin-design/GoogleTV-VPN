package dev.shieldvpn.ui.tv

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.shieldvpn.vpn.Connection

/*
 * A profile's look (tunnel-tv-app.html, "profile look"): an icon on two colours, like a Google TV
 * profile avatar. It comes from words in the profile's name until the user picks one.
 */

/** Material Symbols paths, the same icons Google TV uses. Listed in the picker's order. */
enum class LookIcon(val label: String, val path: String) {
    Home("House", "M10 20v-6h4v6h5v-8h3L12 3 2 12h3v8z"),
    Work("Office", "M20 6h-4V4c0-1.11-.89-2-2-2h-4c-1.11 0-2 .89-2 2v2H4c-1.11 0-1.99.89-1.99 2L2 19c0 1.11.89 2 2 2h16c1.11 0 2-.89 2-2V8c0-1.11-.89-2-2-2zm-6 0h-4V4h4v2z"),
    Building("Building", "M12 7V3H2v18h20V7H12zM6 19H4v-2h2v2zm0-4H4v-2h2v2zm0-4H4V9h2v2zm0-4H4V5h2v2zm4 12H8v-2h2v2zm0-4H8v-2h2v2zm0-4H8V9h2v2zm0-4H8V5h2v2zm10 12h-8v-2h2v-2h-2v-2h2v-2h-2V9h8v10zm-2-8h-2v2h2v-2zm0 4h-2v2h2v-2z"),
    School("School", "M5 13.18v4L12 21l7-3.82v-4L12 17l-7-3.82zM12 3L1 9l11 6 9-4.91V17h2V9L12 3z"),
    Lab("Lab", "M19.8 18.4L14 10.67V6.5l1.35-1.69c.26-.33.03-.81-.39-.81H9.04c-.42 0-.65.48-.39.81L10 6.5v4.17L4.2 18.4c-.49.66-.02 1.6.8 1.6h14c.82 0 1.29-.94.8-1.6z"),
    Outdoors("Outdoors", "M17 12h2L12 2 5.05 12H7l-3.9 6h6.92v4h3.95v-4H21z"),
    Server("Server", "M20 13H4c-.55 0-1 .45-1 1v6c0 .55.45 1 1 1h16c.55 0 1-.45 1-1v-6c0-.55-.45-1-1-1zM7 19c-1.1 0-2-.9-2-2s.9-2 2-2 2 .9 2 2-.9 2-2 2zM20 3H4c-.55 0-1 .45-1 1v6c0 .55.45 1 1 1h16c.55 0 1-.45 1-1V4c0-.55-.45-1-1-1zM7 9c-1.1 0-2-.9-2-2s.9-2 2-2 2 .9 2 2-.9 2-2 2z"),
    Tv("TV", "M21 6h-7.59l3.29-3.29L16 2l-4 4-4-4-.71.71L10.59 6H3c-1.1 0-2 .89-2 2v12c0 1.1.9 2 2 2h18c1.1 0 2-.9 2-2V8c0-1.11-.9-2-2-2zm0 14H3V8h18v12zM9 10v8l7-4z"),
    Games("Games", "M21 6H3c-1.1 0-2 .9-2 2v8c0 1.1.9 2 2 2h18c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2zm-10 7H8v3H6v-3H3v-2h3V8h2v3h3v2zm4.5 2c-.83 0-1.5-.67-1.5-1.5s.67-1.5 1.5-1.5 1.5.67 1.5 1.5-.67 1.5-1.5 1.5zm4-3c-.83 0-1.5-.67-1.5-1.5S18.67 9 19.5 9s1.5.67 1.5 1.5-.67 1.5-1.5 1.5z"),
    Travel("Travel", "M21 16v-2l-8-5V3.5c0-.83-.67-1.5-1.5-1.5S10 2.67 10 3.5V9l-8 5v2l8-2.5V19l-2 1.5V22l3.5-1 3.5 1v-1.5L13 19v-5.5l8 2.5z"),
    World("World", "M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-1 17.93c-3.95-.49-7-3.85-7-7.93 0-.62.08-1.21.21-1.79L9 15v1c0 1.1.9 2 2 2v1.93zm6.9-2.54c-.26-.81-1-1.39-1.9-1.39h-1v-3c0-.55-.45-1-1-1H8v-2h2c.55 0 1-.45 1-1V7h2c1.1 0 2-.9 2-2v-.41c2.93 1.19 5 4.06 5 7.41 0 2.08-.8 3.97-2.1 5.39z"),
    Favourite("Favourite", "M12 21.35l-1.45-1.32C5.4 15.36 2 12.28 2 8.5 2 5.42 4.42 3 7.5 3c1.74 0 3.41.81 4.5 2.09C13.09 3.81 14.76 3 16.5 3 19.58 3 22 5.42 22 8.5c0 3.78-3.4 6.86-8.55 11.54L12 21.35z"),
}

/** The apps grid, for the All profiles card. */
const val AppsPath = "M4 8h4V4H4v4zm6 12h4v-4h-4v4zm-6 0h4v-4H4v4zm0-6h4v-4H4v4zm6 0h4v-4h-4v4zm6-10v4h4V4h-4zm-6 4h4V4h-4v4zm6 6h4v-4h-4v4zm0 6h4v-4h-4v4z"

/** The two colours of each swatch, top-left to bottom-right. */
val Swatches: List<Pair<Color, Color>> = listOf(
    Color(0xFFF7924F) to Color(0xFFD23F6B),
    Color(0xFFFF8C6B) to Color(0xFFC23A7A),
    Color(0xFFFF7D8C) to Color(0xFFB444D6),
    Color(0xFFA877FF) to Color(0xFFD9479F),
    Color(0xFF8C7DFF) to Color(0xFF4757D1),
    Color(0xFF4D9BFF) to Color(0xFF5847E0),
    Color(0xFF2EC7B4) to Color(0xFF2272C6),
    Color(0xFF4FCB86) to Color(0xFF16806E),
    Color(0xFFF5BE45) to Color(0xFFE5762B),
)

/** The All profiles card's quiet colours. */
val AllProfilesColours = Color(0xFF3A4658) to Color(0xFF1A212C)

data class ProfileLook(val icon: LookIcon, val colour: Int, val chosen: Boolean) {
    val colours: Pair<Color, Color> get() = Swatches[colour]
}

private val ByName = listOf(
    Regex("home|house|hjem", RegexOption.IGNORE_CASE) to (LookIcon.Home to 0),
    Regex("office|work|jobb", RegexOption.IGNORE_CASE) to (LookIcon.Work to 5),
    Regex("lab|test|dev", RegexOption.IGNORE_CASE) to (LookIcon.Lab to 3),
    Regex("cabin|hytte|cottage", RegexOption.IGNORE_CASE) to (LookIcon.Outdoors to 7),
    Regex("seed|server|nas|box", RegexOption.IGNORE_CASE) to (LookIcon.Server to 8),
    Regex("travel|trip|router|hotel", RegexOption.IGNORE_CASE) to (LookIcon.Travel to 2),
)

/** Colours for names that match no word above. */
private val OtherColours = intArrayOf(1, 4, 6)

fun lookOf(c: Connection): ProfileLook {
    val icon = c.lookIcon?.let { name -> LookIcon.entries.firstOrNull { it.name == name } }
    val colour = c.lookColour
    if (icon != null && colour != null && colour in Swatches.indices) return ProfileLook(icon, colour, chosen = true)
    ByName.firstOrNull { it.first.containsMatchIn(c.name) }?.let { (_, look) -> return ProfileLook(look.first, look.second, chosen = false) }
    var h = 0L
    for (ch in c.name) h = (h * 31 + ch.code) and 0xFFFFFFFFL
    return ProfileLook(LookIcon.World, OtherColours[(h % OtherColours.size).toInt()], chosen = false)
}

/** CSS linear-gradient(135deg, a, b) over a box of [size]. */
fun gradient135(colours: Pair<Color, Color>, size: Size): Brush {
    // The gradient line runs corner to corner at 45 degrees, so it reaches a quarter of w + h each way.
    val d = (size.width + size.height) / 4
    val c = Offset(size.width / 2, size.height / 2)
    return Brush.linearGradient(listOf(colours.first, colours.second), Offset(c.x - d, c.y - d), Offset(c.x + d, c.y + d))
}

fun Modifier.lookFill(colours: Pair<Color, Color>): Modifier = drawBehind { drawRect(gradient135(colours, size)) }

private val filled = HashMap<String, ImageVector>()

private fun filledVector(path: String): ImageVector = filled.getOrPut(path) {
    ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .apply { addPath(pathData = addPathNodes(path), fill = SolidColor(Color.White)) }
        .build()
}

/** A filled Material icon at [size] design pixels. */
@Composable
fun MsIcon(path: String, size: Float, color: Color = Color.White, modifier: Modifier = Modifier) {
    VectorIcon(filledVector(path), color, modifier.size(size.dp))
}

/** .av: the profile's icon on its colours. */
@Composable
fun Avatar(look: ProfileLook, size: Float, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.size(size.dp).clip(RoundedCornerShape(12.dp)).lookFill(look.colours),
        contentAlignment = Alignment.Center,
    ) { MsIcon(look.icon.path, size * 0.56f) }
}

/**
 * .card: a profile's colours with its icon and name. Focused, it grows and gets a white ring.
 * Without [onClick] it is a still preview.
 */
@Composable
fun LookCard(
    colours: Pair<Color, Color>,
    iconPath: String,
    iconSize: Float,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onFocus: () -> Unit = {},
    onBlur: () -> Unit = {},
    focusRequester: FocusRequester? = null,
) {
    val f = rememberFocusState()
    val scale by animateFloatAsState(if (f.focused) 1.09f else 1f, tween(200, easing = FocusEase), label = "card")
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier = modifier
            .aspectRatio(16f / 9f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .shadow(if (f.focused) 27.dp else 15.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(shape)
            .drawBehind {
                drawRect(gradient135(colours, size))
                // .card::before: a soft light from the top-left (an ellipse 110% wide, 90% high)
                // and a darker bottom for the name.
                val ry = size.height * 0.9f
                val sx = size.width * 1.1f / ry
                scale(sx, 1f, pivot = Offset.Zero) {
                    drawRect(
                        Brush.radialGradient(0f to Color.White.copy(alpha = 0.2f), 0.55f to Color.White.copy(alpha = 0f), center = Offset.Zero, radius = ry),
                        size = Size(size.width / sx, size.height),
                    )
                }
                drawRect(Brush.verticalGradient(0.4f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.3f)))
            }
            .then(if (f.focused) Modifier.border(3.dp, Tv.Focus, shape) else Modifier)
            .then(if (onClick != null) Modifier.onFocusChanged { if (!it.isFocused) onBlur() }.tvFocus(f, onClick, focusRequester, onFocus) else Modifier),
    ) {
        MsIcon(iconPath, iconSize, modifier = Modifier.padding(14.dp))
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(start = 14.dp, end = 14.dp, top = 26.dp, bottom = 12.dp),
        ) {
            Text(title, style = TvType.ui(16f, FontWeight.Bold, Color.White), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = TvType.ui(13f, color = Color.White.copy(alpha = 0.74f)), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

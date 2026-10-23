package dev.shieldvpn.ui.tv

import androidx.compose.foundation.Image
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.isUnspecified
import androidx.compose.ui.unit.sp

/*
 * Text and icons on Compose's foundation alone. Every style in [TvType] sets its colour, so
 * nothing here needs Material's theme, and the build leaves the Material library out.
 */

/** The line height the layout was designed with: Material's body text gave every style without its own 24sp. */
private fun TextStyle.withLineHeight(): TextStyle = if (lineHeight.isUnspecified) copy(lineHeight = 24.sp) else this

@Composable
fun Text(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = TvType.ui(16f),
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    BasicText(text, modifier, style.withLineHeight(), overflow = overflow, maxLines = maxLines)
}

@Composable
fun Text(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    style: TextStyle = TvType.ui(16f),
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    BasicText(text, modifier, style.withLineHeight(), overflow = overflow, maxLines = maxLines)
}

/** A vector icon drawn in one colour. */
@Composable
fun VectorIcon(image: ImageVector, tint: Color, modifier: Modifier = Modifier) {
    Image(rememberVectorPainter(image), contentDescription = null, modifier = modifier, colorFilter = ColorFilter.tint(tint))
}

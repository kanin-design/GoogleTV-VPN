package render

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import java.io.File

/** Android TV's own fonts, which shoot.sh downloads: Roboto for sans-serif, Droid Sans Mono for monospace. */
object Fonts {
    private val dir = File(System.getProperty("render.fonts") ?: "fonts")
    val Sans: FontFamily = FontFamily(listOf(300, 400, 500, 600, 700, 800, 900).map { w -> Font(File(dir, "Roboto-$w.ttf"), FontWeight(w)) })
    val Mono: FontFamily = FontFamily(Font(File(dir, "DroidSansMono.ttf"), FontWeight.Normal))
}

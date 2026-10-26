package dev.shieldvpn.ui.tv

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder

private val QrInk = Color(0xFF0A0E13)
private val QrPaper = Color(0xFFF2F5F8)

/** The modules of a QR code for [text], true where dark. High error correction, so the logo can sit on top. */
private class QrMatrix(text: String) {
    val size: Int
    private val dark: BooleanArray

    init {
        val code = Encoder.encode(text, ErrorCorrectionLevel.H, mapOf(EncodeHintType.CHARACTER_SET to "UTF-8"))
        val m = code.matrix
        size = m.width
        dark = BooleanArray(size * size) { m.get(it % size, it / size).toInt() == 1 }
    }

    operator fun get(x: Int, y: Int): Boolean = x in 0 until size && y in 0 until size && dark[y * size + x]

    /** The three big squares in the corners, drawn separately as rounded eyes. */
    fun inFinder(x: Int, y: Int): Boolean {
        fun near(ox: Int, oy: Int) = x in ox until ox + 7 && y in oy until oy + 7
        return near(0, 0) || near(size - 7, 0) || near(0, size - 7)
    }
}

/**
 * A QR code on a light card: rounded dots, rounded corner eyes and the app's mark in the
 * middle. [size] is in design pixels and includes the quiet zone.
 */
@Composable
fun QrCode(text: String, size: Float, modifier: Modifier = Modifier) {
    val matrix = remember(text) { QrMatrix(text) }
    val logo = remember { Ic.Logo.paths.map { PathParser().parsePathString(it).toPath() } }
    Canvas(modifier.size(size.dp)) {
        val quiet = 2.2f
        val cell = this.size.width / (matrix.size + quiet * 2)
        val origin = cell * quiet
        drawRoundRect(QrPaper, cornerRadius = CornerRadius(cell * 3.2f))

        // The middle is left clear for the logo; high error correction covers what it hides.
        val clear = (matrix.size * 0.22f).toInt() or 1
        val c0 = (matrix.size - clear) / 2
        fun cleared(x: Int, y: Int) = x in c0 - 1 until c0 + clear + 1 && y in c0 - 1 until c0 + clear + 1

        for (y in 0 until matrix.size) for (x in 0 until matrix.size) {
            if (!matrix[x, y] || matrix.inFinder(x, y) || cleared(x, y)) continue
            val inset = cell * 0.08f
            drawRoundRect(
                QrInk,
                topLeft = Offset(origin + x * cell + inset, origin + y * cell + inset),
                size = Size(cell - inset * 2, cell - inset * 2),
                cornerRadius = CornerRadius(cell * 0.38f),
            )
        }
        eye(origin, origin, cell)
        eye(origin + (matrix.size - 7) * cell, origin, cell)
        eye(origin, origin + (matrix.size - 7) * cell, cell)

        // The app's mark on a small rounded tile.
        val tile = cell * clear
        val tl = Offset(origin + c0 * cell, origin + c0 * cell)
        drawRoundRect(QrInk, topLeft = tl, size = Size(tile, tile), cornerRadius = CornerRadius(tile * 0.28f))
        val k = tile * 0.62f / 24f
        val lo = Offset(tl.x + (tile - 24 * k) / 2, tl.y + (tile - 24 * k) / 2)
        translate(lo.x, lo.y) {
            scale(k, k, pivot = Offset.Zero) {
                logo.forEach { drawPath(it, QrPaper, style = Stroke(width = 2.4f, cap = StrokeCap.Round, join = StrokeJoin.Round)) }
            }
        }
    }
}

/** A finder pattern: a rounded ring around a rounded square. */
private fun DrawScope.eye(x: Float, y: Float, cell: Float) {
    val ring = cell
    drawRoundRect(
        QrInk,
        topLeft = Offset(x + ring / 2, y + ring / 2),
        size = Size(cell * 7 - ring, cell * 7 - ring),
        cornerRadius = CornerRadius(cell * 2f),
        style = Stroke(width = ring),
    )
    drawRoundRect(
        QrInk,
        topLeft = Offset(x + cell * 2, y + cell * 2),
        size = Size(cell * 3, cell * 3),
        cornerRadius = CornerRadius(cell * 1f),
    )
}

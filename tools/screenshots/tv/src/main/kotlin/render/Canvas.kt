package render

import androidx.compose.ui.graphics.nativeCanvas as skiaCanvas
import org.jetbrains.skia.BlendMode
import org.jetbrains.skia.PaintMode
import org.jetbrains.skia.PaintStrokeCap

/** The parts of android.graphics.Paint the background uses, drawn with Skia. */
class Paint {
    enum class Cap { BUTT, ROUND, SQUARE }

    val skia = org.jetbrains.skia.Paint().apply { mode = PaintMode.STROKE }
    var isAntiAlias: Boolean
        get() = skia.isAntiAlias
        set(v) {
            skia.isAntiAlias = v
        }
    var strokeWidth: Float
        get() = skia.strokeWidth
        set(v) {
            skia.strokeWidth = v
        }
    var color: Int
        get() = skia.color
        set(v) {
            skia.color = v
        }
    var strokeCap: Cap = Cap.BUTT
        set(v) {
            field = v
            skia.strokeCap = when (v) {
                Cap.BUTT -> PaintStrokeCap.BUTT
                Cap.ROUND -> PaintStrokeCap.ROUND
                Cap.SQUARE -> PaintStrokeCap.SQUARE
            }
        }
    var xfermode: PorterDuffXfermode? = null
        set(v) {
            field = v
            skia.blendMode = if (v?.mode == PorterDuff.Mode.ADD) BlendMode.PLUS else BlendMode.SRC_OVER
        }
}

object PorterDuff {
    enum class Mode { ADD, SRC_OVER }
}

class PorterDuffXfermode(val mode: PorterDuff.Mode)

/** Android's animator settings: animations are on. */
object ValueAnimator {
    fun areAnimatorsEnabled() = true
}

/** android.graphics.Canvas's drawPoints and drawLines, over Skia's canvas. */
class NativeCanvas(private val canvas: org.jetbrains.skia.Canvas) {
    fun drawPoints(pts: FloatArray, offset: Int, count: Int, paint: Paint) {
        if (count > 0) canvas.drawPoints(pts.copyOfRange(offset, offset + count), paint.skia)
    }

    fun drawLines(pts: FloatArray, offset: Int, count: Int, paint: Paint) {
        if (count > 0) canvas.drawLines(pts.copyOfRange(offset, offset + count), paint.skia)
    }
}

val androidx.compose.ui.graphics.Canvas.nativeCanvas: NativeCanvas
    get() = NativeCanvas(skiaCanvas)

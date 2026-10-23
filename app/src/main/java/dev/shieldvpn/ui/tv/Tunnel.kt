package dev.shieldvpn.ui.tv

import android.animation.ValueAnimator
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/*
 * The tunnel behind every screen (tunnel-tv-app.html, makeTunnel). The name made literal: you
 * look down a lit tunnel, and its light is the connection. Green and drifting while connected,
 * amber and quicker while connecting, dim red and still when stopped.
 *
 * It keeps no pictures. Every arch is the same shape at another depth, so each one is a circle
 * and two rectangles, which the GPU draws directly. It draws 30 times a second while it is on
 * screen and not at all when the app is hidden.
 */

private const val N = 18
private const val SP = 0.75f
private const val ZMAX = N * SP
private const val VX = 905f
private const val VY = 356f
private const val U = 250f
private const val DUST = 120

/** One step of 8-bit colour: anything fainter is invisible when added to the picture. */
private const val MIN_ALPHA = 1.5f / 255f

/** The light for a state: its colour, brightness and how fast the arches drift. */
private class Light(r: Int, g: Int, b: Int, val light: Float, val speed: Float) {
    val tone = floatArrayOf(r.toFloat(), g.toFloat(), b.toFloat())
}

private val OnLight = Light(96, 240, 190, 1f, 0.32f)
private val BusyLight = Light(255, 196, 72, 1f, 0.8f)
private val OffLight = Light(255, 78, 66, 0.42f, 0f)

private fun lightFor(mode: Mode): Light = when (mode) {
    Mode.On -> OnLight
    Mode.Busy -> BusyLight
    Mode.Off, Mode.Paused, Mode.Error -> OffLight
}

/** The tunnel, filling [modifier]'s bounds as the 1280 × 720 design canvas. */
@Composable
fun TunnelBackdrop(mode: Mode, modifier: Modifier = Modifier, covered: Boolean = false) {
    val scene = remember { TunnelScene() }
    val current by rememberUpdatedState(mode)
    val hidden by rememberUpdatedState(covered)
    var frame by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        // With animations off in Android's settings, the arches stand still and only the colour changes.
        val moving = ValueAnimator.areAnimatorsEnabled()
        var last = withFrameNanosCompat()
        while (true) {
            // 30 frames a second is plenty for a slow drift, and halves the drawing.
            delay(20)
            val now = withFrameNanosCompat()
            // Behind panels and sheets, where little of it shows, the arches hold still. Every
            // frame here also redraws the panels on top, so this is most of the app's work there.
            val still = !moving || hidden
            scene.step(current, min(0.05f, (now - last) / 1_000_000_000f), moving = !still)
            last = now
            frame++
            if (still && scene.settled()) {
                // Nothing changes until the state does, or the tunnel shows again.
                val settledOn = current
                snapshotFlow { current to hidden }.first { (m, h) -> m != settledOn || (moving && !h) }
                last = withFrameNanosCompat()
            }
        }
    }
    // Its own layer, so a new frame redraws only the tunnel, not the screens over it.
    Canvas(modifier.graphicsLayer()) {
        frame
        scene.draw(this)
    }
}

private suspend fun withFrameNanosCompat(): Long = androidx.compose.runtime.withFrameNanos { it }

private class TunnelScene {
    private val arch = FloatArray(N) { 0.5f + it * SP }
    private val dustX = FloatArray(DUST)
    private val dustY = FloatArray(DUST)
    private val dustZ = FloatArray(DUST)
    private val dustS = FloatArray(DUST)
    private val tone = OnLight.tone.copyOf()
    private var light = 1f
    private var speed = OnLight.speed
    private var mode = Mode.On
    private var t = 0f

    init {
        val r = rng(99)
        for (i in 0 until DUST) {
            dustX[i] = (r() * 2 - 1) * 1.05f
            dustY[i] = -1.15f + r() * 2.05f
            dustZ[i] = 0.4f + r() * ZMAX
            dustS[i] = 0.6f + r() * 1.2f
        }
    }

    fun step(next: Mode, dt: Float, moving: Boolean) {
        mode = next
        val target = lightFor(next)
        val k = 1 - exp(-dt * 2.5f)
        for (i in 0..2) tone[i] += (target.tone[i] - tone[i]) * k
        light += (target.light - light) * k
        speed += (target.speed - speed) * (1 - exp(-dt * 1.6f))
        if (!moving) return
        t += dt
        for (i in 0 until N) {
            arch[i] -= speed * dt
            if (arch[i] < 0.42f) arch[i] += ZMAX
        }
        for (i in 0 until DUST) {
            dustZ[i] -= speed * dt * 1.1f
            if (dustZ[i] < 0.35f) dustZ[i] += ZMAX
        }
    }

    /** The colour has reached its target. */
    fun settled(): Boolean {
        val target = lightFor(mode)
        return (0..2).all { kotlin.math.abs(tone[it] - target.tone[it]) < 0.5f } && kotlin.math.abs(light - target.light) < 0.005f
    }

    // The gradients depend only on the colour and brightness, which are steady while the state is.
    // They are made again only when those change, so a steady tunnel creates no shaders per frame.
    private var brushKey = Long.MIN_VALUE
    private lateinit var wideGlow: Brush
    private lateinit var nearGlow: Brush
    private lateinit var floor: Brush
    private lateinit var rayLeft: Brush
    private lateinit var rayRight: Brush
    private lateinit var coreGlow: Brush
    private lateinit var whiteGlow: Brush
    private val vignette = Brush.radialGradient(
        0f to Color.Transparent,
        300f / 820f to Color.Transparent,
        1f to Color.Black.copy(alpha = 0.55f),
        center = Offset(640f, 360f),
        radius = 820f,
    )

    /** Strokes by width in quarter pixels; there are only a few hundred widths. */
    private val strokes = HashMap<Int, Stroke>()

    private fun stroke(w: Float): Stroke = strokes.getOrPut((w * 4).toInt()) { Stroke(((w * 4).toInt() + 0.5f) / 4) }

    private fun brushes(toneC: Color, core: Color, l: Float) {
        // 1/512 steps of brightness and whole steps of colour look the same as exact values.
        val key = ((l * 512).toLong() shl 27) or (tone[0].toLong() shl 18) or (tone[1].toLong() shl 9) or tone[2].toLong()
        if (key == brushKey) return
        brushKey = key
        wideGlow = radial(VX, VY, 640f, toneC, 0.2f * l)
        nearGlow = radial(VX, VY, 260f, toneC, 0.32f * l)
        floor = Brush.verticalGradient(
            0f to toneC.copy(alpha = 0f),
            0.04f to toneC.copy(alpha = 0.05f * l),
            0.3f to Color(4, 7, 10).copy(alpha = 0.15f),
            1f to Color(2, 3, 5).copy(alpha = 0.9f),
            startY = VY - 6,
            endY = 720f,
        )
        rayLeft = Brush.linearGradient(listOf(toneC.copy(alpha = 0.22f * l), toneC.copy(alpha = 0f)), Offset(VX, VY), rayEnd(-1))
        rayRight = Brush.linearGradient(listOf(toneC.copy(alpha = 0.22f * l), toneC.copy(alpha = 0f)), Offset(VX, VY), rayEnd(1))
        coreGlow = radial(VX, VY - 4, 90f, core, 0.55f * l)
        whiteGlow = radial(VX, VY - 4, 26f, Color.White, 0.85f * l)
    }

    private fun rayEnd(side: Int) = Offset(VX + side * 1.15f * 3 * U, VY + 0.95f * 3 * U)

    private fun radial(x: Float, y: Float, r: Float, c: Color, a: Float): Brush =
        Brush.radialGradient(listOf(c.copy(alpha = a.coerceIn(0f, 1f)), c.copy(alpha = 0f)), Offset(x, y), r)

    fun draw(scope: DrawScope) = with(scope) {
        val unit = size.width / 1280f
        scale(unit, unit, pivot = Offset.Zero) {
            val flick = when (lightFor(mode)) {
                OffLight -> 0.88f + 0.12f * sin(t * 11) * sin(t * 4.3f)
                BusyLight -> 0.84f + 0.16f * sin(t * 2.2f)
                else -> 1f
            }
            val l = light * flick
            val toneC = Color(tone[0] / 255f, tone[1] / 255f, tone[2] / 255f)
            val core = Color(lerp(tone[0], 255f, 0.45f) / 255f, lerp(tone[1], 255f, 0.45f) / 255f, lerp(tone[2], 255f, 0.45f) / 255f)

            brushes(toneC, core, l)
            drawRect(Color(0xFF030508))
            drawCircle(wideGlow, radius = 640f, center = Offset(VX, VY))
            drawCircle(nearGlow, radius = 260f, center = Offset(VX, VY))
            drawRect(floor, topLeft = Offset(0f, VY - 6), size = Size(1280f, 726f - VY))

            for (z in arch) {
                val zn = z / ZMAX
                val alpha = smooth(0.42f, 1.25f, z) * (1 - smooth(0.72f, 1f, zn)) * (0.22f + 0.78f * zn.pow(0.75f)) * l
                if (alpha < 0.005f) continue
                val s = 1 / z
                val lw = (0.012f * s * U).coerceIn(0.6f, 7f)
                archStrokes(s, lw, mirror = true, toneC, core, alpha * 0.2f)
                archStrokes(s, lw, mirror = false, toneC, core, alpha)
            }
            drawLine(rayLeft, Offset(VX, VY), rayEnd(-1), strokeWidth = 1.2f, blendMode = BlendMode.Plus)
            drawLine(rayRight, Offset(VX, VY), rayEnd(1), strokeWidth = 1.2f, blendMode = BlendMode.Plus)
            for (i in 0 until DUST) {
                val z = dustZ[i]
                val s = 1 / z
                val a = smooth(0.35f, 1.2f, z) * (1 - smooth(0.7f, 1f, z / ZMAX)) * 0.55f * l
                if (a < MIN_ALPHA) continue
                val px = (dustS[i] * s * 1.3f).coerceIn(0.5f, 4f)
                drawRect(
                    core.copy(alpha = a),
                    topLeft = Offset(VX + dustX[i] * s * U - px / 2, VY + dustY[i] * s * U - px / 2),
                    size = Size(px, px),
                    blendMode = BlendMode.Plus,
                )
            }
            drawCircle(coreGlow, radius = 90f, center = Offset(VX, VY - 4), blendMode = BlendMode.Plus)
            drawCircle(whiteGlow, radius = 26f, center = Offset(VX, VY - 4), blendMode = BlendMode.Plus)
            drawRect(vignette)
        }
    }

    /**
     * One arch at depth 1/[s]: its walls, then the half circle on top. The mirror is its
     * reflection in the floor, upside down and fainter. Three strokes each: a wide soft glow,
     * a narrower one, and a bright core line.
     */
    private fun DrawScope.archStrokes(s: Float, lw: Float, mirror: Boolean, toneC: Color, core: Color, alpha: Float) {
        val half = 1.15f * s * U
        val floorY = VY + 0.95f * s * U
        val wall = s * U
        val cy = if (mirror) floorY + wall else floorY - wall
        val wallTop = if (mirror) floorY else floorY - wall
        // Strokes fainter than one step of colour add nothing on screen, so they are not drawn.
        // That is most of the reflection's wide glow, and a good share of the draw calls.
        // The strokes get brighter from 0 to 2, so the first visible one starts the list.
        var first = 0
        while (first <= 2 && strokeColor(first, toneC, core, alpha).alpha < MIN_ALPHA) first++
        if (first > 2) return
        for (i in first..2) {
            val w = strokeWidth(i, lw)
            val c = strokeColor(i, toneC, core, alpha)
            drawRect(c, topLeft = Offset(VX - half - w / 2, wallTop), size = Size(w, wall), blendMode = BlendMode.Plus)
            drawRect(c, topLeft = Offset(VX + half - w / 2, wallTop), size = Size(w, wall), blendMode = BlendMode.Plus)
        }
        // The half circle on the walls, as an arc: the same picture as a circle cut in half by a
        // clip, without the clip's save and restore for every stroke.
        for (i in first..2) {
            drawArc(
                strokeColor(i, toneC, core, alpha),
                startAngle = if (mirror) 0f else 180f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = Offset(VX - half, cy - half),
                size = Size(half * 2, half * 2),
                style = stroke(strokeWidth(i, lw)),
                blendMode = BlendMode.Plus,
            )
        }
    }

    private fun strokeWidth(i: Int, lw: Float): Float = when (i) {
        0 -> lw * 7
        1 -> lw * 2.6f
        else -> lw
    }

    private fun strokeColor(i: Int, toneC: Color, core: Color, alpha: Float): Color = when (i) {
        0 -> toneC.copy(alpha = (0.045f * alpha).coerceAtMost(1f))
        1 -> toneC.copy(alpha = (0.16f * alpha).coerceAtMost(1f))
        else -> core.copy(alpha = (0.85f * alpha).coerceAtMost(1f))
    }

}

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

private fun smooth(a: Float, b: Float, x: Float): Float {
    val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
    return t * t * (3 - 2 * t)
}

/** The design's xorshift generator, so the dust lands where it does in the prototype. */
private fun rng(seed: Int): () -> Float {
    var s = (seed.toLong() * 2654435761L) and 0xFFFFFFFFL
    if (s == 0L) s = 1
    return {
        s = s xor ((s shl 13) and 0xFFFFFFFFL)
        s = s xor (s ushr 17)
        s = s xor ((s shl 5) and 0xFFFFFFFFL)
        s / 4294967296f
    }
}

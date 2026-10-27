package dev.shieldvpn.ui.tv

import android.animation.ValueAnimator
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.first
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * The background behind every screen, which is also the status light: a network of nodes
 * drifting in a slow field, with three named stops, this device, the VPN server and the internet.
 *
 * - Connected (green): your traffic goes from the device up through the network to the VPN, and
 *   on from there, by its own way, to the internet.
 * - Connecting (yellow): the link to the VPN lights up hop by hop, the VPN answers, over again.
 * - Off (red): the VPN goes dark, your traffic hops through the network straight to the internet,
 *   around the VPN, and the network's links shorten, flicker and break. While the kill switch blocks
 *   traffic after a drop, that way stays dark and nothing moves on it.
 *
 * The three stops stay still; the rest of the network wanders slowly around them.
 *
 * It keeps no pictures and no offscreen buffers: lines, circles and points, drawn on the 1280 × 720
 * design canvas at the screen's own resolution. It draws 30 times a second while it is on screen,
 * holds still behind panels and sheets, and draws nothing while the app is hidden.
 */

/**
 * The background, filling [modifier]'s bounds as the 1280 × 720 design canvas. [blocked]: the
 * kill switch is holding traffic back after a drop.
 */
@Composable
fun TunnelBackdrop(mode: Mode, modifier: Modifier = Modifier, covered: Boolean = false, blocked: Boolean = false) {
    val measurer = rememberTextMeasurer()
    val scene = remember { NetworkScene(measurer) }
    val current by rememberUpdatedState(mode)
    val stopped by rememberUpdatedState(blocked)
    val hidden by rememberUpdatedState(covered)
    var frame by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        // With animations off in Android's settings, nothing moves and only the colour changes.
        val moving = ValueAnimator.areAnimatorsEnabled()
        var last = withFrameNanos { it }
        while (true) {
            // Every other frame: 30 a second at 60 Hz, plenty for a slow drift and half the
            // drawing. Counting frames, not milliseconds, spaces them evenly, so motion stays smooth.
            withFrameNanos { }
            val now = withFrameNanos { it }
            // Behind panels and sheets, where little of it shows, it holds still. Every frame here
            // also redraws the panels on top, so this is most of the app's work there.
            val still = !moving || hidden
            scene.step(current, stopped, min(0.05f, (now - last) / 1_000_000_000f), moving = !still)
            last = now
            frame++
            if (still && scene.settled()) {
                // Nothing changes until the state does, or the background shows again.
                val settledOn = current to stopped
                snapshotFlow { Triple(current, stopped, hidden) }.first { (m, b, h) -> (m to b) != settledOn || (moving && !h) }
                last = withFrameNanos { it }
            }
        }
    }
    // Its own layer, so a new frame redraws only the background, not the screens over it.
    Canvas(modifier.graphicsLayer()) {
        frame
        // Behind a panel or a sheet the names would read through it, out of place: they show on Connect.
        scene.named = !hidden
        val unit = size.width / 1280f
        scale(unit, unit, pivot = Offset.Zero) { scene.draw(this) }
    }
}

/**
 * The status light: its colour, brightness and energy (how fast and how busy it moves), eased
 * towards the state's targets so a change of state glides.
 */
private class StatusLight {
    private class Target(r: Int, g: Int, b: Int, val light: Float, val energy: Float) {
        val tone = floatArrayOf(r.toFloat(), g.toFloat(), b.toFloat())
    }

    private val on = Target(84, 236, 170, 1f, 0.35f)
    private val busy = Target(255, 196, 72, 1f, 1f)
    private val off = Target(255, 70, 58, 0.5f, 0f)

    private fun target(m: Mode) = when (m) {
        Mode.On -> on
        Mode.Busy -> busy
        Mode.Off, Mode.Paused, Mode.Error -> off
    }

    private val tone = on.tone.copyOf()
    var light = 1f
        private set

    /** 0 while stopped, about 0.35 while connected, 1 while connecting. */
    var energy = on.energy
        private set
    private var mode = Mode.On

    /** Seconds of motion so far. It stops while the background holds still. */
    var t = 0f
        private set

    fun step(next: Mode, dt: Float, moving: Boolean) {
        mode = next
        val target = target(next)
        val k = 1 - exp(-dt * 2.5f)
        for (i in 0..2) tone[i] += (target.tone[i] - tone[i]) * k
        light += (target.light - light) * k
        energy += (target.energy - energy) * (1 - exp(-dt * 1.6f))
        if (moving) t += dt
    }

    fun settled(): Boolean {
        val target = target(mode)
        return (0..2).all { abs(tone[it] - target.tone[it]) < 0.5f } && abs(light - target.light) < 0.005f
    }

    /** The state's colour at [alpha]. */
    fun tone(alpha: Float = 1f): Color = Color(tone[0] / 255f, tone[1] / 255f, tone[2] / 255f, alpha.coerceIn(0f, 1f))

    /** The colour mixed [white] of the way towards white, for hot cores of lines and dots. */
    fun core(alpha: Float = 1f, white: Float = 0.45f): Color = Color(
        (tone[0] + (255 - tone[0]) * white) / 255f,
        (tone[1] + (255 - tone[1]) * white) / 255f,
        (tone[2] + (255 - tone[2]) * white) / 255f,
        alpha.coerceIn(0f, 1f),
    )

    /** A key that changes when the colour or brightness changes visibly, for caching brushes. */
    val key: Long get() = ((light * 512).toLong() shl 27) or (tone[0].toLong() shl 18) or (tone[1].toLong() shl 9) or tone[2].toLong()
}

private class NetworkScene(private val measurer: TextMeasurer) {
    private val light = StatusLight()

    // Each node rests at (bx, by). All but the three stops drift around it on a slow loop of their own.
    // Node 0 is this device, node 1 the VPN server and node 2 the internet.
    private val bx = FloatArray(NODES)
    private val by = FloatArray(NODES)
    private val phase = FloatArray(NODES)
    private val pace = FloatArray(NODES)
    private val x = FloatArray(NODES)
    private val y = FloatArray(NODES)
    private val count: Int

    // Links between resting positions; hops from the device over them; the way to the VPN, and from the VPN on.
    private val neighbours: Array<IntArray>
    private val hops: IntArray
    private val maxHops: Int
    private val toVpn: IntArray
    private val toNet: IntArray

    /** Off: the way from the device to the internet through the network, around the VPN. */
    private val direct: IntArray

    private val dots = FloatArray(NODES * 2)
    private val paint = Paint().apply {
        isAntiAlias = true
        strokeCap = Paint.Cap.ROUND
        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
    }

    private var travel = 0f

    // 1 while traffic runs straight to the internet when off, 0 while the kill switch blocks it.
    private var flow = 1f
    private var blocked = false

    init {
        val r = seededRandom(5)
        bx[0] = DEVICE.x; by[0] = DEVICE.y
        bx[1] = VPN.x; by[1] = VPN.y
        bx[2] = NET.x; by[2] = NET.y
        var placed = ENDS
        var tries = 0
        while (placed < NODES && tries < 5000) {
            tries++
            val cx = 470f + r() * 800f
            val cy = 70f + r() * 560f
            // Fewer nodes on the left, where the text is, and none too close to another.
            if (cx < 640f && r() < 0.6f) continue
            if ((0 until placed).any { dist(cx, cy, bx[it], by[it]) < if (it < ENDS) 70f else 58f }) continue
            bx[placed] = cx
            by[placed] = cy
            placed++
        }
        count = placed
        for (i in 0 until ENDS) {
            x[i] = bx[i]
            y[i] = by[i]
        }
        for (i in 0 until count) phase[i] = r() * 6.28f
        for (i in 0 until count) pace[i] = 0.16f + r() * 0.14f
        neighbours = Array(count) { a -> (0 until count).filter { b -> b != a && dist(bx[a], by[a], bx[b], by[b]) <= LINK }.toIntArray() }
        hops = hopsFrom(0)
        maxHops = hops.maxOrNull()?.coerceAtLeast(1) ?: 1
        toVpn = shortest(0, 1)
        // The way on shares nothing with the way in but the VPN, so it never looks like a shortcut past it.
        toNet = shortest(1, 2, avoid = toVpn.toSet() - 1)
        direct = shortest(0, 2, avoid = setOf(1))
    }

    /** Hops from [start] to every node. */
    private fun hopsFrom(start: Int): IntArray {
        val h = IntArray(count) { -1 }
        h[start] = 0
        val queue = ArrayDeque(listOf(start))
        while (queue.isNotEmpty()) {
            val a = queue.removeFirst()
            for (b in neighbours[a]) {
                if (h[b] >= 0) continue
                h[b] = h[a] + 1
                queue.addLast(b)
            }
        }
        return h
    }

    /** The shortest way from [a] to [b] by length, so it runs fairly straight, not through [avoid]. */
    private fun shortest(a: Int, b: Int, avoid: Set<Int> = emptySet()): IntArray {
        val d = FloatArray(count) { Float.MAX_VALUE }
        val from = IntArray(count) { -1 }
        val done = BooleanArray(count)
        d[a] = 0f
        while (true) {
            var u = -1
            for (i in 0 until count) if (!done[i] && d[i] < Float.MAX_VALUE && (u < 0 || d[i] < d[u])) u = i
            if (u < 0 || u == b) break
            done[u] = true
            for (v in neighbours[u]) {
                if (v in avoid) continue
                val nd = d[u] + dist(bx[u], by[u], bx[v], by[v])
                if (nd < d[v]) {
                    d[v] = nd
                    from[v] = u
                }
            }
        }
        val path = ArrayList<Int>()
        var n = b
        while (n >= 0) {
            path.add(n)
            n = from[n]
        }
        return if (path.last() == a) path.reversed().toIntArray() else intArrayOf(a, b)
    }

    fun step(mode: Mode, blocked: Boolean, dt: Float, moving: Boolean) {
        light.step(mode, dt, moving)
        this.blocked = blocked
        flow += ((if (blocked) 0f else 1f) - flow) * (1 - exp(-dt * 2.5f))
        if (moving) travel += dt * (0.2f + 0.45f * light.energy)
    }

    /** Nothing will change until the state does. */
    fun settled(): Boolean = light.settled() && abs(flow - if (blocked) 0f else 1f) < 0.005f

    // ---- Labels and icons ------------------------------------------------------------------

    private var labels: List<TextLayoutResult>? = null

    /** The stops are named (THIS DEVICE, VPN SERVER, INTERNET) only while the drawing is uncovered. */
    var named = true

    private fun labels(): List<TextLayoutResult> = labels ?: run {
        // Measured at one pixel a unit: the canvas is already scaled to the design's units.
        val style = TextStyle(fontSize = 12.5.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.14.em, color = Color.White)
        listOf("THIS DEVICE", "VPN SERVER", "INTERNET").map { measurer.measure(it, style, density = Density(1f)) }.also { labels = it }
    }

    private val deviceIcon = icon(Ic.Apps)
    private val vpnIcon = icon(Ic.Shield)
    private val netIcon = icon(Ic.Globe)

    private fun icon(ic: Ic): List<Path> = ic.paths.map { PathParser().parsePathString(it).toPath() }

    // ---- Drawing ---------------------------------------------------------------------------

    private var brushKey = Long.MIN_VALUE
    private lateinit var deviceGlow: Brush
    private lateinit var vpnGlow: Brush
    private lateinit var netGlow: Brush
    private lateinit var field: Brush
    private val vignette = Brush.radialGradient(
        0f to Color.Transparent, 320f / 820f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.55f),
        center = Offset(640f, 360f), radius = 820f,
    )

    private fun brushes() {
        if (light.key == brushKey) return
        brushKey = light.key
        val l = light.light
        deviceGlow = Brush.radialGradient(listOf(light.core(0.5f * l), light.tone(0f)), Offset(bx[0], by[0]), 70f)
        vpnGlow = Brush.radialGradient(listOf(light.core(0.6f * l), light.tone(0f)), Offset(bx[1], by[1]), 100f)
        netGlow = Brush.radialGradient(listOf(light.core(0.5f * l), light.tone(0f)), Offset(bx[2], by[2]), 80f)
        field = Brush.radialGradient(listOf(light.tone(0.12f * l), light.tone(0f)), Offset(900f, 360f), 600f)
    }

    fun draw(scope: DrawScope) = with(scope) {
        brushes()
        val l = light.light
        val e = light.energy
        val t = light.t
        val alive = smoothstep(0f, 0.3f, e)
        val searching = smoothstep(0.5f, 1f, e)
        val on = alive * (1 - searching)
        val drift = 0.4f + 0.6f * alive + 0.8f * searching
        // The three stops stay put. Every other node wanders on its own slow, uneven loop (two
        // waves of different speeds), so the web keeps reshaping around them.
        for (i in ENDS until count) {
            val sag = (1 - alive) * 26f * (0.4f + 0.6f * ((by[i] - 70f) / 560f))
            val p = pace[i]
            val a = 16f * drift
            x[i] = bx[i] + a * (0.72f * sin(t * p + phase[i]) + 0.28f * sin(t * p * 2.3f + phase[i] * 1.7f))
            y[i] = by[i] + a * 0.8f * (0.72f * cos(t * p * 0.87f + phase[i] * 1.3f) + 0.28f * cos(t * p * 1.9f + phase[i] * 0.6f)) + sag
        }
        drawRect(Color(0xFF030508))
        drawCircle(field, radius = 600f, center = Offset(900f, 360f))
        drawLinks(l, t, alive, searching)
        drawTraffic(l, t, alive, searching, on)
        drawNodes(l, alive)
        drawEnds(l, t, searching, on)
        drawRect(vignette)
    }

    /** Every link short enough; while connecting, a faint search spreading out from the device. */
    private fun DrawScope.drawLinks(l: Float, t: Float, alive: Float, searching: Float) {
        val front = (t * 4.2f) % (maxHops + 1.5f)
        // Connecting shows on the link to the VPN; the network around it only echoes it.
        val frontShown = searching * 0.3f
        val reach = LINK * (0.55f + 0.45f * alive)
        for (a in 0 until count) {
            for (b in a + 1 until count) {
                val d = dist(x[a], y[a], x[b], y[b])
                if (d > reach) continue
                var alpha = (1 - d / reach) * (0.16f + 0.18f * alive) * l
                if (alive < 0.5f) alpha *= 0.55f + 0.45f * sin(t * 3f + a * 1.7f + b)
                if (frontShown > 0.01f && hops[a] >= 0 && hops[b] >= 0) {
                    val h = maxOf(hops[a], hops[b])
                    alpha += exp(-(front - h) * (front - h) * 1.2f) * 0.75f * frontShown * l
                }
                if (alpha < 0.008f) continue
                drawLine(light.tone(alpha), Offset(x[a], y[a]), Offset(x[b], y[b]), strokeWidth = 1.2f, blendMode = BlendMode.Plus)
            }
        }
    }

    private fun DrawScope.drawTraffic(l: Float, t: Float, alive: Float, searching: Float, on: Float) {
        val h1 = toVpn.size - 1
        val h2 = toNet.size - 1
        if (on > 0.02f) {
            // The tunnel to the VPN, wider and brighter than the open way on from there.
            pathLine(toVpn, light.tone(0.14f * l * on), 11f)
            pathLine(toVpn, light.core(0.65f * l * on, 0.4f), 2.4f)
            pathLine(toNet, light.tone(0.1f * l * on), 6f)
            pathLine(toNet, light.core(0.5f * l * on, 0.4f), 1.6f)
            val total = (h1 + h2).toFloat()
            for (p in 0 until 4) {
                val u = ((travel + p / 4f) % 1f) * total
                for (s in 4 downTo 1) {
                    val back = u - s * 0.07f
                    if (back > 0f) drawCircle(light.core(0.45f * l * on / (s + 1), 0.5f), radius = 2.6f, center = alongBoth(back, h1), blendMode = BlendMode.Plus)
                }
                packet(alongBoth(u, h1), l * on)
            }
        }
        if (searching > 0.02f) {
            // Connecting: the link lights up from the device to the VPN, then the VPN answers; over again.
            val k = (t % HANDSHAKE) / HANDSHAKE
            val upTo = smoothstep(0f, 0.7f, k) * h1
            partial(toVpn, upTo, light.tone(0.16f * l * searching), 11f)
            partial(toVpn, upTo, light.core(0.7f * l * searching, 0.45f), 2.4f)
            if (k < 0.72f) spark(along(toVpn, upTo), l * searching)
            if (k > 0.7f) flash(Offset(x[1], y[1]), (k - 0.7f) * HANDSHAKE, l * searching)
            // Not on to the internet yet.
            pathLine(toNet, light.tone(0.05f * l * searching), 1.4f)
        }
        // Off: no VPN. Your traffic hops through the network straight to the internet, around
        // the VPN. While the kill switch blocks it, the way stays dark and nothing moves on it.
        val exposed = 1 - alive
        if (exposed > 0.02f) {
            val open = exposed * flow
            val steps = (direct.size - 1).toFloat()
            pathLine(direct, light.tone(0.14f * l * open), 10f)
            pathLine(direct, light.core((0.12f + 0.48f * flow) * l * exposed, 0.4f), 2f)
            if (open > 0.02f) {
                for (p in 0 until 3) {
                    val u = ((travel * 1.4f + p / 3f) % 1f) * steps
                    for (s in 4 downTo 1) {
                        val back = u - s * 0.07f
                        if (back > 0f) drawCircle(light.core(0.45f * l * open / (s + 1), 0.5f), radius = 2.6f, center = along(direct, back), blendMode = BlendMode.Plus)
                    }
                    packet(along(direct, u), l * open)
                }
            }
        }
    }

    /** The three stops, each a dark disc with a ring and an icon, named below or above. */
    private fun DrawScope.drawEnds(l: Float, t: Float, searching: Float, on: Float) {
        // The VPN is lit while in use, pulses while connecting, and is dark and unused when off.
        val vpnLit = (0.25f + 0.75f * on + searching * (0.55f + 0.35f * sin(t * 6f))).coerceAtMost(1f)
        drawCircle(deviceGlow, radius = 70f, center = Offset(x[0], y[0]), blendMode = BlendMode.Plus)
        drawCircle(vpnGlow, radius = 100f, center = Offset(x[1], y[1]), alpha = vpnLit, blendMode = BlendMode.Plus)
        drawCircle(netGlow, radius = 80f, center = Offset(x[2], y[2]), blendMode = BlendMode.Plus)
        stop(0, deviceIcon, 1f, l)
        stop(1, vpnIcon, vpnLit, l)
        stop(2, netIcon, 1f, l)
        if (!named) return
        val names = labels()
        label(names[0], x[0], y[0] + 34f, 1f)
        label(names[1], x[1], y[1] - 36f - names[1].size.height, 0.4f + 0.6f * vpnLit)
        label(names[2], x[2], y[2] + 34f, 1f)
    }

    private fun DrawScope.stop(i: Int, icon: List<Path>, lit: Float, l: Float) {
        val c = Offset(x[i], y[i])
        drawCircle(Color(0xFF05080C), radius = 23f, center = c)
        drawCircle(light.core(0.9f * lit * l, 0.45f), radius = 23f, center = c, style = Stroke(2.2f), blendMode = BlendMode.Plus)
        translate(c.x - 11f, c.y - 11f) {
            scale(22f / 24f, pivot = Offset.Zero) {
                icon.forEach { drawPath(it, light.core((0.35f + 0.6f * lit) * l, 0.55f), style = IconStroke) }
            }
        }
    }

    private fun DrawScope.label(text: TextLayoutResult, cx: Float, top: Float, a: Float) {
        drawText(text, color = Color.White.copy(alpha = 0.62f * a), topLeft = Offset(cx - text.size.width / 2f, top))
    }

    private fun DrawScope.packet(c: Offset, a: Float) {
        drawCircle(light.tone(0.22f * a), radius = 12f, center = c, blendMode = BlendMode.Plus)
        drawCircle(light.core(0.95f * a, 0.7f), radius = 3.2f, center = c, blendMode = BlendMode.Plus)
    }

    private fun DrawScope.spark(c: Offset, a: Float) {
        drawCircle(light.tone(0.3f * a), radius = 18f, center = c, blendMode = BlendMode.Plus)
        drawCircle(light.core(a, 0.8f), radius = 4.4f, center = c, blendMode = BlendMode.Plus)
    }

    /** A ring that grows out from [c] for 0.6 s. */
    private fun DrawScope.flash(c: Offset, age: Float, a: Float) {
        if (age > 0.6f) return
        val f = 1 - age / 0.6f
        drawCircle(light.core(0.8f * a * f * f, 0.5f), radius = 24f + age * 110f, center = c, style = Stroke(2.4f), blendMode = BlendMode.Plus)
    }

    private fun DrawScope.pathLine(path: IntArray, c: Color, w: Float) {
        for (k in 0 until path.size - 1) {
            drawLine(c, Offset(x[path[k]], y[path[k]]), Offset(x[path[k + 1]], y[path[k + 1]]), strokeWidth = w, blendMode = BlendMode.Plus)
        }
    }

    /** The first [upTo] hops of [path], the last one partly. */
    private fun DrawScope.partial(path: IntArray, upTo: Float, c: Color, w: Float) {
        var k = 0
        while (k < path.size - 1 && k < upTo) {
            val end = if (upTo - k >= 1f) Offset(x[path[k + 1]], y[path[k + 1]]) else along(path, upTo)
            drawLine(c, Offset(x[path[k]], y[path[k]]), end, strokeWidth = w, blendMode = BlendMode.Plus)
            k++
        }
    }

    /** A point [u] hops along [path]. */
    private fun along(path: IntArray, u: Float): Offset {
        val k = floor(u).toInt().coerceIn(0, path.size - 2)
        val f = (u - k).coerceIn(0f, 1f)
        val a = path[k]
        val b = path[k + 1]
        return Offset(x[a] + (x[b] - x[a]) * f, y[a] + (y[b] - y[a]) * f)
    }

    /** A point [u] hops along the way to the VPN and on to the internet; [h1] is the first part's hops. */
    private fun alongBoth(u: Float, h1: Int): Offset = if (u <= h1) along(toVpn, u) else along(toNet, u - h1)

    private fun DrawScope.drawNodes(l: Float, alive: Float) {
        for (i in ENDS until count) {
            dots[(i - ENDS) * 2] = x[i]
            dots[(i - ENDS) * 2 + 1] = y[i]
        }
        drawIntoCanvas { canvas ->
            paint.color = light.core((0.6f + 0.3f * alive) * l, 0.35f).toArgb()
            paint.strokeWidth = 3.4f
            canvas.nativeCanvas.drawPoints(dots, 0, (count - ENDS) * 2, paint)
        }
    }

    private fun dist(ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = ax - bx
        val dy = ay - by
        return sqrt(dx * dx + dy * dy)
    }

    private companion object {
        const val NODES = 72
        const val ENDS = 3
        const val LINK = 125f
        const val HANDSHAKE = 1.8f

        // The device and the internet level with each other, the VPN up between them, so the way
        // through the VPN is a clear detour and the way without it a straight line.
        // Above the hero's buttons, which reach far to the right once a profile is connected.
        val DEVICE = Offset(700f, 372f)
        val VPN = Offset(940f, 158f)
        val NET = Offset(1180f, 372f)
        val IconStroke = Stroke(width = 2.1f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    }
}

private fun smoothstep(a: Float, b: Float, x: Float): Float {
    val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
    return t * t * (3 - 2 * t)
}

/** The design's xorshift generator: the same seed lays things out the same way every time. */
private fun seededRandom(seed: Int): () -> Float {
    var s = (seed.toLong() * 2654435761L) and 0xFFFFFFFFL
    if (s == 0L) s = 1
    return {
        s = s xor ((s shl 13) and 0xFFFFFFFFL)
        s = s xor (s ushr 17)
        s = s xor ((s shl 5) and 0xFFFFFFFFL)
        s / 4294967296f
    }
}

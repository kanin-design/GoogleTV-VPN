package dev.shieldvpn.ui.tv

import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * The mesh behind the background's stops (Backdrop.kt draws the stops, the lit ways and the
 * packets over it), and the tools both use to draw a lot of lines cheaply: each frame's lines and
 * dots go into a few batches by brightness, a handful of draw calls in all, and the arrays, paths
 * and strokes are made once, so drawing a frame allocates next to nothing.
 */

/** What the mesh needs to know each frame: the state's weights, its beats, and how much of each way is lit. */
internal class MeshFrame(val light: StatusLight) {
    var l = 1f
    var clock = 0f
    var wOff = 1f
    var wBusy = 0f
    var wOn = 0f
    val awake: Float get() = 1 - wOff

    /** Seconds since the VPN last answered while connecting, and since the tunnel opened. */
    var answerAge = 99f
    var openAge = 99f

    /** How far along each way is lit, in design pixels, and how brightly (0 to 1). */
    var tunnelTo = 0f
    var tunnelLit = 0f
    var netTo = 0f
    var netLit = 0f
    var directTo = 0f
    var directLit = 0f

    /** Connecting: the head running to the VPN, as a distance along the tunnel (or -1), and the VPN's answer flashing the tunnel. */
    var head = -1f
    var flash = 0f
}

/**
 * Where the mesh's nodes rest and how they are linked; it never changes, so it is made once for the
 * whole process ([of]). Nodes are spread evenly, and each is linked to its natural neighbours: two
 * nodes are linked when no third node lies in the circle between them (a Gabriel graph), so links
 * run between nodes that are next to each other, in every direction, and never cross.
 *
 * The device, the VPN and the internet are nodes of the mesh, and so are the hops of the three ways:
 * ordinary nodes, linked to their neighbours like the rest, spaced along gentle curves (up and over
 * to the VPN, down from it to the internet, and under the VPN for the way around it). With [loose],
 * the hops rest a little off their curve; without it, exactly on it.
 */
internal class MeshLayout private constructor(val loose: Boolean) {
    // The device and the internet level with each other, the VPN up between them, so the way
    // through the VPN is a clear detour. Above the hero's buttons, which reach far to the right
    // once a profile is connected.
    val device = Offset(700f, 372f)
    val vpn = Offset(940f, 158f)
    val net = Offset(1180f, 372f)

    // Nodes: where each rests, how it wanders, and how much it fades towards the text. 0 is the
    // device, 1 the VPN, 2 the internet.
    val bx = FloatArray(MAX)
    val by = FloatArray(MAX)
    val ax = FloatArray(MAX)
    val ay = FloatArray(MAX)
    val fq = Array(4) { FloatArray(MAX) }
    val ph = Array(4) { FloatArray(MAX) }
    val fade = FloatArray(MAX)

    /** Which way a hop is on: 0 the tunnel, 1 the way on, 2 the way around; -1 for the rest. */
    val onWay = IntArray(MAX) { -1 }
    val n: Int

    // Links, their length at rest, the links at each node, and hops from the VPN over them.
    val la: IntArray
    val lb: IntArray
    val rest: FloatArray
    val deg = IntArray(MAX)
    val hops: IntArray

    /** The hops of the tunnel, the way on and the way around, from end to end. */
    val tunnel: IntArray
    val onward: IntArray
    val around: IntArray

    // A grid of [CELL] px squares listing the nodes in each (the first, then each the next), so
    // looking for a node's neighbours visits the few squares around it instead of every node.
    private val first = IntArray(COLS * ROWS) { -1 }
    private val nextIn = IntArray(MAX) { -1 }

    init {
        val r = SeededRandom(71)
        val stops = arrayOf(device, vpn, net)
        for (i in 0 until 3) {
            bx[i] = stops[i].x
            by[i] = stops[i].y
            file(i)
        }
        var c = 3
        // The ways' hops first, one every [HOP] px along each way's curve, which bends towards [bend].
        fun seed(from: Int, bend: Offset, to: Int, id: Int): IntArray {
            val samples = 120
            val qx = FloatArray(samples + 1)
            val qy = FloatArray(samples + 1)
            val len = FloatArray(samples + 1)
            for (k in 0..samples) {
                val t = k / samples.toFloat()
                val u = 1 - t
                qx[k] = u * u * bx[from] + 2 * u * t * bend.x + t * t * bx[to]
                qy[k] = u * u * by[from] + 2 * u * t * bend.y + t * t * by[to]
                if (k > 0) len[k] = len[k - 1] + dist(qx[k], qy[k], qx[k - 1], qy[k - 1])
            }
            val count = max(2, Math.round(len[samples] / HOP))
            val out = ArrayList<Int>()
            out.add(from)
            var k = 0
            for (h in 1 until count) {
                val want = len[samples] * h / count
                while (k < samples && len[k + 1] < want) k++
                bx[c] = qx[k]
                by[c] = qy[k]
                if (loose) {
                    // A little to one side of the curve or the other.
                    val tx = qx[min(k + 1, samples)] - qx[max(k - 1, 0)]
                    val ty = qy[min(k + 1, samples)] - qy[max(k - 1, 0)]
                    val tl = sqrt(tx * tx + ty * ty).coerceAtLeast(0.001f)
                    val off = (r() * 2 - 1) * JITTER
                    bx[c] -= ty / tl * off
                    by[c] += tx / tl * off
                }
                onWay[c] = id
                file(c)
                out.add(c++)
            }
            out.add(to)
            return out.toIntArray()
        }
        tunnel = seed(0, Offset(785f, 190f), 1, 0)
        onward = seed(1, Offset(1095f, 190f), 2, 1)
        around = seed(0, Offset(940f, 440f), 2, 2)
        val ways = arrayOf(tunnel, onward, around)
        // Then the rest, spread evenly, thinning out towards the text, a little clear of the ways and the names.
        var tries = 0
        while (c < MAX && tries < 40000) {
            tries++
            val cx = 420f + r() * 880f
            val cy = 14f + r() * 706f
            if (r() > 0.05f + 0.95f * smoothstep(430f, 800f, cx)) continue
            if (underLabel(cx, cy)) continue
            var free = !crowded(cx, cy)
            if (free) for (w in ways) for (k in 0 until w.size - 1) {
                if (toSegment(cx, cy, w[k], w[k + 1]) < CLEAR) {
                    free = false
                    break
                }
            }
            if (!free) continue
            bx[c] = cx
            by[c] = cy
            file(c)
            c++
        }
        n = c
        for (i in 0 until n) {
            ax[i] = 10f + r() * 10f
            ay[i] = ax[i] * (0.7f + r() * 0.3f)
            for (k in 0 until 4) {
                fq[k][i] = 0.16f + r() * 0.34f
                ph[k][i] = r() * TAU
            }
            fade[i] = if (i < 3) 1f else 0.12f + 0.88f * leftFade(bx[i])
        }
        // The ways' links, then each node to its natural neighbours, none crossing a way.
        val a = ArrayList<Int>()
        val b = ArrayList<Int>()
        val linked = HashSet<Int>()
        fun link(i: Int, j: Int) {
            if (!linked.add(if (i < j) i * MAX + j else j * MAX + i)) return
            a.add(i)
            b.add(j)
            deg[i]++
            deg[j]++
        }
        for (w in ways) for (k in 0 until w.size - 1) link(w[k], w[k + 1])
        val wayLinks = a.size
        val add = ::link
        for (i in 0 until n) for (row in rowOf(by[i] - REACH)..rowOf(by[i] + REACH)) for (col in colOf(bx[i] - REACH)..colOf(bx[i] + REACH)) {
            var j = first[row * COLS + col]
            while (j >= 0) {
                if (j > i) linkIfNeighbours(i, j, a, b, wayLinks, add)
                j = nextIn[j]
            }
        }
        // Everyone joined up: a node or group left apart is linked to the nearest node of the rest.
        while (true) {
            val reach = hopsFrom(0, a, b)
            if ((0 until n).all { reach[it] >= 0 }) break
            var best = Float.MAX_VALUE
            var bi = -1
            var bj = -1
            for (i in 0 until n) if (reach[i] >= 0) for (j in 0 until n) if (reach[j] < 0) {
                val d = dist(bx[i], by[i], bx[j], by[j])
                if (d < best) {
                    best = d
                    bi = i
                    bj = j
                }
            }
            link(bi, bj)
        }
        la = a.toIntArray()
        lb = b.toIntArray()
        rest = FloatArray(la.size) { dist(bx[la[it]], by[la[it]], bx[lb[it]], by[lb[it]]) }
        hops = hopsFrom(1, a, b)
    }

    /**
     * Links [i] and [j] if they are natural neighbours: near enough, with no third node in the
     * circle between them (a Gabriel graph), and their link would cross none of the first
     * [wayLinks] links, the ways'.
     */
    private fun linkIfNeighbours(i: Int, j: Int, a: List<Int>, b: List<Int>, wayLinks: Int, link: (Int, Int) -> Unit) {
        val d = dist(bx[i], by[i], bx[j], by[j])
        if (d > REACH) return
        if (anyWithin((bx[i] + bx[j]) / 2, (by[i] + by[j]) / 2, d / 2, i, j)) return
        for (k in 0 until wayLinks) {
            val p = a[k]
            val q = b[k]
            if (p == i || p == j || q == i || q == j) continue
            if (cross(bx[i], by[i], bx[j], by[j], bx[p], by[p], bx[q], by[q])) return
        }
        link(i, j)
    }

    private fun colOf(x: Float) = (x / CELL).toInt().coerceIn(0, COLS - 1)
    private fun rowOf(y: Float) = (y / CELL).toInt().coerceIn(0, ROWS - 1)

    private fun file(i: Int) {
        val s = rowOf(by[i]) * COLS + colOf(bx[i])
        nextIn[i] = first[s]
        first[s] = i
    }

    /** Whether a node is too near ([cx], [cy]) for another: [SPACING], or 80 px from a stop. */
    private fun crowded(cx: Float, cy: Float): Boolean {
        for (row in rowOf(cy - 80f)..rowOf(cy + 80f)) for (col in colOf(cx - 80f)..colOf(cx + 80f)) {
            var i = first[row * COLS + col]
            while (i >= 0) {
                if (dist(cx, cy, bx[i], by[i]) < if (i < 3) 80f else SPACING) return true
                i = nextIn[i]
            }
        }
        return false
    }

    /** Whether a node other than [i] and [j] lies less than [r] from ([cx], [cy]). */
    private fun anyWithin(cx: Float, cy: Float, r: Float, i: Int, j: Int): Boolean {
        val rr = r * r
        for (row in rowOf(cy - r)..rowOf(cy + r)) for (col in colOf(cx - r)..colOf(cx + r)) {
            var k = first[row * COLS + col]
            while (k >= 0) {
                if (k != i && k != j) {
                    val dx = bx[k] - cx
                    val dy = by[k] - cy
                    if (dx * dx + dy * dy < rr) return true
                }
                k = nextIn[k]
            }
        }
        return false
    }

    private fun underLabel(cx: Float, cy: Float): Boolean =
        (abs(cx - device.x) < 66f && cy - device.y in 16f..60f) ||
            (abs(cx - net.x) < 60f && cy - net.y in 16f..60f) ||
            (abs(cx - vpn.x) < 62f && vpn.y - cy in 16f..62f)

    /** How far ([cx], [cy]) is from the link between nodes [p] and [q], at rest. */
    private fun toSegment(cx: Float, cy: Float, p: Int, q: Int): Float {
        val dx = bx[q] - bx[p]
        val dy = by[q] - by[p]
        val t = (((cx - bx[p]) * dx + (cy - by[p]) * dy) / (dx * dx + dy * dy)).coerceIn(0f, 1f)
        return dist(cx, cy, bx[p] + dx * t, by[p] + dy * t)
    }

    private fun cross(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float, x4: Float, y4: Float): Boolean {
        fun side(px: Float, py: Float, qx: Float, qy: Float, rx: Float, ry: Float) = (qx - px) * (ry - py) - (qy - py) * (rx - px)
        return side(x3, y3, x4, y4, x1, y1) * side(x3, y3, x4, y4, x2, y2) < 0 &&
            side(x1, y1, x2, y2, x3, y3) * side(x1, y1, x2, y2, x4, y4) < 0
    }

    /** Hops from [start] to every node over the links [a] to [b] (-1 for none). */
    private fun hopsFrom(start: Int, a: List<Int>, b: List<Int>): IntArray {
        // Each node's links gathered together first: node i's are at[from[i] until from[i + 1]].
        val from = IntArray(n + 1)
        for (k in a.indices) {
            from[a[k] + 1]++
            from[b[k] + 1]++
        }
        for (i in 1..n) from[i] += from[i - 1]
        val fill = from.copyOf()
        val at = IntArray(a.size * 2)
        for (k in a.indices) {
            at[fill[a[k]]++] = b[k]
            at[fill[b[k]]++] = a[k]
        }
        val h = IntArray(n) { -1 }
        val queue = IntArray(n)
        var head = 0
        var tail = 0
        h[start] = 0
        queue[tail++] = start
        while (head < tail) {
            val p = queue[head++]
            for (k in from[p] until from[p + 1]) {
                val q = at[k]
                if (h[q] >= 0) continue
                h[q] = h[p] + 1
                queue[tail++] = q
            }
        }
        return h
    }

    companion object {
        private val looseLayout = lazy { MeshLayout(loose = true) }
        private val exactLayout = lazy { MeshLayout(loose = false) }

        /**
         * The layout, made the first time it is asked for. That takes a moment on a TV (some tens
         * of thousands of tries to spread the nodes), so the first ask belongs off the main thread.
         */
        fun of(loose: Boolean): MeshLayout = (if (loose) looseLayout else exactLayout).value

        /** The layout if it has been made already, or null. */
        fun ready(loose: Boolean): MeshLayout? = (if (loose) looseLayout else exactLayout).let { if (it.isInitialized()) it.value else null }

        const val MAX = 120
        const val SPACING = 62f
        const val HOP = 64f
        const val CLEAR = 40f
        const val REACH = 140f

        /** A loose hop rests up to this far off its curve. */
        const val JITTER = 7f

        // The grid's squares, and how many across and down cover the canvas and a little beyond.
        const val CELL = 32f
        const val COLS = 42
        const val ROWS = 24
    }
}

/**
 * The mesh as it moves, over its [layout]. Each node wanders about its place on a path of its own
 * at a pace of its own; the links stretch with the nodes, so they never pop in and out. A way runs
 * from hop to hop along its links. Its hops wander like the rest while it is unused, and glide back
 * towards their curve while it is lit, so a way in use reads as a curve, never a random zigzag: a
 * loose layout's hops keep drifting a little, no longer drawn with a ruler but still plainly a curve.
 */
internal class Mesh(private val layout: MeshLayout) {
    val device get() = layout.device
    val vpn get() = layout.vpn
    val net get() = layout.net

    /** The way from the device to the VPN, from the VPN on to the internet, and from the device around the VPN. */
    val toVpn = Way(layout.bx, layout.by, layout.tunnel)
    val toNet = Way(layout.bx, layout.by, layout.onward)
    val direct = Way(layout.bx, layout.by, layout.around)

    private val n = layout.n
    private val bx = layout.bx
    private val by = layout.by
    private val ax = layout.ax
    private val ay = layout.ay
    private val fq = layout.fq
    private val ph = layout.ph
    private val fade = layout.fade
    private val onWay = layout.onWay
    private val la = layout.la
    private val lb = layout.lb
    private val rest = layout.rest
    private val deg = layout.deg
    private val hops = layout.hops
    private val loose = layout.loose

    // Where each node is now.
    private val x = bx.copyOf()
    private val y = by.copyOf()
    private var time = 0f
    private var heldIn = 0f
    private var heldAround = 1f

    private val lines = LineBatch(la.size)
    private val small = DotBatch(n)
    private val middle = DotBatch(n)
    private val large = DotBatch(n)
    private val paint = addPaint()
    private val hopDots = FloatArray(n * 2)
    private val shade = Paint().apply {
        isAntiAlias = true
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 9.2f
        color = 0xFF05080C.toInt()
    }

    /** Moves the mesh by [dt] seconds (only while [moving]) and lays the ways through it. */
    fun update(f: MeshFrame, dt: Float, moving: Boolean) {
        if (moving) time += dt * (0.5f + 0.5f * f.awake + 0.4f * f.wBusy)
        // The tunnel's hops hold to their curve while connecting and connected, the way around's while off.
        val k = if (moving) 1 - exp(-dt * 3.5f) else 1f
        heldIn += ((if (f.wOff < 0.5f) 1f else 0f) - heldIn) * k
        heldAround += ((if (f.wOff >= 0.5f) 1f else 0f) - heldAround) * k
        // Off, the mesh slows and wanders less.
        val amp = 0.55f + 0.45f * f.awake
        for (i in 3 until n) {
            val held = when (onWay[i]) {
                -1 -> 0f
                2 -> heldAround
                else -> heldIn
            }
            val wx = ax[i] * amp * (0.65f * sin(time * fq[0][i] + ph[0][i]) + 0.35f * sin(time * fq[1][i] * 2.1f + ph[1][i]))
            val wy = ay[i] * amp * (0.65f * cos(time * fq[2][i] + ph[2][i]) + 0.35f * cos(time * fq[3][i] * 1.9f + ph[3][i]))
            // Held, a hop keeps none of its wander, or a little of it when loose.
            val keep = 1 - held * (if (loose) 1 - LOOSE else 1f)
            x[i] = bx[i] + wx * keep
            y[i] = by[i] + wy * keep
        }
        toVpn.follow(x, y)
        toNet.follow(x, y)
        direct.follow(x, y)
    }

    /** The mesh, under the ways and the stops. */
    fun draw(scope: DrawScope, f: MeshFrame) {
        val light = f.light
        val l = f.l
        val awake = f.awake
        val base = (0.13f + 0.17f * awake + 0.04f * f.wBusy) * l
        // After each answer while connecting, and when the tunnel opens, a front runs out from the VPN hop by hop.
        val echo = if (f.answerAge < 1.2f) f.wBusy * 0.3f * (1 - f.answerAge / 1.2f) else 0f
        val front = f.answerAge * 7f
        val opened = if (f.openAge < 1.6f) f.wOn * 0.25f * (1 - f.openAge / 1.6f) else 0f
        val openFront = f.openAge * 8f
        lines.clear()
        for (k in la.indices) {
            val a = la[k]
            val b = lb[k]
            val d = dist(x[a], y[a], x[b], y[b])
            // A stretched link thins out a little.
            var alpha = base * min(fade[a], fade[b]) * (rest[k] / d).coerceIn(0.6f, 1f)
            // Off, the links fade slowly in and out, as if failing.
            if (f.wOff > 0.01f) alpha *= 1 - 0.55f * f.wOff * (0.5f + 0.5f * sin(time * 0.9f + k * 2.39f))
            val h = max(hops[a], hops[b]).toFloat()
            if (echo > 0.01f) alpha += exp(-(front - h) * (front - h) * 1.5f) * echo * l
            if (opened > 0.01f) alpha += exp(-(openFront - h) * (openFront - h) * 1.2f) * opened * l
            lines.add(x[a], y[a], x[b], y[b], alpha)
        }
        lines.draw(scope, paint, 1.3f, light)
        small.clear()
        middle.clear()
        large.clear()
        for (i in 3 until n) {
            if (fade[i] < 0.15f) continue
            val a = (0.42f + 0.33f * awake) * l * fade[i]
            when {
                deg[i] >= 6 -> large.add(x[i], y[i], a)
                deg[i] >= 4 -> middle.add(x[i], y[i], a)
                else -> small.add(x[i], y[i], a)
            }
        }
        small.draw(scope, paint, 2.5f, light, 0.35f)
        middle.draw(scope, paint, 3.2f, light, 0.38f)
        large.draw(scope, paint, 4.0f, light, 0.42f)
    }

    /** The hops of a lit way show as nodes on it, so it reads as running from node to node. */
    fun drawOver(scope: DrawScope, f: MeshFrame) {
        hopsOn(scope, toVpn, f.tunnelTo, max(f.tunnelLit, if (f.head >= 0f) 1f else 0f), f, f.head)
        hopsOn(scope, toNet, f.netTo, f.netLit, f, -1f)
        hopsOn(scope, direct, f.directTo, 0.35f + 0.65f * f.directLit, f, -1f)
    }

    /**
     * The hops of [way] up to [upTo] along it (or up to the head at [head], while connecting): a dark
     * disc that cuts the way, and a lit dot in it, two draw calls for all of them.
     */
    private fun hopsOn(scope: DrawScope, way: Way, upTo: Float, lit: Float, f: MeshFrame, head: Float) {
        val reach = if (head >= 0f) head else upTo
        if (reach <= 0.5f || lit <= 0f) return
        var m = 0
        for (k in 1 until way.size - 1) {
            if (way.hopAt(k) > reach) break
            hopDots[m++] = way.hopX(k)
            hopDots[m++] = way.hopY(k)
        }
        if (m == 0) return
        scope.drawIntoCanvas { c ->
            c.nativeCanvas.drawPoints(hopDots, 0, m, shade)
            paint.strokeWidth = 5.8f
            paint.color = f.light.core(0.85f * lit * f.l, 0.5f).toArgb()
            c.nativeCanvas.drawPoints(hopDots, 0, m, paint)
        }
    }

    private companion object {
        /** How much of its wander a loose hop keeps while its way is lit. */
        const val LOOSE = 0.35f
    }
}

// ---- Drawing tools ------------------------------------------------------------------------------

/**
 * A way through the mesh: the hops [idx] of ([xs], [ys]) from end to end. Distances along it are
 * measured once, where the hops rest, and everything that runs along it goes by that measure: as
 * the hops drift the way bends with them, but a packet keeps one steady speed and never jumps.
 */
internal class Way(xs: FloatArray, ys: FloatArray, private val idx: IntArray) {
    private val px = FloatArray(idx.size) { xs[idx[it]] }
    private val py = FloatArray(idx.size) { ys[idx[it]] }
    private val cum = FloatArray(idx.size)
    val size get() = idx.size
    val length: Float

    init {
        for (k in 1 until idx.size) cum[k] = cum[k - 1] + dist(px[k], py[k], px[k - 1], py[k - 1])
        length = cum[idx.size - 1]
    }

    /** Moves the way onto where its hops are now. */
    fun follow(xs: FloatArray, ys: FloatArray) {
        for (k in idx.indices) {
            px[k] = xs[idx[k]]
            py[k] = ys[idx[k]]
        }
    }

    /** How far along the way hop [k] is, and where it is now. */
    fun hopAt(k: Int) = cum[k]
    fun hopX(k: Int) = px[k]
    fun hopY(k: Int) = py[k]

    fun at(d: Float): Offset {
        if (size == 1 || d <= 0f) return Offset(px[0], py[0])
        var lo = 0
        var hi = size - 1
        if (d >= cum[hi]) return Offset(px[hi], py[hi])
        while (hi - lo > 1) {
            val m = (lo + hi) ushr 1
            if (cum[m] <= d) lo = m else hi = m
        }
        val k = (d - cum[lo]) / (cum[hi] - cum[lo]).coerceAtLeast(0.001f)
        return Offset(px[lo] + (px[hi] - px[lo]) * k, py[lo] + (py[hi] - py[lo]) * k)
    }

    /** The part from [d0] to [d1], into [path]. */
    fun part(path: Path, d0: Float, d1: Float) {
        path.reset()
        val s = at(d0)
        path.moveTo(s.x, s.y)
        for (k in 1 until size) {
            if (cum[k] <= d0) continue
            if (cum[k] >= d1) break
            path.lineTo(px[k], py[k])
        }
        val e = at(d1)
        path.lineTo(e.x, e.y)
    }
}

/** A stroke with round ends and joins: no notches at a way's corners. */
internal fun roundStroke(w: Float) = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round)

/** Strokes the part of [way] from [d0] to [d1]. */
internal fun DrawScope.strokeWay(path: Path, way: Way, d0: Float, d1: Float, c: Color, stroke: Stroke) {
    if (d1 <= d0 || c.alpha < 0.004f || way.size < 2) return
    way.part(path, d0, d1)
    drawPath(path, c, style = stroke, blendMode = BlendMode.Plus)
}

/**
 * Lines sorted into brightness steps, so hundreds of them take a handful of draw calls. The steps
 * are finer at the faint end, where most lines are.
 */
private class LineBatch(cap: Int, private val steps: Int = 6, private val top: Float = 0.6f) {
    private val pts = Array(steps) { FloatArray(cap * 4) }
    private val n = IntArray(steps)

    fun clear() = n.fill(0)

    fun add(x0: Float, y0: Float, x1: Float, y1: Float, a: Float) {
        if (a < 0.006f) return
        val i = (sqrt(a / top) * steps).toInt().coerceIn(0, steps - 1)
        val arr = pts[i]
        var k = n[i]
        if (k + 4 > arr.size) return
        arr[k++] = x0
        arr[k++] = y0
        arr[k++] = x1
        arr[k++] = y1
        n[i] = k
    }

    /** Draws them in the light's colour, mixed [white] of the way to white (none below 0). */
    fun draw(scope: DrawScope, paint: Paint, width: Float, light: StatusLight, white: Float = -1f) = scope.drawIntoCanvas { c ->
        paint.strokeWidth = width
        for (i in 0 until steps) {
            if (n[i] == 0) continue
            val level = (i + 0.5f) / steps
            val a = level * level * top
            paint.color = (if (white < 0f) light.tone(a) else light.core(a, white)).toArgb()
            c.nativeCanvas.drawLines(pts[i], 0, n[i], paint)
        }
    }
}

/** Dots of one size, sorted into brightness steps like [LineBatch]. */
private class DotBatch(cap: Int, private val steps: Int = 4, private val top: Float = 1f) {
    private val pts = Array(steps) { FloatArray(cap * 2) }
    private val n = IntArray(steps)

    fun clear() = n.fill(0)

    fun add(x: Float, y: Float, a: Float) {
        if (a < 0.01f) return
        val i = (sqrt(a / top) * steps).toInt().coerceIn(0, steps - 1)
        val arr = pts[i]
        var k = n[i]
        if (k + 2 > arr.size) return
        arr[k++] = x
        arr[k++] = y
        n[i] = k
    }

    /** Draws them in the light's colour, mixed [white] of the way to white. */
    fun draw(scope: DrawScope, paint: Paint, size: Float, light: StatusLight, white: Float) = scope.drawIntoCanvas { c ->
        paint.strokeWidth = size
        for (i in 0 until steps) {
            if (n[i] == 0) continue
            val level = (i + 0.5f) / steps
            val a = level * level * top
            paint.color = light.core(a, white).toArgb()
            c.nativeCanvas.drawPoints(pts[i], 0, n[i], paint)
        }
    }
}

/** Light that adds to what is under it, with round ends: lines and dots that glow where they cross. */
internal fun addPaint() = Paint().apply {
    isAntiAlias = true
    strokeCap = Paint.Cap.ROUND
    xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
}

/** The network thins out towards the text on the left instead of ending in a hard edge. */
private fun leftFade(x: Float) = smoothstep(440f, 800f, x)

private fun dist(ax: Float, ay: Float, bx: Float, by: Float): Float {
    val dx = ax - bx
    val dy = ay - by
    return sqrt(dx * dx + dy * dy)
}

internal fun easeOut(v: Float): Float {
    val c = v.coerceIn(0f, 1f)
    return 1 - (1 - c) * (1 - c)
}

private const val TAU = (2 * PI).toFloat()

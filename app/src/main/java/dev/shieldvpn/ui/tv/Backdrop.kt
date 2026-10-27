package dev.shieldvpn.ui.tv

import android.animation.ValueAnimator
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
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
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min

/*
 * The background behind every screen, which is also the status light: a mesh of nodes, each
 * wandering on its own, with three named stops in it, this device, the VPN server and the internet
 * (the mesh itself is in Mesh.kt).
 *
 * - Connected (green): a ring from the VPN, then your traffic runs from the device up a curve of
 *   hops to the VPN, and down another from there to the internet, a packet at a time.
 * - Connecting (yellow): a head runs from the device to the VPN, the VPN answers with a ring and a
 *   reply runs back, over again. Connecting never passes through what connected looks like.
 * - Off (red): the tunnel pulls back and stays as a faint ghost; your traffic runs under the VPN,
 *   around it, to the internet, and the mesh slows, its links fading in and out as if failing.
 *   While the kill switch blocks traffic after a drop, that way stays dark and nothing moves on it.
 *
 * Each change of state plays its own beat, so a state is never one eased value between two looks.
 * The three stops stay still, and a lit way's hops hold close to its curve; the rest wanders slowly.
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
    // The mesh is laid out once a process, off the main thread: on a TV it takes a moment, and the
    // first screen shouldn't wait for it. Until then the background is plain, and then fades in.
    val layout by produceState(MeshLayout.ready(LOOSE_PATH)) {
        if (value == null) value = withContext(Dispatchers.Default) { MeshLayout.of(LOOSE_PATH) }
    }
    val scene = remember(layout) { layout?.let { BackdropScene(measurer, Mesh(it)) } }
    val active by rememberUpdatedState(scene)
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
            val s = active
            if (s == null) {
                last = now
                continue
            }
            s.step(current, stopped, min(0.05f, (now - last) / 1_000_000_000f), moving = !still)
            last = now
            frame++
            if (still && s.settled()) {
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
        val s = scene
        if (s == null) {
            drawRect(Base)
            return@Canvas
        }
        // Behind a panel or a sheet the names would read through it, out of place: they show on Connect.
        s.named = !hidden
        val unit = size.width / 1280f
        scale(unit, unit, pivot = Offset.Zero) { s.draw(this) }
    }
}

/** The mesh's hops lie a little off their curves; false is the backup, with them exactly on. */
private const val LOOSE_PATH = true

/** The background's own colour, under everything else. */
private val Base = Color(0xFF030508)

/**
 * The status light: its colour, brightness and energy (how fast and how busy it moves), eased
 * towards the state's targets so a change of state glides.
 */
internal class StatusLight {
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

/**
 * What the background shows for each state, over the [mesh]: the stops, the lit ways, the packets
 * and the beat each change of state plays. It eases between states by weights for each state, not
 * one value between two looks, so connecting never shows what connected looks like.
 */
private class BackdropScene(private val measurer: TextMeasurer, private val mesh: Mesh) {
    /** The stops are named (THIS DEVICE, VPN SERVER, INTERNET) only while the drawing is uncovered. */
    var named = true
    private val light = StatusLight()
    private val f = MeshFrame(light)

    private var shown: Mode? = null
    private var from = Mode.Off
    private var since = 99f
    private var pendingOn = false
    private var flow = 1f
    private var blocked = false

    // The handshake while connecting: 0 a short wait, 1 the sweep, 2 the VPN's answer, 3 the reply, 4 a rest.
    private var hsPhase = 0
    private var hsTime = 0f
    private var sweep = 0f
    private var sweeps = 0
    private var answeredAt = -10f
    private var vpnKick = 0f

    // How far the packets have gone since the tunnel opened or closed, in design pixels.
    private var onTravel = 0f
    private var offTravel = 0f

    // From the plain background in to the full drawing, over its first half second.
    private var appear = 0f

    init {
        mesh.update(f, 0f, false)
    }

    fun step(mode: Mode, blocked: Boolean, dt: Float, moving: Boolean) {
        f.clock += dt
        appear = if (moving) min(1f, appear + dt * 2f) else 1f
        this.blocked = blocked
        flow += ((if (blocked) 0f else 1f) - flow) * (1 - exp(-dt * 2.5f))
        val want = when (mode) {
            Mode.On -> Mode.On
            Mode.Busy -> Mode.Busy
            else -> Mode.Off
        }
        val cur = shown
        when {
            cur == null || !moving -> if (cur != want) enter(want, beats = false)
            want == cur -> pendingOn = false
            // Connected before the VPN has answered: the sweep plays first.
            want == Mode.On && cur == Mode.Off -> {
                enter(Mode.Busy, beats = true)
                pendingOn = true
            }
            want == Mode.On && cur == Mode.Busy -> if (hsPhase <= 1) pendingOn = true else enter(Mode.On, beats = true)
            else -> enter(want, beats = true)
        }
        since += dt
        val k = 1 - exp(-dt * 6f)
        f.wOff += ((if (shown == Mode.Off) 1f else 0f) - f.wOff) * k
        f.wBusy += ((if (shown == Mode.Busy) 1f else 0f) - f.wBusy) * k
        f.wOn += ((if (shown == Mode.On) 1f else 0f) - f.wOn) * k
        light.step(shown!!, dt, moving)
        vpnKick *= exp(-dt * 2.2f)
        if (shown == Mode.Busy) handshake(dt)
        if (moving) {
            onTravel += dt * ON_SPEED
            offTravel += dt * OFF_SPEED
        }
        f.l = light.light
        f.answerAge = f.clock - answeredAt
        f.openAge = if (shown == Mode.On) since else 99f
        mesh.update(f, dt, moving)
        lit()
    }

    private fun enter(m: Mode, beats: Boolean) {
        from = shown ?: m
        shown = m
        since = if (beats) 0f else 99f
        pendingOn = false
        when (m) {
            Mode.Busy -> {
                hsPhase = 0
                hsTime = 0f
                sweep = 0f
                sweeps = 0
            }
            Mode.On -> {
                if (beats) vpnKick = 1f
                onTravel = if (beats) -0.9f * ON_SPEED else 1e6f
            }
            else -> offTravel = if (beats && from != Mode.Off) -0.9f * OFF_SPEED else 1e6f
        }
        if (!beats) {
            f.wOff = if (m == Mode.Off) 1f else 0f
            f.wBusy = if (m == Mode.Busy) 1f else 0f
            f.wOn = if (m == Mode.On) 1f else 0f
        }
    }

    private fun handshake(dt: Float) {
        hsTime += dt
        when (hsPhase) {
            0 -> if (hsTime >= 0.15f) next(1)
            1 -> {
                // 0.9 s from the device to the VPN; a connect that waits on a later sweep hurries it (0.35 s at most).
                val rate = if (pendingOn && sweeps > 0) 1f / 0.35f else 1f / 0.9f
                sweep = min(1f, sweep + dt * rate)
                if (sweep >= 1f) {
                    sweeps++
                    answeredAt = f.clock
                    vpnKick = 1f
                    next(2)
                    if (pendingOn) {
                        enter(Mode.On, beats = true)
                        return
                    }
                }
            }
            2 -> if (hsTime >= 0.6f) next(3)
            3 -> if (hsTime >= 0.6f) next(4)
            4 -> if (hsTime >= 0.3f) {
                sweep = 0f
                next(1)
            }
        }
    }

    private fun next(p: Int) {
        hsPhase = p
        hsTime = 0f
    }

    /** Nothing will change until the state does. */
    fun settled(): Boolean = appear >= 1f && shown != Mode.Busy && !pendingOn && since > 3f && light.settled() &&
        abs(f.wOff - if (shown == Mode.Off) 1f else 0f) < 0.005f && abs(f.wOn - if (shown == Mode.On) 1f else 0f) < 0.005f &&
        abs(flow - if (blocked) 0f else 1f) < 0.005f

    /** How much of each way is lit for the state and the beat it is in. */
    private fun lit() {
        val lenVpn = mesh.toVpn.length
        val lenNet = mesh.toNet.length
        val lenDirect = mesh.direct.length
        val s = since
        val st = shown ?: Mode.Off
        f.tunnelTo = 0f
        f.tunnelLit = 0f
        f.netTo = 0f
        f.netLit = 0f
        f.head = -1f
        f.flash = 0f
        when (st) {
            Mode.Busy -> {
                f.tunnelTo = lenVpn
                f.tunnelLit = 0.3f
                if (hsPhase == 1) f.head = lenVpn * easeOut(sweep)
                if (hsPhase == 2) f.flash = 1 - hsTime / 0.6f
            }
            Mode.On -> {
                f.tunnelTo = lenVpn
                f.tunnelLit = 1f
                f.netTo = lenNet * if (from == Mode.Busy && s < 2f) smoothstep(0.15f, 0.9f, s) else 1f
                f.netLit = 1f
            }
            else -> if (from != Mode.Off && s < 0.5f) {
                // Pulling back: the way on towards the VPN, the tunnel towards the device.
                f.tunnelTo = lenVpn * (1 - smoothstep(0.05f, 0.35f, s))
                f.tunnelLit = if (from == Mode.On) 1f else 0.3f
                f.netTo = lenNet * (1 - smoothstep(0f, 0.3f, s))
                f.netLit = if (from == Mode.On) 1f else 0f
            }
        }
        // Off: the way around the VPN, drawn in from the device after the tunnel has gone; pulled
        // back to the device when connecting starts.
        f.directTo = when {
            st == Mode.Off -> lenDirect * if (from != Mode.Off && s < 2f) smoothstep(0.3f, 0.9f, s) else 1f
            st == Mode.Busy && from == Mode.Off && s < 0.3f -> lenDirect * (1 - smoothstep(0f, 0.3f, s))
            else -> 0f
        }
        // While the kill switch blocks traffic, that way stays dark.
        f.directLit = flow
    }

    // ---- Labels, icons, brushes -------------------------------------------------------------------

    private var labels: List<TextLayoutResult>? = null

    private fun labels(): List<TextLayoutResult> = labels ?: run {
        val style = TextStyle(fontSize = 12.5.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.14.em, color = Color.White)
        listOf("THIS DEVICE", "VPN SERVER", "INTERNET").map { measurer.measure(it, style, density = Density(1f)) }.also { labels = it }
    }

    private val deviceIcon = icon(Ic.Apps)
    private val vpnIcon = icon(Ic.Shield)
    private val netIcon = icon(Ic.Globe)

    private fun icon(ic: Ic): List<Path> = ic.paths.map { PathParser().parsePathString(it).toPath() }

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
        deviceGlow = Brush.radialGradient(listOf(light.core(0.5f * l), light.tone(0f)), mesh.device, 70f)
        vpnGlow = Brush.radialGradient(listOf(light.core(0.6f * l), light.tone(0f)), mesh.vpn, 100f)
        netGlow = Brush.radialGradient(listOf(light.core(0.5f * l), light.tone(0f)), mesh.net, 80f)
        field = Brush.radialGradient(listOf(light.tone(0.12f * l), light.tone(0f)), FieldAt, 480f)
    }

    private val way = Path()

    // ---- Drawing -------------------------------------------------------------------------------

    fun draw(scope: DrawScope) = with(scope) {
        brushes()
        drawRect(Base)
        drawCircle(field, radius = 480f, center = FieldAt)
        mesh.draw(this, f)
        drawWays()
        mesh.drawOver(this, f)
        drawStops()
        drawRect(vignette)
        if (appear < 1f) drawRect(Base, alpha = 1 - appear)
    }

    private fun DrawScope.drawWays() {
        val l = f.l
        val tv = mesh.toVpn
        val tn = mesh.toNet
        val dr = mesh.direct
        val st = shown ?: Mode.Off
        val s = since
        // The dead tunnel, faint, while off.
        if (f.wOff > 0.02f) {
            strokeWay(way, tv, 0f, tv.length, light.tone(0.05f * l * f.wOff), Glow6)
            strokeWay(way, tn, 0f, tn.length, light.tone(0.04f * l * f.wOff), Glow4)
        }
        if (f.tunnelTo > 0.5f) {
            strokeWay(way, tv, 0f, f.tunnelTo, light.tone(0.14f * l * f.tunnelLit), Glow11)
            strokeWay(way, tv, 0f, f.tunnelTo, light.core(0.65f * l * f.tunnelLit, 0.4f), Line24)
        }
        if (f.netTo > 0.5f && f.netLit > 0f) {
            strokeWay(way, tn, 0f, f.netTo, light.tone(0.1f * l * f.netLit), Glow6)
            strokeWay(way, tn, 0f, f.netTo, light.core(0.5f * l * f.netLit, 0.4f), Line16)
            if (st == Mode.On && f.netTo < tn.length - 1f) spark(tn.at(f.netTo), l)
        }
        if (st == Mode.Busy) {
            if (f.head >= 0f) {
                strokeWay(way, tv, 0f, f.head, light.tone(0.16f * l), Glow11)
                strokeWay(way, tv, 0f, f.head, light.core(0.7f * l, 0.45f), Line24)
                spark(tv.at(f.head), l)
            }
            // Lit whole as the VPN answers, then back down to the resting 30 %.
            if (f.flash > 0f) strokeWay(way, tv, 0f, tv.length, light.core(0.7f * l * f.flash, 0.45f), Line24)
            if (hsPhase == 3) {
                val at = tv.at(tv.length * (1 - hsTime / 0.6f))
                drawCircle(light.tone(0.2f * l), radius = 9f, center = at, blendMode = BlendMode.Plus)
                drawCircle(light.core(0.6f * l, 0.7f), radius = 3f, center = at, blendMode = BlendMode.Plus)
            }
        }
        if (st == Mode.On) {
            // A wave back along the tunnel from the VPN: it worked.
            if (from == Mode.Busy && s in 0.15f..0.65f) {
                val at = tv.length * (1 - (s - 0.15f) / 0.5f)
                strokeWay(way, tv, (at - 28f).coerceAtLeast(0f), (at + 28f).coerceAtMost(tv.length), light.core(0.7f * l, 0.7f), Glow4)
            }
            val lenVpn = tv.length
            packets(l, lenVpn + tn.length, onTravel, 4, 1f) { d -> if (d <= lenVpn) tv.at(d) else tn.at(d - lenVpn) }
        }
        if (f.directTo > 0.5f) {
            val open = f.directLit
            strokeWay(way, dr, 0f, f.directTo, light.tone(0.16f * l * (0.35f + 0.65f * open)), Glow10)
            // A redder core than the tunnel's: red loses most of its weight when mixed towards white.
            strokeWay(way, dr, 0f, f.directTo, light.core((0.25f + 0.5f * open) * l, 0.12f), Line24)
            if (st == Mode.Off && open > 0.02f) packets(l * open, dr.length, offTravel, 3, f.directTo / dr.length) { d -> dr.at(d) }
        }
    }

    /**
     * [n] packets evenly spaced along a way [total] long, let out one at a time from its start
     * ([travel] counts up from below zero when the way opens), only on its drawn part.
     */
    private inline fun DrawScope.packets(a: Float, total: Float, travel: Float, n: Int, drawnPart: Float, at: (Float) -> Offset) {
        if (total < 1f) return
        val gap = total / n
        for (i in 0 until n) {
            val d0 = travel - i * gap
            if (d0 < 0f) continue
            val d = d0 % total
            if (d > total * drawnPart) continue
            for (s in 3 downTo 1) {
                if (d - s * 7f > 0f) drawCircle(light.core(0.4f * a / (s + 1), 0.5f), radius = 3f - s * 0.5f, center = at(d - s * 7f), blendMode = BlendMode.Plus)
            }
            drawCircle(light.tone(0.22f * a), radius = 12f, center = at(d), blendMode = BlendMode.Plus)
            drawCircle(light.core(0.95f * a, 0.7f), radius = 3.2f, center = at(d), blendMode = BlendMode.Plus)
        }
    }

    private fun DrawScope.drawStops() {
        val l = f.l
        val s = since
        val st = shown ?: Mode.Off
        // Lit while in use, flaring at each answer; off, it drops quickly to its resting quarter.
        val vpnLit = ((0.25f + 0.75f * f.wOn) + f.wBusy * (0.35f + 0.4f * vpnKick) + 0.3f * vpnKick * f.wOn).coerceIn(0f, 1f)
        drawCircle(deviceGlow, radius = 70f, center = mesh.device, blendMode = BlendMode.Plus)
        drawCircle(vpnGlow, radius = 100f, center = mesh.vpn, alpha = vpnLit, blendMode = BlendMode.Plus)
        drawCircle(netGlow, radius = 80f, center = mesh.net, blendMode = BlendMode.Plus)
        // The rings that mark each beat.
        if (st == Mode.Busy && from == Mode.Off) ring(mesh.device, s, 0.4f, 24f, 62f, 0.7f, l)
        if (st == Mode.Busy && hsPhase == 2) ring(mesh.vpn, hsTime, 0.6f, 24f, 90f, 0.8f, l)
        if (st == Mode.On && from == Mode.Busy) {
            ring(mesh.vpn, s, 0.7f, 24f, 110f, 0.9f, l)
            ring(mesh.net, s - 0.9f, 0.5f, 24f, 60f, 0.7f, l)
        }
        if (st == Mode.Off && from != Mode.Off) ring(mesh.net, s - 0.9f, 0.5f, 24f, 60f, 0.7f, l)
        stop(mesh.device, deviceIcon, 1f, l)
        stop(mesh.vpn, vpnIcon, vpnLit, l)
        stop(mesh.net, netIcon, 1f, l)
        if (!named) return
        val names = labels()
        label(names[0], mesh.device.x, mesh.device.y + 34f, 1f)
        // Held steady: a label that pulses with the VPN reads as flicker.
        label(names[1], mesh.vpn.x, mesh.vpn.y - 36f - names[1].size.height, if (st == Mode.Off) 0.55f else 0.85f)
        label(names[2], mesh.net.x, mesh.net.y + 34f, 1f)
    }

    /** A ring from [c], [age] seconds into [life], growing from [r0] to [r1]. */
    private fun DrawScope.ring(c: Offset, age: Float, life: Float, r0: Float, r1: Float, a: Float, l: Float) {
        if (age < 0f || age > life) return
        val k = 1 - age / life
        drawCircle(light.core(a * l * k * k, 0.5f), radius = r0 + (r1 - r0) * easeOut(age / life), center = c, style = Ring, blendMode = BlendMode.Plus)
    }

    private fun DrawScope.stop(c: Offset, icon: List<Path>, lit: Float, l: Float) {
        drawCircle(Color(0xFF05080C), radius = 23f, center = c)
        drawCircle(light.core(0.9f * lit * l, 0.45f), radius = 23f, center = c, style = Ring, blendMode = BlendMode.Plus)
        translate(c.x - 11f, c.y - 11f) {
            scale(22f / 24f, pivot = Offset.Zero) {
                icon.forEach { drawPath(it, light.core((0.35f + 0.6f * lit) * l, 0.55f), style = IconStroke) }
            }
        }
    }

    private fun DrawScope.label(text: TextLayoutResult, cx: Float, top: Float, a: Float) {
        drawText(text, color = Color.White.copy(alpha = 0.62f * a.coerceAtLeast(0.55f)), topLeft = Offset(cx - text.size.width / 2f, top))
    }

    private fun DrawScope.spark(c: Offset, a: Float) {
        drawCircle(light.tone(0.3f * a), radius = 18f, center = c, blendMode = BlendMode.Plus)
        drawCircle(light.core(a, 0.8f), radius = 4.4f, center = c, blendMode = BlendMode.Plus)
    }

    private companion object {
        const val ON_SPEED = 260f
        const val OFF_SPEED = 190f
        val IconStroke = Stroke(width = 2.1f, cap = StrokeCap.Round, join = StrokeJoin.Round)

        /** The centre of the faint field of the state's colour behind the mesh. */
        val FieldAt = Offset(900f, 360f)
        val Ring = Stroke(2.2f)

        // The ways' strokes, made once instead of on every frame.
        val Glow11 = roundStroke(11f)
        val Glow10 = roundStroke(10f)
        val Glow6 = roundStroke(6f)
        val Glow4 = roundStroke(4f)
        val Line24 = roundStroke(2.4f)
        val Line16 = roundStroke(1.6f)
    }
}

internal fun smoothstep(a: Float, b: Float, x: Float): Float {
    val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
    return t * t * (3 - 2 * t)
}

/**
 * The design's xorshift generator: the same seed lays things out the same way every time. A class,
 * not a lambda, so a number comes back as a plain float, not a boxed one: laying out the mesh asks
 * for some hundred thousand.
 */
internal class SeededRandom(seed: Int) {
    private var s = ((seed.toLong() * 2654435761L) and 0xFFFFFFFFL).let { if (it == 0L) 1L else it }

    /** The next number, from 0 up to 1. */
    operator fun invoke(): Float {
        s = s xor ((s shl 13) and 0xFFFFFFFFL)
        s = s xor (s ushr 17)
        s = s xor ((s shl 5) and 0xFFFFFFFFL)
        return s / 4294967296f
    }
}

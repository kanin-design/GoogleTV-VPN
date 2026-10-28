package dev.shieldvpn.ui.tv

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import dev.shieldvpn.APP_NAME
import dev.shieldvpn.vpn.SpeedTest
import dev.shieldvpn.vpn.VpnStatus
import dev.shieldvpn.vpn.VpnUiState
import dev.shieldvpn.vpn.isActive
import kotlinx.coroutines.CancellationException
import kotlin.math.cos
import kotlin.math.sin

private enum class Stage { Ask, Connecting, Testing, Done, Failed }

/** The speedometer's marks, each the same angle apart, so slow and fast lines both read. */
private val Scale = listOf(0.0, 5.0, 10.0, 25.0, 50.0, 100.0, 250.0, 500.0)
private const val START = 150f
private const val SWEEP = 240f

/** Where [mbps] sits on the gauge, 0 to 1. */
private fun gaugeFraction(mbps: Double): Float {
    if (mbps <= 0) return 0f
    val i = Scale.indexOfFirst { it >= mbps }
    if (i < 0) return 1f
    val lo = Scale[i - 1]
    val hi = Scale[i]
    return ((i - 1) + (mbps - lo) / (hi - lo)).toFloat() / (Scale.size - 1)
}

fun fmtMbps(mbps: Double): String = if (mbps < 10) "%.1f".format(mbps) else "%.0f".format(mbps)

/** What a download speed is good for, as one line and the tone it is shown in. */
fun speedVerdict(downMbps: Double): Pair<String, Color> = when {
    downMbps >= 25 -> "Fast enough for 4K" to Tv.Mint
    downMbps >= 8 -> "Good for HD, not 4K" to Tv.Amber
    downMbps >= 3 -> "Enough for SD only" to Tv.Amber
    else -> "Too slow to stream smoothly" to Tv.Red
}

/**
 * Tests how fast a profile is: connects to it first when the user agrees, then measures ping,
 * download and upload through the tunnel, and says what that is good for.
 */
@Composable
fun SpeedSheet(state: VpnUiState, actions: TunnelActions, req: Requesters, o: Overlay.Speed, connect: (String) -> Unit, close: () -> Unit) {
    val c = state.connection(o.id) ?: return
    val here = state.activeId == c.id && state.status is VpnStatus.Connected
    val other = state.status.isActive && !here
    var stage by remember { mutableStateOf(if (here) Stage.Testing else Stage.Ask) }
    var direct by remember { mutableStateOf(false) }
    var run by remember { mutableIntStateOf(if (here) 1 else 0) }
    var phase by remember { mutableStateOf<SpeedTest.Phase?>(null) }
    var live by remember { mutableDoubleStateOf(0.0) }
    var ping by remember { mutableStateOf<Int?>(null) }
    var down by remember { mutableStateOf<Double?>(null) }
    var up by remember { mutableStateOf<Double?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val test: (Boolean) -> Unit = { withoutVpn ->
        direct = withoutVpn
        stage = Stage.Testing
        run++
    }
    // Waiting for the tunnel: test once it is up, or say why it did not come up.
    LaunchedEffect(stage, here, state.status) {
        if (stage != Stage.Connecting) return@LaunchedEffect
        val s = state.status
        when {
            here -> test(false)
            s is VpnStatus.Failed && s.connectionId == c.id -> {
                error = s.message
                stage = Stage.Failed
            }
        }
    }
    LaunchedEffect(run) {
        if (run == 0) return@LaunchedEffect
        phase = null
        live = 0.0
        ping = null
        down = null
        up = null
        error = null
        try {
            val r = SpeedTest.run { p, v ->
                phase = p
                live = v
                when (p) {
                    SpeedTest.Phase.Ping -> if (v > 0) ping = v.toInt()
                    SpeedTest.Phase.Down -> down = v
                    SpeedTest.Phase.Up -> up = v
                }
            }
            ping = r.pingMs
            down = r.downMbps
            up = r.upMbps
            actions.saveSpeed(if (direct) SpeedTest.DIRECT else c.id, r)
            phase = null
            stage = Stage.Done
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = if (direct) "No answer from the speed test. Is this device online?" else "No answer through the tunnel. ${e.message.orEmpty()}".trim()
            phase = null
            stage = Stage.Failed
        }
    }
    // Testing here would switch away from the live tunnel, so focus starts on Close, not on the switch.
    LaunchedEffect(stage) { req[if (stage == Stage.Ask && other) "ov-close" else "ov-first"].focusWhenReady() }

    val last = state.speeds[if (direct) SpeedTest.DIRECT else c.id]
    val sub = when {
        stage == Stage.Ask && other -> "You are connected to ${state.active?.name ?: "another profile"}. ${APP_NAME} switches to ${c.name} for the test."
        stage == Stage.Ask -> "Connect to see how fast it is. The test takes about 20 seconds."
        stage == Stage.Connecting -> "Connecting to ${c.name}…"
        direct -> "Your connection without the VPN, to compare with."
        else -> "Through the VPN to Cloudflare’s nearest server."
    }

    Sheet(TvSize.SheetLarge) {
        // Where it was opened, as every other sheet's eyebrow says.
        Text("${o.eyebrow} › Speed test", style = TvType.eyebrow(15f, 0.6f))
        Text(if (direct) "Without the VPN" else c.name, style = TvType.display(32f), modifier = Modifier.padding(top = 4.dp))
        Text(sub, style = TvType.ui(17f, color = Tv.soft(0.62f)), maxLines = 1, modifier = Modifier.padding(top = 8.dp))
        Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            val tone = if (phase == SpeedTest.Phase.Up) Tv.Blue else Tv.Mint
            val value = when {
                phase == SpeedTest.Phase.Down || phase == SpeedTest.Phase.Up -> live
                stage == Stage.Done -> down ?: 0.0
                else -> 0.0
            }
            Gauge(value, tone) {
                when {
                    stage == Stage.Connecting || (stage == Stage.Testing && phase == null) -> Spinner(size = 34f)
                    phase == SpeedTest.Phase.Ping -> GaugeReadout("Ping", ping?.toString() ?: "…", "ms")
                    phase != null -> GaugeReadout(if (phase == SpeedTest.Phase.Down) "Download" else "Upload", fmtMbps(live), "Mbps")
                    stage == Stage.Done -> GaugeReadout("Download", fmtMbps(down ?: 0.0), "Mbps")
                    else -> GaugeReadout("", "—", "Mbps")
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val showLast = stage == Stage.Ask && last != null
                Stat("Ping", (if (showLast) last!!.pingMs else ping)?.let { "$it ms" }, phase == SpeedTest.Phase.Ping)
                Stat("Download", (if (showLast) last!!.downMbps else down)?.let { "${fmtMbps(it)} Mbps" }, phase == SpeedTest.Phase.Down)
                Stat("Upload", (if (showLast) last!!.upMbps else up)?.let { "${fmtMbps(it)} Mbps" }, phase == SpeedTest.Phase.Up)
                if (showLast) Text("From the last test, ${ago(last!!.at)}", style = TvType.ui(15f, color = Tv.soft(0.6f)), modifier = Modifier.padding(start = 4.dp))
            }
        }
        Verdict(stage, down, up, error, if (direct) null else state.speeds[SpeedTest.DIRECT])
        Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val (label, icon, primary) = when (stage) {
                // One button whose text changes, so focus stays on it as the test moves on.
                Stage.Ask -> Triple<String, Ic?, () -> Unit>(if (other) "Switch to ${c.name} and test" else "Connect and test", Ic.Power) {
                    stage = Stage.Connecting
                    connect(c.id)
                }
                Stage.Connecting, Stage.Testing -> Triple("Close", null, close)
                Stage.Done -> Triple("Close", null, close)
                Stage.Failed -> Triple("Try again", Ic.Swap) {
                    if (direct || here) {
                        test(direct)
                    } else {
                        stage = Stage.Connecting
                        connect(c.id)
                    }
                }
            }
            TvButton(label, primary, icon = icon, focusRequester = req["ov-first"])
            when (stage) {
                Stage.Ask -> {
                    if (!state.status.isActive) TvButton("Test without the VPN", { test(true) }, icon = Ic.Globe, glass = true)
                    TvButton("Close", close, glass = true, focusRequester = req["ov-close"])
                }
                Stage.Done -> TvButton("Test again", { test(direct) }, icon = Ic.Swap, glass = true)
                Stage.Failed -> TvButton("Close", close, glass = true)
                else -> Unit
            }
        }
    }
}

/** The speedometer: a 240° track, the speed lit along it in [tone], and [centre] inside. */
@Composable
private fun Gauge(mbps: Double, tone: Color, centre: @Composable () -> Unit) {
    val target = gaugeFraction(mbps)
    val shown by animateFloatAsState(target, tween(if (target == 0f) 420 else 240, easing = LinearEasing), label = "gauge")
    val measurer = rememberTextMeasurer()
    val labels = remember(measurer) {
        val style = TextStyle(fontFamily = TvType.Ui, fontSize = TvType.ui(15f).fontSize, fontWeight = FontWeight.Medium, color = Tv.soft(0.42f))
        Scale.map { measurer.measure(if (it >= 500) "500+" else it.toInt().toString(), style) }
    }
    Box(Modifier.size(300.dp, 262.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = 14.dp.toPx()
            val r = 124.dp.toPx()
            val c = Offset(size.width / 2, 140.dp.toPx())
            val box = Size(r * 2, r * 2)
            val tl = Offset(c.x - r, c.y - r)
            drawArc(Tv.white(0.08f), START, SWEEP, false, tl, box, style = Stroke(w, cap = StrokeCap.Round))
            if (shown > 0.002f) {
                // A wide faint pass under the lit arc reads as glow without an offscreen blur.
                drawArc(tone.copy(alpha = 0.12f), START, SWEEP * shown, false, tl, box, style = Stroke(w * 2.4f, cap = StrokeCap.Round))
                drawArc(tone, START, SWEEP * shown, false, tl, box, style = Stroke(w, cap = StrokeCap.Round))
                val a = Math.toRadians((START + SWEEP * shown).toDouble())
                val tip = Offset(c.x + r * cos(a).toFloat(), c.y + r * sin(a).toFloat())
                drawCircle(Color.White, w * 0.32f, tip)
            }
            labels.forEachIndexed { i, label ->
                val a = Math.toRadians((START + SWEEP * i / (Scale.size - 1)).toDouble())
                val lr = r - w - 18.dp.toPx()
                val p = Offset(c.x + lr * cos(a).toFloat(), c.y + lr * sin(a).toFloat())
                drawText(label, topLeft = Offset(p.x - label.size.width / 2f, p.y - label.size.height / 2f))
            }
        }
        Box(Modifier.padding(top = 16.dp), contentAlignment = Alignment.Center) { centre() }
    }
}

@Composable
private fun GaugeReadout(label: String, value: String, unit: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = TvType.eyebrow(15f, 0.55f))
        Text(value, style = TvType.display(TvType.Gauge), modifier = Modifier.padding(top = 2.dp))
        Text(unit, style = TvType.ui(17f, FontWeight.SemiBold, Tv.soft(0.55f)))
    }
}

@Composable
private fun Stat(label: String, value: String?, running: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(TvSize.Row.dp)
            .clip(RoundedCornerShape(TvRadius.Small.dp))
            .background(Tv.white(if (running) 0.09f else 0.05f))
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = TvType.ui(17f, FontWeight.SemiBold, Tv.soft(if (value != null || running) 0.9f else 0.5f)), modifier = Modifier.weight(1f))
        if (running && value == null) {
            Spinner(size = 18f)
        } else {
            Text(value ?: "—", style = TvType.ui(19f, FontWeight.SemiBold, if (value != null) Tv.Ink else Tv.soft(0.35f)))
        }
    }
}

/** What the result means, or why there is none. With [baseline], how much of the plain connection the VPN keeps. */
@Composable
private fun Verdict(stage: Stage, down: Double?, up: Double?, error: String?, baseline: SpeedTest.Result?) {
    val (title, tone, detail) = when {
        stage == Stage.Failed -> Triple("The test did not finish", Tv.Red, error.orEmpty())
        stage == Stage.Done && down != null -> {
            val (t, tone) = speedVerdict(down)
            val keeps = baseline?.takeIf { it.downMbps > 0 }?.let { b ->
                " That is ${(down / b.downMbps * 100).toInt().coerceAtMost(100)} % of the ${fmtMbps(b.downMbps)} Mbps measured without the VPN ${ago(b.at)}."
            }
            // Streaming barely uploads, but video calls and sending files feel a slow upload.
            val slowUp = if (up != null && up < 2) " Uploads are slow, which streaming does not mind." else ""
            Triple(t, tone, "4K needs about 25 Mbps, HD about 8." + keeps.orEmpty() + slowUp)
        }
        else -> Triple(null, Tv.soft(0.5f), "4K needs about 25 Mbps, HD about 8.")
    }
    Row(
        modifier = Modifier
            // Close under the gauge, whose box is taller than the arc: with it the sheet fits the panels' box.
            .padding(top = 8.dp)
            .fillMaxWidth()
            // Two lines tall from the start, so the sheet does not grow when the result arrives.
            .heightIn(min = TvSize.RowTall.dp)
            .clip(RoundedCornerShape(TvRadius.Small.dp))
            .background(if (title != null) tone.copy(alpha = 0.1f) else Tv.white(0.04f))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (title != null) {
            Box(Modifier.size(28.dp).clip(CircleShape).background(tone), contentAlignment = Alignment.Center) {
                TvIcon(if (tone == Tv.Mint) Ic.Check else Ic.Bang, 15f, Color(0xFF06140D), stroke = 3f)
            }
        }
        Column(Modifier.weight(1f)) {
            if (title != null) Text(title, style = TvType.ui(17f, FontWeight.SemiBold))
            Text(detail, style = TvType.ui(15f, color = Tv.soft(0.62f)), maxLines = 2)
        }
    }
}

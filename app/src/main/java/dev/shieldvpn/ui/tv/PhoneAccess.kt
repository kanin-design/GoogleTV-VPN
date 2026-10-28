package dev.shieldvpn.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.shieldvpn.APP_NAME
import dev.shieldvpn.control.Words
import dev.shieldvpn.http.HttpServer
import dev.shieldvpn.remote.Asks
import dev.shieldvpn.remote.Device
import dev.shieldvpn.remote.Devices
import dev.shieldvpn.remote.Pairing
import kotlinx.coroutines.delay

/** Holds the server while the pairing sheet, or a question it led to, is open. */
const val PAIRING_HOLDER = "pairing"

/** Holds the server while the app is open with phones paired. */
const val PANEL_HOLDER = "panel while the app is open"

/** "Paired 3 days ago · seen just now", for the paired phones' rows. */
fun deviceLine(d: Device, now: Long = System.currentTimeMillis()): String =
    "Paired ${Words.ago(d.paired, now)} · last seen ${Words.ago(maxOf(d.seen, Devices.lastSeen(d.id)), now)}"

/**
 * Settings › Phone access › Pair a phone: the QR code for the panel, with the one-time secret in
 * it, for five minutes. The phone then asks to pair, and the TV asks "Pair Pixel 8?".
 */
@Composable
fun PairSheet(nav: Nav, req: Requesters, o: Overlay.PairPhone, close: () -> Unit) {
    val offer by Pairing.offer.collectAsState()
    val result by Pairing.result.collectAsState()
    val address = HttpServer.address
    val current = offer?.takeIf { it.id == o.offerId }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(current) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val done = result?.takeIf { it.first == o.offerId }?.second
    LaunchedEffect(done) {
        if (done is Pairing.Result.Paired) {
            nav.toast("${done.device.name} is paired and can open the panel from now on")
            close()
        }
    }
    val left = current?.let { ((it.until - now) / 1000).coerceAtLeast(0) } ?: 0
    Sheet(TvSize.SheetMedium) {
        PanelHeading(
            "Settings › Phone access", "Pair a phone",
            "Then the phone can see and change everything here from its browser: profiles, settings, the log. Only on your home network.",
            titleSize = 32f,
        )
        Row(Modifier.padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(32.dp), verticalAlignment = Alignment.CenterVertically) {
            val qrShadow = Modifier.shadow(10.dp, RoundedCornerShape(TvRadius.Small.dp), ambientColor = Color.Black, spotColor = Color.Black)
            if (current != null && address != null && left > 0 && done == null) {
                QrCode(Pairing.link(address, current), 210f, qrShadow)
            } else {
                Box(Modifier.size(210.dp).clip(RoundedCornerShape(TvRadius.Small.dp)).background(Tv.white(0.06f)), contentAlignment = Alignment.Center) {
                    Text(if (address == null) "No home network" else "Code ended", style = TvType.ui(17f, color = Tv.soft(0.6f)))
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf(
                    "Scan the code with the phone’s camera, on the same Wi-Fi",
                    "Check the phone’s name there, then press Pair",
                    "Press Pair here when the TV asks",
                ).forEachIndexed { i, step ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.size(28.dp).clip(CircleShape).background(Tv.white(0.12f)), contentAlignment = Alignment.Center) {
                            Text("${i + 1}", style = TvType.ui(15f, FontWeight.SemiBold))
                        }
                        Text(step, style = TvType.ui(17f, color = Tv.soft(0.86f)), modifier = Modifier.widthIn(max = 380.dp))
                    }
                }
                Text(
                    when {
                        address == null -> NO_HOME_NETWORK
                        done == Pairing.Result.Refused -> "Not paired. Show a new code to try again."
                        left > 0 -> "The code works once, for ${left / 60}:${"%02d".format(left % 60)} more."
                        else -> "The code has ended. Show a new one."
                    },
                    style = TvType.ui(15f, color = if (done == Pairing.Result.Refused) Tv.Amber else Tv.soft(0.6f)),
                    modifier = Modifier.padding(top = 8.dp).widthIn(max = 420.dp),
                )
            }
        }
        Row(Modifier.padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvButton(if (done is Pairing.Result.Paired) "Done" else "Close", close, icon = if (done is Pairing.Result.Paired) Ic.Check else null, focusRequester = req["ov-first"])
            if (left == 0L || done != null) {
                TvButton("Show a new code", { nav.overlay = Overlay.PairPhone(Pairing.show().id) }, icon = Ic.Swap, glass = true)
            }
        }
    }
}

/**
 * A phone's question for the remote: pairing, or a change that needs the TV's OK. A change's
 * question starts on Not now, so a press meant for something else doesn't allow it.
 */
@Composable
fun AskSheet(req: Requesters, o: Overlay.Ask, answer: (Boolean) -> Unit) {
    val a = o.ask
    Sheet(TvSize.SheetMedium) {
        PanelHeading(a.eyebrow, a.title, a.text)
        Row(Modifier.padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (a.danger) {
                TvButton(a.no, { answer(false) }, focusRequester = req["ov-first"])
                TvButton(a.yes, { answer(true) }, icon = Ic.Check, glass = true, danger = true)
            } else {
                TvButton(a.yes, { answer(true) }, icon = Ic.Check, focusRequester = req["ov-first"])
                TvButton(a.no, { answer(false) }, glass = true)
            }
        }
        Text(
            if (a.paired) "Asked from a paired phone. Phones are listed in Settings › Phone access, where they can be removed."
            else "Only a phone that scanned the code on this TV can ask this.",
            style = TvType.ui(15f, color = Tv.soft(0.6f)),
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}

/** What Back and the remote's answer do with a question: say no, and go back to the sheet it covered. */
fun answerAsk(nav: Nav, o: Overlay.Ask, yes: Boolean) {
    Asks.answer(o.ask.id, yes)
    nav.overlay = o.back
    // With no sheet under it, focus goes back to where it was when the question came.
    if (o.back == null) nav.refocusOpener()
}

/** Words for the panel's place in Settings: shown once phones are paired. */
fun phoneAccessLine(count: Int) = when (count) {
    0 -> "No phone is paired yet"
    1 -> "1 phone is paired"
    else -> "$count phones are paired"
} + " · $APP_NAME’s panel, in the phone’s browser"

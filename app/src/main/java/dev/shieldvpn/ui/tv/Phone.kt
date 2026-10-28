package dev.shieldvpn.ui.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import dev.shieldvpn.phone.PhoneFile
import dev.shieldvpn.phone.PhoneForm
import dev.shieldvpn.phone.PhoneReply
import dev.shieldvpn.phone.PhoneServer
import dev.shieldvpn.phone.PhoneSession
import dev.shieldvpn.vpn.VpnUiState
import kotlinx.coroutines.delay

/** The app's one phone server, while its screens are in front. */
val LocalPhoneServer = staticCompositionLocalOf<PhoneServer?> { null }

/** The one sentence for a TV the phone can't reach. */
const val NO_HOME_NETWORK = "This device isn’t on a home network, so your phone can’t reach it."

private const val SAME_WIFI = "Your phone must be on the same Wi-Fi. Turn off mobile data or a VPN on it."

/** Where a phone page's actions are refused while the PIN locks profiles and settings. */
const val LOCKED_ON_TV = "Profiles and settings are locked with a PIN on the TV. Unlock them there first."

/**
 * The phone session of screen [key] while the caller is on screen. The page is built by [form]
 * from the app's state: on every change on the TV, and again after each request from the phone,
 * also when the screen has gone since. [onAction], [onUpload] and [download] answer the phone on
 * the main thread, with the session as receiver. While [locked] is true they answer that the PIN
 * locks them. Leaving the screen tells the phone the TV moved on; coming back within two minutes
 * keeps the code. With [keepsWorking], the page goes on working for a while after the screen has
 * gone. Null while there is no phone server.
 */
@Composable
fun rememberPhoneSession(
    key: String,
    state: VpnUiState,
    actions: TunnelActions,
    form: PhoneSession.(VpnUiState) -> PhoneForm?,
    keepsWorking: Boolean = false,
    locked: () -> Boolean = { false },
    onAction: suspend PhoneSession.(action: String, values: Map<String, String>) -> PhoneReply = { _, _ -> PhoneReply() },
    onUpload: suspend PhoneSession.(field: String, name: String, bytes: ByteArray) -> PhoneReply = { field, _, _ -> PhoneReply.error(field, "This page takes no files.") },
    download: suspend PhoneSession.(id: String) -> PhoneFile? = { null },
    onDownloaded: PhoneSession.(id: String, name: String) -> Unit = { _, _ -> },
): PhoneSession? {
    val server = LocalPhoneServer.current ?: return null
    val session = remember(server, key) { server.session(key) }
    val build by rememberUpdatedState(form)
    val isLocked by rememberUpdatedState(locked)
    val act by rememberUpdatedState(onAction)
    val upload by rememberUpdatedState(onUpload)
    val file by rememberUpdatedState(download)
    val downloaded by rememberUpdatedState(onDownloaded)
    DisposableEffect(session) {
        session.keepsWorking = keepsWorking
        session.onAction = { a, v -> if (isLocked()) PhoneReply.error(null, LOCKED_ON_TV) else act(a, v) }
        session.onUpload = { f, n, b -> if (isLocked()) PhoneReply.error(f, LOCKED_ON_TV) else upload(f, n, b) }
        session.download = { id -> if (isLocked()) PhoneFile.refused(LOCKED_ON_TV) else file(id) }
        session.onDownloaded = { id, name -> downloaded(id, name) }
        session.rebuild = { build(session, actions.stateNow())?.let(session::update) }
        server.hold(session)
        onDispose { server.release(session) }
    }
    SideEffect { build(session, state)?.let(session::update) }
    return session
}

/** Screen [key]'s session if it is open, for a sheet that only shows its code. */
@Composable
fun phoneSession(key: String?): PhoneSession? {
    val server = LocalPhoneServer.current ?: return null
    return key?.let { server.existing(it) }
}

enum class PhoneBlockSize {
    /** Under a panel's heading: a big code with steps beside it. */
    Panel,

    /** The right-hand column of a sheet. */
    Side,

    /** A smaller code under a panel's rows. */
    Compact,

    /** The big code with everything beside it, for a screen with little height to spare. */
    Wide,
}

/**
 * The one way a phone is offered: the QR code, then "No camera? Open http://… and type 123 456",
 * the same-Wi-Fi hint, and a line saying what the phone did last.
 */
@Composable
fun PhoneBlock(session: PhoneSession?, size: PhoneBlockSize, modifier: Modifier = Modifier, title: String? = null, steps: List<String> = emptyList(), qr: Float? = null) {
    val address = LocalPhoneServer.current?.address
    if (session == null || address == null) {
        Box(modifier) { Notice(Ic.Bang, NO_HOME_NETWORK, amber = true) }
        return
    }
    val url = LocalPhoneServer.current?.url(session) ?: return
    val code = session.code.chunked(3).joinToString(" ")
    // A paired phone has the whole app in its panel: the code is for any other device.
    val paired by dev.shieldvpn.remote.Devices.list.collectAsState()
    val panelLine = when (paired.size) {
        0 -> null
        1 -> "${paired.first().name} is paired: its phone panel does this too."
        else -> "A paired phone does this in its phone panel too."
    }
    val qrShadow = Modifier.shadow(10.dp, RoundedCornerShape(TvRadius.Small.dp), ambientColor = Color.Black, spotColor = Color.Black)
    when (size) {
        PhoneBlockSize.Panel -> Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(32.dp), verticalAlignment = Alignment.CenterVertically) {
                QrCode(url, 196f, qrShadow)
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (title != null) Text(title, style = TvType.ui(19f, FontWeight.SemiBold))
                    steps.forEachIndexed { i, step ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Box(Modifier.size(28.dp).clip(CircleShape).background(Tv.white(0.12f)), contentAlignment = Alignment.Center) {
                                Text("${i + 1}", style = TvType.ui(15f, FontWeight.SemiBold))
                            }
                            Text(step, style = TvType.ui(17f, color = Tv.soft(0.86f)), modifier = Modifier.widthIn(max = 360.dp))
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                AddressPart("No camera? Open on any device", "http://$address")
                AddressPart("And type", code, big = true)
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (panelLine != null) Text(panelLine, style = TvType.ui(15f, color = Tv.soft(0.7f)))
                Text(SAME_WIFI, style = TvType.ui(15f, color = Tv.soft(0.6f)))
                PhoneStatusLine(session)
            }
        }
        PhoneBlockSize.Side -> Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title ?: "Or use your phone", style = TvType.ui(17f, FontWeight.SemiBold))
            QrCode(url, 180f)
            // The column is narrow: the address at the data size, so a long one stays on one line.
            AddressPart("No camera? Open on any device", "http://$address", size = 17f)
            AddressPart("And type", code, big = true)
            if (panelLine != null) Text(panelLine, style = TvType.ui(15f, color = Tv.soft(0.7f)))
            Text(SAME_WIFI, style = TvType.ui(15f, color = Tv.soft(0.6f)))
            PhoneStatusLine(session)
        }
        PhoneBlockSize.Wide -> Row(modifier, horizontalArrangement = Arrangement.spacedBy(32.dp), verticalAlignment = Alignment.CenterVertically) {
            QrCode(url, 196f, qrShadow)
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                steps.forEachIndexed { i, step ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(28.dp).clip(CircleShape).background(Tv.white(0.12f)), contentAlignment = Alignment.Center) {
                            Text("${i + 1}", style = TvType.ui(15f, FontWeight.SemiBold))
                        }
                        Text(step, style = TvType.ui(17f, color = Tv.soft(0.86f)))
                    }
                }
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(36.dp)) {
                    AddressPart("No camera? Open on any device", "http://$address")
                    AddressPart("And type", code, big = true)
                }
                Text(SAME_WIFI, style = TvType.ui(15f, color = Tv.soft(0.6f)))
                PhoneStatusLine(session)
            }
        }
        PhoneBlockSize.Compact -> Row(modifier, horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
            QrCode(url, qr ?: 160f, qrShadow)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title ?: "Or use your phone", style = TvType.ui(17f, FontWeight.SemiBold))
                Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                    AddressPart("No camera? Open on any device", "http://$address")
                    AddressPart("And type", code, big = true)
                }
                Text(SAME_WIFI, style = TvType.ui(15f, color = Tv.soft(0.6f)))
                PhoneStatusLine(session)
            }
        }
    }
}

@Composable
private fun AddressPart(label: String, value: String, big: Boolean = false, size: Float = TvType.MonoUrl) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = TvType.ui(15f, FontWeight.Medium, Tv.soft(0.6f)))
        Text(value, style = TvType.mono(if (big) TvType.MonoCode else size).copy(letterSpacing = if (big) 0.08.em else 0.em), maxLines = 1)
    }
}

/**
 * What the phone did last, or whether one has the page open. Nothing here moves while it waits:
 * these screens otherwise hold still, and an animation would redraw them every frame.
 */
@Composable
fun PhoneStatusLine(session: PhoneSession) {
    val line = session.tvLine
    val seen = session.lastSeen
    // A phone counts as here until 40 s after it last asked; one wait flips that, not a ticking clock.
    var here by remember(seen) { mutableStateOf(System.currentTimeMillis() - seen < 40_000) }
    LaunchedEffect(seen) {
        val left = seen + 40_000 - System.currentTimeMillis()
        if (left > 0) {
            delay(left)
            here = false
        }
    }
    // Waiting is the block's news too, so it reads as plainly as the other two.
    val text = TvType.ui(15f, FontWeight.SemiBold, if (line != null || here) Tv.Ink else Tv.soft(0.86f))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
        when {
            line != null -> {
                TvIcon(Ic.Check, 16f, Tv.Mint, stroke = 3f)
                Text(line, style = text, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            here -> {
                GlowDot(Tv.Mint, 9f)
                Text("Your phone has the page open", style = text)
            }
            else -> {
                TvIcon(Ic.Phone, 16f, Tv.soft(0.86f))
                Text("Waiting for your phone", style = text)
            }
        }
    }
}

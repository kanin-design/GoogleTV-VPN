package dev.shieldvpn.ui.tv

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import dev.shieldvpn.APP_FILE_NAME
import dev.shieldvpn.APP_NAME
import dev.shieldvpn.phone.PhoneBridge
import dev.shieldvpn.vpn.Backup
import dev.shieldvpn.vpn.Certificates
import dev.shieldvpn.vpn.VpnUiState
import kotlinx.coroutines.launch

/** What a phone sheet is for. */
sealed interface PhoneKind {
    data object Log : PhoneKind
    data class Profile(val id: String) : PhoneKind
    data object Backup : PhoneKind
    data object Restore : PhoneKind
    data object Paste : PhoneKind
    data object P12 : PhoneKind
}

/** Android's chooser returns no certificate when none are installed, or when it is closed. */
const val NO_CERT_PICKED = "No certificate picked. To add one, send its .p12 file and choose Install it in the TV’s keystore"

/** A field the text sheet edits. */
enum class TextKind(val title: String, val sub: String, val label: String) {
    CustomDns("Custom DNS servers", "Used instead of the servers your VPN pushes. Separate them with spaces.", "DNS servers"),
    SearchDomains("Search domains", "Short names like nas then find nas.home.lan. Separate them with spaces.", "Domains"),
    LinkUrl("Profile link", "The address of the .ovpn file. ${APP_NAME} downloads it and shows it to you before saving.", "Link"),
    LinkUser("Username for the link", "The username the link asks for.", "Username"),
    LinkPass("Password for the link", "Used once for the download. It is not saved.", "Password"),
    ReviewName("Name", "Shown on cards and on the Connect screen.", "Name"),
}

/**
 * Runs a phone session for as long as the caller is on screen. [onReceive] runs on the main
 * thread. Returns null while starting, or when the TV is not on a home network.
 */
@Composable
fun rememberPhoneBridge(key: Any?, page: PhoneBridge.Page?, onReceive: (PhoneBridge.Received) -> String? = { null }): PhoneBridge? {
    var bridge by remember { mutableStateOf<PhoneBridge?>(null) }
    val receive by rememberUpdatedState(onReceive)
    val current by rememberUpdatedState(page)
    // The session starts once there is a page, and keeps its address and code when the page changes.
    LaunchedEffect(page) { if (page != null) bridge?.page = page }
    DisposableEffect(key, page == null) {
        val page = current
        val main = Handler(Looper.getMainLooper())
        val b = page?.let { p ->
            PhoneBridge.start(p) { got ->
                // Answer the phone with what the app says, worked out on the main thread.
                var answer: String? = null
                val done = java.util.concurrent.CountDownLatch(1)
                main.post {
                    answer = receive(got)
                    done.countDown()
                }
                done.await(5, java.util.concurrent.TimeUnit.SECONDS)
                answer
            }
        }
        bridge = b
        onDispose {
            b?.stop()
            bridge = null
        }
    }
    return bridge
}

/** The QR code and, beside it, the address and code for a phone without a camera. */
@Composable
fun PhoneCode(bridge: PhoneBridge?, qrSize: Float, steps: List<String> = emptyList()) {
    if (bridge == null) {
        Notice(Ic.Bang, "This TV is not on a home network, so a phone can’t reach it.", amber = true)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(30.dp), verticalAlignment = Alignment.CenterVertically) {
            QrCode(bridge.url, qrSize, Modifier.shadow(10.dp, RoundedCornerShape(14.dp), ambientColor = Color.Black, spotColor = Color.Black))
            if (steps.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    steps.forEachIndexed { i, step ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Box(Modifier.size(28.dp).clip(CircleShape).background(Tv.white(0.12f)), contentAlignment = Alignment.Center) {
                                Text("${i + 1}", style = TvType.ui(14f, FontWeight.ExtraBold))
                            }
                            Text(step, style = TvType.ui(17f, color = Tv.soft(0.86f)), modifier = Modifier.widthIn(max = 360.dp))
                        }
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(40.dp)) {
            KeyValue("No camera? Open on any device", "http://${bridge.address}")
            KeyValue("Code", bridge.code.chunked(3).joinToString("\u00A0"), big = true)
        }
    }
}

@Composable
private fun KeyValue(label: String, value: String, big: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = TvType.ui(14f, FontWeight.SemiBold, Tv.soft(0.55f)))
        Text(value, style = TvType.mono(if (big) 24f else 20f).copy(letterSpacing = if (big) 0.08.em else 0.em))
    }
}

@Composable
fun PhoneSheet(state: VpnUiState, nav: Nav, actions: TunnelActions, req: Requesters, o: Overlay.Phone, close: () -> Unit) {
    val (title, lede) = when (val k = o.kind) {
        PhoneKind.Log -> "Send the log to your phone" to "Scan the code to download this session’s connection log."
        is PhoneKind.Profile -> "Send ${state.connection(k.id)?.name ?: "the profile"} to your phone" to "Scan the code to download the .ovpn file. Its saved password is not included."
        PhoneKind.Backup -> "Back up to your phone" to "Scan the code. Your phone downloads one encrypted file with every profile and setting."
        PhoneKind.Restore -> "Send the backup from your phone" to "Scan the code, then pick the .${Backup.EXTENSION} file on your phone."
        PhoneKind.Paste -> "Paste config text" to "Scan the code, paste the config on your phone and press Send."
        PhoneKind.P12 -> "Send a certificate from your phone" to "Scan the code and pick the .p12 or .pfx file. ${APP_NAME} asks for its password next."
    }
    // Files to download are made once, when the sheet opens; the backup takes a moment to encrypt.
    // Lint misreads produceState with a key: the value is set on every run.
    @Suppress("ProduceStateDoesNotAssignValue")
    val page by produceState<PhoneBridge.Page?>(null, o) {
        value = when (val k = o.kind) {
            PhoneKind.Log -> PhoneBridge.Page.Download(title, lede, "$APP_FILE_NAME-log.txt", "text/plain; charset=utf-8", actions.logText().toByteArray())
            is PhoneKind.Profile -> {
                val c = state.connection(k.id)
                actions.profileFile(k.id)?.let { PhoneBridge.Page.Download(title, lede, "${(c?.name ?: "profile").replace(Regex("[^A-Za-z0-9._ -]"), "")}.ovpn", "application/x-openvpn-profile", it) }
            }
            PhoneKind.Backup -> actions.backupFile()?.let { PhoneBridge.Page.Download(title, lede, "$APP_FILE_NAME.${Backup.EXTENSION}", "application/octet-stream", it) }
            PhoneKind.Restore -> PhoneBridge.Page.Upload(title, lede, ".${Backup.EXTENSION},application/octet-stream,application/json", paste = false)
            PhoneKind.Paste -> PhoneBridge.Page.Upload(title, lede, ".ovpn,.conf,text/plain", paste = true)
            PhoneKind.P12 -> PhoneBridge.Page.Upload(title, lede, ".p12,.pfx,application/x-pkcs12", paste = false)
        }
    }
    val bridge = rememberPhoneBridge(o, page) { got ->
        val problem = actions.receive(got.name, got.bytes)
        if (problem == null) close()
        problem
    }
    Sheet(800f) {
        Row(horizontalArrangement = Arrangement.spacedBy(30.dp)) {
            Column(Modifier.weight(1f)) {
                Text(title, style = TvType.display(34f))
                Text(lede, style = TvType.ui(17f, color = Tv.soft(0.7f)), modifier = Modifier.padding(top = 6.dp, bottom = 22.dp))
                if (page == null) {
                    Text("Getting it ready…", style = TvType.ui(17f, color = Tv.soft(0.7f)))
                } else {
                    PhoneCode(bridge, 220f)
                }
                Row(Modifier.padding(top = 26.dp)) {
                    TvButton("Done", close, icon = Ic.Check, focusRequester = req["ov-first"])
                }
            }
        }
    }
}

/** Sets the backup passphrase, or unlocks a backup that arrived. */
@Composable
fun PassphraseSheet(state: VpnUiState, nav: Nav, actions: TunnelActions, req: Requesters, o: Overlay.Passphrase, close: () -> Unit) {
    var value by remember { mutableStateOf("") }
    var shift by remember { mutableStateOf(false) }
    var show by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    TypeInto(nav, { value = value.dropLast(1) }) { ch -> value = (value + ch).take(64) }
    val go: () -> Unit = {
        when {
            busy -> Unit
            !o.restore && value.length < 6 -> nav.toast("Use at least 6 characters")
            !o.restore -> {
                actions.setBackupPassphrase(value)
                nav.toast("Backup passphrase saved")
                close()
            }
            else -> {
                busy = true
                scope.launch {
                    when (val r = actions.restore(value)) {
                        is Backup.Restored.Done -> {
                            nav.toast("Restored ${r.profiles} profile${if (r.profiles == 1) "" else "s"}")
                            close()
                            nav.go(Screen.Profiles)
                        }
                        is Backup.Restored.Problem -> nav.toast(r.message)
                    }
                    busy = false
                }
            }
        }
    }
    Sheet(820f) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(if (o.restore) "Restore a backup" else "Backup passphrase", style = TvType.display(34f))
            Text(
                if (o.restore) "Type the passphrase this backup was made with. Its profiles are added to this TV."
                else "Backups are encrypted with it. You need it to restore on another TV, so keep it somewhere safe.",
                style = TvType.ui(17f, color = Tv.soft(0.7f)),
            )
            InputBox("Passphrase", value, editing = true, onClick = {}, secret = true, showSecret = show)
            Keyboard(shift, { k ->
                when (k) {
                    TvKey.Shift -> shift = !shift
                    TvKey.Done -> req["ov-go"].tryFocus()
                    else -> {
                        value = typeKey(value, k, shift) ?: value
                        if (k is TvKey.Ch && shift) shift = false
                    }
                }
            }, entry = req["ov-first"])
            Row(Modifier.enterAt { req["ov-go"] }, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                TvButton(if (o.restore) (if (busy) "Restoring…" else "Restore") else "Save", go, icon = Ic.Check, focusRequester = req["ov-go"])
                TvButton("Cancel", {
                    if (o.restore) actions.cancelRestore()
                    close()
                }, glass = true)
                TvButton(if (show) "Hide" else "Show", { show = !show }, icon = Ic.Eye, small = true, glass = true)
                if (!o.restore && state.hasBackupPassphrase) {
                    TvButton("Remove", {
                        actions.setBackupPassphrase(null)
                        nav.toast("Backup passphrase removed")
                        close()
                    }, small = true, glass = true, danger = true)
                }
            }
        }
    }
}

/** Asks for the PIN, or sets a new one with a second entry to confirm. */
@Composable
fun PinSheet(nav: Nav, actions: TunnelActions, req: Requesters, o: Overlay.Pin, close: () -> Unit) {
    var digits by remember { mutableStateOf("") }
    var first by remember { mutableStateOf<String?>(null) }
    val title = when {
        !o.setting -> "Enter your PIN"
        first == null -> "Choose a PIN"
        else -> "Enter it again"
    }
    val submit: () -> Unit = {
        when {
            digits.length < 4 -> nav.toast("The PIN has 4 to 8 digits")
            !o.setting -> if (actions.checkPin(digits)) {
                nav.unlocked = true
                close()
                o.then?.invoke()
            } else {
                digits = ""
                nav.toast("Wrong PIN")
            }
            first == null -> {
                first = digits
                digits = ""
            }
            first == digits -> {
                actions.setPin(digits)
                o.mode?.let { m -> actions.changeSettings { it.copy(pinMode = m) } }
                nav.unlocked = true
                nav.toast("PIN saved")
                close()
            }
            else -> {
                first = null
                digits = ""
                nav.toast("The two PINs were different. Try again")
            }
        }
    }
    TypeInto(nav, { digits = digits.dropLast(1) }) { ch -> if (ch.isDigit() && digits.length < 8) digits += ch }
    Sheet(460f) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 8.dp)) {
            Text(title, style = TvType.display(30f))
            Text(
                if (o.setting) "4 to 8 digits. Anyone without it can still connect from Connect." else "Profiles and settings are locked on this TV.",
                style = TvType.ui(15f, color = Tv.soft(0.7f)),
                modifier = Modifier.padding(top = 6.dp),
            )
            Row(Modifier.padding(vertical = 22.dp).height(18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                repeat(maxOf(4, digits.length)) { i ->
                    Box(Modifier.size(16.dp).clip(CircleShape).background(if (i < digits.length) Tv.Ink else Tv.white(0.18f)))
                }
            }
            val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0", "OK")
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                keys.chunked(3).forEach { line ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        line.forEach { k ->
                            TvButton(
                                k,
                                {
                                    when (k) {
                                        "⌫" -> digits = digits.dropLast(1)
                                        "OK" -> submit()
                                        else -> if (digits.length < 8) digits += k
                                    }
                                },
                                modifier = Modifier.width(110.dp),
                                glass = k != "OK",
                                focusRequester = if (k == "5") req["ov-first"] else null,
                            )
                        }
                    }
                }
            }
            if (!o.locksApp) {
                Row(Modifier.padding(top = 18.dp)) {
                    TvButton("Cancel", close, glass = true, small = true)
                }
            }
        }
    }
}

/** One text field with the on-screen keyboard, and a phone to type on instead. */
@Composable
fun TextSheet(nav: Nav, req: Requesters, o: Overlay.Text, close: () -> Unit) {
    var value by remember { mutableStateOf(o.initial) }
    var shift by remember { mutableStateOf(false) }
    val secret = o.kind == TextKind.LinkPass
    TypeInto(nav, { value = value.dropLast(1) }) { ch -> value = (value + ch).take(400) }
    val page = remember(o.kind) { PhoneBridge.Page.Type(o.kind.title, "Type it here, then press Send. It appears on the TV.", o.kind.label, secret) }
    val bridge = rememberPhoneBridge(o, page) { got ->
        value = String(got.bytes).trim().take(400)
        null
    }
    Sheet(1100f) {
        Row(horizontalArrangement = Arrangement.spacedBy(34.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(o.kind.title, style = TvType.display(34f))
                Text(o.kind.sub, style = TvType.ui(17f, color = Tv.soft(0.7f)))
                InputBox(o.kind.label, value, editing = true, onClick = {}, secret = secret, mono = o.kind != TextKind.ReviewName)
                Keyboard(shift, { k ->
                    when (k) {
                        TvKey.Shift -> shift = !shift
                        TvKey.Done -> req["ov-go"].tryFocus()
                        else -> {
                            value = typeKey(value, k, shift, max = 400) ?: value
                            if (k is TvKey.Ch && shift) shift = false
                        }
                    }
                }, entry = req["ov-first"])
                Row(Modifier.enterAt { req["ov-go"] }, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvButton("Save", {
                        o.save(value.trim())
                        close()
                    }, icon = Ic.Check, focusRequester = req["ov-go"])
                    TvButton("Cancel", close, glass = true)
                }
            }
            Column(Modifier.width(300.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Spacer(Modifier.height(4.dp))
                Text("Type it on your phone", style = TvType.ui(18f, FontWeight.Bold))
                if (bridge != null) {
                    QrCode(bridge.url, 180f)
                    Text("Or open http://${bridge.address} and enter ${bridge.code.chunked(3).joinToString("\u00A0")}.", style = TvType.ui(15f, color = Tv.soft(0.7f)))
                } else {
                    Text("Connect the TV to your home network to type on a phone.", style = TvType.ui(15f, color = Tv.soft(0.7f)))
                }
            }
        }
    }
}

/** A .p12 certificate that arrived: its password first, then the profile it goes into. */
@Composable
fun P12Sheet(state: VpnUiState, nav: Nav, actions: TunnelActions, req: Requesters, close: () -> Unit) {
    var password by remember { mutableStateOf("") }
    var shift by remember { mutableStateOf(false) }
    var show by remember { mutableStateOf(false) }
    var opened by remember { mutableStateOf(false) }
    val cancel = {
        actions.cancelP12()
        close()
    }
    if (!opened) TypeInto(nav, { password = password.dropLast(1) }) { ch -> password = (password + ch).take(64) }
    Sheet(820f) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(if (opened) "Add the certificate to a profile" else "Certificate password", style = TvType.display(34f))
            Text(
                if (opened) "Its certificate and key go into the profile’s file, in place of any it had."
                else "The password the .p12 file was exported with. It is used once and not kept.",
                style = TvType.ui(17f, color = Tv.soft(0.7f)),
            )
            if (!opened) {
                InputBox("Password", password, editing = true, onClick = {}, secret = true, showSecret = show)
                Keyboard(shift, { k ->
                    when (k) {
                        TvKey.Shift -> shift = !shift
                        TvKey.Done -> req["ov-go"].tryFocus()
                        else -> {
                            password = typeKey(password, k, shift) ?: password
                            if (k is TvKey.Ch && shift) shift = false
                        }
                    }
                }, entry = req["ov-first"])
                Row(Modifier.enterAt { req["ov-go"] }, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvButton("Open", {
                        val problem = actions.checkP12(password)
                        if (problem != null) nav.toast(problem) else opened = true
                    }, icon = Ic.Lock, focusRequester = req["ov-go"])
                    TvButton("Cancel", cancel, glass = true)
                    TvButton(if (show) "Hide" else "Show", { show = !show }, icon = Ic.Eye, small = true, glass = true)
                }
            } else {
                LaunchedEffect(Unit) { req["p12-first"].focusWhenReady() }
                val list = rememberLazyListState()
                LazyColumn(
                    state = list,
                    modifier = Modifier.heightIn(max = 470.dp).bleed(12.dp).scrollFade(list),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(state.connections.size) { i ->
                        val c = state.connections[i]
                        ListRow(
                            c.name,
                            {
                                val problem = actions.attachP12(password, c.id)
                                if (problem != null) {
                                    nav.toast(problem)
                                } else {
                                    nav.toast("Certificate added to ${c.name}")
                                    close()
                                }
                            },
                            subtitle = if (state.infoOf(c)?.cert != null) "Has its own certificate · it will be replaced" else state.serverOf(c)?.host,
                            focusRequester = if (i == 0) req["p12-first"] else null,
                            leading = { Avatar(lookOf(c), 40f) },
                        )
                    }
                    item {
                    ListRow(
                        "Keep the key in this TV’s keystore instead", {
                            val alias = actions.keepP12(password)
                            if (alias == null) {
                                nav.toast("Android could not keep this key")
                            } else {
                                nav.overlay = Overlay.PickProfile(
                                    "Use it for which profile?", "The key stays in the TV’s keystore and can’t be copied off it.",
                                    cancelled = { Certificates.forget(alias) },
                                ) { id ->
                                    actions.setKeyAlias(id, alias)
                                    nav.toast("${state.connection(id)?.name} now uses ${Certificates.label(alias)}")
                                }
                            }
                        },
                        subtitle = "The key can sign in but can’t be copied off the TV. Pick the profile next",
                        focusRequester = if (state.connections.isEmpty()) req["p12-first"] else null,
                        leading = { IconSquare(Ic.Cert, it) },
                    )
                    }
                }
                Row { TvButton("Cancel", cancel, glass = true) }
            }
        }
    }
}

/** A list of profiles to pick one from. */
@Composable
fun PickProfileSheet(state: VpnUiState, req: Requesters, o: Overlay.PickProfile, close: () -> Unit) {
    Sheet(640f) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(o.title, style = TvType.display(34f))
            Text(o.sub, style = TvType.ui(17f, color = Tv.soft(0.7f)))
            val list = rememberLazyListState()
            LazyColumn(
                state = list,
                modifier = Modifier.heightIn(max = 520.dp).bleed(12.dp).scrollFade(list),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(state.connections.size) { i ->
                    val c = state.connections[i]
                    ListRow(
                        c.name,
                        {
                            close()
                            o.picked(c.id)
                        },
                        subtitle = state.serverOf(c)?.host,
                        focusRequester = if (i == 0) req["ov-first"] else null,
                        leading = { Avatar(lookOf(c), 40f) },
                    )
                }
            }
            if (state.connections.isEmpty()) {
                Text("Add a profile first.", style = TvType.ui(16f, color = Tv.soft(0.7f)))
            }
            TvButton("Cancel", {
                o.cancelled?.invoke()
                close()
            }, glass = true, focusRequester = if (state.connections.isEmpty()) req["ov-first"] else null)
        }
    }
}


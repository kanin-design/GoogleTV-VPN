package dev.shieldvpn.ui.tv

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import dev.shieldvpn.vpn.VpnController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.shieldvpn.vpn.VpnStatus
import dev.shieldvpn.vpn.VpnUiState

/** Sheets over the current screen. Focus stays inside the open sheet until it closes. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun Overlays(state: VpnUiState, nav: Nav, actions: TunnelActions, connect: (String) -> Unit) {
    val overlay = nav.overlay ?: return
    val req = remember { Requesters() }
    val close = {
        val back = nav.opener
        nav.overlay = null
        // Back to what opened the sheet, if it is still there; else to where the screen starts.
        if (back != null && back.isOnScreen) nav.refocus(back) else nav.contentFocus = nav.screen
    }
    LaunchedEffect(overlay, nav.overlayRefocus) {
        val key = when (overlay) {
            is Overlay.SignIn -> "ov-key"
            else -> "ov-first"
        }
        req[key].focusWhenReady()
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF020407).copy(alpha = if (overlay is Overlay.Details) 0.35f else 0.58f))
            .focusProperties { exit = { FocusRequester.Cancel } }
            .focusGroup(),
        contentAlignment = if (overlay is Overlay.Details) Alignment.TopEnd else Alignment.Center,
    ) {
        AnimatedContent(
            targetState = overlay,
            // The fade is drawn below, not with fadeIn and fadeOut: those draw the sheet into an
            // offscreen buffer, and the graphics driver keeps it.
            transitionSpec = {
                if (targetState is Overlay.Details) {
                    slideInHorizontally(tween(300)) { it / 8 } togetherWith ExitTransition.None
                } else {
                    (slideInVertically(tween(260)) { it / 30 } + scaleIn(tween(260), initialScale = 0.98f)) togetherWith ExitTransition.None
                }
            },
            label = "overlay",
        ) { o ->
            val shown by transition.animateFloat({ tween(if (targetState == EnterExitState.Visible) 280 else 100) }, label = "sheetIn") {
                if (it == EnterExitState.Visible) 1f else 0f
            }
            Box(Modifier.fade { shown }) { Sheets(state, nav, actions, req, o, connect, close) }
        }
    }
}

@Composable
private fun Sheets(state: VpnUiState, nav: Nav, actions: TunnelActions, req: Requesters, o: Overlay, connect: (String) -> Unit, close: () -> Unit) {
    when (o) {
        is Overlay.SignIn -> SignInSheet(state, nav, actions, req, o, close)
        Overlay.Details -> DetailsSheet(state, nav, req, close)
        is Overlay.File -> FileSheet(o, req, close)
        is Overlay.Menu -> MenuSheet(state, nav, actions, req, o, connect, close)
        is Overlay.Phone -> PhoneSheet(state, nav, actions, req, o, close)
        is Overlay.Passphrase -> PassphraseSheet(state, nav, actions, req, o, close)
        is Overlay.Pin -> PinSheet(nav, actions, req, o, close)
        is Overlay.Text -> TextSheet(nav, req, o, close)
        Overlay.P12 -> P12Sheet(state, nav, actions, req, close)
        is Overlay.PickProfile -> PickProfileSheet(state, req, o, close)
        is Overlay.Speed -> SpeedSheet(state, actions, req, o, connect, close)
    }
}

@Composable
fun Sheet(width: Float, content: @Composable () -> Unit) {
    // Denser than the panels, so a bright title behind does not show through the keyboard.
    val shape = RoundedCornerShape(30.dp)
    Box(
        modifier = Modifier
            .shadow(40.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
            .width(width.dp)
            .clip(shape)
            .background(Color(0xF50A0E15))
            .border(1.dp, Tv.GlassEdge, shape),
    ) {
        Column(Modifier.padding(horizontal = 34.dp, vertical = 30.dp)) { content() }
    }
}

@Composable
private fun SignInSheet(state: VpnUiState, nav: Nav, actions: TunnelActions, req: Requesters, o: Overlay.SignIn, close: () -> Unit) {
    val c = state.connection(o.id) ?: return
    val login = state.logins[c.id]
    var user by remember(o) { mutableStateOf(login?.username.orEmpty()) }
    var pw by remember(o) { mutableStateOf("") }
    var field by remember(o) { mutableStateOf(if (user.isEmpty()) "user" else "pw") }
    var shift by remember(o) { mutableStateOf(false) }
    var show by remember(o) { mutableStateOf(false) }
    var remember by remember(o) { mutableStateOf(true) }

    fun edit(transform: (String) -> String) {
        if (field == "user") user = transform(user) else pw = transform(pw)
    }

    val go = {
        when {
            user.isBlank() -> {
                field = "user"
                nav.toast("Enter the username first")
            }
            pw.isEmpty() -> {
                field = "pw"
                nav.toast("Enter the password first")
            }
            o.save -> {
                actions.saveLogin(c.id, user, pw)
                close()
                if (o.forStartup) {
                    actions.setStartup(c.id)
                    nav.pick = null
                    nav.toast("Password saved. ${c.name} connects when the device starts")
                } else {
                    nav.toast("Password saved")
                }
            }
            else -> {
                close()
                actions.connect(c.id, user, pw, remember)
            }
        }
    }

    // A phone can type the field instead: what it sends replaces the field being edited.
    val page = remember(field) {
        dev.shieldvpn.phone.PhoneBridge.Page.Type(
            "Sign in to ${c.name}", "Type it here, then press Send. It goes into the field selected on the TV. Reload this page after you move to the other field.",
            if (field == "user") "Username" else "Password", secret = field == "pw",
        )
    }
    val bridge = rememberPhoneBridge(o, page) { got ->
        val text = String(got.bytes).trim().take(64)
        edit { text }
        null
    }
    Sheet(1100f) {
        TypeInto(nav, { edit { it.dropLast(1) } }, onEnter = { req["ov-go"].takeFocus() }) { ch -> edit { (it + ch).take(64) } }
        Row(horizontalArrangement = Arrangement.spacedBy(34.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Column {
                Text("Sign in to ${c.name}", style = TvType.display(34f))
                Text(
                    "This profile asks for a username and password. The password stays in the device’s keystore.",
                    style = TvType.ui(17f, color = Tv.soft(0.7f)),
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                InputBox("Username", user, field == "user", { field = "user" }, Modifier.weight(1f))
                InputBox("Password", pw, field == "pw", { field = "pw" }, Modifier.weight(1f), secret = true, showSecret = show)
            }
            Keyboard(
                shift = shift,
                onKey = { k ->
                    when (k) {
                        TvKey.Done -> req["ov-go"].tryFocus()
                        TvKey.Shift -> shift = !shift
                        else -> {
                            edit { v -> typeKey(v, k, shift) ?: v }
                            if (k is TvKey.Ch && shift) shift = false
                        }
                    }
                },
                entry = req["ov-key"],
            )
            Row(Modifier.enterAt { req["ov-go"] }, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                TvButton(if (o.save) "Save" else "Connect", go, icon = if (o.save) Ic.Check else Ic.Power, focusRequester = req["ov-go"])
                TvButton("Cancel", close, glass = true)
                TvButton(if (show) "Hide" else "Show", { show = !show }, icon = Ic.Eye, small = true, glass = true)
                if (!o.save) {
                    Spacer(Modifier.weight(1f))
                    ListRow(
                        "Remember on this device", { remember = !remember },
                        modifier = Modifier.width(300.dp),
                        trailing = { TvSwitch(remember, it) },
                    )
                }
            }
        }
        Column(Modifier.width(300.dp).padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Type it on your phone", style = TvType.ui(18f, FontWeight.Bold))
            if (bridge != null) {
                QrCode(bridge.url, 180f)
                Text("Or open http://${bridge.address} and enter ${bridge.code.chunked(3).joinToString("\u00A0")}.", style = TvType.ui(15f, color = Tv.soft(0.7f)))
            } else {
                Text("Connect this device to your home network to type on a phone.", style = TvType.ui(15f, color = Tv.soft(0.7f)))
            }
        }
        }
    }
}

@Composable
private fun DetailsSheet(state: VpnUiState, nav: Nav, req: Requesters, close: () -> Unit) {
    val c = state.active ?: return
    val server = state.serverOf(c)
    val info = state.infoOf(c)
    val t = state.tunnel
    val now = rememberNow()
    val since = (state.status as? VpnStatus.Connected)?.sinceMillis
    // The counters are read only while this sheet is open.
    var bytes by remember { mutableStateOf<LongArray?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            // A call into the VPN's process, so not on the main thread.
            bytes = withContext(Dispatchers.IO) { VpnController.stats?.invoke() }
            delay(2000)
            // Not while the app is hidden: the frame clock stops with it.
            withFrameNanos { }
        }
    }
    Box(Modifier.padding(top = 124.dp, end = 64.dp, bottom = 36.dp).width(540.dp)) {
        Glass(Modifier.fillMaxSize(), shape = RoundedCornerShape(30.dp)) {
            Column(Modifier.fillMaxSize().padding(horizontal = 34.dp, vertical = 30.dp)) {
                Text("CONNECTION DETAILS", style = TvType.eyebrow(13.5f, 0.55f))
                Text(c.name, style = TvType.display(34f), modifier = Modifier.padding(top = 6.dp))
                Spacer(Modifier.height(16.dp))
                DetailGrid(
                    listOf(
                        "Server" to listOfNotNull(server?.host, t?.remote).joinToString(" · "),
                        "Protocol" to (server?.let { "${it.protocol.label} ${it.port}" } ?: "—"),
                        "VPN IP (what sites see)" to (t?.publicIp ?: if (since != null) "Looking it up…" else "—"),
                        "Tunnel IP" to (t?.address ?: "—"),
                        "DNS servers" to (t?.dns?.joinToString(", ")?.ifEmpty { null } ?: "—"),
                        "Routes" to (t?.routes?.lastOrNull() ?: "—"),
                        "Data cipher" to (info?.cipher ?: "Chosen by the server"),
                        "Control channel" to (info?.tls ?: "TLS"),
                        "Transferred" to (bytes?.let { "${fmtBytes(it[0])} in · ${fmtBytes(it[1])} out" } ?: "—"),
                        "Connected for" to (since?.let { fmtDuration((now - it).coerceAtLeast(0) / 1000) } ?: "—"),
                    ),
                )
                Spacer(Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvButton("Close", close, focusRequester = req["ov-first"])
                    TvButton("View log", {
                        nav.overlay = null
                        nav.settingsCat = Category.Diagnostics
                        nav.go(Screen.Settings)
                        nav.showLog = true
                    }, icon = Ic.Log, glass = true)
                }
            }
        }
    }
}

@Composable
private fun FileSheet(o: Overlay.File, req: Requesters, close: () -> Unit) {
    Sheet(760f) {
        Text(o.eyebrow.uppercase(), style = TvType.eyebrow(13.5f, 0.55f), maxLines = 1)
        Text(o.title, style = TvType.display(34f), modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(16.dp))
        RawConfig(o.text, maxHeight = 340f, plain = o.plain)
        Row(Modifier.padding(top = 20.dp)) {
            TvButton("Close", close, focusRequester = req["ov-first"])
        }
    }
}

@Composable
private fun MenuSheet(
    state: VpnUiState,
    nav: Nav,
    actions: TunnelActions,
    req: Requesters,
    o: Overlay.Menu,
    connect: (String) -> Unit,
    close: () -> Unit,
) {
    val c = state.connection(o.id) ?: return
    val live = isLive(state, c.id)
    Sheet(460f) {
        Text("QUICK MENU", style = TvType.eyebrow(13.5f, 0.55f))
        Text(c.name, style = TvType.display(34f), modifier = Modifier.padding(top = 6.dp))
        Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ListRow(
                if (live) "Disconnect" else "Connect",
                {
                    nav.overlay = null
                    if (live) actions.disconnect() else connect(c.id)
                    nav.contentFocus = nav.screen
                },
                subtitle = state.serverOf(c)?.host,
                focusRequester = req["ov-first"],
                leading = { IconSquare(Ic.Power, it) },
            )
            ListRow(
                "Speed test",
                { nav.overlay = Overlay.Speed(c.id) },
                subtitle = state.speeds[c.id]?.let { "Last: ${fmtMbps(it.downMbps)} Mbps, ${ago(it.at)}" } ?: "Is it fast enough for 4K?",
                leading = { IconSquare(Ic.Gauge, it) },
            )
            ListRow(
                "Edit profile",
                {
                    nav.overlay = null
                    nav.guard(state) {
                        nav.go(Screen.Profiles, focusContent = false)
                        nav.profFocus = c.id
                        nav.stack.clear()
                        nav.push(Col.Profile(c.id), "pe-name")
                    }
                },
                subtitle = "Name, password, advanced",
                leading = { IconSquare(Ic.Pencil, it) },
            )
            ListRow(
                if (c.pinned) "Unpin from Quick connect" else "Pin to Quick connect",
                {
                    actions.setPinned(c.id, !c.pinned)
                    nav.toast(if (!c.pinned) "${c.name} is pinned to Quick connect" else "${c.name} is unpinned")
                    close()
                },
                subtitle = "Pinned profiles come first",
                leading = { IconSquare(Ic.Pin, it) },
            )
            var confirm by remember { mutableStateOf(false) }
            ListRow(
                if (confirm) "Press again to delete" else "Delete profile",
                {
                    if (!confirm) {
                        confirm = true
                    } else {
                        close()
                        nav.guard(state) {
                            actions.delete(c.id)
                            nav.toast("${c.name} was deleted")
                        }
                    }
                },
                subtitle = if (confirm) "Its saved password goes too" else "Removes it from this device",
                danger = true,
                leading = { IconSquare(Ic.Trash, it) },
            )
        }
    }
}

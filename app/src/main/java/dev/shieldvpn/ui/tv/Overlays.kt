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
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.ui.layout.layout
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import dev.shieldvpn.vpn.VpnKind
import dev.shieldvpn.control.Words
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
import androidx.compose.foundation.layout.fillMaxWidth
import dev.shieldvpn.phone.PhoneAction
import dev.shieldvpn.phone.PhoneField
import dev.shieldvpn.phone.PhoneForm
import dev.shieldvpn.phone.PhoneReply
import dev.shieldvpn.phone.PhoneSection
import dev.shieldvpn.phone.PhoneServer
import dev.shieldvpn.phone.PhoneStatus
import dev.shieldvpn.vpn.Connection
import dev.shieldvpn.vpn.Login
import dev.shieldvpn.vpn.UpdateState
import dev.shieldvpn.vpn.VpnStatus
import dev.shieldvpn.vpn.VpnUiState
import dev.shieldvpn.vpn.isActive

/** Sheets over the current screen. Focus stays inside the open sheet until it closes. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun Overlays(state: VpnUiState, nav: Nav, actions: TunnelActions, connect: (String) -> Unit) {
    val overlay = nav.overlay ?: return
    val close = {
        nav.overlay = null
        nav.refocusOpener()
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            // A PIN that locks the whole app hides the app behind it too.
            .background(if ((overlay as? Overlay.Pin)?.locksApp == true) Color(0xFF020407).copy(alpha = 0.97f) else Tv.Scrim)
            .focusProperties { exit = { FocusRequester.Cancel } }
            .focusGroup(),
        contentAlignment = if (overlay is Overlay.Details) Alignment.TopEnd else Alignment.Center,
    ) {
        AnimatedContent(
            targetState = overlay,
            modifier = if (overlay is Overlay.Details) Modifier else Modifier.inPanelBox(),
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
            // Each sheet has its own focus keys: for a moment the one going and the one coming are both drawn.
            val req = remember(o) { Requesters() }
            val coming = transition.targetState == EnterExitState.Visible
            LaunchedEffect(coming, nav.overlayRefocus) {
                if (coming) req[if (o is Overlay.SignIn || o is Overlay.Form) "ov-key" else "ov-first"].focusWhenReady()
            }
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
        is Overlay.Phone -> PhoneSheet(req, o, close)
        is Overlay.Form -> FormSheet(nav, req, o.spec, close)
        is Overlay.Pin -> PinSheet(nav, actions, req, o, close)
        is Overlay.P12Target -> P12TargetSheet(state, nav, actions, req, o, close)
        is Overlay.PickProfile -> PickProfileSheet(state, req, o, close)
        is Overlay.Speed -> SpeedSheet(state, actions, req, o, connect, close)
        Overlay.Update -> UpdateSheet(state, actions, req, close)
        is Overlay.PairPhone -> PairSheet(nav, req, o, close)
        is Overlay.Ask -> AskSheet(req, o) { yes -> answerAsk(nav, o, yes) }
    }
}

/**
 * Centres a sheet in the box the panels fill, under the tabs ([TvSize.PanelTop]). A sheet taller
 * than the box, one with a keyboard, rises until it is 16 from the screen's bottom, and at most
 * to the middle.
 */
private fun Modifier.inPanelBox(): Modifier = layout { measurable, constraints ->
    val sheet = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
    val inBox = TvSize.PanelTop.dp.roundToPx() + (TvSize.PanelHeight.dp.roundToPx() - sheet.height) / 2
    val y = minOf(inBox, constraints.maxHeight - 16.dp.roundToPx() - sheet.height).coerceAtLeast((constraints.maxHeight - sheet.height) / 2)
    layout(constraints.maxWidth, constraints.maxHeight) { sheet.place((constraints.maxWidth - sheet.width) / 2, y) }
}

@Composable
fun Sheet(width: Float, content: @Composable () -> Unit) {
    // Opaque, unlike the panels: at 96 % a bright title or row behind still read through it.
    val shape = RoundedCornerShape(TvRadius.Large.dp)
    Box(
        modifier = Modifier
            .shadow(40.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
            .width(width.dp)
            .clip(shape)
            .background(Color(0xFF0A0E15))
            .border(1.dp, Tv.GlassEdge, shape),
    ) {
        Column(Modifier.padding(start = TvSize.PanelInset.dp, end = TvSize.PanelInset.dp, top = TvSize.PanelInsetY.dp, bottom = TvSize.PanelInsetY.dp)) { content() }
    }
}

/** The phone's page for signing in: the same fields, and Connect or Save on the phone. */
private fun signInForm(c: Connection, login: Login?, o: Overlay.SignIn) = PhoneForm(
    place = "the sign-in",
    title = if (o.save) "Save the password for ${c.name}" else "Sign in to ${c.name}",
    lede = if (o.save) "The TV keeps it in its keystore, encrypted." else "The TV connects as soon as you press Connect.",
    sections = listOf(
        PhoneSection(
            "login",
            fields = listOfNotNull(
                PhoneField.Text("user", "Username", login?.username.orEmpty(), required = true, mirror = true),
                PhoneField.Text("pw", "Password", secret = true, required = true, mirror = true),
                if (!o.save) PhoneField.Switch("remember", "Remember on the TV", "Then it connects without asking next time", true) else null,
            ),
            actions = listOf(PhoneAction("go", if (o.save) "Save" else "Connect", primary = true, needs = listOf("user", "pw"))),
        ),
    ),
)

@Composable
private fun SignInSheet(state: VpnUiState, nav: Nav, actions: TunnelActions, req: Requesters, o: Overlay.SignIn, close: () -> Unit) {
    val c = state.connection(o.id) ?: return
    val login = state.logins[c.id]
    val key = "signin-${c.id}"
    val spec = remember(o) {
        FormSpec(
            // Where it was opened: Connect, Profiles or Settings › Startup.
            eyebrow = when (nav.screen) {
                Screen.Connect -> "Connect › ${c.name}"
                Screen.Settings -> "Settings › Startup"
                else -> "Profiles › ${c.name}"
            },
            title = when {
                o.forStartup -> "Save the password so ${c.name} can connect by itself"
                o.save -> "Save the password for ${c.name}"
                else -> "Sign in to ${c.name}"
            },
            sub = if (o.save) "The password stays in this device’s keystore, encrypted, never in the .ovpn file." else "This profile asks for a username and password. With Remember on, they stay in this device’s keystore.",
            fields = listOf(FormField("user", "Username", login?.username.orEmpty()), FormField("pw", "Password", secret = true)),
            primary = if (o.save) "Save" else "Connect",
            primaryIcon = if (o.save) Ic.Check else Ic.Power,
            phone = key,
            phoneSection = "login",
            toggle = if (o.save) null else FormToggle("Remember", "On this device", true),
        ) { v, remember ->
            val user = v["user"].orEmpty()
            val pw = v["pw"].orEmpty()
            if (o.save) {
                saveLogin(actions, nav, c, user, pw, o.forStartup).toForm()
            } else {
                loginProblems(user, pw)?.toForm() ?: run {
                    actions.connect(c.id, user.trim(), pw, remember)
                    FormResult.Done
                }
            }
        }
    }
    val session = rememberPhoneSession(
        key, state, actions,
        form = { signInForm(c, login, o) },
        // A failed connect can be tried again from the phone after the sheet has gone.
        keepsWorking = true,
        onAction = { action, v ->
            val user = v["user"].orEmpty()
            val pw = v["pw"].orEmpty()
            // The sheet goes as soon as the phone acts; the phone then shows what happened.
            val closeThis = { if (nav.overlay == o) close() }
            when {
                action != "go" -> PhoneReply()
                o.save -> saveLogin(actions, nav, c, user, pw, o.forStartup).also { if (it is Outcome.Done) closeThis() }
                    .toReply(clear = listOf("pw"), holdMs = PhoneServer.KEEP_MS)
                else -> loginProblems(user, pw)?.toReply() ?: run {
                    closeThis()
                    actions.connect(c.id, user.trim(), pw, v["remember"] != "false")
                    nav.connectWatch = this to c.id
                    PhoneReply(PhoneStatus.busy("Connecting to ${c.name}…"), clear = listOf("pw"), holdMs = PhoneServer.KEEP_MS)
                }
            }
        },
    )
    FormSheet(nav, req, spec, close, session)
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
    Box(Modifier.padding(top = TvSize.PanelTop.dp, end = 64.dp).width(TvSize.SheetSide.dp)) {
        // Solid like the sheets, and the panels' height, with the buttons at its foot: shorter, the
        // tops of the cards under it showed below its edge.
        val shape = RoundedCornerShape(TvRadius.Large.dp)
        Box(Modifier.clip(shape).background(Color(0xFF0A0E15)).border(1.dp, Tv.GlassEdge, shape)) {
            Column(
                Modifier
                    .defaultMinSize(minHeight = TvSize.PanelHeight.dp)
                    .padding(start = TvSize.PanelInset.dp, end = TvSize.PanelInset.dp, top = TvSize.PanelInsetY.dp, bottom = TvSize.PanelInsetY.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
              Column {
                PanelHeading("Connect › ${c.name}", "Connection details")
                Spacer(Modifier.height(16.dp))
                DetailGrid(
                    listOf(
                        // The address it resolved to, only when the file names the server.
                        // The name and the address it reached, one a line: joined, a long name broke the address in two.
                        "Server" to listOfNotNull(server?.host, t?.remote?.takeIf { it != server?.host }).joinToString("\n"),
                        "Protocol" to (server?.let { "${Words.kind(info?.kind ?: VpnKind.OpenVpn)} · ${it.protocol.label} ${it.port}" } ?: "—"),
                        "VPN IP (what sites see)" to (t?.publicIp ?: if (since != null) "Looking it up…" else "—"),
                        "Tunnel IP" to (t?.address ?: "—"),
                        "DNS servers" to (t?.dns?.joinToString(", ")?.ifEmpty { null } ?: "—"),
                        "Routes" to (t?.routes?.lastOrNull() ?: "—"),
                        "Data cipher" to ((t?.cipher ?: info?.cipher)?.let { if (t?.compressed == true) "$it, with compression" else it } ?: "Chosen by the server"),
                        if (info?.kind == VpnKind.WireGuard) "Key exchange" to "Curve25519" else "Control channel" to (info?.tls ?: "TLS"),
                        "Transferred" to (bytes?.let { "${fmtBytes(it[0])} in · ${fmtBytes(it[1])} out" } ?: "—"),
                        "Connected for" to (since?.let { fmtDuration((now - it).coerceAtLeast(0) / 1000) } ?: "—"),
                    ),
                )
              }
                Row(Modifier.padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvButton("Close", close, focusRequester = req["ov-first"])
                    TvButton("View log", {
                        nav.overlay = null
                        nav.openLog(from = nav.screen)
                    }, icon = Ic.Log, glass = true)
                }
            }
        }
    }
}

@Composable
private fun FileSheet(o: Overlay.File, req: Requesters, close: () -> Unit) {
    Sheet(TvSize.SheetMedium) {
        Text(o.eyebrow, style = TvType.eyebrow(15f, 0.6f), maxLines = 1)
        Text(o.title, style = TvType.display(32f), modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(16.dp))
        RawConfig(o.text, maxHeight = 340f, plain = o.plain, focusWhenLong = req["ov-text"])
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
    // The menu opens with a held OK: while a tunnel is up, it doesn't open one press from changing it.
    val tunnelUp = state.status.isActive || state.pausedUntil != null
    Sheet(TvSize.SheetSmall) {
        PanelHeading("Quick menu", c.name)
        Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ListRow(
                when {
                    live -> "Disconnect"
                    tunnelUp -> "Connect instead"
                    else -> "Connect"
                },
                {
                    nav.overlay = null
                    if (live) actions.disconnect() else connect(c.id)
                    nav.contentFocus = nav.screen
                },
                subtitle = profileStatus(state, c).line,
                focusRequester = if (tunnelUp) null else req["ov-first"],
            )
            ListRow(
                "Speed test",
                { nav.overlay = Overlay.Speed(c.id, "Quick menu") },
                subtitle = state.speeds[c.id]?.let { "Last: ${fmtMbps(it.downMbps)} Mbps, ${ago(it.at)}" } ?: "Is it fast enough for 4K?",
                focusRequester = if (tunnelUp) req["ov-first"] else null,
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
                subtitle = if (state.serverOf(c)?.kind == VpnKind.WireGuard) "Name, key, advanced" else "Name, sign-in, advanced",
            )
            ListRow(
                "Duplicate",
                {
                    nav.overlay = null
                    nav.guard(state) {
                        val copy = actions.duplicate(c.id) ?: return@guard
                        nav.copyOf[copy] = c.name
                        nav.go(Screen.Profiles, focusContent = false)
                        nav.profFocus = copy
                        nav.stack.clear()
                        nav.push(Col.Profile(copy), "pe-name")
                    }
                },
                subtitle = Words.DUPLICATE_HELP,
            )
            ListRow(
                if (c.pinned) "Unpin from Quick connect" else "Pin to Quick connect",
                {
                    actions.setPinned(c.id, !c.pinned)
                    nav.toast(if (!c.pinned) "${c.name} is pinned to Quick connect" else "${c.name} is unpinned")
                    close()
                },
                subtitle = "Pinned profiles come first",
            )
            var confirm by remember { mutableStateOf(false) }
            if (confirm) {
                ConfirmBar(
                    // Short, to fit beside the buttons in this narrow sheet; the row above says the rest.
                    deleteQuestion(state, c), "Delete", req["qm-del-no"],
                    onConfirm = {
                        close()
                        nav.guard(state) {
                            // Focus goes on to the next profile in the list.
                            val next = nextAfter(state, c.id)
                            actions.delete(c.id)
                            nav.toast("Deleted ${c.name}")
                            if (nav.screen == Screen.Profiles) {
                                nav.profFocus = next
                                nav.stack.clear()
                                nav.moveStackFocus("pl-${next ?: "add"}")
                            }
                        }
                    },
                    onKeep = {
                        confirm = false
                        nav.refocus(req["qm-del"])
                    },
                )
            } else {
                ListRow(
                    "Delete profile", { confirm = true },
                    subtitle = Words.deleteHelp(state.logins[c.id]?.hasPassword == true),
                    danger = true,
                    focusRequester = req["qm-del"],
                )
            }
        }
    }
}

/** The profile after [id] in the list, or the one before it when [id] is last. */
fun nextAfter(state: VpnUiState, id: String): String? {
    val i = state.connections.indexOfFirst { it.id == id }
    return state.connections.getOrNull(i + 1)?.id ?: state.connections.getOrNull(i - 1)?.id
}

/** Installs the newer build: what it is, then how the download and install are going. */
@Composable
private fun UpdateSheet(state: VpnUiState, actions: TunnelActions, req: Requesters, close: () -> Unit) {
    val u = state.update
    // The buttons change as the update goes on; focus moves to the new first one.
    LaunchedEffect(u::class) { req["ov-first"].focusWhenReady() }
    Sheet(TvSize.SheetMedium) {
        when (u) {
            is UpdateState.Available -> if (u.newKey) {
                PanelHeading("Update", "Build ${u.build.number} needs a fresh install", dev.shieldvpn.vpn.NEW_KEY, titleSize = 32f)
                Row(Modifier.padding(top = 24.dp)) { TvButton("Close", close, focusRequester = req["ov-first"]) }
            } else {
                PanelHeading("Update", "Install build ${u.build.number}?", "${dev.shieldvpn.APP_NAME} ${u.build.version}, ${u.build.size / 100_000 / 10f} MB. Android asks you to confirm. If the VPN is on, it connects again after.", titleSize = 32f)
                Row(Modifier.padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvButton("Install", actions::installUpdate, icon = Ic.Download, focusRequester = req["ov-first"])
                    TvButton("Not now", close, glass = true)
                }
            }
            is UpdateState.Downloading -> {
                PanelHeading("Update", "Downloading build ${u.build.number}", "${u.percent}%. The update is checked before Android installs it.", titleSize = 32f)
                Progress(u.percent / 100f, Modifier.padding(top = 20.dp))
                Row(Modifier.padding(top = 24.dp)) { TvButton("Hide", close, glass = true, focusRequester = req["ov-first"]) }
            }
            is UpdateState.Installing -> {
                PanelHeading("Update", "Installing build ${u.build.number}", "Confirm it when Android asks. The app closes and opens again on the new build.", titleSize = 32f)
                Row(Modifier.padding(top = 24.dp)) { TvButton("Hide", close, glass = true, focusRequester = req["ov-first"]) }
            }
            is UpdateState.Failed -> {
                PanelHeading("Update", "The update didn’t install", u.message, titleSize = 32f)
                Row(Modifier.padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvButton("Try again", actions::checkUpdate, icon = Ic.Swap, focusRequester = req["ov-first"])
                    TvButton("Close", close, glass = true)
                }
            }
            else -> {
                PanelHeading("Update", "This is the newest build", "${dev.shieldvpn.APP_NAME} looks for a newer one each time it opens.", titleSize = 32f)
                Row(Modifier.padding(top = 24.dp)) { TvButton("Close", close, focusRequester = req["ov-first"]) }
            }
        }
    }
}

/** A thin bar that fills as something goes on. */
@Composable
fun Progress(fraction: Float, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(Tv.white(0.12f))) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(8.dp).clip(CircleShape).background(Tv.Mint))
    }
}

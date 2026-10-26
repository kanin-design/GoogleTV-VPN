package dev.shieldvpn.ui.tv

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.shieldvpn.vpn.Connection
import dev.shieldvpn.vpn.VpnStatus
import dev.shieldvpn.vpn.VpnUiState
import dev.shieldvpn.vpn.isActive

/**
 * Connect: the current profile, huge, over the background, with the one button most visits need
 * already focused. Quick connect cards below preview a profile as focus moves over them.
 */
@Composable
fun ConnectScreen(state: VpnUiState, nav: Nav, actions: TunnelActions, connect: (String) -> Unit) {
    val primaryFocus = remember { FocusRequester() }
    LaunchedEffect(nav.contentFocus) {
        if (nav.contentFocus == Screen.Connect) {
            primaryFocus.focusWhenReady()
            nav.contentFocus = null
        }
    }
    // The hero's buttons change with the tunnel's state. If focus was on one that goes away
    // (Pause, Cancel, Disconnect), it moves to the new main button instead of the tabs.
    val mode = modeOf(state)
    SideEffect { nav.entry = primaryFocus }
    var heroFocused by remember { mutableStateOf(false) }
    var heroLostAt by remember { mutableLongStateOf(0L) }
    LaunchedEffect(mode) {
        if (heroFocused || System.currentTimeMillis() - heroLostAt < 800) primaryFocus.focusWhenReady()
    }
    Box(Modifier.fillMaxSize()) {
        // The first profile: the phone's code is right here, so it can be scanned with no press at all.
        if (state.connections.isEmpty()) {
            Box(Modifier.align(Alignment.BottomStart).padding(start = 64.dp, bottom = 64.dp)) { FirstProfile(state, nav, actions, primaryFocus) }
            return@Box
        }
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = 64.dp, bottom = 224.dp)
                .onFocusChanged {
                    if (heroFocused && !it.hasFocus) heroLostAt = System.currentTimeMillis()
                    heroFocused = it.hasFocus
                },
        ) {
            AnimatedContent(
                targetState = nav.preview ?: state.activeId,
                transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
                label = "hero",
            ) { id -> Hero(state, nav, actions, connect, state.connection(id), primaryFocus) }
        }
        QuickRow(state, nav, connect, primaryFocus, Modifier.align(Alignment.BottomStart))
    }
}

@Composable
private fun Hero(
    state: VpnUiState,
    nav: Nav,
    actions: TunnelActions,
    connect: (String) -> Unit,
    c: Connection?,
    primaryFocus: FocusRequester,
) {
    Column(horizontalAlignment = Alignment.Start) {
        if (c == null) return@Column
        val current = c.id == state.activeId
        val mode = modeOf(state)
        val server = state.serverOf(c)
        val info = state.infoOf(c)
        val blocked = state.status is VpnStatus.Blocked
        val edit = {
            nav.guard(state) {
                nav.go(Screen.Profiles, focusContent = false)
                nav.profFocus = c.id
                nav.stack.clear()
                nav.push(Col.Profile(c.id), "pe-name")
            }
        }

        Title(c.name)
        when {
            !current -> Lede(null, authText(state, c))
            mode == Mode.On -> {
                // The address sites see once it is looked up; until then the tunnel's own.
                val ip = state.tunnel?.publicIp?.let { "VPN IP $it" } ?: state.tunnel?.address?.substringBefore('/')?.let { "Tunnel IP $it" }
                ConnectedFor((state.status as? VpnStatus.Connected)?.sinceMillis, ip)
                Traffic("Traffic from your device goes through your VPN")
            }
            mode == Mode.Busy -> {
                Lede("Connecting…", "")
                // An empty line, so the name stays put from not connected to connecting to connected.
                Traffic("")
            }
            mode == Mode.Paused -> PausedFor(state.pausedUntil)
            mode == Mode.Error && blocked -> Lede("Connection dropped", " · The kill switch is blocking traffic.")
            mode == Mode.Error -> {
                val why = (state.status as? VpnStatus.Failed)?.message ?: "Could not connect"
                Lede("Couldn’t connect", " · " + why + if (why.endsWith(".")) "" else ".")
            }
            else -> {
                Lede("Not connected", "")
                Traffic("Traffic from your device goes directly to the internet")
            }
        }
        Meta(listOfNotNull(server?.host, server?.let { "${it.protocol.label} ${it.port}" }, info?.cipher))
        // The buttons change with the tunnel (Resume now goes, Pause comes), and the one pressed
        // goes with them: focus moves to the new first button instead of falling to the cards.
        var inActions by remember { mutableStateOf(false) }
        val hadFocus = inActions
        LaunchedEffect(mode) { if (hadFocus) primaryFocus.focusWhenReady() }
        Actions({ inActions = it }) {
            when {
                !current -> {
                    // While another tunnel is up, the word says this one takes its place.
                    TvButton(if (state.status.isActive || state.pausedUntil != null) "Connect instead" else "Connect", {
                        nav.preview = null
                        connect(c.id)
                    }, icon = Ic.Power, focusRequester = primaryFocus)
                    TvButton("Edit profile", edit, icon = Ic.Pencil, glass = true)
                }
                mode == Mode.On -> {
                    TvButton("Disconnect", actions::disconnect, icon = Ic.Power, focusRequester = primaryFocus)
                    TvButton("Pause 15 minutes", {
                        actions.pause()
                        nav.toast("Paused for 15 minutes")
                    }, icon = Ic.Pause, glass = true)
                    TvButton("Speed test", { nav.overlay = Overlay.Speed(c.id) }, icon = Ic.Gauge, glass = true)
                    TvButton("Details", { nav.overlay = Overlay.Details }, icon = Ic.Info, glass = true)
                }
                mode == Mode.Busy -> TvButton("Cancel", actions::disconnect, icon = Ic.Power, focusRequester = primaryFocus)
                mode == Mode.Paused -> {
                    TvButton("Resume now", actions::resume, icon = Ic.Play, focusRequester = primaryFocus)
                    TvButton("15 more minutes", {
                        actions.pauseMore()
                        nav.toast("15 more minutes")
                    }, icon = Ic.Plus, glass = true)
                    TvButton("Disconnect", actions::disconnect, icon = Ic.Power, glass = true)
                }
                mode == Mode.Error -> {
                    TvButton("Try again", { connect(c.id) }, icon = Ic.Swap, focusRequester = primaryFocus)
                    TvButton("View log", { nav.openLog(from = Screen.Connect) }, icon = Ic.Log, glass = true)
                    TvButton("Edit profile", edit, icon = Ic.Pencil, glass = true)
                    if (blocked) {
                        TvButton("Allow internet", {
                            actions.disconnect()
                            nav.toast("Traffic allowed until you connect again")
                        }, glass = true)
                    }
                }
                else -> TvButton("Connect", { connect(c.id) }, icon = Ic.Power, focusRequester = primaryFocus)
            }
        }
    }
}

/** No profile yet: the Import page's code, and the other ways in. */
@Composable
private fun FirstProfile(state: VpnUiState, nav: Nav, actions: TunnelActions, primaryFocus: FocusRequester) {
    val session = rememberImportSession(state, nav, actions)
    Column(horizontalAlignment = Alignment.Start) {
        Title("Add your VPN profile", size = 72f)
        Lede(null, "Scan the code with your phone and send the .ovpn file.")
        PhoneBlock(session, PhoneBlockSize.Wide, Modifier.padding(top = 26.dp), steps = listOf("Scan the code with your phone’s camera", "Pick the .ovpn file, paste it or type a link", "Check it here and save it"))
        Actions {
            TvButton("Other ways", {
                nav.importMethod = if (actions.hasFilePicker) ImportMethod.Files else ImportMethod.Link
                nav.go(Screen.Import)
            }, icon = Ic.Plus, glass = true, focusRequester = primaryFocus)
        }
    }
}

/** .ttl */
@Composable
private fun Title(text: String, size: Float = 104f) {
    Text(text, style = TvType.title(size), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 1100.dp))
}

/** How long the tunnel has been up. Only this line ticks each second, not the whole hero. */
@Composable
private fun ConnectedFor(since: Long?, ip: String?) {
    val now = rememberNow()
    Lede("Connected", " for ${fmtDuration((now - (since ?: now)).coerceAtLeast(0) / 1000)}" + (ip?.let { " · $it" } ?: ""))
}

/** How long the pause has left, ticking on its own. */
@Composable
private fun PausedFor(until: Long?) {
    val now = rememberNow()
    Lede("Paused", " · back on in ${fmtMinutes(((until ?: now) - now).coerceAtLeast(0) / 1000)}")
}

/** .lede: the state in bold, then what it means. */
@Composable
private fun Lede(bold: String?, rest: String) {
    Text(
        buildAnnotatedString {
            if (bold != null) withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Color.White)) { append(bold) }
            append(rest)
        },
        style = TvType.ui(21f, color = Tv.soft(0.8f)).copy(lineHeight = 29.sp),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 14.dp).widthIn(max = 820.dp),
    )
}

/** Where the device's traffic goes, on its own line under the lede, as the background shows it. */
@Composable
private fun Traffic(text: String) {
    Text(text, style = TvType.ui(19f, color = Tv.soft(0.62f)), maxLines = 1, modifier = Modifier.padding(top = 6.dp))
}

/** .meta: host, protocol and port, cipher, with dots between. */
@Composable
private fun Meta(parts: List<String>) {
    Row(
        modifier = Modifier.padding(top = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        parts.forEachIndexed { i, part ->
            if (i > 0) Box(Modifier.size(4.dp).clip(CircleShape).background(Tv.soft(0.5f)))
            Text(part, style = TvType.ui(17f, FontWeight.Medium, Tv.soft(0.6f)), maxLines = 1)
        }
    }
}

@Composable
private fun Actions(focused: (Boolean) -> Unit = {}, content: @Composable () -> Unit) {
    Row(
        modifier = Modifier.padding(top = 26.dp).onFocusChanged { focused(it.hasFocus) },
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

/** Pinned profiles first, then the most recently used. */
fun quickProfiles(state: VpnUiState): List<Connection> =
    state.connections
        .withIndex()
        .sortedWith(compareBy({ if (it.value.pinned) 0 else 1 }, { -(it.value.lastUsed ?: 0L) }, { it.index }))
        .map { it.value }
        .take(4)

@Composable
private fun QuickRow(state: VpnUiState, nav: Nav, connect: (String) -> Unit, primaryFocus: FocusRequester, modifier: Modifier) {
    val quick = quickProfiles(state)
    Column(
        modifier = modifier
            .padding(start = 64.dp, end = 64.dp, bottom = 36.dp)
            .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("QUICK CONNECT", style = TvType.eyebrow(15f, 0.62f))
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            quick.forEach { c ->
                val current = c.id == state.activeId
                val look = lookOf(c)
                LookCard(
                    colours = look.colours,
                    iconPath = look.icon.path,
                    iconSize = 30f,
                    title = c.name,
                    // The current card names its server while nothing happens, and says what is happening otherwise.
                    subtitle = if (current && modeOf(state) == Mode.Off) state.serverOf(c)?.host.orEmpty() else statusText(state, c),
                    onFocus = {
                        nav.preview = if (current) null else c.id
                        nav.focusedPid = c.id
                    },
                    onBlur = { if (nav.focusedPid == c.id) nav.focusedPid = null },
                    onClick = {
                        val live = current && (modeOf(state) == Mode.On || modeOf(state) == Mode.Busy)
                        if (live) primaryFocus.tryFocus() else connect(c.id)
                    },
                    modifier = Modifier.weight(1f),
                )
            }
            LookCard(
                colours = AllProfilesColours,
                iconPath = AppsPath,
                iconSize = 28f,
                title = "All profiles",
                subtitle = "${state.connections.size} on this device",
                onClick = { nav.go(Screen.Profiles) },
                modifier = Modifier.weight(1f),
            )
            repeat(4 - quick.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

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
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.withFrameNanos
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
import dev.shieldvpn.control.ProfileStatus
import dev.shieldvpn.control.Words
import dev.shieldvpn.vpn.Connection
import dev.shieldvpn.vpn.VpnController
import dev.shieldvpn.vpn.VpnKind
import dev.shieldvpn.vpn.VpnStatus
import dev.shieldvpn.vpn.VpnUiState
import dev.shieldvpn.vpn.isActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Connect: the current profile, huge, over the background, with the one button most visits need
 * already focused. Quick connect cards below preview a profile as focus moves over them.
 */
@Composable
fun ConnectScreen(state: VpnUiState, nav: Nav, actions: TunnelActions, connect: (String) -> Unit) {
    val primaryFocus = remember { FocusRequester() }
    val logFocus = remember { FocusRequester() }
    LaunchedEffect(nav.contentFocus) {
        if (nav.contentFocus == Screen.Connect) {
            // Back from the log goes to View log, not to Try again, which would connect again.
            val fromLog = nav.connectReturn == "log" && modeOf(state) == Mode.Error
            nav.connectReturn = null
            (if (fromLog) logFocus else primaryFocus).focusWhenReady()
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
                .padding(start = 64.dp, bottom = 228.dp)
                .onFocusChanged {
                    if (heroFocused && !it.hasFocus) heroLostAt = System.currentTimeMillis()
                    heroFocused = it.hasFocus
                },
        ) {
            AnimatedContent(
                targetState = nav.preview ?: state.activeId,
                transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
                label = "hero",
            ) { id -> Hero(state, nav, actions, connect, state.connection(id), primaryFocus, logFocus) }
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
    logFocus: FocusRequester,
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

        // Five rows, each one line high and always there, so nothing moves between states and every
        // profile takes the same room: the name, the status, a live line, the kind and server, the buttons.
        val kind = info?.kind ?: VpnKind.OpenVpn
        Title(c.name)
        val failure = (state.status as? VpnStatus.Failed)?.message
        // The state's word in its colour, as the drawing and the card show it; a preview of
        // another profile says its own state in white.
        val tone = if (current) Tv.tone(mode) else null
        when {
            !current -> Idle(state, c, null)
            mode == Mode.On -> {
                ConnectedFor((state.status as? VpnStatus.Connected)?.sinceMillis)
                LiveTraffic(state.tunnel?.publicIp?.let { "VPN IP $it" } ?: state.tunnel?.address?.substringBefore('/')?.let { "Tunnel IP $it" } ?: "VPN IP: looking up")
            }
            mode == Mode.Busy -> {
                ConnectingFor((state.status as? VpnStatus.Connecting)?.sinceMillis)
                Live(if (kind == VpnKind.WireGuard) "Waiting for the server’s handshake" else "Reaching ${server?.host ?: "the server"}")
            }
            mode == Mode.Paused -> {
                PausedFor(state.pausedUntil)
                Live("Traffic goes directly to the internet until then")
            }
            mode == Mode.Error && blocked -> {
                // A tunnel that never came up (a switch, a try again) says why; one that dropped says so.
                val why = (state.status as? VpnStatus.Blocked)?.reason
                if (why != null) {
                    Lede("Couldn’t connect", " · ${firstSentence(why)}", tone)
                    Live("The kill switch holds traffic back. Try again, or allow internet")
                } else {
                    Lede("Blocked", " · the tunnel dropped and the kill switch is on", tone)
                    Live("Nothing reaches the internet until the VPN connects again, or you let traffic out without it")
                }
            }
            mode == Mode.Error -> {
                val why = failure ?: "Could not connect"
                Lede("Couldn’t connect", " · ${firstSentence(why)}", tone)
                Live(whatToCheck(why))
            }
            else -> Idle(state, c, tone)
        }
        // Connected, the cipher the server chose; otherwise what the file suggests. The kind of VPN
        // leads the line, as the one place it is said.
        val cipher = state.tunnel?.cipher?.takeIf { current } ?: info?.cipher
        Meta(listOf(listOfNotNull(Words.kind(kind), server?.let { Words.serverLine(it.host, it.port, it.protocol, cipher) }).joinToString(" · ")))
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
                    }, icon = Ic.Power, primary = true, focusRequester = primaryFocus)
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
                mode == Mode.Busy -> TvButton("Cancel", actions::disconnect, icon = Ic.Close, focusRequester = primaryFocus)
                mode == Mode.Paused -> {
                    TvButton("Resume now", actions::resume, icon = Ic.Play, primary = true, focusRequester = primaryFocus)
                    TvButton("15 more minutes", {
                        actions.pauseMore()
                        nav.toast("15 more minutes")
                    }, icon = Ic.Plus, glass = true)
                    TvButton("Disconnect", actions::disconnect, icon = Ic.Power, glass = true)
                }
                mode == Mode.Error -> {
                    TvButton("Try again", { connect(c.id) }, icon = Ic.Swap, primary = true, focusRequester = primaryFocus)
                    TvButton("View log", { nav.openLog(from = Screen.Connect) }, icon = Ic.Log, glass = true, focusRequester = logFocus)
                    // Three buttons at most: while blocked, letting traffic out matters more than editing.
                    if (blocked) {
                        TvButton("Allow internet", {
                            actions.disconnect()
                            nav.toast("Traffic allowed until you connect again")
                        }, icon = Ic.Globe, glass = true)
                    } else {
                        TvButton("Edit profile", edit, icon = Ic.Pencil, glass = true)
                    }
                }
                else -> {
                    TvButton("Connect", { connect(c.id) }, icon = Ic.Power, primary = true, focusRequester = primaryFocus)
                    TvButton("Edit profile", edit, icon = Ic.Pencil, glass = true)
                }
            }
        }
    }
}

/** No profile yet: the Import page's code, and the other ways in. */
@Composable
private fun FirstProfile(state: VpnUiState, nav: Nav, actions: TunnelActions, primaryFocus: FocusRequester) {
    val session = rememberImportSession(state, nav, actions)
    Column(horizontalAlignment = Alignment.Start) {
        Title("Add your VPN profile", size = TvType.HeroCompact, maxWidth = 1100f)
        Lede(null, "Scan the code with your phone and send the .ovpn file.")
        PhoneBlock(session, PhoneBlockSize.Wide, Modifier.padding(top = 24.dp), steps = listOf("Scan the code with your phone’s camera", "Pick the .ovpn file, paste it or type a link", "Check the profile here and save it"))
        Actions {
            TvButton("Other ways", {
                nav.importMethod = if (actions.hasFilePicker) ImportMethod.Files else ImportMethod.Link
                nav.go(Screen.Import)
            }, icon = Ic.Plus, glass = true, focusRequester = primaryFocus)
        }
    }
}

/**
 * .ttl: a profile's name, a size smaller when it is long. It stops short of the drawing on the
 * right, where the path to the server rises.
 */
@Composable
private fun Title(text: String, size: Float = if (text.length > 14) TvType.HeroCompact else TvType.Hero, maxWidth: Float = 680f) {
    Text(text, style = TvType.title(size), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = maxWidth.dp))
}

/** How long the tunnel has been up. Only this line ticks each second, not the whole hero. */
@Composable
private fun ConnectedFor(since: Long?) {
    val now = rememberNow()
    Lede("Connected", " · ${fmtDuration((now - (since ?: now)).coerceAtLeast(0) / 1000)}", Tv.tone(Mode.On))
}

/** How long it has been trying, from when the attempt began, on every screen alike. */
@Composable
private fun ConnectingFor(since: Long?) {
    val fallback = remember { System.currentTimeMillis() }
    val start = since ?: fallback
    val now = rememberNow()
    Lede("Connecting", " · ${fmtDuration(((now - start) / 1000).coerceAtLeast(0))}", Tv.tone(Mode.Busy))
}

/**
 * Nothing running for [c]: the same two lines whether it is the current profile or a preview,
 * in the words its card uses ([ProfileStatus]).
 */
@Composable
private fun Idle(state: VpnUiState, c: Connection, tone: Color?) {
    val now = minuteTick.longValue
    val ps = ProfileStatus.of(c, null, state.asksForLogin(c), now)
    val failed = c.lastError
    if (failed != null) {
        Lede(ps.word, " · ${firstSentence(failed)}", tone)
        Live(whatToCheck(failed))
    } else {
        Lede(ps.word, "", tone)
        Live(ps.detail ?: Words.lastConnected(c.lastUsed, now))
    }
}

/** The live line: what is happening now, in the same place in every state. */
@Composable
private fun Live(text: String) {
    // A fixed height: some glyphs (the traffic arrows) come from a fallback font with a taller line.
    Text(text, style = TvType.ui(19f, color = Tv.soft(0.66f)), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp).height(25.dp).widthIn(max = 820.dp))
}

/** The address sites see and what went through, read every two seconds while it shows. */
@Composable
private fun LiveTraffic(ip: String) {
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
    Live(ip + (bytes?.let { " · ↓ ${fmtBytes(it[0])}  ↑ ${fmtBytes(it[1])}" } ?: ""))
}

/** A failure's first sentence, for the status line; the rest goes on the live line. */
private fun firstSentence(text: String): String = text.substringBefore(". ").trimEnd('.')

/** What to check after [why], for the live line. */
private fun whatToCheck(why: String): String = Words.whatToCheck(why)

/** How long the pause has left, ticking on its own. */
@Composable
private fun PausedFor(until: Long?) {
    val now = rememberNow()
    Lede("Paused", " · connects again in ${fmtMinutes(((until ?: now) - now).coerceAtLeast(0) / 1000)}", Tv.tone(Mode.Paused))
}

/** .lede: the state in bold, in its [tone] when it is the tunnel's, then what it means. */
@Composable
private fun Lede(bold: String?, rest: String, tone: Color? = null) {
    Text(
        buildAnnotatedString {
            if (bold != null) withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = tone ?: Color.White)) { append(bold) }
            append(rest)
        },
        style = TvType.ui(21f, color = Tv.soft(0.8f)).copy(lineHeight = 29.sp),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 16.dp).height(26.dp).widthIn(max = 820.dp),
    )
}

/** .meta: the kind of VPN, host, protocol and port, cipher, with dots between. */
@Composable
private fun Meta(parts: List<String>) {
    Row(
        modifier = Modifier.padding(top = 12.dp).height(21.dp),
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
        modifier = Modifier.padding(top = 24.dp).onFocusChanged { focused(it.hasFocus) },
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

/** Pinned profiles first, then the most recently used; the profile Connect shows is always one of them. */
fun quickProfiles(state: VpnUiState): List<Connection> {
    val sorted = state.connections
        .withIndex()
        .sortedWith(compareBy({ if (it.value.pinned) 0 else 1 }, { -(it.value.lastUsed ?: 0L) }, { it.index }))
        .map { it.value }
    val four = sorted.take(4)
    val active = state.active ?: return four
    return if (active in four) four else four.take(3) + active
}

@Composable
private fun QuickRow(state: VpnUiState, nav: Nav, connect: (String) -> Unit, primaryFocus: FocusRequester, modifier: Modifier) {
    val quick = quickProfiles(state)
    Column(
        modifier = modifier
            // 40 from the bottom: a focused card grows 4 past its edge, and stays inside the screen's margin.
            .padding(start = 64.dp, end = 64.dp, bottom = 40.dp)
            .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Quick connect", style = TvType.eyebrow(15f, 0.62f))
        // The profile Connect shows says how its tunnel is doing in that state's colour.
        val mode = modeOf(state)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            quick.forEach { c ->
                val current = c.id == state.activeId
                val look = lookOf(c)
                LookCard(
                    colours = look.colours,
                    iconPath = look.icon.path,
                    iconSize = 30f,
                    title = c.name,
                    subtitle = cardStatus(state, c),
                    // A profile that failed last time says so in red: its line is only the reason.
                    tone = when {
                        current && mode != Mode.Off -> Tv.tone(mode)
                        profileStatus(state, c).tone == ProfileStatus.Tone.Bad -> Tv.Red
                        else -> null
                    },
                    onFocus = {
                        nav.preview = if (current) null else c.id
                        nav.focusedPid = c.id
                    },
                    onBlur = { if (nav.focusedPid == c.id) nav.focusedPid = null },
                    onClick = {
                        // A card connects its profile, switching from another one if it is up. The card of
                        // the profile that is up or connecting goes to Disconnect instead.
                        val live = current && (mode == Mode.On || mode == Mode.Busy)
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

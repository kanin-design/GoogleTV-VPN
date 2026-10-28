package dev.shieldvpn.ui.tv

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.shieldvpn.control.Words
import dev.shieldvpn.APP_NAME
import dev.shieldvpn.phone.PhoneAction
import dev.shieldvpn.phone.PhoneField
import dev.shieldvpn.phone.PhoneForm
import dev.shieldvpn.phone.PhoneReply
import dev.shieldvpn.phone.PhoneSection
import dev.shieldvpn.phone.PhoneSession
import dev.shieldvpn.phone.Staged
import dev.shieldvpn.vpn.Certificates
import dev.shieldvpn.vpn.Inspected
import dev.shieldvpn.vpn.Connection
import dev.shieldvpn.vpn.VpnKind
import dev.shieldvpn.vpn.VpnStatus
import dev.shieldvpn.vpn.VpnUiState
import dev.shieldvpn.vpn.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Material's shared-axis "in" curve, as in the design: decelerate over 420 ms. */
private val SlideEase = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

private enum class Role { Root, Mini, Cur, Gone }

private fun roleOf(i: Int, depth: Int): Role = when {
    depth == 0 -> if (i == 0) Role.Root else Role.Gone
    i == depth -> Role.Cur
    i == depth - 1 -> Role.Mini
    else -> Role.Gone
}

/**
 * Profiles: a sliding stack of columns, like tvOS Settings. Deeper, everything slides left
 * and the column you came from narrows to its names; Back slides it right again. Column
 * content keeps a fixed width and is clipped, so text never reflows as a column narrows.
 */
@Composable
fun ProfilesScreen(state: VpnUiState, nav: Nav, actions: TunnelActions, connect: (String) -> Unit) {
    val req = remember { Requesters() }
    val depth = nav.stack.size
    SideEffect { nav.entry = req[nav.stack.lastOrNull()?.let { entryKey(it, state) } ?: "pl-${nav.profFocus ?: "add"}"] }

    LaunchedEffect(state.connections) {
        nav.keepLists(state.connections.map { it.id }.toSet())
        if (state.connection(nav.profFocus) == null) nav.profFocus = state.connections.firstOrNull()?.id
        // A profile that is gone takes its columns with it.
        if (nav.stack.isNotEmpty() && state.connection(nav.stack.first().id) == null) {
            nav.stack.clear()
            nav.moveStackFocus("pl-${nav.profFocus ?: "add"}")
        }
    }
    LaunchedEffect(nav.contentFocus) {
        if (nav.contentFocus == Screen.Profiles) {
            val key = nav.stack.lastOrNull()?.let { entryKey(it, state) } ?: "pl-${nav.profFocus ?: "add"}"
            awaitLayout()
            if (!req[key].tryFocus()) req["pl-add"].tryFocus()
            nav.contentFocus = null
        }
    }
    LaunchedEffect(nav.stackMoves) {
        // Used once: coming back to this screen must not replay an old move.
        val key = nav.stackFocus ?: return@LaunchedEffect
        nav.stackFocus = null
        // The row may only come back once the columns have slid (after a delete): keep trying a moment.
        repeat(6) {
            req[key].focusWhenReady()
            if (req[key].hasFocusNow) return@LaunchedEffect
            delay(120)
        }
    }

    // The open profile's phone page, with one code for all of its columns and sheets.
    nav.stack.firstOrNull()?.let { state.connection(it.id) }?.let { ProfilePhone(state, nav, actions, it) }

    Box(Modifier.fillMaxSize()) {
        val heroAlpha by animateFloatAsState(if (depth == 0) 1f else 0f, tween(250), label = "heroA")
        val heroShift by animateFloatAsState(if (depth == 0) 0f else 40f, tween(300, easing = SlideEase), label = "heroX")
        Box(
            Modifier
                .align(Alignment.BottomStart)
                // On the panels' bottom edge, 36 above the screen's.
                .padding(start = 536.dp, bottom = TvSize.PanelBottom.dp)
                .width(680.dp)
                .graphicsLayer {
                    alpha = heroAlpha
                    translationX = heroShift * density
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                },
        ) { state.connection(nav.profFocus)?.let { ProfileHero(state, it) } }

        // Coming back into a narrow column lands on the row that is open beside it.
        val openRow = { i: Int -> nav.stack.getOrNull(i)?.let { req[rowKeyOpening(it, nav.stack.getOrNull(i - 1))] } }
        SlidingColumn(roleOf(0, depth), list = true, Modifier.enterAt { openRow(0) }) { mini -> ListColumn(state, nav, req, mini) }

        val last = remember { arrayOfNulls<Col>(4) }
        val slide = with(LocalDensity.current) { 48.dp.roundToPx() }
        for (i in 1..3) {
            val entry = nav.stack.getOrNull(i - 1)
            if (entry != null) last[i] = entry
            AnimatedVisibility(
                visible = entry != null,
                enter = slideInHorizontally(tween(420, easing = SlideEase)) { slide },
                exit = slideOutHorizontally(tween(200)) { slide },
            ) {
                // Faded here, not with fadeIn and fadeOut: those draw the column into a new
                // offscreen buffer at every width it passes, and the buffers stay cached.
                val shown by transition.animateFloat({ tween(if (targetState == EnterExitState.Visible) 300 else 200) }, label = "colIn") {
                    if (it == EnterExitState.Visible) 1f else 0f
                }
                val col = last[i] ?: return@AnimatedVisibility
                SlidingColumn(roleOf(i, depth), list = false, Modifier.enterAt { openRow(i) }, shown = { shown }) { mini ->
                    AnimatedContent(
                        targetState = col,
                        transitionSpec = { (fadeIn(tween(320)) + slideInVertically(tween(320, easing = SlideEase)) { it / 40 }) togetherWith fadeOut(tween(120)) },
                        label = "col$i",
                    ) { shown ->
                        val c = state.connection(shown.id)
                        if (c != null) {
                            when (shown) {
                                is Col.Profile -> ProfileColumn(state, nav, actions, req, c, i, mini, connect)
                                is Col.Advanced -> AdvancedColumn(state, nav, actions, req, c, i, mini)
                                is Col.Leaf -> LeafColumn(state, nav, actions, req, c, shown.kind, i)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Where focus lands in a column. A profile opens on its Connect button, unless a tunnel is up:
 * then on Name, so one OK doesn't switch away from it.
 */
private fun entryKey(col: Col, state: VpnUiState): String = when (col) {
    is Col.Profile -> if (state.status.isActive) "pe-name" else "pe-connect"
    // A file that picks its own servers starts on Protocol, as Server and port can't change it.
    is Col.Advanced -> if (state.connection(col.id)?.let { state.infoOf(it) }?.connectionBlocks == true) "pa-Protocol" else "pa-Server"
    is Col.Leaf -> when (col.kind) {
        LeafKind.Look -> "lk-sel"
        LeafKind.SignIn -> "sl-login"
        LeafKind.Protocol, LeafKind.Order, LeafKind.Kill, LeafKind.Dns -> "op-sel"
        LeafKind.File -> "ed-done"
    }
}

@Composable
private fun SlidingColumn(role: Role, list: Boolean, focus: Modifier, shown: () -> Float = { 1f }, content: @Composable (mini: Boolean) -> Unit) {
    val left by animateDpAsState(
        when (role) {
            Role.Root, Role.Mini -> 64.dp
            Role.Cur -> 352.dp
            Role.Gone -> 24.dp
        },
        tween(420, easing = SlideEase),
        label = "left",
    )
    val width by animateDpAsState(
        when (role) {
            Role.Root -> TvSize.ListWidth.dp
            Role.Mini, Role.Gone -> 264.dp
            Role.Cur -> 864.dp
        },
        tween(420, easing = SlideEase),
        label = "width",
    )
    val alpha by animateFloatAsState(if (role == Role.Gone) 0f else 1f, tween(300), label = "alpha")
    // The narrow column fades out at its right edge, so it reads as behind the current one.
    val fade by animateFloatAsState(if (role == Role.Mini || role == Role.Gone) 1f else 0f, tween(420, easing = SlideEase), label = "fade")
    if (alpha <= 0.01f && role == Role.Gone) return
    Glass(
        modifier = Modifier
            .placed({ left }, 124.dp, { width })
            .fillMaxHeight()
            .padding(bottom = 160.dp)
            // No offscreen buffer: one would be made again at every width while the column
            // narrows, and the graphics driver keeps each of them while the tunnel draws.
            .graphicsLayer {
                this.alpha = alpha * shown()
                compositingStrategy = CompositingStrategy.ModulateAlpha
            },
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .wrapContentWidth(Alignment.Start, unbounded = true)
                .width(if (list) TvSize.ListWidth.dp else 864.dp)
                .then(focus),
        ) { content(role == Role.Mini) }
        // The edge sinks into the dark page beside it, which looks the same as fading the column.
        if (fade > 0.01f) {
            Box(
                Modifier.matchParentSize().drawBehind {
                    drawRect(Brush.horizontalGradient(0.84f to Color.Transparent, 1f to Tv.Page.copy(alpha = fade)))
                },
            )
        }
    }
}

/** .c-head: eyebrow, title and a line under it. In the narrow column only the title stays. */
@Composable
private fun ColumnHead(eyebrow: String?, title: String, sub: String?, mini: Boolean, count: Int? = null, inList: Boolean = false, extra: (@Composable () -> Unit)? = null) {
    val fold by animateFloatAsState(if (mini) 0f else 1f, tween(250), label = "headFold")
    // In a list, its padding and gap already give part of the inset.
    val side = if (inList) TvSize.PanelInset - 16 else TvSize.PanelInset
    Column(Modifier.padding(start = side.dp, end = side.dp, top = (if (inList) TvSize.PanelInsetY - 8 else TvSize.PanelInsetY).dp, bottom = if (inList) 4.dp else 12.dp)) {
        if (eyebrow != null && fold > 0.01f) Text(eyebrow, style = TvType.eyebrow(15f, 0.6f), maxLines = 1, modifier = Modifier.fade { fold })
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = if (eyebrow != null) 4.dp else 0.dp)) {
            Text(title, style = TvType.display(if (mini) TvType.Heading else 32f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (count != null && !mini) Text("$count", style = TvType.ui(17f, FontWeight.SemiBold, Tv.soft(0.6f)), modifier = Modifier.padding(start = 12.dp, bottom = 4.dp))
        }
        if (sub != null && !mini) Text(sub, style = TvType.ui(17f, color = Tv.soft(0.68f)), maxLines = 1, modifier = Modifier.padding(top = 8.dp))
        if (extra != null && !mini) Box(Modifier.padding(top = 16.dp)) { extra() }
    }
}

// ---- The list --------------------------------------------------------------------

@Composable
private fun ListColumn(state: VpnUiState, nav: Nav, req: Requesters, mini: Boolean) {
    Column(Modifier.fillMaxSize()) {
        ColumnHead(null, "Profiles", null, mini, count = state.connections.size)
        val list = rememberLazyListState()
        WholeRows(8.dp) { LazyColumn(
            state = list,
            modifier = Modifier.scrollFade(list),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.connections, key = { it.id }) { c ->
                val open = nav.stack.firstOrNull()?.id == c.id
                ListRow(
                    title = c.name,
                    subtitle = statusText(state, c),
                    kind = RowKind.Profile,
                    open = open,
                    mini = mini,
                    modifier = Modifier.onFocusChanged { if (!it.isFocused && nav.focusedPid == c.id) nav.focusedPid = null },
                    focusRequester = req["pl-${c.id}"],
                    onFocus = {
                        nav.focusedPid = c.id
                        if (nav.stack.isEmpty()) nav.profFocus = c.id
                    },
                    onClick = { nav.guard(state) {
                        nav.profFocus = c.id
                        val entry = entryKey(Col.Profile(c.id), state)
                        if (nav.stack.isEmpty()) {
                            // Opened afresh, the column starts at its top, where its entry row is.
                            nav.resetList("profile-${c.id}")
                            nav.push(Col.Profile(c.id), entry)
                        } else if (open) {
                            while (nav.stack.size > 1) nav.stack.removeAt(nav.stack.lastIndex)
                            nav.moveStackFocus(entry)
                        } else {
                            nav.stack.clear()
                            nav.resetList("profile-${c.id}")
                            nav.push(Col.Profile(c.id), entry)
                        }
                    } },
                    leading = { Avatar(lookOf(c), 40f) },
                    trailing = { if (isLive(state, c.id)) GlowDot(Tv.tone(modeOf(state)), 10f) },
                )
            }
            item {
                ListRow(
                    title = "Add a profile",
                    subtitle = "From your phone, USB or a link",
                    kind = RowKind.Profile,
                    mini = mini,
                    focusRequester = req["pl-add"],
                    onFocus = { nav.focusedPid = null },
                    onClick = { nav.go(Screen.Import) },
                    leading = { focused -> AddSquare(focused) },
                )
            }
        } }
    }
}

@Composable
private fun ProfileHero(state: VpnUiState, c: Connection) {
    val server = state.serverOf(c)
    val facts = listOfNotNull(
        authText(state, c),
        "Connects at startup".takeIf { state.settings.boot && state.settings.startupId == c.id },
        "Pinned to Quick connect".takeIf { c.pinned },
    )
    Column(horizontalAlignment = Alignment.Start) {
        Text(c.name, style = TvType.title(TvType.HeroCompact), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 16.dp))
        MetaLine(listOf(listOfNotNull(Words.kind(server?.kind ?: VpnKind.OpenVpn), server?.let { Words.serverLine(it.host, it.port, it.protocol, state.infoOf(c)?.cipher) }).joinToString(" · ")))
        MetaLine(facts)
        Row(Modifier.padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            // One key, two ways to press it.
            Hint("OK", "Open · hold OK for the quick menu")
        }
    }
}

/** .meta: short facts with dots between. */
@Composable
private fun MetaLine(parts: List<String>) {
    Row(
        modifier = Modifier.padding(top = 12.dp),
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
private fun Hint(key: String, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .border(2.dp, Tv.white(0.3f), CircleShape),
            contentAlignment = Alignment.Center,
        ) { Text(key, style = TvType.ui(15f, FontWeight.SemiBold, Color.White)) }
        Text(text, style = TvType.ui(17f, FontWeight.SemiBold, Tv.soft(0.7f)))
    }
}

// ---- A profile ----------------------------------------------------------------------

/** Opens [col] from a row in column [index]: deeper from the current column, or in place of it from the narrow one. */
private fun open(nav: Nav, index: Int, col: Col, focus: String) {
    if (index == nav.stack.size) nav.push(col, focus) else nav.swap(col, focus)
}

/** A row in the narrow column that does not open anything: first step back to its column. */
private fun stepBackTo(nav: Nav, index: Int, key: String): Boolean {
    if (index == nav.stack.size) return false
    while (nav.stack.size > index) nav.stack.removeAt(nav.stack.lastIndex)
    nav.moveStackFocus(key)
    return true
}

/** The name the .ovpn file gets on a phone: the profile's, with its letters, less what file names can't hold. */
fun ovpnName(c: Connection, kind: VpnKind? = null): String =
    c.name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "").trim().ifEmpty { "profile" } + if (kind == VpnKind.WireGuard) ".conf" else ".ovpn"

/** The profile's phone code, big, pointed at one part of its page. */
private fun profilePhone(c: Connection, title: String, lede: String, section: String? = null) =
    Overlay.Phone("profile-${c.id}", "Profiles › ${c.name}", title, lede, section)

@Composable
private fun ProfileColumn(
    state: VpnUiState,
    nav: Nav,
    actions: TunnelActions,
    req: Requesters,
    c: Connection,
    index: Int,
    mini: Boolean,
    connect: (String) -> Unit,
) {
    val server = state.serverOf(c)
    val info = state.infoOf(c)
    val login = state.logins[c.id]
    val live = isLive(state, c.id)
    var confirm by remember(c.id) { mutableStateOf(false) }
    var confirmStartup by remember(c.id) { mutableStateOf(false) }
    val opened = nav.stack.getOrNull(index)
    val openKey = opened?.let { rowKeyOpening(it, nav.stack.getOrNull(index - 1)) }
    val needsLogin = info?.needsLogin != false
    val saved = login != null && login.hasPassword && login.username.isNotEmpty()
    val startup = state.settings.boot && state.settings.startupId == c.id

    Column(Modifier.fillMaxSize()) {
        val list = nav.listState("profile-${c.id}")
        LazyColumn(
            state = list,
            modifier = Modifier.scrollFade(list),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // The heading scrolls with the rows, so the rows under it get the column's height.
            item("head") {
                ColumnHead(nav.copyOf[c.id]?.let { "Copy of $it" }, c.name, listOfNotNull(Words.kind(server?.kind ?: VpnKind.OpenVpn), server?.let { Words.serverLine(it.host, it.port, it.protocol, info?.cipher) }).joinToString(" · "), mini, extra = {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        val connecting = (state.status as? VpnStatus.Connecting)?.connectionId == c.id
                        TvButton(
                            when {
                                connecting -> "Cancel"
                                live -> "Disconnect"
                                else -> "Connect"
                            },
                            {
                                if (live) actions.disconnect() else connect(c.id)
                                // A profile without its password opens the sign-in sheet instead.
                                if (!live && !state.asksForLogin(c)) nav.toast("Connecting to ${c.name}")
                            },
                            icon = if (connecting) Ic.Close else Ic.Power,
                            small = true,
                            focusRequester = req["pe-connect"],
                        )
                        TvButton("Edit on your phone", {
                            nav.overlay = profilePhone(c, "Edit ${c.name} on your phone", "Scan the code: the page has every setting of this profile, and its .ovpn file. Save there keeps only what you changed.")
                        }, icon = Ic.Phone, small = true, glass = true)
                    }
                }, inList = true)
            }
            item {
                ListRow(
                    "Name", { if (!stepBackTo(nav, index, "pe-name")) nav.overlay = Overlay.Form(nameSpec(nav, actions, c)) },
                    subtitle = Words.NAME_HELP, mini = mini,
                    focusRequester = req["pe-name"],
                    flash = nav.flashKey == "pe-name",
                    trailing = { ValueChip(c.name, it) },
                )
            }
            item {
                val look = lookOf(c)
                ListRow(
                    "Icon", { open(nav, index, Col.Leaf(c.id, LeafKind.Look), "lk-sel") },
                    subtitle = if (look.chosen) "Chosen by you" else "Picked from the name", open = openKey == "pe-look", mini = mini,
                    focusRequester = req["pe-look"],
                    trailing = { ValueChip("", it) { Avatar(look, 24f) } },
                )
            }
            // A WireGuard profile signs in with its key: the row shows the public one, which the server needs.
            if (state.infoOf(c)?.kind == VpnKind.WireGuard) item {
                val key = state.logins[c.id]?.publicKey
                ListRow(
                    "This device’s public key",
                    {
                        if (key != null) {
                            nav.overlay = Overlay.File(
                                "Profiles › ${c.name}", "This device’s public key",
                                "$key\n\nThe server needs it in its list of peers. A paired phone can copy it from the panel, in Profiles › ${c.name}.",
                                plain = true,
                            )
                        }
                    },
                    subtitle = key ?: "The key could not be read. Import the .conf file again.",
                    mini = mini,
                    focusRequester = req["pe-signin"],
                    trailing = { if (key != null) Chevron(it) },
                )
            } else item {
                ListRow(
                    "Sign-in", { open(nav, index, Col.Leaf(c.id, LeafKind.SignIn), "sl-login") },
                    subtitle = authText(state, c),
                    open = openKey == "pe-signin", mini = mini,
                    focusRequester = req["pe-signin"],
                    flash = nav.flashKey == "pe-signin",
                    trailing = {
                        ValueChip(Words.signIn(state.infoOf(c), state.logins[c.id], c.keyAlias).method, it)
                    },
                )
            }
            item {
                // Another profile has the slot: say so before taking it, since one OK would.
                val holder = state.settings.startupId?.takeIf { state.settings.boot && it != c.id }?.let { state.connection(it) }
                if (confirmStartup && holder != null && !mini) {
                    ConfirmBar(
                        "${holder.name} stops connecting at startup. Change it to ${c.name}?", "Change", req["pe-startup-no"],
                        onConfirm = {
                            confirmStartup = false
                            if (needsLogin && !saved) nav.overlay = Overlay.SignIn(c.id, save = true, forStartup = true) else startAtBoot(state, actions, nav, c)
                            nav.moveStackFocus("pe-startup")
                        },
                        onKeep = {
                            confirmStartup = false
                            nav.moveStackFocus("pe-startup")
                        },
                        icon = Ic.Rocket,
                    )
                } else {
                    ListRow(
                        // The narrow column, once a page opens beside it, has room for the short form only.
                        if (mini) "Connect at startup" else "Connect when the device starts",
                        {
                            if (stepBackTo(nav, index, "pe-startup")) return@ListRow
                            when {
                                startup -> actions.setStartup(null)
                                holder != null -> confirmStartup = true
                                // It can only connect by itself with its password saved; that is asked for first.
                                needsLogin && !saved -> nav.overlay = Overlay.SignIn(c.id, save = true, forStartup = true)
                                else -> startAtBoot(state, actions, nav, c)
                            }
                        },
                        subtitle = "Connects before you open the app",
                        mini = mini,
                        focusRequester = req["pe-startup"],
                        flash = nav.flashKey == "pe-startup",
                        trailing = { TvSwitch(startup, it) },
                    )
                }
            }
            item {
                ListRow(
                    "Pin to Quick connect",
                    {
                        if (stepBackTo(nav, index, "pe-pin")) return@ListRow
                        actions.setPinned(c.id, !c.pinned)
                    },
                    subtitle = "Show this profile first on the Connect screen", mini = mini,
                    focusRequester = req["pe-pin"],
                    trailing = { TvSwitch(c.pinned, it) },
                )
            }
            item {
                ListRow(
                    "Advanced options", { open(nav, index, Col.Advanced(c.id), entryKey(Col.Advanced(c.id), state)) },
                    subtitle = "Server, protocol, kill switch, DNS, the file", open = openKey == "pe-adv", mini = mini,
                    focusRequester = req["pe-adv"],
                    trailing = { Chevron(it) },
                )
            }
            if (!mini) item { SectionLabel("More") }
            item {
                val last = state.speeds[c.id]
                ListRow(
                    "Speed test", {
                        if (!stepBackTo(nav, index, "pe-speed")) nav.overlay = Overlay.Speed(c.id, "Profiles › ${c.name}")
                    },
                    subtitle = last?.let { "${fmtMbps(it.downMbps)} Mbps ${ago(it.at)} · ${speedVerdict(it.downMbps).first.replaceFirstChar(Char::lowercase)}" }
                        ?: if (live) "Is the VPN fast enough for 4K?" else "Connects, then measures the speed",
                    mini = mini,
                    focusRequester = req["pe-speed"],
                    trailing = { Chevron(it) },
                )
            }
            if (live) {
                item {
                    ListRow(
                        "Connection details", {
                            if (!stepBackTo(nav, index, "pe-details")) nav.overlay = Overlay.Details
                        },
                        subtitle = "Addresses, DNS, routes and what has gone through", mini = mini,
                        focusRequester = req["pe-details"],
                        trailing = { Chevron(it) },
                    )
                }
            }
            item {
                ListRow(
                    "Send to phone", {
                        if (!stepBackTo(nav, index, "pe-send")) {
                            nav.overlay = profilePhone(
                                c, "Send ${c.name} to your phone",
                                "Scan the code and press Download. ${keyNote(state, c)}",
                                section = "download",
                            )
                        }
                    },
                    subtitle = "Get ${Words.fileTitle(state.infoOf(c)?.kind).removePrefix("The ")} on your phone", mini = mini,
                    focusRequester = req["pe-send"],
                    trailing = { Chevron(it) },
                )
            }
            item {
                ListRow(
                    "Duplicate", {
                        if (stepBackTo(nav, index, "pe-dup")) return@ListRow
                        // The copy opens, ready to be changed.
                        val copy = actions.duplicate(c.id) ?: return@ListRow
                        nav.copyOf[copy] = c.name
                        nav.stack.clear()
                        nav.profFocus = copy
                        nav.resetList("profile-$copy")
                        nav.push(Col.Profile(copy), "pe-name")
                    },
                    subtitle = Words.DUPLICATE_HELP, mini = mini,
                    focusRequester = req["pe-dup"],
                    trailing = { Chevron(it) },
                )
            }
            item {
                if (confirm && !mini) {
                    ConfirmBar(
                        text = deleteQuestion(state, c),
                        confirm = "Delete",
                        keep = req["pe-del-no"],
                        onConfirm = {
                            confirm = false
                            // Focus goes on to the next profile in the list.
                            val next = nextAfter(state, c.id)
                            nav.stack.clear()
                            actions.delete(c.id)
                            nav.toast("Deleted ${c.name}")
                            nav.profFocus = next
                            nav.moveStackFocus("pl-${next ?: "add"}")
                        },
                        onKeep = {
                            confirm = false
                            nav.moveStackFocus("pe-del")
                        },
                    )
                } else {
                    ListRow(
                        "Delete profile", {
                            if (stepBackTo(nav, index, "pe-del")) return@ListRow
                            confirm = true
                        },
                        subtitle = Words.deleteHelp(state.logins[c.id]?.hasPassword == true), danger = true, mini = mini,
                        focusRequester = req["pe-del"],
                        trailing = { Chevron(it) },
                    )
                }
            }
        }
    }
}

// ---- Sheets for a profile's text --------------------------------------------------------

private fun nameSpec(nav: Nav, actions: TunnelActions, c: Connection) = FormSpec(
    "Profiles › ${c.name}", "Name", "${Words.NAME_HELP}.",
    listOf(FormField("name", "Name", c.name)), primary = "Save", phone = "profile-${c.id}", phoneSection = "name",
) { v, _ -> renameProfile(actions, nav, c, v["name"].orEmpty()).toForm() }

private fun loginSpec(state: VpnUiState, nav: Nav, actions: TunnelActions, c: Connection): FormSpec {
    val login = state.logins[c.id]
    val saved = login != null && login.hasPassword
    return FormSpec(
        "Profiles › ${c.name} › Sign-in", "Username and password",
        "Kept in the device’s keystore, never in the .ovpn file." + if (saved) " Leave the password empty to keep the saved one." else "",
        listOf(FormField("user", "Username", login?.username.orEmpty()), FormField("pw", "Password", secret = true, optional = saved)),
        primary = "Save", phone = "profile-${c.id}", phoneSection = "signin",
    ) { v, _ -> saveLogin(actions, nav, c, v["user"].orEmpty(), v["pw"].orEmpty(), keepsSaved = saved).toForm() }
}

private fun serverSpec(state: VpnUiState, nav: Nav, actions: TunnelActions, c: Connection): FormSpec {
    val server = state.serverOf(c)
    val info = state.infoOf(c)
    val changed = server != null && info != null && (server.host != info.fileHost || server.port != info.filePort)
    return FormSpec(
        "Profiles › ${c.name} › Advanced", "Server and port", "Use another address than the one in the file.",
        listOf(FormField("host", "Server", server?.host.orEmpty(), mono = true), FormField("port", "Port", server?.port?.toString().orEmpty(), mono = true, max = 5)),
        primary = "Save", phone = "profile-${c.id}", phoneSection = "server",
        extra = if (changed) {
            FormExtra("Use the file’s server") {
                actions.resetServer(c.id)
                nav.flash("pa-Server")
                FormResult.Done
            }
        } else {
            null
        },
    ) { v, _ -> setServer(actions, nav, c, v["host"].orEmpty(), v["port"].orEmpty()).toForm() }
}

/** Settings › DNS's custom servers, typed from a profile that is set to Custom DNS. */
private fun customDnsSpec(state: VpnUiState, nav: Nav, actions: TunnelActions, c: Connection) = FormSpec(
    // The list is the app's, not this profile's: the eyebrow says where it lives.
    "Settings › DNS", "Custom DNS servers", "Shared by every profile set to Custom DNS, not only ${c.name}. Separate them with spaces.",
    listOf(FormField("dns", "DNS servers", state.settings.customDns.joinToString(" "), mono = true, max = 400)),
    primary = "Save", phone = "profile-${c.id}", phoneSection = "dns",
) { v, _ ->
    val text = v["dns"].orEmpty()
    if (dnsServers(text).isEmpty()) {
        FormResult.Problems("dns", "Type at least one DNS server")
    } else {
        val outcome = saveCustomDns(actions, nav, text, "pa-Dns")
        if (outcome is Outcome.Done) actions.setDnsMode(c.id, "Custom")
        outcome.toForm()
    }
}

// ---- The profile's phone page ---------------------------------------------------------------

/** A profile's page on the phone: every field it has, its certificate, and its .ovpn file. */
private fun profileForm(state: VpnUiState, nav: Nav, c: Connection, fileSize: Int?, focus: String?): PhoneForm {
    val server = state.serverOf(c)
    val info = state.infoOf(c)
    val login = state.logins[c.id]
    val needsLogin = info?.needsLogin != false
    val saved = login != null && login.hasPassword && login.username.isNotEmpty()
    val changed = server != null && info != null && (server.host != info.fileHost || server.port != info.filePort)
    val staged = nav.staged["profile-${c.id}"]
    val hasCert = info?.cert != null || c.keyAlias != null
    val certNote = when {
        c.keyAlias != null -> "This profile signs in with a key in the TV’s keystore: ${Certificates.label(c.keyAlias)}."
        info?.cert != null -> "The file has its own certificate, valid ${info.cert.validUntil}."
        else -> "There is no certificate in the file. Add one if the server asks for it."
    }
    val sections = listOfNotNull(
        PhoneSection("name", "Name", fields = listOf(PhoneField.Text("name", "Name", c.name, required = true, mirror = true, help = Words.NAME_HELP_PHONE))),
        if (needsLogin) {
            PhoneSection(
                "signin", "Sign-in",
                note = "${Words.signIn(info, login, c.keyAlias).summary}.",
                fields = listOf(
                    PhoneField.Text("user", "Username", login?.username.orEmpty(), mirror = true),
                    PhoneField.Text("pw", "Password", secret = true, mirror = true, help = if (saved) "Leave the password empty to keep the saved one." else "Saved on the TV, encrypted."),
                ),
                actions = if (saved) listOf(PhoneAction("forget", "Forget the saved password", danger = true, confirm = "Forget the saved password for ${c.name}? The TV asks for it at the next connect.", confirmLabel = "Forget")) else emptyList(),
            )
        } else {
            null
        },
        PhoneSection(
            "server", "Server",
            note = if (changed) "Changed from the file’s ${Words.address(info!!.fileHost.orEmpty(), info.filePort)}." else null,
            fields = listOf(
                PhoneField.Text("host", "Address", server?.host.orEmpty(), mirror = true),
                PhoneField.Text("port", "Port", server?.port?.toString().orEmpty(), input = "numeric", max = 5, mirror = true),
            ),
            actions = if (changed) listOf(PhoneAction("fileserver", "Use the file’s server")) else emptyList(),
        ),
        if (c.dnsMode == "Custom") {
            PhoneSection("dns", "DNS servers", fields = listOf(PhoneField.Lines("dns", "Custom DNS servers", state.settings.customDns, help = "One per line. Every profile set to Custom DNS uses them.", placeholder = "1.1.1.1", mirror = true)))
        } else {
            null
        },
        PhoneSection("save", actions = listOf(PhoneAction("save", "Save", primary = true))),
        if (info?.kind == VpnKind.WireGuard) null else PhoneSection(
            "cert", "Certificate", note = certNote,
            fields = listOf(
                PhoneField.Upload("p12", "Add a .p12 or .pfx file", "The certificate and key in it replace the profile’s", ".p12,.pfx,application/x-pkcs12,application/octet-stream", staged?.let { Staged(it.name, it.bytes.size, "Certificate") }),
            ) + if (staged != null) p12Fields(state, c) else emptyList(),
            actions = if (staged != null) {
                listOf(PhoneAction("attach", "Add to ${c.name}", primary = true, needs = listOf("p12pass"), confirm = if (hasCert) "${c.name} has a certificate already. Replace it?" else null, confirmLabel = "Replace"))
            } else {
                emptyList()
            },
        ),
        // The file holds the profile's private key, so it is offered only after Send to phone on the TV.
        if (fileSize != null && focus == "download") {
            PhoneSection("download", Words.fileTitle(info?.kind), fields = listOf(PhoneField.Download("ovpn", ovpnName(c, info?.kind), fileSize, note = keyNote(state, c))))
        } else {
            PhoneSection("download", Words.fileTitle(info?.kind), note = "The file is only sent after you press Send to phone in the profile on the TV.")
        },
    )
    return PhoneForm("the ${c.name} profile", "Edit ${c.name}", "Changes here go to ${c.name} on the TV. Save keeps only what you changed.", sections)
}

/** The open profile's phone session, for as long as one of its columns is open. */
@Composable
private fun ProfilePhone(state: VpnUiState, nav: Nav, actions: TunnelActions, c: Connection) {
    val fileSize = remember(c.serverId, state.servers[c.serverId], state.infos[c.serverId]) { actions.profileFile(c.id)?.size }
    rememberPhoneSession(
        "profile-${c.id}", state, actions,
        form = { s -> s.connection(c.id)?.let { now -> profileForm(s, nav, now, fileSize, focus) } },
        locked = { locked(actions.stateNow(), nav) },
        onAction = { action, v -> profileAction(this, nav, actions, c.id, action, v) },
        onUpload = { field, name, bytes ->
            if (actions.inspect(name, bytes) != Inspected.Pkcs12) {
                PhoneReply.error(field, "That isn’t a .p12 or .pfx certificate.")
            } else {
                nav.staged["profile-${c.id}"] = StagedFile(name, bytes)
                tvLine = "Received a certificate: $name"
                PhoneReply()
            }
        },
        download = { id ->
            val now = actions.stateNow().connection(c.id)
            when {
                id != "ovpn" || now == null -> null
                focus != "download" -> dev.shieldvpn.phone.PhoneFile.refused(pressOnTv("Send to phone"))
                else -> actions.profileFile(now.id)?.let {
                    val kind = actions.stateNow().infoOf(now)?.kind
                    dev.shieldvpn.phone.PhoneFile(ovpnName(now, kind), if (kind == VpnKind.WireGuard) "text/plain" else "application/x-openvpn-profile", it)
                }
            }
        },
        onDownloaded = { _, name -> tvLine = "Sent $name to your phone" },
    )
}

private suspend fun profileAction(session: PhoneSession, nav: Nav, actions: TunnelActions, id: String, action: String, v: Map<String, String>): PhoneReply {
    val state = actions.stateNow()
    val c = state.connection(id) ?: return PhoneReply.error(null, "This profile isn’t on the TV any more.")
    val server = state.serverOf(c)
    val login = state.logins[c.id]
    val needsLogin = state.infoOf(c)?.needsLogin != false
    return when (action) {
        "save" -> {
            // Every part is checked first, so nothing is saved while another part is wrong.
            val name = v["name"].orEmpty().trim()
            val user = v["user"]?.trim()
            val pw = v["pw"].orEmpty()
            val loginChange = needsLogin && user != null && (pw.isNotEmpty() || user != login?.username.orEmpty())
            val host = v["host"]?.trim()
            val port = v["port"]?.trim()
            val serverChange = host != null && port != null && (host != server?.host || port != server?.port?.toString())
            val dnsText = v["dns"]
            val errors = buildMap {
                if (name.isEmpty()) put("name", "A profile needs a name")
                if (loginChange && pw.isEmpty() && login?.hasPassword != true) put("pw", "Type the password too, to change the username")
                if (loginChange && user.isNullOrBlank()) put("user", "Type the username")
                if (serverChange) serverProblems(host!!, port!!)?.let { putAll(it.errors) }
                if (dnsText != null) dnsProblem(dnsText)?.let { put("dns", it) }
            }
            if (errors.isNotEmpty()) return PhoneReply(errors = errors)
            val done = mutableListOf<String>()
            if (name != c.name) {
                renameProfile(actions, nav, c, name)
                done += "name"
            }
            if (loginChange) {
                saveLogin(actions, nav, c, user!!, pw, keepsSaved = login?.hasPassword == true)
                done += "signin"
            }
            if (serverChange) {
                setServer(actions, nav, c, host!!, port!!)
                done += "server"
            }
            if (dnsText != null && dnsServers(dnsText) != state.settings.customDns) {
                saveCustomDns(actions, nav, dnsText, "pa-Dns")
                done += "dns"
            }
            done.forEach { session.saved(it) }
            if (done.isNotEmpty()) session.tvLine = "Saved from your phone"
            PhoneReply.done(if (done.isEmpty()) "Nothing changed." else "Saved on the TV.", clear = listOf("pw"))
        }
        "forget" -> {
            actions.forgetPassword(c.id)
            session.saved("signin")
            nav.flash("sl-login")
            PhoneReply.done("The saved password is forgotten.")
        }
        "fileserver" -> {
            actions.resetServer(c.id)
            session.saved("server")
            nav.flash("pa-Server")
            PhoneReply.done("The profile uses the file’s server again.")
        }
        "attach" -> {
            val staged = nav.staged["profile-${c.id}"] ?: return PhoneReply.error("p12", "Send the certificate again.")
            val outcome = attachCertificate(state, actions, nav, staged.bytes, v, c.id)
            if (outcome is Outcome.Done) {
                nav.staged.remove("profile-${c.id}")
                session.tvLine = "Added the certificate"
            }
            outcome.toReply(clear = listOf("p12pass"))
        }
        else -> PhoneReply()
    }
}

// ---- Advanced -------------------------------------------------------------------------

@Composable
private fun AdvancedColumn(state: VpnUiState, nav: Nav, actions: TunnelActions, req: Requesters, c: Connection, index: Int, mini: Boolean) {
    val server = state.serverOf(c)
    val info = state.infoOf(c)
    val opened = nav.stack.getOrNull(index)
    val openKey = opened?.let { rowKeyOpening(it, nav.stack.getOrNull(index - 1)) }
    val changed = server != null && info != null && (server.host != info.fileHost || server.port != info.filePort)

    @Composable
    fun adv(kind: LeafKind, title: String, sub: String, value: String?) {
        val key = "pa-${kind.name}"
        ListRow(
            title, { open(nav, index, Col.Leaf(c.id, kind), entryKey(Col.Leaf(c.id, kind), state)) },
            subtitle = sub, open = openKey == key, mini = mini, focusRequester = req[key],
            flash = nav.flashKey == key,
            trailing = { if (value != null) ValueChip(value, it) else Chevron(it) },
        )
    }

    Column(Modifier.fillMaxSize()) {
        ColumnHead("Profiles › ${c.name}", "Advanced options", "Most profiles never need these.", mini)
        val list = nav.listState("advanced-${c.id}")
        LazyColumn(
            state = list,
            modifier = Modifier.scrollFade(list),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                // A file with <connection> blocks lists its own servers, and those are used as they are.
                val blocks = info?.connectionBlocks == true
                ListRow(
                    "Server and port", {
                        if (!stepBackTo(nav, index, "pa-Server")) nav.overlay = Overlay.Form(serverSpec(state, nav, actions, c))
                    },
                    subtitle = when {
                        blocks -> "The file chooses its servers in <connection> blocks"
                        changed -> "Changed to ${Words.address(server!!.host, server.port)}"
                        else -> "From the file: ${Words.address(server?.host.orEmpty(), server?.port)}"
                    },
                    enabled = !blocks,
                    mini = mini, focusRequester = req["pa-Server"], flash = nav.flashKey == "pa-Server",
                    trailing = { ValueChip(if (changed && !blocks) "Changed" else "From file", it) },
                )
            }
            val wg = info?.wg
            if (wg != null) {
                // WireGuard always uses UDP, and its file says which networks go through the tunnel.
                item {
                    ListRow(
                        "What goes through", {},
                        subtitle = if (wg.fullTunnel) "All traffic · address ${wg.addresses.joinToString(", ")}" else "Only ${wg.allowedIps.joinToString(", ")}",
                        mini = mini,
                        trailing = { ValueChip(if (wg.fullTunnel) "All" else "Some", it) },
                    )
                }
            } else {
                item { adv(LeafKind.Protocol, "Protocol", "Override the protocol in the file", protocolMode(state, c)) }
                item { adv(LeafKind.Order, "Server order", if ((info?.remotes ?: 1) > 1) "The file lists ${info!!.remotes} servers" else "When the file lists several servers", c.order) }
            }
            item { adv(LeafKind.Kill, "Kill switch", "For this profile only", c.killMode) }
            item { adv(LeafKind.Dns, "DNS", "For this profile only", c.dnsMode) }
            item {
                val facts = if (wg != null) listOfNotNull(info.cipher, wg.keepalive?.let { "keepalive $it s" }) else listOfNotNull(info?.tls, info?.cipher)
                adv(LeafKind.File, Words.fileTitle(info?.kind), facts.joinToString(" · ").ifEmpty { "Read-only" }, null)
            }
        }
    }
}

private fun protocolMode(state: VpnUiState, c: Connection): String {
    val server = state.serverOf(c) ?: return "From file"
    val file = state.infoOf(c)?.fileProtocol
    return if (file == null || server.protocol == file) "From file" else server.protocol.label
}

// ---- One setting ------------------------------------------------------------------------

private val LeafText = mapOf(
    LeafKind.Look to ("Icon" to "Shown on the profile’s card on Connect and in your profile list."),
    LeafKind.SignIn to ("Sign-in" to "How this profile proves who you are: a password, a certificate, or both."),
    LeafKind.Protocol to ("Protocol" to "Override the protocol in the file."),
    LeafKind.Order to ("Server order" to "When the file lists several servers, which one to try first."),
    LeafKind.Kill to ("Kill switch" to "For this profile only. App setting follows Settings › Protection."),
    LeafKind.Dns to ("DNS" to "For this profile only. Custom uses the servers in Settings › DNS, which every profile set to Custom shares."),
    LeafKind.File to ("The .ovpn file" to "The file as it is used, with the server from Server and port. Import the file again to change anything else."),
)

private val Options = mapOf(
    LeafKind.Protocol to listOf("From file" to "Use what the profile says", "UDP" to "Faster. The usual choice", "TCP" to "Gets through strict networks"),
    LeafKind.Order to listOf("In order" to "Try the servers as they are written", "Random" to "Spread the load across servers"),
    LeafKind.Kill to listOf("App setting" to "Follow Settings › Protection", "Always on" to "Block traffic whenever this tunnel is down", "Off" to "Never block for this profile"),
    LeafKind.Dns to listOf("App setting" to "The server's DNS, or 1.1.1.1 if it sends none", "Server only" to "Only the DNS servers this server pushes", "Custom" to "Your own DNS servers"),
)

@Composable
private fun LeafColumn(state: VpnUiState, nav: Nav, actions: TunnelActions, req: Requesters, c: Connection, kind: LeafKind, index: Int) {
    val (label, sub) = (LeafText[kind] ?: ("" to "")).let { (l, d) -> if (kind == LeafKind.File) Words.fileTitle(state.infoOf(c)?.kind) to d else l to d }
    val parentIsAdvanced = nav.stack.getOrNull(index - 2) is Col.Advanced
    val path = if (parentIsAdvanced) "Profiles › ${c.name} › Advanced" else "Profiles › ${c.name}"
    var savedPulse by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    // A choice applies at once, then the column steps back to the row, which says Saved.
    val saved: () -> Unit = {
        savedPulse++
        scope.launch {
            delay(520)
            if (nav.stack.lastOrNull() == Col.Leaf(c.id, kind)) {
                nav.pop()
                nav.flash(rowKeyOpening(Col.Leaf(c.id, kind), nav.stack.lastOrNull()))
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth()) {
            ColumnHead(path, label, sub, mini = false)
            SavedChip(savedPulse, Modifier.align(Alignment.TopEnd).padding(top = 24.dp, end = 24.dp))
        }
        Box(Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 20.dp)) {
            when (kind) {
                LeafKind.Protocol, LeafKind.Order, LeafKind.Kill, LeafKind.Dns -> OptionsBody(state, nav, actions, req, c, kind, saved)
                LeafKind.File -> FileBody(state, nav, actions, req, c)
                LeafKind.SignIn -> SignInBody(state, nav, actions, req, c)
                LeafKind.Look -> LookBody(state, nav, actions, req, c) { savedPulse++ }
            }
        }
    }
}

@Composable
private fun SavedChip(pulse: Int, modifier: Modifier) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(pulse) {
        if (pulse > 0) {
            visible = true
            delay(1400)
            visible = false
        }
    }
    SavedPill(visible, modifier)
}

@Composable
private fun OptionsBody(state: VpnUiState, nav: Nav, actions: TunnelActions, req: Requesters, c: Connection, kind: LeafKind, saved: () -> Unit) {
    val current = when (kind) {
        LeafKind.Protocol -> protocolMode(state, c)
        LeafKind.Order -> c.order
        LeafKind.Kill -> c.killMode
        else -> c.dnsMode
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Options.getValue(kind).forEachIndexed { i, (value, sub) ->
            val selected = value == current
            ListRow(
                value,
                {
                    // Custom uses the servers in Settings › DNS; without any, they are asked for first.
                    if (kind == LeafKind.Dns && value == "Custom" && state.settings.customDns.isEmpty()) {
                        nav.overlay = Overlay.Form(customDnsSpec(state, nav, actions, c))
                        return@ListRow
                    }
                    when (kind) {
                        LeafKind.Protocol -> actions.setProtocol(c.id, value)
                        LeafKind.Order -> actions.setOrder(c.id, value)
                        LeafKind.Kill -> actions.setKillMode(c.id, value)
                        else -> actions.setDnsMode(c.id, value)
                    }
                    saved()
                },
                subtitle = optionDetail(state, c, kind, value) ?: sub,
                selected = selected,
                focusRequester = if (selected) req["op-sel"] else if (i == 0 && Options.getValue(kind).none { it.first == current }) req["op-sel"] else null,
                trailing = {
                    if (selected) CheckBadge(it)
                },
            )
        }
    }
}

@Composable
private fun FileBody(state: VpnUiState, nav: Nav, actions: TunnelActions, req: Requesters, c: Connection) {
    val text = remember(c.serverId, state.servers[c.serverId]) { actions.rawConfig(c.serverId) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        RawConfig(text, maxHeight = 270f, focusWhenLong = req["ed-text"])
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvButton("Close", { nav.pop() }, focusRequester = req["ed-done"])
            TvButton("Send to phone", {
                nav.overlay = profilePhone(
                    c, "Send ${c.name} to your phone",
                    "Scan the code and press Download. ${keyNote(state, c)}",
                    section = "download",
                )
            }, icon = Ic.Phone, glass = true)
        }
    }
}

/** Sign-in: the saved username and password, and the certificate, in one place. */
@Composable
private fun SignInBody(state: VpnUiState, nav: Nav, actions: TunnelActions, req: Requesters, c: Connection) {
    val info = state.infoOf(c)
    val cert = info?.cert
    val login = state.logins[c.id]
    val needsLogin = info?.needsLogin != false
    val saved = login != null && login.hasPassword && login.username.isNotEmpty()
    var confirmForget by remember(c.id) { mutableStateOf(false) }
    val list = rememberLazyListState()
    LazyColumn(state = list, modifier = Modifier.heightIn(max = 420.dp).bleed(28.dp).scrollFade(list), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (needsLogin) {
            item { SectionLabel("Password") }
            item {
                ListRow(
                    "Username and password", { nav.overlay = Overlay.Form(loginSpec(state, nav, actions, c)) },
                    subtitle = authText(state, c),
                    focusRequester = req["sl-login"], flash = nav.flashKey == "sl-login",
                )
            }
            if (saved) {
                item {
                    if (confirmForget) {
                        ConfirmBar(
                            "Forget the saved password for ${c.name}? The TV asks for it at the next connect.", "Forget", req["sl-forget-no"],
                            onConfirm = {
                                confirmForget = false
                                actions.forgetPassword(c.id)
                                nav.refocus(req["sl-login"])
                                nav.flash("sl-login")
                            },
                            onKeep = {
                                confirmForget = false
                                nav.refocus(req["sl-forget"])
                            },
                        )
                    } else {
                        ListRow(
                            "Forget the saved password", { confirmForget = true },
                            subtitle = "The TV asks for the password at the next connect", danger = true,
                            focusRequester = req["sl-forget"],
                            trailing = { Chevron(it) },
                        )
                    }
                }
            }
        }
        item { SectionLabel("Certificate") }
        item {
            Box(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                when {
                    c.keyAlias != null -> DetailGrid(listOf("Certificate" to Certificates.label(c.keyAlias), "Stored" to "In this device’s keystore. The key can’t be copied off the device"), columns = 1)
                    cert == null -> Text(
                        if (needsLogin) "There is no client certificate in this file. The username and password sign you in." else "There is no client certificate in this file, and no password either.",
                        style = TvType.ui(17f, color = Tv.soft(0.7f)),
                    )
                    else -> DetailGrid(
                        listOf(
                            "Subject" to cert.subject,
                            "Issued by" to cert.issuer,
                            "Valid" to cert.validUntil,
                            "Key" to cert.key,
                            "Fingerprint (SHA-256)" to cert.fingerprint,
                            "Stored" to "Inside the .ovpn file",
                        ),
                    )
                }
            }
        }
        item {
            ListRow(
                "Add a .p12 from your phone", {
                    nav.overlay = profilePhone(c, "Add a certificate from your phone", "Scan the code, pick the .p12 or .pfx file, and type its password there. The certificate and key in it replace the profile’s.", section = "cert")
                },
                subtitle = if (state.infoOf(c)?.clientCert == true) "The certificate and key in it replace the ones in the file" else "The certificate and key in it go into the file",
                focusRequester = if (needsLogin) req["sl-p12"] else req["sl-login"],
                leading = { IconSquare(Ic.Phone, it) },
                trailing = { Chevron(it) },
            )
        }
        item {
            ListRow(
                if (c.keyAlias == null) "Use one installed on this device" else "Choose another on this device", {
                    actions.chooseKeyAlias { alias ->
                        if (alias != null) {
                            actions.setKeyAlias(c.id, alias)
                            nav.flash("sl-keystore")
                        } else {
                            nav.toast(NO_CERT_PICKED)
                        }
                    }
                },
                subtitle = if (state.infoOf(c)?.clientCert == true) "A certificate in Android’s keystore, instead of the file’s" else "A certificate in Android’s keystore",
                flash = nav.flashKey == "sl-keystore",
                leading = { IconSquare(Ic.Lock, it) },
                trailing = { Chevron(it) },
            )
        }
        if (c.keyAlias != null) {
            item {
                ListRow(
                    if (cert != null) "Use the file’s certificate" else "Stop using ${Certificates.label(c.keyAlias)}", {
                        actions.setKeyAlias(c.id, null)
                        nav.refocus(req["sl-p12"])
                    },
                    subtitle = if (cert != null) "The one inside the .ovpn file signs in again" else "Then nothing but the file signs in",
                    leading = { IconSquare(Ic.Cert, it) },
                    trailing = { Chevron(it) },
                )
            }
        }
    }
}

/**
 * Icon and colour: a preview of the card, then the choices. Each OK saves at once and the
 * column stays open, so the user can try a few; Done goes back.
 */
@Composable
private fun LookBody(state: VpnUiState, nav: Nav, actions: TunnelActions, req: Requesters, c: Connection, saved: () -> Unit) {
    val look = lookOf(c)
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxHeight()) {
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            LookCard(look.colours, look.icon.path, 34f, c.name, state.serverOf(c)?.host.orEmpty(), Modifier.width(290.dp))
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Icon", style = TvType.ui(15f, FontWeight.Medium, Tv.soft(0.7f)))
                Column(Modifier.padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    LookIcon.entries.chunked(6).forEach { line ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            line.forEach { icon ->
                                val selected = icon == look.icon
                                LookTile(
                                    selected = selected,
                                    colours = look.colours,
                                    focusRequester = if (selected) req["lk-sel"] else req["lk-${icon.name}"],
                                    onClick = {
                                        actions.setLook(c.id, icon.name, look.colour)
                                        saved()
                                    },
                                ) { MsIcon(icon.path, 30f) }
                            }
                        }
                    }
                }
                Text("Colour", style = TvType.ui(15f, FontWeight.Medium, Tv.soft(0.7f)))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Swatches.forEachIndexed { i, colours ->
                        LookSwatch(colours, selected = i == look.colour, focusRequester = req["lc-$i"]) {
                            actions.setLook(c.id, look.icon.name, i)
                            saved()
                        }
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvButton("Done", { nav.pop() }, icon = Ic.Check, focusRequester = req["ed-done"])
            if (look.chosen) {
                TvButton("Use the one from the name", {
                    actions.setLook(c.id, null, null)
                    saved()
                    // This button goes away; focus moves to the icon now in use, once it is marked.
                    scope.launch { req["lk-sel"].focusWhenReady() }
                }, glass = true)
            }
        }
    }
}

/** .lk-ic: one icon to pick, on the profile's colours when it is the one in use. */
@Composable
private fun LookTile(selected: Boolean, colours: Pair<Color, Color>, focusRequester: FocusRequester, onClick: () -> Unit, content: @Composable () -> Unit) {
    val f = rememberFocusState()
    val scale by animateFloatAsState(if (f.focused) 1.12f else 1f, tween(TvFocus.Ms, easing = FocusEase), label = "tile")
    val shape = RoundedCornerShape(TvRadius.Small.dp)
    Box(
        modifier = Modifier
            .size(62.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .shadow(if (f.focused) 14.dp else 0.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(shape)
            .then(if (selected) Modifier.lookFill(colours) else Modifier.background(Tv.white(0.08f)))
            .then(if (f.focused) Modifier.border(3.dp, Tv.Focus, shape) else Modifier)
            .tvFocus(f, onClick, focusRequester),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** .lk-sw: one colour to pick, with a check on the one in use. */
@Composable
private fun LookSwatch(colours: Pair<Color, Color>, selected: Boolean, focusRequester: FocusRequester, onClick: () -> Unit) {
    val f = rememberFocusState()
    val scale by animateFloatAsState(if (f.focused) 1.18f else 1f, tween(TvFocus.Ms, easing = FocusEase), label = "swatch")
    Box(
        modifier = Modifier
            .size(44.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .shadow(if (f.focused) 12.dp else 0.dp, CircleShape, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(CircleShape)
            .lookFill(colours)
            .then(if (f.focused) Modifier.border(3.dp, Tv.Focus, CircleShape) else Modifier)
            .tvFocus(f, onClick, focusRequester),
        contentAlignment = Alignment.Center,
    ) { if (selected) TvIcon(Ic.Check, 18f, Color.White, stroke = 3f) }
}

/** .dl: label over value, in two columns. */
@Composable
fun DetailGrid(rows: List<Pair<String, String>>, columns: Int = 2) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        rows.chunked(columns).forEach { line ->
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                line.forEach { (k, v) ->
                    Column(Modifier.weight(1f)) {
                        Text(k, style = TvType.ui(15f, FontWeight.Medium, Tv.soft(0.6f)))
                        // Two lines, so a long server name and its address both show.
                        Text(v, style = TvType.mono(17f), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                    }
                }
                repeat(columns - line.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** .raw: the config text with options in blue, block tags in amber and key material dimmed. Up and down scroll it. */
@Composable
fun RawConfig(text: String, maxHeight: Float, plain: Boolean = false, focusWhenLong: FocusRequester? = null) {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val f = rememberFocusState()
    val styled = remember(text, plain) { if (plain) AnnotatedString(text) else highlight(text) }
    // Text longer than the box takes focus first, so Down scrolls it and, at the end, goes on to the buttons.
    val long = scroll.maxValue > 0
    LaunchedEffect(long) { if (long) focusWhenLong?.focusWhenReady() }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (focusWhenLong != null) Modifier.focusRequester(focusWhenLong) else Modifier)
            .heightIn(max = maxHeight.dp)
            .clip(RoundedCornerShape(TvRadius.Small.dp))
            .background(Color.Black.copy(alpha = 0.35f))
            .border(2.dp, if (f.focused) Tv.Focus else Color.Transparent, RoundedCornerShape(TvRadius.Small.dp))
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (e.key) {
                    Key.DirectionDown -> if (scroll.value < scroll.maxValue) {
                        scope.launch { scroll.animateScrollBy(120f) }
                        true
                    } else {
                        false
                    }
                    Key.DirectionUp -> if (scroll.value > 0) {
                        scope.launch { scroll.animateScrollBy(-120f) }
                        true
                    } else {
                        false
                    }
                    else -> false
                }
            }
            .onFocusChanged { f.hasFocus = it.isFocused }
            .focusable()
            .verticalScroll(scroll)
            .padding(horizontal = 16.dp, vertical = 16.dp),
    ) {
        Text(styled, style = TvType.mono(15f, color = Tv.soft(0.82f)))
    }
}

private val TagLine = Regex("^</?[a-z0-9-]+>$")
private val KeyLine = Regex("^-----|^[A-Za-z0-9+/=]{12,}$")

private fun highlight(text: String): AnnotatedString = buildAnnotatedString {
    text.lines().forEachIndexed { i, raw ->
        if (i > 0) append('\n')
        val line = raw.trimEnd()
        when {
            TagLine.matches(line.trim()) -> withStyle(SpanStyle(color = Tv.Amber)) { append(line) }
            KeyLine.containsMatchIn(line.trim()) -> withStyle(SpanStyle(color = Tv.soft(0.4f))) { append(line) }
            line.isBlank() || line.trimStart().startsWith("#") || line.trimStart().startsWith(";") -> withStyle(SpanStyle(color = Tv.soft(0.4f))) { append(line) }
            else -> {
                val word = line.trimStart().substringBefore(' ')
                val lead = line.length - line.trimStart().length
                append(line.take(lead))
                withStyle(SpanStyle(color = Tv.Blue)) { append(word) }
                append(line.drop(lead + word.length))
            }
        }
    }
}

/** What a downloaded .ovpn carries, said where it is offered. */
private fun keyNote(state: VpnUiState, c: Connection): String =
    if (state.infoOf(c)?.kind == VpnKind.WireGuard) "The file holds this device’s private key, so keep it safe."
    else if (state.infoOf(c)?.clientCert == true) "The file holds the profile’s private key, so keep it safe; the saved password is not in it."
    else "The saved password is not in the file."

/** What a choice that follows something else means for this profile now, or null for its fixed line. */
private fun optionDetail(state: VpnUiState, c: Connection, kind: LeafKind, value: String): String? {
    val s = state.settings
    return when {
        kind == LeafKind.Dns && value == "Custom" && s.customDns.isNotEmpty() -> s.customDns.joinToString(", ")
        kind == LeafKind.Dns && value == "App setting" -> when {
            s.customDns.isNotEmpty() -> "Settings › DNS: your servers, ${s.customDns.joinToString(", ")}"
            s.pushedDns -> "Settings › DNS: the server’s, or 1.1.1.1 if it sends none"
            else -> "Settings › DNS: 1.1.1.1"
        }
        kind == LeafKind.Kill && value == "App setting" -> "Settings › Protection: ${if (s.killSwitch) "on" else "off"}"
        kind == LeafKind.Protocol && value == "From file" -> state.infoOf(c)?.fileProtocol?.let { "The file says ${it.label}" }
        else -> null
    }
}

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
import dev.shieldvpn.APP_NAME
import dev.shieldvpn.vpn.Certificates
import dev.shieldvpn.vpn.Connection
import dev.shieldvpn.vpn.VpnUiState
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
    SideEffect { nav.entry = req[nav.stack.lastOrNull()?.let { entryKey(it) } ?: "pl-${nav.profFocus ?: "add"}"] }

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
            val key = nav.stack.lastOrNull()?.let { entryKey(it) } ?: "pl-${nav.profFocus ?: "add"}"
            awaitLayout()
            if (!req[key].tryFocus()) req["pl-add"].tryFocus()
            nav.contentFocus = null
        }
    }
    LaunchedEffect(nav.stackMoves) {
        // Used once: coming back to this screen must not replay an old move.
        val key = nav.stackFocus ?: return@LaunchedEffect
        nav.stackFocus = null
        req[key].focusWhenReady()
        if (!req[key].hasFocusNow) {
            delay(150)
            req[key].focusWhenReady()
        }
    }

    Box(Modifier.fillMaxSize()) {
        val heroAlpha by animateFloatAsState(if (depth == 0) 1f else 0f, tween(250), label = "heroA")
        val heroShift by animateFloatAsState(if (depth == 0) 0f else 40f, tween(300, easing = SlideEase), label = "heroX")
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = 536.dp, bottom = 44.dp)
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
                                is Col.Advanced -> AdvancedColumn(state, nav, req, c, i, mini)
                                is Col.Leaf -> LeafColumn(state, nav, actions, req, c, shown.kind, i)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Where focus starts in a column. */
private fun entryKey(col: Col): String = when (col) {
    is Col.Profile -> "pe-name"
    is Col.Advanced -> "pa-Server"
    is Col.Leaf -> when (col.kind) {
        LeafKind.Name, LeafKind.Password, LeafKind.Server -> "ed-key"
        LeafKind.Look -> "lk-sel"
        LeafKind.Protocol, LeafKind.Order, LeafKind.Kill, LeafKind.Dns -> "op-sel"
        LeafKind.File, LeafKind.Cert -> "ed-done"
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
            Role.Root -> 440.dp
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
            .offset(x = left, y = 124.dp)
            .width(width)
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
                .width(if (list) 440.dp else 864.dp)
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
private fun ColumnHead(eyebrow: String, title: String, sub: String?, mini: Boolean, count: Int? = null, extra: (@Composable () -> Unit)? = null) {
    val fold by animateFloatAsState(if (mini) 0f else 1f, tween(250), label = "headFold")
    Column(Modifier.padding(start = 26.dp, end = 26.dp, top = 22.dp, bottom = 10.dp)) {
        if (fold > 0.01f) Text(eyebrow.uppercase(), style = TvType.eyebrow(13f, 0.55f, 0.12f), maxLines = 1, modifier = Modifier.fade { fold })
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 6.dp)) {
            Text(title, style = TvType.display(if (mini) 24f else 32f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (count != null && !mini) Text("$count", style = TvType.ui(16f, FontWeight.SemiBold, Tv.soft(0.55f)), modifier = Modifier.padding(start = 10.dp, bottom = 5.dp))
        }
        if (sub != null && !mini) Text(sub, style = TvType.ui(16f, color = Tv.soft(0.68f)), maxLines = 1, modifier = Modifier.padding(top = 6.dp))
        if (extra != null && !mini) Box(Modifier.padding(top = 14.dp)) { extra() }
    }
}

// ---- The list --------------------------------------------------------------------

@Composable
private fun ListColumn(state: VpnUiState, nav: Nav, req: Requesters, mini: Boolean) {
    Column(Modifier.fillMaxSize()) {
        ColumnHead("On this device", "Profiles", null, mini, count = state.connections.size)
        val list = rememberLazyListState()
        LazyColumn(
            state = list,
            modifier = Modifier.scrollFade(list),
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(state.connections.size) { index ->
                val c = state.connections[index]
                val open = nav.stack.firstOrNull()?.id == c.id
                ListRow(
                    title = c.name,
                    subtitle = statusText(state, c),
                    kind = RowKind.Profile,
                    open = open,
                    mini = mini,
                    focusRequester = req["pl-${c.id}"],
                    onFocus = {
                        nav.focusedPid = c.id
                        if (nav.stack.isEmpty()) nav.profFocus = c.id
                    },
                    onClick = { nav.guard(state) {
                        nav.profFocus = c.id
                        if (nav.stack.isEmpty()) nav.push(Col.Profile(c.id), "pe-name")
                        else if (open) {
                            if (nav.stack.size > 1) {
                                while (nav.stack.size > 1) nav.stack.removeAt(nav.stack.lastIndex)
                                nav.moveStackFocus("pe-name")
                            } else {
                                nav.moveStackFocus("pe-name")
                            }
                        } else {
                            nav.stack.clear()
                            nav.push(Col.Profile(c.id), "pe-name")
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
        }
    }
}

@Composable
private fun ProfileHero(state: VpnUiState, c: Connection) {
    val server = state.serverOf(c)
    val facts = listOfNotNull(
        authText(state, c),
        "starts with the device".takeIf { state.settings.boot && state.settings.startupId == c.id },
        "in Quick connect".takeIf { c.pinned },
    )
    Column(horizontalAlignment = Alignment.Start) {
        Text(c.name, style = TvType.title(92f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 18.dp))
        MetaLine(listOfNotNull(server?.host, server?.let { "${it.protocol.label} ${it.port}" }, state.infoOf(c)?.cipher))
        MetaLine(facts)
        Row(Modifier.padding(top = 22.dp), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            Hint("OK", "Open")
            Hint("▶", "Connect")
            Hint("☰", "Quick menu")
        }
    }
}

/** .meta: short facts with dots between. */
@Composable
private fun MetaLine(parts: List<String>) {
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
private fun Hint(key: String, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .border(2.dp, Tv.white(0.3f), CircleShape),
            contentAlignment = Alignment.Center,
        ) { Text(key, style = TvType.ui(12f, FontWeight.ExtraBold, Color.White)) }
        Text(text, style = TvType.ui(16f, FontWeight.SemiBold, Tv.soft(0.7f)))
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
    val opened = nav.stack.getOrNull(index)
    val openKey = opened?.let { rowKeyOpening(it, nav.stack.getOrNull(index - 1)) }
    val needsLogin = info?.needsLogin != false
    val saved = login != null && login.hasPassword && login.username.isNotEmpty()
    val startup = state.settings.boot && state.settings.startupId == c.id

    Column(Modifier.fillMaxSize()) {
        ColumnHead("Profiles", c.name, server?.let { "${it.host}:${it.port} · ${it.protocol.label}" }, mini, extra = {
            TvButton(
                if (live) "Disconnect" else "Connect",
                {
                    if (live) actions.disconnect() else connect(c.id)
                    if (!live) nav.toast("Connecting to ${c.name}")
                },
                icon = Ic.Power,
                small = true,
                focusRequester = req["pe-connect"],
            )
        })
        val list = nav.listState("profile-${c.id}")
        LazyColumn(
            state = list,
            modifier = Modifier.scrollFade(list),
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item {
                ListRow(
                    "Name", { open(nav, index, Col.Leaf(c.id, LeafKind.Name), "ed-key") },
                    subtitle = "Shown on cards and on Connect", open = openKey == "pe-name", mini = mini,
                    focusRequester = req["pe-name"],
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
            item {
                if (!needsLogin) {
                    ListRow(
                        "Sign-in", { open(nav, index, Col.Leaf(c.id, LeafKind.Cert), "ed-done") },
                        subtitle = "The certificate inside the file signs you in", open = openKey == "pe-signin", mini = mini,
                        focusRequester = req["pe-signin"],
                        trailing = { ValueChip("Certificate", it) },
                    )
                } else {
                    ListRow(
                        "Saved password", { open(nav, index, Col.Leaf(c.id, LeafKind.Password), "ed-key") },
                        subtitle = if (saved) "Saved for ${login!!.username}" else "Nothing saved. ${APP_NAME} asks each time",
                        open = openKey == "pe-signin", mini = mini,
                        focusRequester = req["pe-signin"],
                        trailing = { ValueChip(if (saved) "Change" else "Add", it) },
                    )
                }
            }
            item {
                ListRow(
                    "Connect when the device starts",
                    {
                        if (stepBackTo(nav, index, "pe-startup")) return@ListRow
                        when {
                            needsLogin && !saved -> nav.toast("Save the password first, so it can connect by itself")
                            startup -> {
                                actions.setStartup(null)
                                nav.toast("Nothing connects when the device starts")
                            }
                            else -> {
                                actions.setStartup(c.id)
                                nav.toast("${c.name} connects when the device starts")
                            }
                        }
                    },
                    subtitle = if (needsLogin && !saved) "Save the password first" else "Only one profile can do this",
                    mini = mini,
                    focusRequester = req["pe-startup"],
                    trailing = { TvSwitch(startup, it) },
                )
            }
            item {
                ListRow(
                    "Pin to Quick connect",
                    {
                        if (stepBackTo(nav, index, "pe-pin")) return@ListRow
                        actions.setPinned(c.id, !c.pinned)
                        nav.toast(if (!c.pinned) "${c.name} is pinned to Quick connect" else "${c.name} is unpinned")
                    },
                    subtitle = "Show it first on the Connect screen", mini = mini,
                    focusRequester = req["pe-pin"],
                    trailing = { TvSwitch(c.pinned, it) },
                )
            }
            item {
                ListRow(
                    "Advanced options", { open(nav, index, Col.Advanced(c.id), "pa-Server") },
                    subtitle = "Server, protocol, kill switch, DNS, the file", open = openKey == "pe-adv", mini = mini,
                    focusRequester = req["pe-adv"],
                    trailing = { ValueChip("7 settings", it) },
                )
            }
            if (!mini) item { SectionLabel("More") }
            item {
                val last = state.speeds[c.id]
                ListRow(
                    "Speed test", {
                        if (!stepBackTo(nav, index, "pe-speed")) nav.overlay = Overlay.Speed(c.id)
                    },
                    subtitle = last?.let { "${fmtMbps(it.downMbps)} Mbps ${ago(it.at)} · ${speedVerdict(it.downMbps).first.replaceFirstChar(Char::lowercase)}" }
                        ?: if (live) "Is it fast enough for 4K?" else "Connects, then measures it",
                    mini = mini,
                    focusRequester = req["pe-speed"],
                    leading = { IconSquare(Ic.Gauge, it) },
                    trailing = { TvIcon(Ic.Chev, 18f, if (it) Tv.OnFocus else Tv.Ink, stroke = 2.4f) },
                )
            }
            item {
                ListRow(
                    "Send to phone", {
                        if (!stepBackTo(nav, index, "pe-send")) nav.overlay = Overlay.Phone(PhoneKind.Profile(c.id))
                    },
                    subtitle = "Get the .ovpn file on your phone", mini = mini,
                    focusRequester = req["pe-send"],
                    leading = { IconSquare(Ic.Phone, it) },
                    trailing = { TvIcon(Ic.Chev, 18f, if (it) Tv.OnFocus else Tv.Ink, stroke = 2.4f) },
                )
            }
            item {
                ListRow(
                    "Duplicate", {
                        if (stepBackTo(nav, index, "pe-dup")) return@ListRow
                        actions.duplicate(c.id)
                        nav.toast("Made ${c.name} (copy). It is in the list")
                    },
                    subtitle = "Make a copy to try other settings", mini = mini,
                    focusRequester = req["pe-dup"],
                    leading = { IconSquare(Ic.Copy, it) },
                    trailing = { TvIcon(Ic.Chev, 18f, if (it) Tv.OnFocus else Tv.Ink, stroke = 2.4f) },
                )
            }
            item {
                if (confirm && !mini) {
                    DeleteConfirm(
                        text = "Delete ${c.name} and its saved password?",
                        keep = req["pe-del-no"],
                        onDelete = {
                            confirm = false
                            nav.stack.clear()
                            actions.delete(c.id)
                            nav.toast("Deleted ${c.name}")
                            nav.moveStackFocus("pl-add")
                        },
                        onKeep = {
                            confirm = false
                            nav.moveStackFocus("pe-del")
                        },
                    )
                    LaunchedEffect(Unit) { req["pe-del-no"].focusWhenReady() }
                } else {
                    ListRow(
                        "Delete profile", {
                            if (stepBackTo(nav, index, "pe-del")) return@ListRow
                            confirm = true
                        },
                        subtitle = "Removes it from this device", danger = true, mini = mini,
                        focusRequester = req["pe-del"],
                        leading = { IconSquare(Ic.Trash, it) },
                        trailing = { TvIcon(Ic.Chev, 18f, if (it) Tv.OnFocus else Tv.Ink, stroke = 2.4f) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DeleteConfirm(text: String, keep: androidx.compose.ui.focus.FocusRequester, onDelete: () -> Unit, onKeep: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Tv.Red.copy(alpha = 0.12f))
            .padding(start = 18.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(text, style = TvType.ui(16f, FontWeight.SemiBold), modifier = Modifier.weight(1f))
        TvButton("Delete", onDelete, icon = Ic.Trash, small = true, danger = true)
        TvButton("Keep", onKeep, small = true, focusRequester = keep)
    }
}

// ---- Advanced -------------------------------------------------------------------------

@Composable
private fun AdvancedColumn(state: VpnUiState, nav: Nav, req: Requesters, c: Connection, index: Int, mini: Boolean) {
    val server = state.serverOf(c)
    val info = state.infoOf(c)
    val opened = nav.stack.getOrNull(index)
    val openKey = opened?.let { rowKeyOpening(it, nav.stack.getOrNull(index - 1)) }
    val changed = server != null && info != null && (server.host != info.fileHost || server.port != info.filePort)

    @Composable
    fun adv(kind: LeafKind, title: String, sub: String, value: String) {
        val key = "pa-${kind.name}"
        ListRow(
            title, { open(nav, index, Col.Leaf(c.id, kind), entryKey(Col.Leaf(c.id, kind))) },
            subtitle = sub, open = openKey == key, mini = mini, focusRequester = req[key],
            trailing = { ValueChip(value, it) },
        )
    }

    Column(Modifier.fillMaxSize()) {
        ColumnHead("Profiles › ${c.name}", "Advanced options", "Most profiles never need these.", mini)
        val list = nav.listState("advanced-${c.id}")
        LazyColumn(
            state = list,
            modifier = Modifier.scrollFade(list),
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item {
                adv(
                    LeafKind.Server, "Server and port",
                    if (changed) "Changed to ${server!!.host} ${server.port}" else "From the file: ${server?.host.orEmpty()} ${server?.port ?: ""}",
                    if (changed) "Changed" else "From file",
                )
            }
            item { adv(LeafKind.Protocol, "Protocol", "Override the protocol in the file", protocolMode(state, c)) }
            item { adv(LeafKind.Order, "Server order", if ((info?.remotes ?: 1) > 1) "The file lists ${info!!.remotes} servers" else "When the file lists several servers", c.order) }
            item { adv(LeafKind.Kill, "Kill switch", "For this profile only", c.killMode) }
            item { adv(LeafKind.Dns, "DNS", "For this profile only", c.dnsMode) }
            item { adv(LeafKind.File, "The .ovpn file", listOfNotNull(info?.tls, info?.cipher).joinToString(" · ").ifEmpty { "Read-only" }, "View") }
            item { adv(LeafKind.Cert, "Client certificate", c.keyAlias?.let { "In the device’s keystore · ${Certificates.label(it)}" } ?: info?.cert?.validUntil ?: "Not in the file", "Details") }
        }
    }
}

private fun protocolMode(state: VpnUiState, c: Connection): String {
    val server = state.serverOf(c) ?: return "From file"
    val file = state.infoOf(c)?.fileProtocol
    return if (file == null || server.protocol == file) "From file" else server.protocol.label
}

// ---- One setting ------------------------------------------------------------------------

private class Editor(val keys: List<String>, initial: Map<String, String>, first: String) {
    val values = mutableStateMapOf<String, String>().apply { putAll(initial) }
    var field by mutableStateOf(first)
    var shift by mutableStateOf(false)
    var show by mutableStateOf(false)

    fun type(key: TvKey) {
        val next = typeKey(values[field].orEmpty(), key, shift) ?: return
        values[field] = next
        if (key == TvKey.Shift) shift = !shift else if (key is TvKey.Ch && shift) shift = false
    }
}

private val Labels = mapOf("name" to "Name", "host" to "Server", "port" to "Port", "user" to "Username", "pw" to "Password")

private val LeafText = mapOf(
    LeafKind.Name to ("Name" to "Shown on cards and on the Connect screen."),
    LeafKind.Look to ("Icon" to "Shown on its card on Connect and in your profile list."),
    LeafKind.Password to ("Saved password" to "Kept in the device’s keystore, never in the .ovpn file."),
    LeafKind.Cert to ("Client certificate" to "The certificate inside the .ovpn file that signs you in."),
    LeafKind.Server to ("Server and port" to "Use another address than the one in the file."),
    LeafKind.Protocol to ("Protocol" to "Override the protocol in the file."),
    LeafKind.Order to ("Server order" to "When the file lists several servers, which one to try first."),
    LeafKind.Kill to ("Kill switch" to "For this profile only. App setting follows Settings › Protection."),
    LeafKind.Dns to ("DNS" to "For this profile only."),
    LeafKind.File to ("The .ovpn file" to "Read-only. Import the file again to change it."),
)

private val Options = mapOf(
    LeafKind.Protocol to listOf("From file" to "Use what the profile says", "UDP" to "Faster. The usual choice", "TCP" to "Gets through strict networks"),
    LeafKind.Order to listOf("In order" to "Try them as they are written", "Random" to "Spread the load across servers"),
    LeafKind.Kill to listOf("App setting" to "Follow Settings › Protection", "Always on" to "Block traffic whenever this tunnel is down", "Off" to "Never block for this profile"),
    LeafKind.Dns to listOf("App setting" to "The server's DNS, or 1.1.1.1 if it sends none", "Server only" to "Only the DNS servers this server pushes", "Custom" to "Your own DNS servers"),
)

@Composable
private fun LeafColumn(state: VpnUiState, nav: Nav, actions: TunnelActions, req: Requesters, c: Connection, kind: LeafKind, index: Int) {
    val (label, sub) = LeafText[kind] ?: ("" to "")
    val parentIsAdvanced = nav.stack.getOrNull(index - 2) is Col.Advanced
    val path = if (parentIsAdvanced) "Profiles › ${c.name} › Advanced" else "Profiles › ${c.name}"
    var savedPulse by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    // After a save the chip says so, then the column steps back to where it came from.
    val saved: () -> Unit = {
        savedPulse++
        scope.launch {
            delay(520)
            if (nav.stack.lastOrNull() == Col.Leaf(c.id, kind)) nav.pop()
        }
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth()) {
            ColumnHead(path, label, sub, mini = false)
            SavedChip(savedPulse, Modifier.align(Alignment.TopEnd).padding(top = 24.dp, end = 26.dp))
        }
        Box(Modifier.padding(start = 26.dp, end = 26.dp, top = 6.dp, bottom = 20.dp)) {
            when (kind) {
                LeafKind.Name, LeafKind.Password, LeafKind.Server -> EditorBody(state, nav, actions, req, c, kind, saved)
                LeafKind.Protocol, LeafKind.Order, LeafKind.Kill, LeafKind.Dns -> OptionsBody(state, nav, actions, req, c, kind, saved)
                LeafKind.File -> FileBody(state, nav, actions, req, c)
                LeafKind.Cert -> CertBody(state, nav, actions, req, c)
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
    AnimatedVisibility(visible, modifier = modifier, enter = fadeIn(tween(200)) + scaleIn(tween(320), initialScale = 0.7f), exit = fadeOut(tween(200)) + scaleOut(tween(200), targetScale = 0.7f)) {
        Row(
            modifier = Modifier
                .clip(CircleShape)
                .background(Tv.Mint.copy(alpha = 0.16f))
                .padding(horizontal = 14.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TvIcon(Ic.Check, 16f, Tv.Mint, stroke = 3f)
            Text("Saved", style = TvType.ui(14f, FontWeight.ExtraBold, Tv.Mint))
        }
    }
}

@Composable
private fun EditorBody(
    state: VpnUiState,
    nav: Nav,
    actions: TunnelActions,
    req: Requesters,
    c: Connection,
    kind: LeafKind,
    saved: () -> Unit,
) {
    val server = state.serverOf(c)
    val info = state.infoOf(c)
    val login = state.logins[c.id]
    val editor = remember(c.id, kind) {
        when (kind) {
            LeafKind.Name -> Editor(listOf("name"), mapOf("name" to c.name), "name")
            LeafKind.Server -> Editor(listOf("host", "port"), mapOf("host" to server?.host.orEmpty(), "port" to (server?.port?.toString() ?: "")), "host")
            else -> Editor(listOf("user", "pw"), mapOf("user" to login?.username.orEmpty(), "pw" to ""), if (login?.username.isNullOrEmpty()) "user" else "pw")
        }
    }
    val changed = server != null && info != null && (server.host != info.fileHost || server.port != info.filePort)
    val save: () -> Unit = {
        when (kind) {
            LeafKind.Name -> {
                val name = editor.values["name"].orEmpty().trim()
                if (name.isEmpty()) nav.toast("A profile needs a name") else {
                    actions.rename(c.id, name)
                    saved()
                }
            }
            LeafKind.Server -> {
                val host = editor.values["host"].orEmpty().trim()
                val port = editor.values["port"].orEmpty().trim().toIntOrNull()
                when {
                    host.isEmpty() || host.any { it.isWhitespace() } -> nav.toast("Type the server's address")
                    port == null || port !in 1..65535 -> nav.toast("The port must be a number from 1 to 65535")
                    else -> {
                        actions.setServer(c.id, host, port)
                        saved()
                    }
                }
            }
            else -> {
                val pw = editor.values["pw"].orEmpty()
                if (pw.isEmpty()) {
                    editor.field = "pw"
                    nav.toast("Type the password first")
                } else {
                    actions.saveLogin(c.id, editor.values["user"].orEmpty(), pw)
                    saved()
                }
            }
        }
    }
    TypeInto(nav, { editor.type(TvKey.Del) }) { ch -> editor.values[editor.field] = (editor.values[editor.field].orEmpty() + ch).take(64) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            editor.keys.forEach { key ->
                InputBox(
                    label = Labels.getValue(key),
                    value = editor.values[key].orEmpty(),
                    editing = editor.field == key,
                    onClick = { editor.field = key },
                    secret = key == "pw",
                    showSecret = editor.show,
                    mono = key == "port",
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Keyboard(
            shift = editor.shift,
            onKey = { k -> if (k == TvKey.Done) req["ed-save"].tryFocus() else editor.type(k) },
            compact = true,
            entry = req["ed-key"],
        )
        Row(
            // Coming down from the keyboard lands on Save, wherever the key above it was.
            modifier = Modifier.padding(top = 2.dp).enterAt { req["ed-save"] },
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TvButton("Save", save, icon = Ic.Check, focusRequester = req["ed-save"])
            TvButton("Cancel", { nav.pop() }, glass = true)
            if (kind == LeafKind.Password) {
                TvButton(if (editor.show) "Hide" else "Show", { editor.show = !editor.show }, icon = Ic.Eye, small = true, glass = true)
                if (login?.hasPassword == true) {
                    TvButton("Forget", {
                        actions.forgetPassword(c.id)
                        nav.toast("Saved password forgotten")
                        saved()
                    }, small = true, glass = true, danger = true)
                }
            }
            if (kind == LeafKind.Server && changed) {
                TvButton("Use the file’s server", {
                    actions.resetServer(c.id)
                    saved()
                }, small = true, glass = true)
            }
            // .ed-phone: the same field, typed on a phone.
            val page = remember(editor.field) {
                dev.shieldvpn.phone.PhoneBridge.Page.Type(
                    LeafText[kind]?.first ?: APP_NAME, "Type it here, then press Send. It goes into the field selected on the TV.",
                    Labels.getValue(editor.field), secret = editor.field == "pw",
                )
            }
            val bridge = rememberPhoneBridge(kind, page) { got ->
                editor.values[editor.field] = String(got.bytes).trim().take(64)
                null
            }
            Spacer(Modifier.weight(1f))
            if (bridge != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TvIcon(Ic.Phone, 18f, Tv.soft(0.6f))
                    Text("Or use your phone:", style = TvType.ui(14f, color = Tv.soft(0.6f)))
                    Text("${bridge.address} · ${bridge.code.chunked(3).joinToString("\u00A0")}", style = TvType.mono(14f, color = Color.White))
                }
            }
        }
    }
}

@Composable
private fun OptionsBody(state: VpnUiState, nav: Nav, actions: TunnelActions, req: Requesters, c: Connection, kind: LeafKind, saved: () -> Unit) {
    val current = when (kind) {
        LeafKind.Protocol -> protocolMode(state, c)
        LeafKind.Order -> c.order
        LeafKind.Kill -> c.killMode
        else -> c.dnsMode
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Options.getValue(kind).forEachIndexed { i, (value, sub) ->
            val selected = value == current
            ListRow(
                value,
                {
                    // Custom uses the servers in Settings › DNS; without any, they are asked for first.
                    if (kind == LeafKind.Dns && value == "Custom" && state.settings.customDns.isEmpty()) {
                        nav.overlay = Overlay.Text(TextKind.CustomDns, "") { text ->
                            val servers = text.split(' ', ',').map { it.trim() }.filter { it.isNotEmpty() }
                            if (servers.isNotEmpty()) {
                                actions.changeSettings { it.copy(customDns = servers) }
                                actions.setDnsMode(c.id, value)
                                saved()
                            }
                        }
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
                subtitle = if (kind == LeafKind.Dns && value == "Custom" && state.settings.customDns.isNotEmpty()) state.settings.customDns.joinToString(", ") else sub,
                selected = selected,
                focusRequester = if (selected) req["op-sel"] else if (i == 0 && Options.getValue(kind).none { it.first == current }) req["op-sel"] else null,
                trailing = {
                    if (selected) CheckBadge(it)
                },
            )
        }
        Spacer(Modifier.size(8.dp))
        TvButton("Back", { nav.pop() }, icon = Ic.Back, glass = true)
    }
}

@Composable
private fun FileBody(state: VpnUiState, nav: Nav, actions: TunnelActions, req: Requesters, c: Connection) {
    val text = remember(c.serverId, state.servers[c.serverId]) { actions.rawConfig(c.serverId) }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        RawConfig(text, maxHeight = 270f)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvButton("Back", { nav.pop() }, icon = Ic.Back, glass = true, focusRequester = req["ed-done"])
            TvButton("Send to phone", { nav.overlay = Overlay.Phone(PhoneKind.Profile(c.id)) }, icon = Ic.Phone, glass = true)
        }
    }
}

@Composable
private fun CertBody(state: VpnUiState, nav: Nav, actions: TunnelActions, req: Requesters, c: Connection) {
    val cert = state.infoOf(c)?.cert
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (c.keyAlias != null) {
            DetailGrid(
                listOf(
                    "Certificate" to Certificates.label(c.keyAlias),
                    "Stored" to "In this device’s keystore. The key can’t be copied off it",
                ),
                columns = 1,
            )
        } else if (cert == null) {
            Text(
                "There is no client certificate in this file. The username and password sign you in.",
                style = TvType.ui(17f, color = Tv.soft(0.7f)),
            )
        } else {
            DetailGrid(
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
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvButton("Back", { nav.pop() }, icon = Ic.Back, glass = true, focusRequester = req["ed-done"])
            TvButton(if (c.keyAlias == null) "Use one installed on this device" else "Choose another", {
                actions.chooseKeyAlias { alias ->
                    if (alias != null) {
                        actions.setKeyAlias(c.id, alias)
                        nav.toast("${c.name} now uses ${Certificates.label(alias)}")
                    } else {
                        nav.toast(NO_CERT_PICKED)
                    }
                }
            }, icon = Ic.Lock, glass = true)
            if (c.keyAlias != null) {
                TvButton(if (cert != null) "Use the file’s certificate" else "Stop using it", {
                    actions.setKeyAlias(c.id, null)
                    nav.toast(if (cert != null) "${c.name} uses the certificate in its file" else "${c.name} no longer uses ${Certificates.label(c.keyAlias)}")
                }, glass = true)
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
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(30.dp)) {
            LookCard(look.colours, look.icon.path, 34f, c.name, state.serverOf(c)?.host.orEmpty(), Modifier.width(290.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Icon", style = TvType.ui(15f, FontWeight.Bold, Tv.soft(0.7f)))
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
                Text("Colour", style = TvType.ui(15f, FontWeight.Bold, Tv.soft(0.7f)))
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
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
        Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
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
    val scale by animateFloatAsState(if (f.focused) 1.12f else 1f, tween(180, easing = FocusEase), label = "tile")
    val shape = RoundedCornerShape(16.dp)
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
    val scale by animateFloatAsState(if (f.focused) 1.18f else 1f, tween(180, easing = FocusEase), label = "swatch")
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
            Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                line.forEach { (k, v) ->
                    Column(Modifier.weight(1f)) {
                        Text(k, style = TvType.ui(14f, FontWeight.SemiBold, Tv.soft(0.55f)))
                        Text(v, style = TvType.mono(16.5f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
                    }
                }
                repeat(columns - line.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** .raw: the config text with options in blue, block tags in amber and key material dimmed. Up and down scroll it. */
@Composable
fun RawConfig(text: String, maxHeight: Float, plain: Boolean = false) {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val f = rememberFocusState()
    val styled = remember(text, plain) { if (plain) AnnotatedString(text) else highlight(text) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.Black.copy(alpha = 0.35f))
            .border(2.dp, if (f.focused) Tv.Focus else Color.Transparent, RoundedCornerShape(14.dp))
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
            .padding(horizontal = 18.dp, vertical = 14.dp),
    ) {
        Text(styled, style = TvType.mono(14f, color = Tv.soft(0.82f)))
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

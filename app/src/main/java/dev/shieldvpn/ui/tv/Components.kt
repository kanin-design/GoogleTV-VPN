package dev.shieldvpn.ui.tv

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** The design's motion curve for focus and small moves: cubic-bezier(.2,.7,.2,1). */
val FocusEase = CubicBezierEasing(0.2f, 0.7f, 0.2f, 1f)

/** Remembers whether a component has the remote's focus, and wires focus and OK to it. */
class FocusState {
    /** The remote's focus is on it. */
    var hasFocus by mutableStateOf(false)

    /** A mouse pointer is over it. */
    var hovered by mutableStateOf(false)

    /** Drawn as focused: the remote is on it, or the mouse is. */
    val focused: Boolean get() = hasFocus || hovered
}

@Composable
fun rememberFocusState() = remember { FocusState() }

/**
 * Requesters whose item is attached to the layout now, by how many items use each. An item
 * can be composed but detached, for example while a list or an animation still holds it.
 */
private val onScreen = java.util.WeakHashMap<FocusRequester, Int>()

/** The item this requester points at is composed, so focus can be sent to it. */
val FocusRequester.isOnScreen: Boolean get() = (onScreen[this] ?: 0) > 0

/** The focus state of the item each requester points at. */
private val states = java.util.WeakHashMap<FocusRequester, FocusState>()

/** Every focusable item on screen. */
private val items = java.util.WeakHashMap<FocusState, Unit>()

/** A row, button, card or key has the remote's focus. */
fun anyItemFocused(): Boolean = items.keys.any { it.hasFocus }

/** The requester of the item that has the remote's focus, if it has one. */
fun focusedRequester(): FocusRequester? = states.entries.firstOrNull { it.value.hasFocus }?.key

/** The item this requester points at has the remote's focus now. */
val FocusRequester.hasFocusNow: Boolean get() = states[this]?.hasFocus == true

/** Focus requester, focus callback and OK for one focusable item. Put it after the visual modifiers. */
@Composable
fun Modifier.tvFocus(
    state: FocusState,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
    onFocus: (() -> Unit)? = null,
): Modifier {
    // Every item gets a requester, so focus can come back to it, for example after a sheet.
    val own = remember { FocusRequester() }
    val requester = focusRequester ?: own
    DisposableEffect(requester) {
        states[requester] = state
        items[state] = Unit
        onDispose {
            if (states[requester] === state) states.remove(requester)
            items.remove(state)
        }
    }
    return this.then(AttachedElement(requester)).tvFocusModifiers(state, onClick, requester, onFocus)
}

/** Counts [requester] as on screen only while its node is attached. */
private data class AttachedElement(val requester: FocusRequester) : ModifierNodeElement<AttachedNode>() {
    override fun create() = AttachedNode(requester)
    override fun update(node: AttachedNode) {
        if (node.requester !== requester) {
            node.detachCount()
            node.requester = requester
            if (node.isAttached) node.attachCount()
        }
    }
}

private class AttachedNode(var requester: FocusRequester) : Modifier.Node() {
    private var counted = false

    fun attachCount() {
        if (!counted) {
            onScreen[requester] = (onScreen[requester] ?: 0) + 1
            counted = true
        }
    }

    fun detachCount() {
        if (counted) {
            onScreen[requester] = ((onScreen[requester] ?: 1) - 1).coerceAtLeast(0)
            counted = false
        }
    }

    override fun onAttach() = attachCount()
    override fun onDetach() = detachCount()
}

@Composable
private fun Modifier.tvFocusModifiers(
    state: FocusState,
    onClick: () -> Unit,
    focusRequester: FocusRequester?,
    onFocus: (() -> Unit)?,
): Modifier {
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    state.hovered = hovered
    return this
        .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
        .onFocusChanged {
            state.hasFocus = it.isFocused
            if (it.isFocused) onFocus?.invoke()
        }
        // A mouse click does not move the remote's focus, so it first does what landing on
        // the item would: a tab opens its screen, a category shows its settings.
        .clickable(interactionSource = interactions, indication = null) {
            if (!state.hasFocus) onFocus?.invoke()
            onClick()
        }
        .hoverable(interactions)
}

/** .glass and .glass.deep: a translucent panel with a hairline edge. */
@Composable
fun Glass(
    modifier: Modifier = Modifier,
    deep: Boolean = true,
    shape: Shape = RoundedCornerShape(26.dp),
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .clip(shape)
            .background(if (deep) Tv.GlassDeep else Tv.Glass)
            .border(1.dp, Tv.GlassEdge, shape),
        content = content,
    )
}

/**
 * Fades this content by [alpha] without an offscreen buffer. A layer with alpha below 1 is
 * otherwise drawn into its own texture first (8 MB for a full-screen one at 1080p), and the
 * graphics driver keeps those around. Right for anything without overlapping parts.
 */
fun Modifier.fade(alpha: () -> Float): Modifier = graphicsLayer {
    this.alpha = alpha()
    compositingStrategy = CompositingStrategy.ModulateAlpha
}

/** .scroll: a list fades out at an edge it can scroll past, instead of being cut off. */
fun Modifier.scrollFade(list: LazyListState, edge: Dp = 14.dp): Modifier = this
    // The fade needs its own buffer; a list that fits on screen has no fade and needs none.
    .graphicsLayer {
        compositingStrategy = if (list.canScrollBackward || list.canScrollForward) CompositingStrategy.Offscreen else CompositingStrategy.Auto
    }
    .drawWithContent {
        drawContent()
        val top = list.canScrollBackward
        val bottom = list.canScrollForward
        if (top || bottom) {
            val e = (edge.toPx() / size.height).coerceAtMost(0.5f)
            drawRect(
                Brush.verticalGradient(
                    0f to if (top) Color.Transparent else Color.Black,
                    e to Color.Black,
                    1f - e to Color.Black,
                    1f to if (bottom) Color.Transparent else Color.Black,
                ),
                blendMode = BlendMode.DstIn,
            )
        }
    }

/**
 * Places this at [x], [y] with the width [width], read while laying out: an animated place or
 * width then moves it without composing it again on every frame.
 */
fun Modifier.placed(x: () -> Dp, y: Dp, width: () -> Dp): Modifier = this
    .offset { IntOffset(x().roundToPx(), y.roundToPx()) }
    .layout { measurable, constraints ->
        val w = width().roundToPx()
        val placeable = measurable.measure(constraints.copy(minWidth = w, maxWidth = w))
        layout(w, placeable.height) { placeable.place(0, 0) }
    }

/**
 * Widens a list by [x] on both sides without moving its rows, when the list's content padding
 * gives the same [x] back. A focused row grows a little, and this keeps its edges from being cut.
 */
fun Modifier.bleed(x: Dp): Modifier = layout { measurable, constraints ->
    val px = x.roundToPx()
    val placeable = measurable.measure(constraints.copy(minWidth = constraints.minWidth + 2 * px, maxWidth = constraints.maxWidth + 2 * px))
    layout(constraints.maxWidth, placeable.height) { placeable.place(-px, 0) }
}

/** .dot: a small dot with a soft glow in its tone. */
@Composable
fun GlowDot(tone: Color, size: Float = 9f) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .drawBehind {
                val r = this.size.minDimension
                drawCircle(Brush.radialGradient(listOf(tone.copy(alpha = 0.55f), tone.copy(alpha = 0f)), center, r * 1.6f), r * 1.6f)
                drawCircle(tone, r / 2)
            },
    )
}

/** .btn: a pill button. Focused, it turns white with dark text and grows a little. */
@Composable
fun TvButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: Ic? = null,
    small: Boolean = false,
    primary: Boolean = false,
    glass: Boolean = false,
    danger: Boolean = false,
    focusRequester: FocusRequester? = null,
    onFocus: (() -> Unit)? = null,
) {
    val f = rememberFocusState()
    val scale by animateFloatAsState(if (f.focused) 1.06f else 1f, tween(180, easing = FocusEase), label = "btn")
    val bg = when {
        f.focused -> Tv.Focus
        primary -> Tv.Mint.copy(alpha = 0.18f)
        glass -> Tv.Glass
        else -> Tv.white(0.09f)
    }
    val edge = when {
        f.focused -> Color.Transparent
        primary -> Tv.Mint.copy(alpha = 0.45f)
        else -> Tv.white(0.13f)
    }
    val fg = when {
        f.focused && danger -> Tv.DarkRed
        f.focused -> Tv.OnFocus
        danger -> Tv.Red
        else -> Tv.Ink
    }
    Row(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .shadow(if (f.focused) 16.dp else 0.dp, CircleShape, ambientColor = Color.Black, spotColor = Color.Black)
            .height(if (small) 48.dp else 60.dp)
            .clip(CircleShape)
            .background(bg)
            .border(1.dp, edge, CircleShape)
            .tvFocus(f, onClick, focusRequester, onFocus)
            .padding(horizontal = if (small) 20.dp else 28.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (small) 9.dp else 12.dp),
    ) {
        icon?.let { TvIcon(it, if (small) 18f else 22f, fg) }
        Text(
            text,
            style = TvType.ui(if (small) 16f else 19f, if (f.focused) FontWeight.ExtraBold else FontWeight.SemiBold, fg),
            maxLines = 1,
        )
    }
}

/** Row shapes from the design: .row, .prow (lists of profiles and pictures) and .mrow (import methods). */
enum class RowKind(val minHeight: Float, val radius: Float, val scale: Float, val titleSize: Float) {
    Row(60f, 16f, 1.02f, 17f),
    Profile(72f, 16f, 1.02f, 18f),
    Pick(76f, 18f, 1.03f, 18f),
    Method(74f, 18f, 1.03f, 18f),
}

/**
 * A focusable row with a title, an optional line under it, and slots on both sides.
 * [open] marks the row whose column is open next to it; [mini] is the narrow column,
 * where only names show. [flash] shows "Saved" on it for a moment; a row that can't be used
 * here ([enabled] false) is dimmed and focus passes it by.
 */
@Composable
fun ListRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    kind: RowKind = RowKind.Row,
    open: Boolean = false,
    selected: Boolean = false,
    danger: Boolean = false,
    mini: Boolean = false,
    monoTitle: Boolean = false,
    enabled: Boolean = true,
    flash: Boolean = false,
    focusRequester: FocusRequester? = null,
    onFocus: (() -> Unit)? = null,
    leading: (@Composable (focused: Boolean) -> Unit)? = null,
    trailing: (@Composable RowScope.(focused: Boolean) -> Unit)? = null,
) {
    val f = rememberFocusState()
    val scale by animateFloatAsState(if (f.focused) kind.scale else 1f, tween(180, easing = FocusEase), label = "row")
    val foldAlpha by animateFloatAsState(if (mini) 0f else 1f, tween(250), label = "fold")
    val shape = RoundedCornerShape(kind.radius.dp)
    val bg by animateColorAsState(
        when {
            f.focused -> Tv.Focus
            open -> Tv.white(0.14f)
            selected && kind == RowKind.Row -> Tv.white(0.13f)
            selected -> Tv.white(0.1f)
            kind == RowKind.Row || kind == RowKind.Profile -> Tv.white(0.04f)
            else -> Color.Transparent
        },
        tween(180),
        label = "rowBg",
    )
    val ring = !f.focused && (open || (selected && kind == RowKind.Row))
    val titleColor = when {
        f.focused && danger -> Tv.DarkRed
        f.focused -> Tv.OnFocus
        danger -> Tv.Red
        else -> Tv.Ink
    }
    val titleAlpha = if (mini && !open && !f.focused) 0.55f else 1f
    val subColor = if (f.focused) Tv.dark(0.6f) else Tv.soft(0.6f)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .shadow(if (f.focused) 14.dp else 0.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
            .defaultMinSize(minHeight = kind.minHeight.dp)
            .clip(shape)
            .background(bg)
            .then(if (ring) Modifier.border(1.dp, Tv.white(0.16f), shape) else Modifier)
            .then(if (enabled) Modifier.tvFocus(f, onClick, focusRequester, onFocus) else Modifier.fade { 0.42f })
            .padding(horizontal = if (kind == RowKind.Row) 16.dp else 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (leading != null && foldAlpha > 0.01f) {
            Box(Modifier.fade { foldAlpha }) { leading(f.focused) }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                style = if (monoTitle) TvType.mono(kind.titleSize - 1, FontWeight.SemiBold, titleColor) else TvType.ui(kind.titleSize, FontWeight.Bold, titleColor),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fade { titleAlpha },
            )
            if (subtitle != null && foldAlpha > 0.01f) {
                Text(
                    subtitle,
                    style = TvType.ui(14f, color = subColor),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fade { foldAlpha },
                )
            }
        }
        SavedPill(flash && foldAlpha > 0.01f)
        if (trailing != null && foldAlpha > 0.01f) {
            Row(
                modifier = Modifier.fade { foldAlpha },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) { trailing(f.focused) }
        }
    }
}

/** The chevron at the end of a row that opens something. */
@Composable
fun Chevron(focused: Boolean) {
    TvIcon(Ic.Chev, 18f, if (focused) Tv.OnFocus else Tv.Ink, stroke = 2.4f)
}

/** The mint "Saved" pill a row shows for a moment after its value was saved. */
@Composable
fun SavedPill(visible: Boolean, modifier: Modifier = Modifier) {
    AnimatedVisibility(visible, modifier = modifier, enter = fadeIn(tween(200)), exit = fadeOut(tween(300))) {
        Row(
            modifier = Modifier
                .clip(CircleShape)
                .background(Tv.Mint.copy(alpha = 0.16f))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            TvIcon(Ic.Check, 15f, Tv.Mint, stroke = 3f)
            Text("Saved", style = TvType.ui(14f, FontWeight.ExtraBold, Tv.Mint))
        }
    }
}

/** .val: the current value on the right of a row, with a chevron. */
@Composable
fun ValueChip(text: String, focused: Boolean, leading: (@Composable () -> Unit)? = null) {
    Row(
        modifier = Modifier
            .widthIn(max = 300.dp)
            .clip(CircleShape)
            .background(if (focused) Tv.dark(0.1f) else Tv.white(0.1f))
            .padding(start = 14.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val c = if (focused) Tv.OnFocus else Tv.soft(0.9f)
        leading?.invoke()
        if (text.isNotEmpty()) Text(text, style = TvType.ui(15f, FontWeight.Bold, c), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        TvIcon(Ic.Chev, 16f, c, stroke = 2.4f)
    }
}

/** .sw: an on/off switch. */
@Composable
fun TvSwitch(on: Boolean, focused: Boolean) {
    val track = when {
        focused && on -> Tv.OnFocus
        focused -> Tv.dark(0.2f)
        on -> Tv.Mint
        else -> Tv.white(0.18f)
    }
    val knob = when {
        focused && on -> Tv.Mint
        focused -> Tv.dark(0.6f)
        on -> Color(0xFF06140D)
        else -> Tv.soft(0.7f)
    }
    val x by animateFloatAsState(if (on) 24f else 0f, tween(200, easing = FocusEase), label = "knob")
    Box(
        modifier = Modifier
            .size(54.dp, 30.dp)
            .clip(CircleShape)
            .background(track)
            .padding(4.dp),
    ) {
        Box(
            Modifier
                .graphicsLayer { translationX = x * density }
                .size(22.dp)
                .clip(CircleShape)
                .background(knob),
        )
    }
}

/** .pk-check: the mark on the chosen option. */
@Composable
fun CheckBadge(focused: Boolean) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(if (focused) Tv.OnFocus else Tv.Mint),
        contentAlignment = Alignment.Center,
    ) { TvIcon(Ic.Check, 16f, if (focused) Tv.Mint else Color(0xFF06140D), stroke = 3f) }
}

/** The icon square at the start of an action row. */
@Composable
fun IconSquare(icon: Ic, focused: Boolean, size: Float = 40f, radius: Float = 12f) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(RoundedCornerShape(radius.dp))
            .background(if (focused) Tv.dark(0.1f) else Tv.white(0.09f)),
        contentAlignment = Alignment.Center,
    ) { TvIcon(icon, 20f, if (focused) Tv.OnFocus else Tv.Ink) }
}

/** .addi: the dashed square on "Add" rows. */
@Composable
fun AddSquare(focused: Boolean, icon: Ic = Ic.Plus) {
    Box(
        modifier = Modifier
            .size(96.dp, 54.dp)
            .clip(RoundedCornerShape(10.dp))
            .border(2.dp, if (focused) Tv.dark(0.3f) else Tv.white(0.25f), RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center,
    ) { TvIcon(icon, 22f, if (focused) Tv.OnFocus else Tv.Ink) }
}

/** .sect: a small heading between rows. */
@Composable
fun SectionLabel(text: String) {
    Text(text.uppercase(), style = TvType.eyebrow(12.5f, 0.5f, 0.13f), modifier = Modifier.padding(start = 10.dp, top = 12.dp, bottom = 4.dp))
}

/** .p-eyebrow, .p-title and .p-sub: the heading of a panel. */
@Composable
fun PanelHeading(eyebrow: String?, title: String, sub: String? = null, titleSize: Float = 34f) {
    Column {
        if (eyebrow != null) Text(eyebrow.uppercase(), style = TvType.eyebrow(13.5f, 0.55f))
        Text(title, style = TvType.display(titleSize), modifier = Modifier.padding(top = if (eyebrow != null) 6.dp else 0.dp))
        if (sub != null) Text(sub, style = TvType.ui(17f, color = Tv.soft(0.7f)).copy(lineHeight = (17 * 1.45f).sp), modifier = Modifier.padding(top = 8.dp).widthIn(max = 600.dp))
    }
}

// ---- Keyboard ---------------------------------------------------------------

/** One key of the on-screen keyboard. */
sealed interface TvKey {
    data class Ch(val c: Char) : TvKey
    data object Shift : TvKey
    data object Space : TvKey
    data object Del : TvKey
    data object Done : TvKey

    /** Switches between letters and symbols. */
    data object Page : TvKey
}

private fun keys(text: String) = text.map { TvKey.Ch(it) to 1 }

private val Bottom = listOf(TvKey.Page to 1, TvKey.Space to 4, TvKey.Ch('-') to 1, TvKey.Del to 2, TvKey.Done to 2)

private val Letters: List<List<Pair<TvKey, Int>>> = listOf(
    keys("1234567890"),
    keys("qwertyuiop"),
    keys("asdfghjkl@"),
    listOf<Pair<TvKey, Int>>(TvKey.Shift to 1) + keys("zxcvbnm._"),
    Bottom,
)

// What links, addresses and strong passwords need beyond letters and digits.
private val Symbols: List<List<Pair<TvKey, Int>>> = listOf(
    keys("1234567890"),
    keys("!#$%&*()+="),
    keys("/:;?'\",<>~"),
    keys("[]{}|\\^`_."),
    Bottom,
)

/**
 * The design's keyboard: typing on a TV without the system keyboard, so the remote's focus never
 * gets stuck in a text field. It keeps its own Shift and its page of symbols; [onType] gets each
 * character, space included. [entry] goes on the key focus starts on, the "a" (or "!" on symbols).
 */
@Composable
fun Keyboard(onType: (Char) -> Unit, onDelete: () -> Unit, onDone: () -> Unit, compact: Boolean = false, entry: FocusRequester? = null) {
    var shift by remember { mutableStateOf(false) }
    var symbols by remember { mutableStateOf(false) }
    val gap = if (compact) 6f else 7f
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Color.Black.copy(alpha = 0.25f))
            .padding((if (compact) 12 else 14).dp),
        verticalArrangement = Arrangement.spacedBy(gap.dp),
    ) {
        (if (symbols) Symbols else Letters).forEachIndexed { r, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(gap.dp)) {
                row.forEachIndexed { c, (key, span) ->
                    KeyCap(
                        key = key,
                        shift = shift,
                        symbols = symbols,
                        height = if (compact) 40f else 46f,
                        onClick = {
                            when (key) {
                                is TvKey.Ch -> {
                                    onType(if (shift) key.c.uppercaseChar() else key.c)
                                    shift = false
                                }
                                TvKey.Space -> onType(' ')
                                TvKey.Del -> onDelete()
                                TvKey.Done -> onDone()
                                TvKey.Shift -> shift = !shift
                                TvKey.Page -> {
                                    symbols = !symbols
                                    shift = false
                                }
                            }
                        },
                        focusRequester = if (r == 2 && c == 0) entry else null,
                        modifier = Modifier.weight(span.toFloat()),
                    )
                }
            }
        }
    }
}

@Composable
private fun KeyCap(key: TvKey, shift: Boolean, symbols: Boolean, height: Float, onClick: () -> Unit, focusRequester: FocusRequester?, modifier: Modifier) {
    val f = rememberFocusState()
    val scale by animateFloatAsState(if (f.focused) 1.1f else 1f, tween(120, easing = FocusEase), label = "key")
    val special = key !is TvKey.Ch
    val lit = key == TvKey.Shift && shift
    val label = when (key) {
        is TvKey.Ch -> if (shift) key.c.uppercaseChar().toString() else key.c.toString()
        TvKey.Shift -> "⇧"
        TvKey.Space -> "space"
        TvKey.Del -> "⌫"
        TvKey.Done -> "Done"
        TvKey.Page -> if (symbols) "abc" else "#+="
    }
    val bg = when {
        f.focused -> Tv.Focus
        lit -> Tv.Mint
        special -> Tv.white(0.17f)
        else -> Tv.white(0.1f)
    }
    val fg = if (f.focused) Tv.OnFocus else if (lit) Color(0xFF06140D) else Tv.Ink
    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .shadow(if (f.focused) 10.dp else 0.dp, RoundedCornerShape(10.dp), ambientColor = Color.Black, spotColor = Color.Black)
            .height(height.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .tvFocus(f, onClick, focusRequester),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = TvType.ui(if (special) 15f else 19f, FontWeight.SemiBold, fg), maxLines = 1)
    }
}

/** .input: a field that shows what the on-screen keyboard types into it, and what is wrong with it under it. */
@Composable
fun InputBox(
    label: String,
    value: String,
    editing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    secret: Boolean = false,
    showSecret: Boolean = false,
    mono: Boolean = false,
    focusRequester: FocusRequester? = null,
    error: String? = null,
) {
    val f = rememberFocusState()
    val shape = RoundedCornerShape(15.dp)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(label, style = TvType.ui(14f, FontWeight.Bold, Tv.soft(0.6f)))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (f.focused) Modifier.border(5.dp, Tv.white(0.12f), RoundedCornerShape(19.dp)).padding(0.dp) else Modifier)
                .height(58.dp)
                .clip(shape)
                .background(if (f.focused) Tv.white(0.12f) else Tv.white(0.07f))
                .border(2.dp, if (f.focused) Tv.Focus else if (error != null) Tv.Red else if (editing) Tv.white(0.3f) else Color.Transparent, shape)
                .tvFocus(f, onClick, focusRequester)
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val shown = if (secret && !showSecret) "•".repeat(value.length) else value
            if (value.isEmpty()) {
                Text(label, style = TvType.ui(20f, FontWeight.Medium, Tv.soft(0.45f)), maxLines = 1)
            } else {
                Text(
                    shown,
                    style = if (secret || mono) TvType.mono(19f, FontWeight.SemiBold).copy(letterSpacing = 0.08.em) else TvType.ui(20f, FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            if (editing) Caret()
        }
        if (error != null) Text(error, style = TvType.ui(14.5f, FontWeight.SemiBold, Tv.Red), maxLines = 2)
    }
}

@Composable
private fun Caret() {
    // Two frames a second; an endless animation would redraw the open sheet every frame.
    var on by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            on = !on
        }
    }
    Spacer(Modifier.width(2.dp))
    Box(Modifier.size(2.dp, 26.dp).background(if (on) Color.White else Color.Transparent))
}

// ---- Toast --------------------------------------------------------------------

/** .toast: a short message at the bottom of the screen. */
@Composable
fun BoxScope.TvToast(message: String?) {
    AnimatedVisibility(
        visible = message != null,
        enter = fadeIn(tween(200)) + slideInVertically(tween(200)) { it / 4 },
        exit = fadeOut(tween(200)) + slideOutVertically(tween(200)) { it / 4 },
        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp),
    ) {
        var last by remember { mutableStateOf("") }
        if (message != null) last = message
        // Solid, so it stays readable over the hints and rows it lands on.
        Box(
            Modifier
                .shadow(18.dp, CircleShape, ambientColor = Color.Black, spotColor = Color.Black)
                .clip(CircleShape)
                .background(Color(0xFF111821))
                .border(1.dp, Tv.GlassEdge, CircleShape),
        ) {
            Text(last, style = TvType.ui(16f, FontWeight.Bold), maxLines = 1, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
        }
    }
}

/**
 * Lets a real keyboard (or adb) type into a text field while it is on screen: Backspace deletes,
 * characters are added, and Enter does what the on-screen Done does ([onEnter], true when it
 * did something). It works wherever focus is, because the app root hands keys to it.
 */
@Composable
fun TypeInto(nav: Nav, onDelete: () -> Unit, onEnter: () -> Boolean = { false }, onChar: (Char) -> Unit) {
    val delete by rememberUpdatedState(onDelete)
    val enter by rememberUpdatedState(onEnter)
    val char by rememberUpdatedState(onChar)
    DisposableEffect(nav) {
        var entered = false
        val handler: (KeyEvent) -> Boolean = { e ->
            when {
                e.key == Key.Backspace -> {
                    if (e.type == KeyEventType.KeyDown) delete()
                    true
                }
                // A remote's OK can be Enter too, and presses the focused key; a keyboard's Enter is Done.
                (e.key == Key.Enter || e.key == Key.NumPadEnter) && e.fromKeyboard -> {
                    if (e.type == KeyEventType.KeyDown) entered = enter()
                    entered
                }
                e.type == KeyEventType.KeyDown && e.utf16CodePoint >= 32 -> {
                    char(e.utf16CodePoint.toChar())
                    true
                }
                else -> false
            }
        }
        nav.typists.add(handler)
        onDispose { nav.typists.remove(handler) }
    }
}

/**
 * The one confirm for things that can't be undone: the row turns red, says what goes, and offers
 * [confirm] and Keep, with Keep focused.
 */
@Composable
fun ConfirmBar(text: String, confirm: String, keep: FocusRequester, onConfirm: () -> Unit, onKeep: () -> Unit, icon: Ic = Ic.Trash) {
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
        TvButton(confirm, onConfirm, icon = icon, small = true, danger = true)
        TvButton("Keep", onKeep, small = true, focusRequester = keep)
    }
    LaunchedEffect(Unit) { keep.focusWhenReady() }
    // Back is Keep, and closes only the bar: added after the app's own handler, so it goes first.
    BackHandler(onBack = onKeep)
}

/** The key came from a keyboard with letters, not from a remote. */
private val KeyEvent.fromKeyboard: Boolean
    get() = nativeKeyEvent.device?.keyboardType == android.view.InputDevice.KEYBOARD_TYPE_ALPHABETIC

/** Moves focus to this requester's item unless it has it already. True when focus moved. */
fun FocusRequester.takeFocus(): Boolean = !hasFocusNow && tryFocus()

/**
 * Focus coming into this column from beside it lands on [target], its selected row, instead of
 * whichever row happens to be nearest. Selecting by focus would otherwise switch what is shown.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
fun Modifier.enterAt(target: () -> FocusRequester?): Modifier =
    focusProperties { enter = { target()?.takeIf { it.isOnScreen } ?: FocusRequester.Default } }.focusGroup()


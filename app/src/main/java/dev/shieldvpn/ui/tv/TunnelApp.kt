package dev.shieldvpn.ui.tv

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.shieldvpn.APP_NAME
import dev.shieldvpn.vpn.AppSettings
import dev.shieldvpn.vpn.Backup
import dev.shieldvpn.vpn.Connection
import dev.shieldvpn.vpn.UpdateState
import dev.shieldvpn.vpn.VpnStatus
import dev.shieldvpn.vpn.VpnUiState
import dev.shieldvpn.vpn.isActive
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Everything the screens can ask the app to do. MainActivity carries these out. */
interface TunnelActions {
    /** Connects [id]; a typed login is saved when [remember] is on. Android's VPN consent is handled here. */
    fun connect(id: String, username: String? = null, password: String? = null, remember: Boolean = true)
    fun disconnect()

    /** Keeps the last speed test of profile [key], or of [SpeedTest.DIRECT]. */
    fun saveSpeed(key: String, result: dev.shieldvpn.vpn.SpeedTest.Result)
    fun pause()
    fun pauseMore()
    fun resume()
    fun rename(id: String, name: String)
    fun saveLogin(id: String, username: String, password: String)
    fun forgetPassword(id: String)
    fun setServer(id: String, host: String, port: Int)
    fun resetServer(id: String)
    fun setProtocol(id: String, value: String)
    fun setKillMode(id: String, value: String)
    fun setDnsMode(id: String, value: String)
    fun setOrder(id: String, value: String)
    fun setPinned(id: String, pinned: Boolean)
    fun setLook(id: String, icon: String?, colour: Int?)
    fun setStartup(id: String?)
    fun duplicate(id: String): String?
    fun delete(id: String)
    fun pickFile()
    fun cancelReview()
    fun renameReview(name: String)
    fun saveReview(): String?
    fun rawConfig(serverId: String): String
    fun changeSettings(change: (AppSettings) -> AppSettings)
    fun clearLog()
    fun resetAll()

    /** A file or text from a phone, and a login typed with it. Returns what is wrong with it, or null when it arrived. */
    fun receive(name: String, bytes: ByteArray, username: String = "", password: String = ""): String?
    suspend fun download(url: String, username: String?, password: String?): String?
    fun cancelRestore()
    suspend fun restore(passphrase: String): Backup.Restored
    fun setBackupPassphrase(passphrase: String?)
    suspend fun backupFile(): ByteArray?
    fun profileFile(id: String): ByteArray?
    fun logText(): String
    fun setPin(pin: String?)
    fun checkPin(pin: String): Boolean

    /** The .p12 waiting for its password: null when [password] opens it, or what is wrong. */
    fun checkP12(password: String): String?
    fun attachP12(password: String, id: String): String?
    fun cancelP12()

    /** Moves the waiting .p12's key into Android's keystore. Returns its alias, or null. */
    fun keepP12(password: String): String?
    fun setKeyAlias(id: String, alias: String?)

    /** Android's list of certificates installed on the TV; [chosen] gets the alias, or null. */
    fun chooseKeyAlias(chosen: (String?) -> Unit)

    /** The apps on the TV's home screen, for Settings › Apps. */
    fun launcherApps(): List<LauncherApp>

    /** Opens Android's VPN settings, for Always-on VPN. Returns false if the TV has none. */
    fun openVpnSettings(): Boolean
    fun exit()
    val versionName: String

    /** Looks on GitHub for a newer build (Settings › About). */
    fun checkUpdate()

    /** Downloads and installs the newer build found. */
    fun installUpdate()
}

/** An app on the TV's home screen. */
class LauncherApp(val pkg: String, val label: String, val icon: androidx.compose.ui.graphics.ImageBitmap?)

enum class Screen(val label: String) { Connect("Connect"), Profiles("Profiles"), Import("Import"), Settings("Settings") }

/** A column in the Profiles stack, after the list. */
sealed interface Col {
    val id: String

    data class Profile(override val id: String) : Col
    data class Advanced(override val id: String) : Col
    data class Leaf(override val id: String, val kind: LeafKind) : Col
}

enum class LeafKind { Name, Look, Password, Cert, Server, Protocol, Order, Kill, Dns, File }

sealed interface Overlay {
    /** Asks for a username and password, then connects, or only saves when [save] is set. */
    data class SignIn(val id: String, val save: Boolean = false, val forStartup: Boolean = false) : Overlay
    data object Details : Overlay
    /** Text to read: a config file (coloured), or plain text with [plain]. */
    data class File(val eyebrow: String, val title: String, val text: String, val plain: Boolean = false) : Overlay
    data class Menu(val id: String) : Overlay
    data class Phone(val kind: PhoneKind) : Overlay

    /** Sets the backup passphrase, or with [restore], unlocks a backup that arrived; with [thenBackup], the backup follows. */
    data class Passphrase(val restore: Boolean, val thenBackup: Boolean = false) : Overlay

    /** Asks for the PIN, then runs [then]; with [setting], chooses a new PIN and turns on [mode]. */
    class Pin(val setting: Boolean = false, val mode: String? = null, val locksApp: Boolean = false, val then: (() -> Unit)? = null) : Overlay

    /** A .p12 certificate that arrived: its password, then the profile it goes into. */
    data object P12 : Overlay

    /** Picks a profile for [title], then hands its id to [picked]. */
    class PickProfile(val title: String, val sub: String, val cancelled: (() -> Unit)? = null, val picked: (String) -> Unit) : Overlay

    /** Tests how fast profile [id] is, connecting to it first if the user agrees. */
    data class Speed(val id: String) : Overlay

    /** Edits one line of text, typed here or on a phone, and hands it to [save]. */
    class Text(val kind: TextKind, val initial: String, val check: (String) -> String? = { null }, val save: (String) -> Unit) : Overlay
}

/** Where the user is in the app. Lives in the composition, beside the tunnel state from the view model. */
class Nav {
    var screen by mutableStateOf(Screen.Connect)

    /** Connect: the card in focus, shown in the hero; null shows the current profile. */
    var preview by mutableStateOf<String?>(null)

    /** Profiles: the list row in focus, shown in the hero. */
    var profFocus by mutableStateOf<String?>(null)
    val stack = mutableStateListOf<Col>()

    /** Profiles: the row to focus once the stack has moved, and a counter to trigger it. */
    var stackFocus by mutableStateOf<String?>(null)
    var stackMoves by mutableIntStateOf(0)

    // From your phone works on every TV; many have no file picker for USB or Downloads.
    var importMethod by mutableStateOf(ImportMethod.Phone)
    var importMore by mutableStateOf(false)

    var settingsCat by mutableStateOf(Category.Protection)
    var pick by mutableStateOf<Pick?>(null)
    var showLog by mutableStateOf(false)

    /** The Settings row focus goes back to when Back closes what it opened. */
    var settingsReturn: String? = null
    var confirmReset by mutableStateOf(false)

    /** The sheet on top, if any. Opening one remembers what had focus, so closing it can go back there. */
    var overlay: Overlay?
        get() = overlayState
        set(value) {
            if (overlayState == null && value != null) opener = focusedRequester()
            overlayState = value
        }
    private var overlayState by mutableStateOf<Overlay?>(null)

    /** What had focus when the sheet opened. */
    var opener: FocusRequester? = null

    /** The screen whose content should take focus next, for example after OK on its tab. */
    var contentFocus by mutableStateOf<Screen?>(null)
    var tabFocus by mutableStateOf(false)

    /** The remote's focus is on the tabs, so Back from the Connect tab leaves the app. */
    var onTabs by mutableStateOf(false)

    var toast by mutableStateOf<String?>(null)
    var toastCount by mutableIntStateOf(0)

    /** The profile under the remote's focus, for the Menu and Play keys. */
    var focusedPid by mutableStateOf<String?>(null)

    /** Scroll positions of the Profiles columns, so a column comes back where it was. */
    private val lists = HashMap<String, androidx.compose.foundation.lazy.LazyListState>()

    fun listState(key: String) = lists.getOrPut(key) { androidx.compose.foundation.lazy.LazyListState() }

    /** Lets go of the scroll positions of profiles that are gone. */
    fun keepLists(ids: Set<String>) {
        lists.keys.removeAll { it.substringAfter('-') !in ids }
    }

    /** Starts [key]'s list at the top again, so a column opened afresh shows its first rows. */
    fun resetList(key: String) {
        lists.remove(key)
    }

    /** Where the remote's focus starts on the current screen, when it comes back from nowhere. */
    var entry: FocusRequester? = null

    /** Asks for focus on [target] once the screen has redrawn without the sheet. */
    var refocusTarget by mutableStateOf<FocusRequester?>(null)

    fun refocus(target: FocusRequester) {
        refocusTarget = target
    }

    /** Counts presses that should put focus back into the open sheet. */
    var overlayRefocus by mutableIntStateOf(0)

    /** The PIN was entered in this visit to the app. */
    var unlocked by mutableStateOf(false)

    /** Settings › Diagnostics: the connection test is open. */
    var showTest by mutableStateOf(false)

    /** Import › From a link. */
    var linkUrl by mutableStateOf("")
    var linkLogin by mutableStateOf(false)
    var linkUser by mutableStateOf("")
    var linkPass by mutableStateOf("")

    /**
     * The open text fields' keyboard handlers, newest last. A real keyboard types into the
     * newest one wherever focus is, for example after a mouse click put Android in touch mode.
     */
    val typists = mutableListOf<(KeyEvent) -> Boolean>()

    fun toast(message: String) {
        toast = message
        toastCount++
    }

    fun go(to: Screen, focusContent: Boolean = true) {
        if (to != screen) {
            if (to != Screen.Connect) preview = null
            if (to != Screen.Profiles) stack.clear()
            if (to != Screen.Settings) {
                pick = null
                showLog = false
                confirmReset = false
            }
            screen = to
        }
        overlay = null
        if (focusContent) contentFocus = to
    }

    fun push(col: Col, focus: String) {
        stack.add(col)
        moveStackFocus(focus)
    }

    /** Back one column; focus goes to the row that opened it. */
    fun pop(): Boolean {
        if (stack.isEmpty()) return false
        val gone = stack.removeAt(stack.lastIndex)
        moveStackFocus(rowKeyOpening(gone, stack.lastOrNull()))
        return true
    }

    /** Replaces the open column, from a row in the narrow column next to it. */
    fun swap(col: Col, focus: String) {
        if (stack.isNotEmpty()) stack[stack.lastIndex] = col else stack.add(col)
        moveStackFocus(focus)
    }

    fun moveStackFocus(key: String) {
        stackFocus = key
        stackMoves++
    }
}

/**
 * Runs [block] now, or after the PIN when Settings › Protection locks profiles and settings
 * and it has not been entered in this visit.
 */
fun Nav.guard(state: VpnUiState, block: () -> Unit) {
    if (state.settings.pinMode != "Off" && state.hasPin && !unlocked) overlay = Overlay.Pin(then = block) else block()
}

/** The key of the row that opens [col], in the column before it. */
fun rowKeyOpening(col: Col, parent: Col?): String = when (col) {
    is Col.Profile -> "pl-${col.id}"
    is Col.Advanced -> "pe-adv"
    is Col.Leaf -> if (parent is Col.Advanced) "pa-${col.kind.name}" else when (col.kind) {
        LeafKind.Name -> "pe-name"
        LeafKind.Look -> "pe-look"
        else -> "pe-signin"
    }
}

/** The app: the tunnel, the top bar and the four screens, on the 4K design canvas. */
@Composable
fun TunnelApp(state: VpnUiState, actions: TunnelActions) {
    val nav = remember { Nav().apply { contentFocus = Screen.Connect } }
    val mode = modeOf(state)

    LaunchedEffect(nav.toastCount) {
        val shown = nav.toast ?: return@LaunchedEffect
        // Long enough to read: about a second per eight words, at least two.
        delay((1500L + shown.length * 45L).coerceIn(2200L, 7000L))
        nav.toast = null
    }
    // A picked file opens its review on the Import screen.
    LaunchedEffect(state.review) {
        if (state.review != null) nav.go(Screen.Import)
    }
    // A backup that arrives asks for its passphrase, and a certificate for its password.
    LaunchedEffect(state.pendingRestore) {
        if (state.pendingRestore != null) nav.overlay = Overlay.Passphrase(restore = true)
    }
    LaunchedEffect(state.pendingP12) {
        if (state.pendingP12 != null) nav.overlay = Overlay.P12
    }
    // The check when the app opened found a newer build: say so once; Settings › About installs it.
    LaunchedEffect(state.update) {
        val u = state.update
        if (u is UpdateState.Available && u.found) nav.toast("Build ${u.build.number} is ready: Settings › About")
    }

    BackHandler { if (!back(nav, state, actions)) actions.exit() }
    LaunchedEffect(nav.refocusTarget) {
        val target = nav.refocusTarget ?: return@LaunchedEffect
        target.focusWhenReady()
        if (!target.hasFocusNow) nav.contentFocus = nav.screen
        nav.refocusTarget = null
    }

    // Settings › Protection › Lock with a PIN: the PIN is asked again after the app was away,
    // and with "For everything", before anything else.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // The observer lives as long as the screen, so it reads the settings as they are now.
    val latest by rememberUpdatedState(state)
    DisposableEffect(lifecycle) {
        val watch = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) nav.unlocked = false
            if (event == Lifecycle.Event.ON_START && latest.settings.pinMode == "For everything" && latest.hasPin && !nav.unlocked) {
                nav.overlay = Overlay.Pin(locksApp = true)
            }
        }
        lifecycle.addObserver(watch)
        onDispose { lifecycle.removeObserver(watch) }
    }

    // Focus can be lost: at launch the window gets focus after the first request, Android's
    // dialogs take it away, and a mouse click puts Android in touch mode, where nothing has it.
    // Android uses up the first remote press after a click to leave touch mode; that switch is
    // when the screen takes focus back, so that press already lands somewhere useful.
    var anyFocus by remember { mutableStateOf(false) }
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val inputMode = LocalInputModeManager.current.inputMode
    LaunchedEffect(windowFocused, anyFocus, inputMode) {
        // Leaving touch mode, Android can hand focus to something that is not a row or button.
        if (windowFocused && inputMode == InputMode.Keyboard && !anyItemFocused()) {
            delay(80)
            if (!anyItemFocused()) {
                if (nav.overlay != null) nav.overlayRefocus++ else nav.contentFocus = nav.screen
            }
        }
    }

    val connect: (String) -> Unit = { id ->
        val c = state.connection(id)
        if (c != null && state.asksForLogin(c)) nav.overlay = Overlay.SignIn(id) else actions.connect(id)
    }

    TvStage {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onFocusChanged { anyFocus = it.hasFocus }
                // After a mouse click, the first remote press lands on the screen's main item, not the tabs.
                .enterAt { if (nav.overlay == null) nav.entry else null }
                .onPreviewKeyEvent { e ->
                    if (nav.typists.lastOrNull()?.invoke(e) == true) return@onPreviewKeyEvent true
                    // After a mouse click nothing has the remote's focus. The first press brings it
                    // back to where the screen starts, instead of jumping from the edge.
                    if (!anyFocus && e.type == KeyEventType.KeyDown && e.key in RemoteMoves) {
                        if (nav.overlay != null) nav.overlayRefocus++ else nav.contentFocus = nav.screen
                        return@onPreviewKeyEvent true
                    }
                    e.type == KeyEventType.KeyDown && remoteKey(e.key, nav, state, actions, connect)
                },
        ) {
            TunnelBackdrop(
                mode,
                Modifier.fillMaxSize(),
                covered = nav.overlay != null || nav.screen == Screen.Import || nav.screen == Screen.Settings,
                blocked = state.status is VpnStatus.Blocked,
            )
            Scrims(nav)
            when (nav.screen) {
                Screen.Connect -> ConnectScreen(state, nav, actions, connect)
                Screen.Profiles -> ProfilesScreen(state, nav, actions, connect)
                Screen.Import -> ImportScreen(state, nav, actions, connect)
                Screen.Settings -> SettingsScreen(state, nav, actions)
            }
            TopBar(nav)
            Overlays(state, nav, actions, connect)
            TvToast(nav.toast)
        }
    }
}

/** The remote's Menu and Play/Pause keys work from every screen. */
private fun remoteKey(key: Key, nav: Nav, state: VpnUiState, actions: TunnelActions, connect: (String) -> Unit): Boolean {
    when (key) {
        Key.Menu -> {
            if (nav.overlay != null) return false
            val id = nav.focusedPid ?: return false
            nav.overlay = Overlay.Menu(id)
            return true
        }
        Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> {
            if (nav.overlay != null) return true
            val id = nav.focusedPid
            if (id != null && id != state.activeId) {
                connect(id)
                // A profile without its password opens the sign-in sheet instead.
                state.connection(id)?.takeIf { !state.asksForLogin(it) }?.let { nav.toast("Connecting to ${it.name}") }
                return true
            }
            primary(state, actions, connect)?.let(nav::toast)
            return true
        }
        else -> return false
    }
}

private val RemoteMoves = setOf(Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight, Key.DirectionCenter, Key.Enter)

/** Connect's main button: connect, cancel or disconnect the current profile. Returns a toast. */
fun primary(state: VpnUiState, actions: TunnelActions, connect: (String) -> Unit): String? {
    val c = state.active ?: return null
    return when (modeOf(state)) {
        Mode.On, Mode.Busy, Mode.Paused -> {
            actions.disconnect()
            "Disconnected"
        }
        Mode.Off, Mode.Error -> {
            if (state.status is VpnStatus.Blocked) {
                actions.disconnect()
                "Disconnected"
            } else {
                connect(c.id)
                "Connecting to ${c.name}"
            }
        }
    }
}

/** Back, as on the remote: closes what is open, then steps out towards the tabs. */
private fun back(nav: Nav, state: VpnUiState, actions: TunnelActions): Boolean {
    if (nav.overlay != null) {
        // A PIN that locks the whole app cannot be stepped around: Back leaves the app.
        if ((nav.overlay as? Overlay.Pin)?.locksApp == true) return false
        if ((nav.overlay as? Overlay.Passphrase)?.restore == true) actions.cancelRestore()
        if (nav.overlay == Overlay.P12) actions.cancelP12()
        (nav.overlay as? Overlay.PickProfile)?.cancelled?.invoke()
        val opener = nav.opener
        nav.overlay = null
        if (opener != null && opener.isOnScreen) nav.refocus(opener) else nav.contentFocus = nav.screen
        return true
    }
    when (nav.screen) {
        Screen.Profiles -> if (nav.pop()) return true
        Screen.Import -> if (state.review != null) {
            actions.cancelReview()
            nav.contentFocus = Screen.Import
            return true
        }
        // Back closes the choice column, the log or the test and lands on the row that opened it.
        Screen.Settings -> {
            val opener = when {
                nav.pick != null -> nav.pick!!.rowKey.also { nav.pick = null }
                nav.showLog -> "s-log".also { nav.showLog = false }
                nav.showTest -> "s-test".also { nav.showTest = false }
                else -> null
            }
            if (opener != null) {
                nav.settingsReturn = opener
                nav.contentFocus = Screen.Settings
                return true
            }
        }
        Screen.Connect -> if (nav.preview != null) {
            nav.preview = null
            nav.contentFocus = Screen.Connect
            return true
        }
    }
    // Up to the tabs first. With nothing focused (a mouse click puts Android in touch mode, which
    // refuses focus) there is nothing to step up from, so Back goes on.
    if (!nav.onTabs && anyItemFocused()) {
        nav.tabFocus = true
        return true
    }
    if (nav.screen != Screen.Connect) {
        nav.go(Screen.Connect, focusContent = false)
        nav.tabFocus = true
        return true
    }
    return false
}

// ---- Shared state helpers ---------------------------------------------------------

fun modeOf(s: VpnUiState): Mode = when {
    s.pausedUntil != null -> Mode.Paused
    s.status is VpnStatus.Connected -> Mode.On
    s.status is VpnStatus.Connecting -> Mode.Busy
    s.status is VpnStatus.Blocked || s.status is VpnStatus.Failed -> Mode.Error
    else -> Mode.Off
}

/** The line under a profile's name in lists and on cards. */
fun statusText(state: VpnUiState, c: Connection): String {
    if (c.id == state.activeId) {
        return when (modeOf(state)) {
            Mode.On -> "Connected"
            Mode.Busy -> "Connecting…"
            Mode.Paused -> "Paused"
            Mode.Error -> "Failed"
            Mode.Off -> "Not connected"
        }
    }
    if (state.asksForLogin(c)) return "Asks for a password"
    if (c.lastError != null) return "Failed: ${c.lastError}"
    return c.lastUsed?.let { "Last used ${ago(it)}" } ?: "Never connected"
}

fun authText(state: VpnUiState, c: Connection): String {
    val info = state.infoOf(c)
    val login = state.logins[c.id]
    return when {
        info != null && !info.needsLogin -> when {
            c.keyAlias != null -> "Key in the device’s keystore"
            info.clientCert -> "Certificate in the file"
            else -> "No certificate or password in the file"
        }
        login != null && login.hasPassword && login.username.isNotEmpty() -> "Password saved · ${login.username}"
        else -> "Asks for a password"
    }
}

fun ago(millis: Long): String {
    val minutes = (System.currentTimeMillis() - millis) / 60_000
    val hours = minutes / 60
    val days = hours / 24
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        hours < 24 -> "$hours h ago"
        days == 1L -> "yesterday"
        days < 7 -> "$days days ago"
        days < 14 -> "last week"
        else -> "${days / 7} weeks ago"
    }
}

fun fmtDuration(seconds: Long): String =
    "%02d:%02d:%02d".format(seconds / 3600, (seconds / 60) % 60, seconds % 60)

fun fmtMinutes(seconds: Long): String = "%02d:%02d".format(seconds / 60, seconds % 60)

/** 1.24 GB, 86 MB, 512 KB. */
fun fmtBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "%.2f GB".format(bytes / 1e9)
    bytes >= 1_000_000 -> "%.0f MB".format(bytes / 1e6)
    bytes >= 1_000 -> "%.0f KB".format(bytes / 1e3)
    else -> "$bytes B"
}

/** The current time, ticking once a second while the screens are in front. */
@Composable
fun rememberNow(): Long {
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(1000)
            // The frame clock stops while the app is hidden, so the ticking stops with it.
            withFrameNanos { }
            value = System.currentTimeMillis()
        }
    }
    return now
}

/** Waits until content that just appeared has been laid out, so a focus request lands. */
suspend fun awaitLayout() {
    withFrameNanos { }
    withFrameNanos { }
}

/** Moves focus to this requester if its target is on screen. False when there is no target. */
fun FocusRequester.tryFocus(): Boolean = runCatching { requestFocus() }.isSuccess

/**
 * Moves focus here once the target is laid out. Right after a mouse click Android is still
 * in touch mode for a frame or two and refuses focus, so it tries again for a short while.
 */
suspend fun FocusRequester.focusWhenReady() {
    awaitLayout()
    repeat(8) {
        tryFocus()
        withFrameNanos { }
        if (hasFocusNow) return
    }
}

/** Focus requesters by key, for rows that focus has to come back to. */
class Requesters {
    private val map = HashMap<String, FocusRequester>()
    operator fun get(key: String): FocusRequester = map.getOrPut(key) { FocusRequester() }

    /** Makes [alias] reach the same row as [key]. */
    fun alias(alias: String, key: String) {
        map[alias] = get(key)
    }
}

// ---- Top bar and scrims ------------------------------------------------------------

@Composable
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
private fun TopBar(nav: Nav) {
    val tabs = remember { Requesters() }
    LaunchedEffect(nav.tabFocus) {
        if (nav.tabFocus) {
            tabs[nav.screen.name].focusWhenReady()
            nav.tabFocus = false
        }
    }
    Row(
        modifier = Modifier
            .padding(start = 64.dp, end = 64.dp, top = 36.dp)
            .height(56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(Tv.white(0.08f))
                    .border(1.dp, Tv.white(0.12f), RoundedCornerShape(13.dp)),
                contentAlignment = Alignment.Center,
            ) { TvIcon(Ic.Logo, 24f, stroke = 2f) }
            Text(APP_NAME, style = TvType.display(25f, tracking = -0.02f))
        }
        Spacer(Modifier.width(34.dp))
        Glass(deep = false, shape = CircleShape) {
            Row(
                // Up from a screen lands on that screen's tab, not on whichever tab is nearest.
                modifier = Modifier
                    .onFocusChanged { nav.onTabs = it.hasFocus }
                    .focusProperties { enter = { tabs[nav.screen.name] } }
                    .focusGroup()
                    .padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Screen.entries.forEach { s ->
                    TabItem(
                        label = s.label,
                        selected = s == nav.screen,
                        focusRequester = tabs[s.name],
                        onFocus = { if (s != nav.screen) nav.go(s, focusContent = false) },
                        onEnter = { nav.contentFocus = s },
                    )
                }
            }
        }
        Spacer(Modifier.weight(1f))
        val now = rememberNow()
        Text(
            remember(now / 60_000) { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(now)) },
            style = TvType.ui(20f, FontWeight.SemiBold, Tv.soft(0.72f)),
        )
    }
}

@Composable
private fun TabItem(label: String, selected: Boolean, focusRequester: FocusRequester, onFocus: () -> Unit, onEnter: () -> Unit) {
    val f = rememberFocusState()
    val scale by animateFloatAsState(if (f.focused) 1.06f else 1f, tween(180, easing = FocusEase), label = "tab")
    Box(
        modifier = Modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .background(
                when {
                    f.focused -> Tv.Focus
                    selected -> Tv.white(0.15f)
                    else -> Color.Transparent
                },
            )
            .onKeyEvent { e ->
                // Down from a tab goes into the screen, wherever its content starts; so does Up,
                // as nothing is above the tabs.
                if (e.type == KeyEventType.KeyDown && (e.key == Key.DirectionDown || e.key == Key.DirectionUp)) {
                    onEnter()
                    true
                } else {
                    false
                }
            }
            .tvFocus(f, onEnter, focusRequester, onFocus)
            .padding(horizontal = 22.dp, vertical = 10.dp),
    ) {
        Text(
            label,
            style = TvType.ui(
                17f,
                when {
                    f.focused -> FontWeight.ExtraBold
                    selected -> FontWeight.Bold
                    else -> FontWeight.SemiBold
                },
                when {
                    f.focused -> Tv.OnFocus
                    selected -> Color.White
                    else -> Tv.soft(0.66f)
                },
            ),
        )
    }
}

/** The shading that keeps text readable over the background, by screen. */
@Composable
private fun Scrims(nav: Nav) {
    val hero by animateFloatAsState(if (nav.screen == Screen.Connect || nav.screen == Screen.Profiles) 1f else 0f, tween(450), label = "hero")
    val side by animateFloatAsState(if (nav.screen == Screen.Profiles) 1f else 0f, tween(450), label = "side")
    val dim by animateFloatAsState(
        when {
            nav.screen == Screen.Import || nav.screen == Screen.Settings -> 1f
            nav.screen == Screen.Profiles && nav.stack.size >= 2 -> 0.6f
            nav.screen == Screen.Profiles && nav.stack.size == 1 -> 0.45f
            else -> 0f
        },
        tween(450),
        label = "dim",
    )
    val ink = Color(0xFF020307)
    Box(
        Modifier
            .fillMaxSize()
            .fade { hero }
            .background(Brush.verticalGradient(0.46f to ink.copy(alpha = 0f), 0.7f to ink.copy(alpha = 0.5f), 1f to ink.copy(alpha = 0.9f))),
    )
    Box(
        Modifier
            .fillMaxSize()
            .fade { hero }
            .background(Brush.horizontalGradient(0f to ink.copy(alpha = 0.6f), 0.4f to ink.copy(alpha = 0.18f), 0.6f to ink.copy(alpha = 0f))),
    )
    Box(
        Modifier
            .fillMaxSize()
            .fade { side }
            .background(Brush.horizontalGradient(0.45f to ink.copy(alpha = 0f), 1f to ink.copy(alpha = 0.55f))),
    )
    Box(
        Modifier
            .fillMaxSize()
            .fade { dim }
            .background(Color(0x80030509)),
    )
}

/** True while the tunnel for [id] is up or being set up. */
fun isLive(state: VpnUiState, id: String): Boolean =
    id == state.activeId && (state.status.isActive || state.pausedUntil != null) && state.status !is VpnStatus.Blocked

/** Shadow used by the big focus states. */
fun Modifier.liftShadow(on: Boolean, radius: Float): Modifier =
    shadow(if (on) 20.dp else 0.dp, RoundedCornerShape(radius.dp), ambientColor = Color.Black, spotColor = Color.Black)

/** Keeps an offset modifier readable where the design positions things absolutely. */
fun Modifier.at(x: Float, y: Float): Modifier = offset(x.dp, y.dp)

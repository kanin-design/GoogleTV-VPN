package dev.shieldvpn.ui.tv

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.shieldvpn.control.ConnectionTest
import dev.shieldvpn.control.Control
import dev.shieldvpn.control.Schema
import dev.shieldvpn.remote.Device
import dev.shieldvpn.remote.Devices
import dev.shieldvpn.remote.Pairing
import dev.shieldvpn.control.Validate
import dev.shieldvpn.control.Words
import dev.shieldvpn.APP_FILE_NAME
import dev.shieldvpn.APP_NAME
import dev.shieldvpn.BuildConfig
import dev.shieldvpn.phone.PhoneAction
import dev.shieldvpn.phone.PhoneField
import dev.shieldvpn.phone.PhoneForm
import dev.shieldvpn.phone.PhoneReply
import dev.shieldvpn.phone.PhoneSection
import dev.shieldvpn.phone.PhoneStatus
import dev.shieldvpn.phone.Staged
import dev.shieldvpn.vpn.Backup
import dev.shieldvpn.vpn.Inspected
import dev.shieldvpn.vpn.AppSettings
import dev.shieldvpn.vpn.LogKind
import dev.shieldvpn.vpn.UpdateState
import dev.shieldvpn.vpn.VpnStatus
import dev.shieldvpn.vpn.VpnUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Category(val label: String, val icon: Ic, val advanced: Boolean = false) {
    Protection("Protection", Ic.Shield),
    Startup("Startup", Ic.Rocket),
    Apps("Apps", Ic.Apps),
    Diagnostics("Diagnostics", Ic.Pulse),
    Backup("Backup", Ic.Archive),
    Phones("Phone access", Ic.Phone),
    OpenVpn("OpenVPN", Ic.Gear, advanced = true),
    Dns("DNS", Ic.Globe, advanced = true),
    Automation("Automation", Ic.Bolt, advanced = true),
    About("About", Ic.Info),
}

/** A value picked in the column that slides in from the right. */
sealed interface Pick {
    data object Startup : Pick

    /** One of [Values], by key. */
    data class Value(val key: String) : Pick

    /** The row that opens this choice, where focus goes back to when it closes. */
    val rowKey: String get() = when (this) {
        Startup -> "s-boot"
        is Value -> "s-$key"
    }
}

private val PickEase = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)

/**
 * Settings: short by default. Picking a value shrinks the categories to an icon rail and
 * slides a choice column in from the right.
 */
@Composable
fun SettingsScreen(state: VpnUiState, nav: Nav, actions: TunnelActions, connect: (String) -> Unit) {
    val req = remember { Requesters() }
    SideEffect { nav.entry = req[if (nav.pick != null) "pk-sel" else "cat-${nav.settingsCat.name}"] }
    var focusKey by remember { mutableStateOf<String?>(null) }
    var focusMoves by remember { mutableIntStateOf(0) }
    val moveFocus = { key: String ->
        focusKey = key
        focusMoves++
    }
    LaunchedEffect(nav.contentFocus) {
        if (nav.contentFocus == Screen.Settings) {
            val key = nav.settingsReturn?.also { nav.settingsReturn = null } ?: when {
                nav.pick != null -> "pk-sel"
                nav.showLog -> "s-log-back"
                nav.showTest -> "s-test-back"
                else -> "cat-${nav.settingsCat.name}"
            }
            req[key].focusWhenReady()
            nav.contentFocus = null
        }
    }
    LaunchedEffect(focusMoves) {
        focusKey?.let { req[it].focusWhenReady() }
    }
    val picking = nav.pick != null
    val closePick = { opener: String ->
        nav.pick = null
        moveFocus(opener)
        nav.flash(opener)
    }

    Box(Modifier.fillMaxSize()) {
        val navWidth by animateDpAsState(if (picking) 84.dp else 320.dp, tween(380, easing = PickEase), label = "navW")
        val panelLeft by animateDpAsState(if (picking) 172.dp else 408.dp, tween(380, easing = PickEase), label = "panelL")
        val panelWidth by animateDpAsState(if (picking) 528.dp else 808.dp, tween(380, easing = PickEase), label = "panelW")
        val pickAlpha by animateFloatAsState(if (picking) 1f else 0f, tween(280), label = "pickA")
        val pickShift by animateFloatAsState(if (picking) 0f else 70f, tween(380, easing = PickEase), label = "pickX")

        // Categories.
        Glass(Modifier.placed({ 64.dp }, 124.dp, { navWidth }).height(560.dp)) {
            Column(Modifier.fillMaxSize().padding(start = 14.dp, end = 14.dp, top = 22.dp, bottom = 16.dp)) {
                val labelAlpha by animateFloatAsState(if (picking) 0f else 1f, tween(200), label = "labels")
                Text("Settings", style = TvType.display(30f), maxLines = 1, modifier = Modifier.padding(start = 8.dp, bottom = 8.dp).fade { labelAlpha })
                Column(modifier = Modifier.enterAt { req["cat-${nav.settingsCat.name}"] }, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    val shown = Category.entries.filter { !it.advanced || state.settings.advanced }
                    shown.forEachIndexed { i, cat ->
                        // .cat-sep: a line before the advanced categories.
                        if (cat.advanced && i > 0 && !shown[i - 1].advanced) {
                            Box(Modifier.padding(horizontal = 14.dp, vertical = 6.dp).fillMaxWidth().height(1.dp).background(Tv.white(0.1f)))
                        }
                        CategoryRow(
                            cat,
                            selected = cat == nav.settingsCat,
                            labelAlpha = labelAlpha,
                            focusRequester = req["cat-${cat.name}"],
                            onFocus = {
                                if (nav.pick != null) nav.pick = null
                                if (nav.settingsCat != cat) {
                                    nav.settingsCat = cat
                                    nav.showLog = false
                                    nav.showTest = false
                                    nav.confirmReset = false
                                }
                            },
                            onClick = { req["panel-first"].tryFocus() },
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                if (!picking) {
                    ListRow(
                        Schema.words("settings.advanced").first, {
                            val on = !state.settings.advanced
                            actions.changeSettings { it.copy(advanced = on) }
                            if (!on && nav.settingsCat.advanced) nav.settingsCat = Category.Protection
                        },
                        subtitle = Schema.words("settings.advanced").second,
                        focusRequester = req["st-advanced"],
                        trailing = { TvSwitch(state.settings.advanced, it) },
                    )
                }
            }
        }

        // The category's settings. Coming in from the categories lands on the first row.
        Glass(Modifier.placed({ panelLeft }, 124.dp, { panelWidth }).height(560.dp).enterAt { req["panel-first"] }) {
            Column(Modifier.fillMaxSize().padding(horizontal = 34.dp, vertical = 30.dp)) {
                val locked = state.settings.pinMode != "Off" && state.hasPin && !nav.unlocked
                if (locked) {
                    LockedPanel(nav, req)
                } else if (nav.showLog && nav.settingsCat == Category.Diagnostics) {
                    LogPanel(state, nav, actions, req) {
                        nav.showLog = false
                        moveFocus("s-log")
                    }
                } else if (nav.showTest && nav.settingsCat == Category.Diagnostics) {
                    TestPanel(state, nav, actions, req, connect) {
                        nav.showTest = false
                        moveFocus("s-test")
                    }
                } else {
                    Panel(state, nav, actions, req) { pick ->
                        nav.pick = pick
                        moveFocus("pk-sel")
                    }
                }
            }
        }

        // The choice column. It composes as soon as a pick opens, so focus can land in it.
        if (picking || pickAlpha > 0.01f) {
            Glass(
                Modifier
                    .offset(724.dp, 124.dp)
                    .width(492.dp)
                    .height(560.dp)
                    .graphicsLayer {
                        alpha = pickAlpha
                        translationX = pickShift * density
                    },
            ) {
                Column(Modifier.fillMaxSize().padding(start = 28.dp, end = 28.dp, top = 28.dp, bottom = 18.dp)) {
                    when (val pick = nav.pick) {
                        Pick.Startup -> StartupPick(state, nav, actions, req) { closePick(pick.rowKey) }
                        is Pick.Value -> ValuePick(state, nav, actions, req, pick.key) { closePick(pick.rowKey) }
                        null -> Unit
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryRow(cat: Category, selected: Boolean, labelAlpha: Float, focusRequester: androidx.compose.ui.focus.FocusRequester, onFocus: () -> Unit, onClick: () -> Unit) {
    val f = rememberFocusState()
    val scale by animateFloatAsState(if (f.focused) 1.03f else 1f, tween(180, easing = FocusEase), label = "cat")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .height(36.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(
                when {
                    f.focused -> Tv.Focus
                    selected -> Tv.white(0.13f)
                    else -> Color.Transparent
                },
            )
            .tvFocus(f, onClick, focusRequester, onFocus)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val c = when {
            f.focused -> Tv.OnFocus
            selected -> Color.White
            else -> Tv.soft(0.76f)
        }
        TvIcon(cat.icon, 20f, c)
        Text(
            cat.label,
            style = TvType.ui(17f, if (selected || f.focused) FontWeight.ExtraBold else FontWeight.SemiBold, c),
            maxLines = 1,
            modifier = Modifier.weight(1f).fade { labelAlpha },
        )
    }
}

// ---- Panels ------------------------------------------------------------------------

private class PanelScope(val state: VpnUiState, val nav: Nav, val actions: TunnelActions, val req: Requesters, val devices: List<Device> = emptyList()) {
    var first = true

    /** The requester for row [k]. The first row of a panel also answers to "panel-first". */
    fun key(k: String): androidx.compose.ui.focus.FocusRequester {
        if (first) {
            first = false
            req.alias("panel-first", k)
        }
        return req[k]
    }

}

/** A setting picked from a short list, in the column on the right. */
private class ValueSetting(
    val eyebrow: String,
    val title: String,
    val sub: String,
    val options: List<String>,
    val get: (AppSettings) -> String,
    val set: (AppSettings, String) -> AppSettings,
)

private val Values = mapOf(
    "pin" to ValueSetting("Protection", "Lock with a PIN", "Choose what needs the PIN. Useful when children use the TV.", listOf("Off", "For settings", "For everything"), { it.pinMode }, { s, v -> s.copy(pinMode = v) }),
    "appmode" to ValueSetting("Apps", "Which apps use the VPN", "This decides what the switches below mean.", listOf(AppSettings.APPS_SKIP, AppSettings.APPS_ONLY), { it.appMode }, { s, v -> s.copy(appMode = v) }),
    "verb" to ValueSetting("Diagnostics", "Log detail", "How much the connection log shows.", listOf("3 · normal", "4 · detailed", "1 · quiet"), { it.verb }, { s, v -> s.copy(verb = v) }),
    "proto" to ValueSetting("OpenVPN", "Preferred protocol", "Used when a profile lists both UDP and TCP servers.", listOf("From file", "UDP", "TCP"), { it.proto }, { s, v -> s.copy(proto = v) }),
    "timeout" to ValueSetting("OpenVPN", "Connection timeout", "How long to wait before trying the next server.", listOf("30 s", "60 s", "120 s"), { "${it.timeout} s" }, { s, v -> s.copy(timeout = v.substringBefore(' ').toInt()) }),
    "mtu" to ValueSetting("OpenVPN", "Tunnel MTU", "The largest packet sent through the tunnel.", listOf("Auto", "1400", "1300"), { it.mtu }, { s, v -> s.copy(mtu = v) }),
    "tls" to ValueSetting("OpenVPN", "Minimum TLS version", "Servers that only offer older versions are refused.", listOf("TLS 1.2", "TLS 1.3"), { it.tlsMin }, { s, v -> s.copy(tlsMin = v) }),
    "reach" to ValueSetting(
        "Phone access", Schema.words("settings.phoneReach").first, "Paired phones only, on your home network. The VPN can be on with the app closed.",
        Schema.phoneReach.map { it.value }, { it.phoneReach }, { s, v -> s.copy(phoneReach = v) },
    ),
)

/** How a stored value reads on screen, where it is not the value itself. */
private val ValueLabel = Schema.phoneReach.associate { it.value to it.label } + mapOf(
    AppSettings.APPS_SKIP to "All apps, except the ones you choose",
    AppSettings.APPS_ONLY to "Only the apps you choose",
)

/** The shorter form for the value chip on the row. */
private val ValueChipLabel = mapOf(
    AppSettings.APPS_SKIP to "All except chosen",
    AppSettings.APPS_ONLY to "Only chosen",
    AppSettings.REACH_OPEN to "While the app is open",
    AppSettings.REACH_VPN to "Also while the VPN is on",
)

/** The line under each choice (the design's OPT_SUB). */
private val ValueSub = Schema.phoneReach.associate { it.value to it.help.orEmpty() } + mapOf(
    "Off" to "Anyone can change profiles and settings", "For settings" to "Ask before profiles or settings change", "For everything" to "Ask every time ${APP_NAME} opens",
    AppSettings.APPS_SKIP to "The apps you turn on skip the VPN", AppSettings.APPS_ONLY to "Every other app uses your normal connection",
    "1 · quiet" to "Errors and connection changes only", "3 · normal" to "Recommended", "4 · detailed" to "Everything, for troubleshooting with your VPN admin",
    "From file" to "Use what the profile says", "UDP" to "Faster. The usual choice", "TCP" to "Gets through strict networks",
    "30 s" to "Quick to give up on a dead server", "60 s" to "Recommended", "120 s" to "For slow or distant servers",
    "Auto" to "Recommended", "1400" to "Try this if video stalls", "1300" to "For networks that drop large packets",
    "TLS 1.2" to "Works with almost every server", "TLS 1.3" to "Stricter. Newer servers only",
)

/** A profile's name as a quoted shell word. */
fun quoted(name: String) = "\"" + name.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

/** The automation commands, with this device's own profile names. */
private fun commands(state: VpnUiState): String = buildString {
    append("Other apps and ADB can control ${APP_NAME} once\n\"Let other apps control ${APP_NAME}\" is on.\n\n")
    append("Connect the profile on the Connect screen:\n  adb shell am start -n dev.shieldvpn/.Connect\n\n")
    state.connections.take(8).forEach { c -> append("Connect ${c.name}:\n  adb shell am start -n dev.shieldvpn/.Connect --es profile ${quoted(c.name)}\n\n") }
    if (state.connections.isEmpty()) append("Connect a profile by its name:\n  adb shell am start -n dev.shieldvpn/.Connect --es profile \"Home\"\n\n")
    append("Disconnect:\n  adb shell am start -n dev.shieldvpn/.Disconnect\n\n")
    append("Home automation apps send the same intents:\n  component dev.shieldvpn/.Connect or dev.shieldvpn/.Disconnect,\n  with an optional string extra \"profile\".")
}

private const val PRIVACY = """What ${APP_NAME} keeps on this device
  Your profiles (the .ovpn files) and settings, in the app's private storage.
  Saved passwords and the backup passphrase, encrypted with a key in the device's keystore.
  The PIN, as a salted hash.

What leaves the device
  The VPN traffic itself, to the servers named in your profiles.
  While connected, one request through the tunnel to api.ipify.org
  (or icanhazip.com) to show the address sites see.
  Files you send to or from your phone, which stay on your home network.
  A speed test, only when you start one: data to and from speed.cloudflare.com.
  Each time the app opens, unless you turn it off in About, a look at this app's releases on GitHub
  (api.github.com) for a newer build, and the build itself when you choose to install it.

No accounts, no analytics, no ads."""

@Composable
private fun ColumnScope.Panel(state: VpnUiState, nav: Nav, actions: TunnelActions, req: Requesters, openPick: (Pick) -> Unit) {
    val s = state.settings
    val cat = nav.settingsCat
    val context = LocalContext.current
    val (title, sub) = when (cat) {
        Category.Protection -> "Protection" to "What happens when the tunnel drops, and what may leave the device outside it."
        Category.Startup -> "Startup" to "What ${APP_NAME} does when the device turns on or the network changes."
        Category.Apps -> "Apps" to "Some TV services block VPNs. Let those apps skip it, or send only the apps you choose through the VPN."
        Category.Diagnostics -> "Diagnostics" to "For when something does not connect."
        Category.Backup -> "Backup" to "Move everything to a new device, or keep a copy on your phone."
        Category.Phones -> Schema.groups.first { it.id == "phones" }.let { it.label to it.help }
        Category.OpenVpn -> "OpenVPN" to "Applied on top of every profile. A setting inside the .ovpn file wins unless noted."
        Category.Dns -> "DNS" to "Where name lookups go while the tunnel is up."
        Category.Automation -> "Automation" to "For home automation apps and ADB. Off until you turn it on."
        Category.About -> "About" to "${APP_NAME} ${actions.versionName} · build ${BuildConfig.VERSION_CODE} · OpenVPN 3 core."
    }
    PanelHeading("Settings", title, sub)
    // A change that only takes effect on the next connect: one line here while a tunnel is up, not a toast per switch.
    val later = cat in setOf(Category.Protection, Category.Startup, Category.Apps, Category.OpenVpn, Category.Dns)
    if (later && state.status.let { it is VpnStatus.Connected || it is VpnStatus.Connecting }) {
        // The kill switch is the one that changes at once.
        val note = if (cat == Category.Protection) "The kill switch changes at once. The rest apply the next time you connect." else "Changes here apply the next time you connect."
        Text(note, style = TvType.ui(15f, FontWeight.SemiBold, Tv.Amber), modifier = Modifier.padding(top = 10.dp))
    }
    // The category's phone page, with its code under the rows.
    val phone = when (cat) {
        Category.Dns -> dnsPhone(state, nav, actions)
        Category.Backup -> backupPhone(state, nav, actions)
        Category.Diagnostics -> diagnosticsPhone(state, nav, actions)
        Category.Automation -> automationPhone(state, actions)
        else -> null
    }
    val devices by Devices.list.collectAsState()
    val scope = PanelScope(state, nav, actions, req, devices)
    // Lint misreads produceState with a key: the value is set on every run.
    @Suppress("ProduceStateDoesNotAssignValue")
    val apps by produceState<List<LauncherApp>?>(null, cat) {
        // Only Settings › Apps needs them; on any other page the list and its icons are let go.
        value = if (cat == Category.Apps) withContext(Dispatchers.IO) { actions.launcherApps() } else null
    }
    val pickValue = { key: String -> openPick(Pick.Value(key)) }
    val list = remember(cat) { LazyListState() }
    // The phone's code sits under the rows and never scrolls away: it takes no focus, so the remote
    // could not bring it into view.
    val showPhone = phone != null && cat != Category.Diagnostics
    LazyColumn(
        state = list,
        modifier = Modifier.padding(top = 18.dp).weight(1f, fill = false).bleed(12.dp).scrollFade(list),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        when (cat) {
            Category.Protection -> {
                toggle(scope, "s-kill", "settings.killSwitch", s.killSwitch) {
                    actions.changeSettings { it.copy(killSwitch = !it.killSwitch) }
                }
                toggle(scope, "s-lan", "settings.allowLan", s.allowLan) {
                    actions.changeSettings { it.copy(allowLan = !it.allowLan) }
                }
                value(scope, "s-pin", "settings.pinMode", s.pinMode, selected = nav.pick == Pick.Value("pin")) { pickValue("pin") }
                action(scope, "s-always", Ic.Shield, "Always-on VPN", "Set in Android TV system settings") {
                    if (!actions.openVpnSettings()) nav.toast("This device has no VPN settings screen")
                }
                if (s.advanced) {
                    item { SectionLabel("Advanced") }
                    toggle(scope, "s-ipv6", "settings.blockIpv6", s.blockIpv6) {
                        actions.changeSettings { it.copy(blockIpv6 = !it.blockIpv6) }
                    }
                    toggle(scope, "s-dnsblock", "settings.blockDns", s.blockDns) {
                        actions.changeSettings { it.copy(blockDns = !it.blockDns) }
                    }
                }
            }
            Category.Startup -> {
                val startup = state.connection(s.startupId)?.takeIf { s.boot }
                value(scope, "s-boot", "settings.startup", startup?.name ?: "Off", selected = nav.pick == Pick.Startup) {
                    openPick(Pick.Startup)
                }
                toggle(scope, "s-roam", "settings.roam", s.roam) {
                    actions.changeSettings { it.copy(roam = !it.roam) }
                }
                if (s.advanced) {
                    item { SectionLabel("Advanced") }
                    toggle(scope, "s-sleep", "settings.stayAwake", s.stayAwake) {
                        actions.changeSettings { it.copy(stayAwake = !it.stayAwake) }
                    }
                }
            }
            Category.Apps -> {
                value(scope, "s-appmode", "settings.appMode", ValueChipLabel[s.appMode] ?: s.appMode, selected = nav.pick == Pick.Value("appmode")) { pickValue("appmode") }
                val found = apps
                val only = s.appMode == AppSettings.APPS_ONLY
                // With "only" and nothing chosen, the app sends every app through the VPN (see applyApps).
                val noneChosen = found != null && found.none { it.pkg in s.apps }
                item("apps-head") {
                    Column {
                        SectionLabel(if (only) "Only these apps use the VPN" else "These apps skip the VPN")
                        if (only && noneChosen) {
                            Text(
                                "Nothing is chosen yet, so every app still uses the VPN.",
                                style = TvType.ui(15f, color = Tv.soft(0.62f)),
                                modifier = Modifier.padding(start = 10.dp, bottom = 6.dp),
                            )
                        }
                    }
                }
                if (found == null) {
                    item { Text("Finding the apps on this device…", style = TvType.ui(16f, color = Tv.soft(0.6f)), modifier = Modifier.padding(10.dp)) }
                } else {
                    found.chunked(2).forEachIndexed { r, line ->
                        item("apps-$r") {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                line.forEach { app ->
                                    val picked = app.pkg in s.apps
                                    ListRow(
                                        app.label,
                                        {
                                            actions.changeSettings { it.copy(apps = if (picked) it.apps - app.pkg else it.apps + app.pkg) }
                                        },
                                        // What actually happens to this app now.
                                        subtitle = when {
                                            !only -> if (picked) "Skips the VPN" else "Uses the VPN"
                                            noneChosen || picked -> "Uses the VPN"
                                            else -> "Skips the VPN"
                                        },
                                        modifier = Modifier.weight(1f),
                                        leading = { AppIcon(app) },
                                        trailing = { TvSwitch(picked, it) },
                                    )
                                }
                                if (line.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
            Category.Diagnostics -> {
                // Its own item reads the log, so a new line redraws this row and not the panel.
                val logKey = scope.key("s-log")
                item("s-log") {
                    val log by actions.log.collectAsState()
                    ListRow(
                        "Connection log", { nav.showLog = true }, subtitle = "${log.size} lines from this session", focusRequester = logKey,
                        leading = { IconSquare(Ic.Log, it) },
                        trailing = { Chevron(it) },
                    )
                }
                action(scope, "s-test", Ic.Pulse, "Run a connection test", "Checks the tunnel, DNS and IPv6 leaks") { nav.showTest = true }
                val active = state.active
                if (active != null) {
                    action(
                        scope, "s-speed", Ic.Gauge, "Speed test",
                        state.speeds[active.id]?.let { "${active.name}: ${fmtMbps(it.downMbps)} Mbps down, ${ago(it.at)}" } ?: "Is the VPN fast enough for 4K?",
                    ) { nav.overlay = Overlay.Speed(active.id, "Settings › Diagnostics") }
                }
                action(scope, "s-send", Ic.Phone, "Send the log to your phone", "Download it on the phone") { nav.overlay = logSheet() }
                if (s.advanced) {
                    item { SectionLabel("Advanced") }
                    value(scope, "s-verb", "settings.verb", s.verb, selected = nav.pick == Pick.Value("verb")) { pickValue("verb") }
                }
            }
            Category.Backup -> {
                val last = Words.lastBackup(state.lastBackup)
                action(scope, "s-bk", Ic.Archive, "Back up to your phone", last) {
                    // Without a passphrase, it is chosen first and the backup follows straight on.
                    nav.overlay = if (state.hasBackupPassphrase) backupSheet() else Overlay.Form(passphraseSpec(state, nav, actions, thenBackup = true))
                }
                action(scope, "s-rs", Ic.Phone, "Restore a backup", "Send the backup file from your phone") {
                    nav.overlay = Overlay.Phone(
                        "backup", "Settings › Backup", "Restore a backup",
                        "Scan the code, choose the backup file and type its passphrase there. Its profiles are added to this device; ones here already, exactly the same, are left alone.",
                        section = "restore",
                        extra = if (actions.hasFilePicker) "Pick it on a USB drive" to { nav.overlay = null; actions.pickFile() } else null,
                    )
                }
                action(scope, "s-bkpass", Ic.Lock, if (state.hasBackupPassphrase) "Change the backup passphrase" else "Choose a backup passphrase", "Backups are encrypted with it; restoring needs it") {
                    nav.overlay = Overlay.Form(passphraseSpec(state, nav, actions))
                }
                // Its own row, so the passphrase sheet keeps to three buttons.
                if (state.hasBackupPassphrase && nav.confirmRemovePass) {
                    item {
                        ConfirmBar(
                            "Remove the backup passphrase? Backups made with it still need it.", "Remove", req["s-bkrm-no"],
                            onConfirm = {
                                nav.confirmRemovePass = false
                                actions.setBackupPassphrase(null)
                                nav.toast("The backup passphrase was removed")
                                nav.refocus(req["s-bkpass"])
                            },
                            onKeep = {
                                nav.confirmRemovePass = false
                                nav.refocus(req["s-bkrm"])
                            },
                        )
                    }
                } else if (state.hasBackupPassphrase) {
                    item {
                        ListRow(
                            "Remove the backup passphrase", { nav.confirmRemovePass = true },
                            subtitle = "Backing up then asks for a new one", danger = true,
                            focusRequester = scope.key("s-bkrm"),
                            leading = { IconSquare(Ic.Trash, it) },
                        )
                    }
                }
            }
            Category.Phones -> {
                val devices = scope.devices
                action(scope, "s-pair", Ic.Phone, "Pair a phone", "Show a code to scan; the TV asks before it pairs") {
                    nav.overlay = Overlay.PairPhone(Pairing.show().id)
                }
                value(scope, "s-reach", "settings.phoneReach", ValueChipLabel[s.phoneReach] ?: s.phoneReach, selected = nav.pick == Pick.Value("reach")) { pickValue("reach") }
                item("phones-head") { SectionLabel(if (devices.isEmpty()) "No phone is paired yet" else "Paired phones") }
                devices.forEach { d ->
                    val key = "s-ph-${d.id}"
                    if (nav.confirmRemovePhone == d.id) {
                        item(key + "-confirm") {
                            ConfirmBar(
                                "Remove ${d.name}? It can’t reach the TV until it pairs again.", "Remove", req["$key-no"],
                                onConfirm = {
                                    nav.confirmRemovePhone = null
                                    Devices.remove(d.id)
                                    Control.remoteChanged()
                                    nav.toast("${d.name} was removed")
                                    nav.refocus(req["s-pair"])
                                },
                                onKeep = {
                                    nav.confirmRemovePhone = null
                                    nav.refocus(req[key])
                                },
                            )
                        }
                    } else {
                        val fr = scope.key(key)
                        item(key) {
                            ListRow(
                                d.name, { nav.confirmRemovePhone = d.id },
                                subtitle = deviceLine(d), focusRequester = fr,
                                leading = { IconSquare(Ic.Phone, it) },
                                // An action, not a value: red words, no chip.
                                trailing = { focused -> Text("Remove", style = TvType.ui(16f, FontWeight.SemiBold, if (focused) Tv.OnFocus else Tv.Red)) },
                            )
                        }
                    }
                }
            }
            Category.OpenVpn -> {
                value(scope, "s-proto", "settings.proto", s.proto, selected = nav.pick == Pick.Value("proto")) { pickValue("proto") }
                value(scope, "s-timeout", "settings.timeout", "${s.timeout} s", selected = nav.pick == Pick.Value("timeout")) { pickValue("timeout") }
                value(scope, "s-mtu", "settings.mtu", s.mtu, selected = nav.pick == Pick.Value("mtu")) { pickValue("mtu") }
                value(scope, "s-tls", "settings.tlsMin", s.tlsMin, selected = nav.pick == Pick.Value("tls")) { pickValue("tls") }
                toggle(scope, "s-legacy", "settings.legacyCiphers", s.legacyCiphers) {
                    actions.changeSettings { it.copy(legacyCiphers = !it.legacyCiphers) }
                }
                toggle(scope, "s-compress", "settings.compress", s.compress) {
                    actions.changeSettings { it.copy(compress = !it.compress) }
                }
            }
            Category.Dns -> {
                toggle(scope, "s-pushed", "settings.pushedDns", s.pushedDns) {
                    actions.changeSettings { it.copy(pushedDns = !it.pushedDns) }
                }
                value(scope, "s-dnscustom", "settings.customDns", s.customDns.joinToString(", ").ifEmpty { "None" }) {
                    nav.overlay = Overlay.Form(
                        FormSpec(
                            "Settings › DNS", "Custom DNS servers", "Used instead of the servers your VPN pushes. Separate them with spaces.",
                            listOf(FormField("dns", "DNS servers", s.customDns.joinToString(" "), mono = true, max = 400, optional = true)),
                            primary = "Save", phone = "dns", phoneSection = "dns",
                        ) { v, _ -> saveCustomDns(actions, nav, v["dns"].orEmpty(), "s-dnscustom").toForm() },
                    )
                }
                value(scope, "s-dnssearch", "settings.searchDomains", s.searchDomains.joinToString(", ").ifEmpty { "None" }) {
                    nav.overlay = Overlay.Form(
                        FormSpec(
                            "Settings › DNS", "Search domains", "Short names like nas resolve as nas.home.lan. Separate them with spaces.",
                            listOf(FormField("search", "Domains", s.searchDomains.joinToString(" "), mono = true, max = 400, optional = true)),
                            primary = "Save", phone = "dns", phoneSection = "dns",
                        ) { v, _ ->
                            actions.changeSettings { it.copy(searchDomains = dnsServers(v["search"].orEmpty())) }
                            nav.flash("s-dnssearch")
                            FormResult.Done
                        },
                    )
                }
            }
            Category.Automation -> {
                toggle(scope, "s-auto", "settings.automation", s.automation) {
                    actions.changeSettings { it.copy(automation = !it.automation) }
                }
                action(scope, "s-cmds", Ic.Log, "Commands", "With your profiles’ names; copy them on your phone below") {
                    nav.overlay = Overlay.File("Settings › Automation", "Commands", commands(state), plain = true)
                }
            }
            Category.About -> {
                val update = state.update
                val (updateSub, updateValue) = when (update) {
                    UpdateState.Idle -> "Look on GitHub for a newer build" to "Check"
                    UpdateState.Checking -> "Looking on GitHub…" to "Checking"
                    UpdateState.UpToDate -> "This is the newest build" to "Check"
                    is UpdateState.Available -> "Build ${update.build.number} is ready (${update.build.version}, ${update.build.size / 100_000 / 10f} MB)" to "Update"
                    is UpdateState.Downloading -> "Downloading build ${update.build.number}… ${update.percent}%" to "Wait"
                    is UpdateState.Installing -> "Installing build ${update.build.number}. Confirm it when Android asks" to "Wait"
                    is UpdateState.Failed -> update.message to "Try again"
                }
                action(scope, "s-upd", Ic.Download, if (update is UpdateState.Available) "Install the update" else "Updates", updateSub) {
                    when (update) {
                        is UpdateState.Available, is UpdateState.Downloading, is UpdateState.Installing -> nav.overlay = Overlay.Update
                        UpdateState.Checking -> Unit
                        else -> actions.checkUpdate()
                    }
                }
                toggle(scope, "s-autoupd", "settings.autoUpdate", s.autoUpdate) {
                    actions.changeSettings { it.copy(autoUpdate = !it.autoUpdate) }
                }
                action(scope, "s-priv", Ic.Info, "What ${APP_NAME} stores", "Profiles and passwords, encrypted on this device. No accounts, no analytics") {
                    nav.overlay = Overlay.File("Settings › About", "What ${APP_NAME} stores", PRIVACY, plain = true)
                }
                action(scope, "s-lic", Ic.File, "Open-source licenses", "OpenVPN 3 core (MPL 2.0), wireguard-go, OpenSSL, asio, fmt, lz4, xxHash, ZXing") {
                    val text = runCatching { context.assets.open("licenses.txt").bufferedReader().use { it.readText() } }.getOrDefault("The license texts are missing from this build.")
                    nav.overlay = Overlay.File("Settings › About", "Open-source licenses", text, plain = true)
                }
                if (nav.confirmReset) {
                    item {
                        ConfirmBar(
                            "Delete every profile, password and setting? Back up first in Settings › Backup.", "Reset", req["s-reset-no"],
                            onConfirm = {
                                nav.confirmReset = false
                                actions.resetAll()
                                nav.toast("Everything was deleted")
                                // Back to the start, where the first profile is added.
                                nav.go(Screen.Connect)
                            },
                            onKeep = {
                                nav.confirmReset = false
                                nav.refocus(req["s-reset"])
                            },
                        )
                    }
                } else {
                    item {
                        ListRow(
                            "Reset ${APP_NAME}", { nav.confirmReset = true },
                            subtitle = "Delete everything and start over", danger = true,
                            focusRequester = scope.key("s-reset"),
                            leading = { IconSquare(Ic.Trash, it) },
                            trailing = { Chevron(it) },
                        )
                    }
                }
            }
        }
    }
    // Diagnostics has too many rows for the code to fit under them; Send the log to your phone shows it.
    if (showPhone) {
        PhoneBlock(
            phone!!, PhoneBlockSize.Compact, Modifier.padding(top = 12.dp, start = 10.dp, bottom = 6.dp),
            title = when (cat) {
                Category.Dns -> "Or change these on your phone"
                Category.Backup -> "Or restore a backup on your phone"
                else -> "Or copy the commands on your phone"
            },
        )
    }
}

/** The DNS servers or domains typed in a sheet or on a phone, split at spaces, commas and lines. */
fun dnsServers(text: String): List<String> = Validate.dnsServers(text)

/** What is wrong with typed DNS servers, or null; the same check wherever they are typed. */
fun dnsProblem(text: String): String? = Validate.dnsProblem(text)

@Composable
private fun AppIcon(app: LauncherApp) {
    val icon = app.icon
    if (icon != null) {
        Image(icon, contentDescription = null, modifier = Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)))
    } else {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Tv.white(0.1f)), contentAlignment = Alignment.Center) {
            Text(app.label.take(2).uppercase(), style = TvType.ui(13f, FontWeight.ExtraBold))
        }
    }
}

/** A switch whose words come from the [Schema], as the phone's panel shows them too. */
private fun LazyListScope.toggle(scope: PanelScope, key: String, path: String, on: Boolean, onClick: () -> Unit) {
    val (title, sub) = Schema.words(path)
    toggle(scope, key, title, sub, on, onClick)
}

private fun LazyListScope.value(scope: PanelScope, key: String, path: String, value: String, selected: Boolean = false, onClick: () -> Unit) {
    val (title, sub) = Schema.words(path)
    value(scope, key, title, sub, value, selected, onClick)
}

private fun LazyListScope.toggle(scope: PanelScope, key: String, title: String, sub: String, on: Boolean, onClick: () -> Unit) {
    val fr = scope.key(key)
    item(key) { ListRow(title, onClick, subtitle = sub, focusRequester = fr, trailing = { TvSwitch(on, it) }) }
}

private fun LazyListScope.value(
    scope: PanelScope,
    key: String,
    title: String,
    sub: String,
    value: String,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    val fr = scope.key(key)
    item(key) { ListRow(title, onClick, subtitle = sub, selected = selected, flash = scope.nav.flashKey == key, focusRequester = fr, trailing = { ValueChip(value, it) }) }
}

private fun LazyListScope.action(scope: PanelScope, key: String, icon: Ic, title: String, sub: String, onClick: () -> Unit) {
    val fr = scope.key(key)
    item(key) {
        ListRow(
            title, onClick, subtitle = sub, focusRequester = fr, flash = scope.nav.flashKey == key,
            leading = { IconSquare(icon, it) },
            trailing = { Chevron(it) },
        )
    }
}

/** Shown instead of a category while the PIN locks settings. Going into it asks for the PIN at once. */
@Composable
private fun LockedPanel(nav: Nav, req: Requesters) {
    req.alias("panel-first", "s-unlock")
    val ask = {
        if (nav.overlay == null) {
            // With the PIN, focus goes on into the panel, where the remote was heading.
            nav.overlay = Overlay.Pin {
                nav.settingsReturn = "panel-first"
                nav.contentFocus = Screen.Settings
            }
            // Closed without the PIN, focus goes back to the category, not here, which would ask again.
            nav.opener = req["cat-${nav.settingsCat.name}"]
        }
    }
    PanelHeading("Settings", "Settings are locked", "Type the PIN to change settings on this device.")
    Row(Modifier.padding(top = 22.dp)) {
        TvButton("Type the PIN", ask, icon = Ic.Lock, focusRequester = req["s-unlock"], onFocus = ask)
    }
}

@Composable
private fun ColumnScope.LogPanel(state: VpnUiState, nav: Nav, actions: TunnelActions, req: Requesters, back: () -> Unit) {
    PanelHeading("Settings › Diagnostics", "Connection log", "What the VPN did this session, newest at the bottom. Up scrolls back.")
    // The row that opened this is gone, so focus starts on Back.
    req.alias("panel-first", "s-log-back")
    LaunchedEffect(Unit) { req["s-log-back"].focusWhenReady() }
    val quiet = state.settings.verb.startsWith("1")
    // The newest line sits at the bottom, and the list follows each new one. Up and Down scroll back.
    val log by actions.log.collectAsState()
    val lines = remember(log, quiet) { log.filter { !quiet || it.kind != LogKind.Plain } }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val f = rememberFocusState()
    LaunchedEffect(lines.lastOrNull()) { if (lines.isNotEmpty()) list.scrollToItem(lines.lastIndex) }
    Box(
        modifier = Modifier
            .padding(top = 16.dp)
            .fillMaxWidth()
            .weight(1f)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black.copy(alpha = 0.4f))
            .border(2.dp, if (f.focused) Tv.Focus else Color.Transparent, RoundedCornerShape(16.dp))
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                when {
                    e.key == Key.DirectionUp && list.canScrollBackward -> {
                        scope.launch { list.animateScrollBy(-160f) }
                        true
                    }
                    e.key == Key.DirectionDown && list.canScrollForward -> {
                        scope.launch { list.animateScrollBy(160f) }
                        true
                    }
                    else -> false
                }
            }
            .onFocusChanged { f.hasFocus = it.isFocused }
            .focusable(),
    ) {
        LazyColumn(
            state = list,
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Log detail › quiet keeps only errors and connection changes.
            if (lines.isEmpty()) item { Text("The log is empty.", style = TvType.mono(14.5f, color = Tv.soft(0.82f))) }
            items(lines.size) { i ->
                val line = lines[i]
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(line.time, style = TvType.mono(14.5f, color = Tv.soft(0.4f)))
                    Text(
                        line.text,
                        style = TvType.mono(
                            14.5f,
                            color = when (line.kind) {
                                LogKind.Ok -> Tv.Mint
                                LogKind.Err -> Tv.Red
                                LogKind.Plain -> Tv.soft(0.82f)
                            },
                        ),
                        // A failure's advice is at the end of its line: it wraps rather than being cut.
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
    var confirmClear by remember { mutableStateOf(false) }
    if (confirmClear) {
        Box(Modifier.padding(top = 16.dp)) {
            ConfirmBar(
                "Clear this session’s connection log?", "Clear", req["s-log-clear-no"],
                onConfirm = {
                    confirmClear = false
                    actions.clearLog()
                    nav.toast("Log cleared")
                    nav.refocus(req["s-log-back"])
                },
                onKeep = {
                    confirmClear = false
                    nav.refocus(req["s-log-clear"])
                },
            )
        }
    } else Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TvButton("Close", back, focusRequester = req["s-log-back"])
        TvButton("Send to phone", { nav.overlay = logSheet() }, icon = Ic.Phone, glass = true)
        TvButton("Clear", { confirmClear = true }, glass = true, focusRequester = req["s-log-clear"])
    }
}

private enum class CheckState { Idle, Running, Ok, Warn, Fail }

private class Check(val title: String) {
    var result by mutableStateOf(CheckState.Running)
    var detail by mutableStateOf("Checking…")
}

/** Settings › Diagnostics › Run a connection test: four checks, one after the other. */
@Composable
private fun ColumnScope.TestPanel(state: VpnUiState, nav: Nav, actions: TunnelActions, req: Requesters, connect: (String) -> Unit, back: () -> Unit) {
    val connected = state.status is VpnStatus.Connected
    var run by remember { mutableIntStateOf(0) }
    // Connect and test: the checks run again once the tunnel is up.
    var waiting by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf<String?>(null) }
    var checks0Failed by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state.status) {
        val status = state.status
        if (connected && waiting) {
            waiting = false
            run++
        } else if (waiting && status is VpnStatus.Failed) {
            waiting = false
            failed = status.message
            checks0Failed = status.message
        }
    }
    // The button under focus changes with the state; focus follows it.
    LaunchedEffect(waiting, connected) { req["s-test-go"].focusWhenReady() }
    val checks = remember(run) {
        listOf(Check(ConnectionTest.TUNNEL), Check(ConnectionTest.DNS), Check(ConnectionTest.INTERNET))
    }
    LaunchedEffect(run) {
        val t = state.tunnel
        val blocksV6 = state.settings.blockIpv6
        if (!connected) {
            // Nothing to test yet: no red marks for a test that hasn't run.
            checks.forEach { it.result = CheckState.Idle; it.detail = "Needs the tunnel" }
            return@LaunchedEffect
        }
        checks[0].apply {
            if (connected) {
                result = CheckState.Ok
                detail = t?.address?.let { "Tunnel IP $it" } ?: "Connected"
            } else {
                result = CheckState.Fail
                detail = "Not connected"
            }
        }
        delay(300)
        // The same checks as a phone's Apply and test.
        checks[1].apply {
            val c = ConnectionTest.dns(t)
            result = if (c.ok) CheckState.Ok else CheckState.Fail
            detail = c.detail
        }
        delay(300)
        checks[2].apply {
            val c = ConnectionTest.internet()
            result = if (c.ok) CheckState.Ok else CheckState.Fail
            detail = c.detail
        }
        // For the phone's Diagnostics page.
        nav.lastTest = checks.map { c -> c.title to "${if (c.result == CheckState.Ok) "OK" else if (c.result == CheckState.Warn) "Check" else "Failed"}: ${c.detail}" }
    }
    val active = state.active
    PanelHeading(
        "Settings › Diagnostics", "Connection test",
        when {
            connected -> "Testing the tunnel to ${active?.name.orEmpty()}."
            waiting -> "Connecting to ${active?.name.orEmpty()}, then testing it."
            failed != null -> "${active?.name.orEmpty()} couldn’t connect: $failed" + if (failed!!.endsWith(".")) "" else "."
            active != null -> "Not connected. Connect and test checks the tunnel to ${active.name}."
            else -> "Add a profile first, then test its tunnel."
        },
    )
    req.alias("panel-first", "s-test-go")
    LaunchedEffect(Unit) { req["s-test-go"].focusWhenReady() }
    // A connect that failed is the first check failing, in red, not one still waiting for the tunnel.
    LaunchedEffect(checks0Failed, run) {
        checks0Failed?.takeIf { !connected }?.let { why -> checks[0].apply { result = CheckState.Fail; detail = firstWords(why) } }
    }
    Column(Modifier.padding(top = 16.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        checks.forEach { c ->
            Row(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Tv.white(0.05f)).padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                when (c.result) {
                    CheckState.Idle -> Box(Modifier.size(26.dp).clip(CircleShape).background(Tv.white(0.1f)))
                    CheckState.Running -> Spinner()
                    CheckState.Ok -> Box(Modifier.size(26.dp).clip(CircleShape).background(Tv.Mint), contentAlignment = Alignment.Center) { TvIcon(Ic.Check, 15f, Color(0xFF06140D), stroke = 3f) }
                    CheckState.Warn -> Box(Modifier.size(26.dp).clip(CircleShape).background(Tv.Amber), contentAlignment = Alignment.Center) { TvIcon(Ic.Bang, 15f, Color(0xFF1A1200), stroke = 3f) }
                    CheckState.Fail -> Box(Modifier.size(26.dp).clip(CircleShape).background(Tv.Red), contentAlignment = Alignment.Center) { TvIcon(Ic.Bang, 15f, Color(0xFF1A0503), stroke = 3f) }
                }
                Text(c.title, style = TvType.ui(17f, FontWeight.SemiBold), modifier = Modifier.weight(1f))
                Text(c.detail, style = TvType.ui(15f, color = Tv.soft(0.62f)), maxLines = 1)
            }
        }
        // A setting, not a check: said as one, under them.
        Text(
            if (state.settings.blockIpv6) "Block IPv6 is on: IPv6 can’t leave outside the tunnel." else "Block IPv6 is off: IPv6 may leave outside the tunnel.",
            style = TvType.ui(15f, color = Tv.soft(0.62f)),
            modifier = Modifier.padding(start = 4.dp, top = 4.dp),
        )
    }
    Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        when {
            connected -> TvButton("Run again", { run++ }, icon = Ic.Swap, focusRequester = req["s-test-go"])
            waiting -> TvButton("Cancel", {
                waiting = false
                actions.disconnect()
            }, focusRequester = req["s-test-go"])
            active != null -> TvButton(if (failed != null) "Try again" else "Connect and test", {
                checks0Failed = null
                waiting = true
                failed = null
                connect(active.id)
            }, icon = Ic.Power, focusRequester = req["s-test-go"])
            else -> Unit
        }
        TvButton("Close", back, glass = true, focusRequester = if (active != null || connected) null else req["s-test-go"])
        // The one warning with a fix of its own.
        if (!state.settings.blockIpv6) {
            TvButton("Turn on Block IPv6", {
                actions.changeSettings { it.copy(blockIpv6 = true) }
                run++
            }, glass = true)
        }
    }
}

// ---- Choice columns -------------------------------------------------------------------

@Composable
private fun PickHeading(eyebrow: String, title: String, sub: String) {
    PanelHeading("Settings › $eyebrow", title, sub)
    Spacer(Modifier.height(14.dp))
}

@Composable
private fun ValuePick(state: VpnUiState, nav: Nav, actions: TunnelActions, req: Requesters, key: String, done: () -> Unit) {
    val v = Values[key] ?: return
    PickHeading(v.eyebrow, v.title, v.sub)
    val current = v.get(state.settings)
    val list = rememberLazyListState()
    LazyColumn(state = list, modifier = Modifier.bleed(12.dp).scrollFade(list), verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
        items(v.options.size) { i ->
            val option = v.options[i]
            ListRow(
                ValueLabel[option] ?: option,
                {
                    when {
                        key == "pin" && option == "Off" -> actions.setPin(null)
                        // A PIN has to exist before anything can be locked with it.
                        key == "pin" && !state.hasPin -> nav.overlay = Overlay.Pin(setting = true, mode = option)
                        else -> actions.changeSettings { v.set(it, option) }
                    }
                    done()
                },
                subtitle = if (key == "pin" && option == "Off" && state.hasPin) "Removes the PIN" else ValueSub[option],
                kind = RowKind.Pick,
                selected = option == current,
                focusRequester = if (option == current) req["pk-sel"] else null,
                trailing = { if (option == current) CheckBadge(it) },
            )
        }
        if (key == "pin" && state.hasPin) {
            item {
                ListRow(
                    "Change the PIN", {
                        done()
                        nav.overlay = Overlay.Pin(setting = true)
                    },
                    subtitle = "Choose a new one; what it locks stays the same",
                    kind = RowKind.Pick,
                    leading = { IconSquare(Ic.Lock, it) },
                )
            }
        }
    }
}

@Composable
private fun StartupPick(state: VpnUiState, nav: Nav, actions: TunnelActions, req: Requesters, done: () -> Unit) {
    PickHeading("Startup", "Connect when the device starts", "${APP_NAME} connects to this profile when the device turns on, before you open the app.")
    val current = if (state.settings.boot) state.settings.startupId else null
    // Opens scrolled to the chosen profile, with one row above it, so focus coming in lands on it.
    val list = rememberLazyListState(initialFirstVisibleItemIndex = state.connections.indexOfFirst { it.id == current }.coerceAtLeast(0))
    LazyColumn(state = list, modifier = Modifier.bleed(12.dp).scrollFade(list), verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
        item {
            ListRow(
                "Don’t connect at startup", {
                    actions.changeSettings { it.copy(boot = false) }
                    done()
                },
                subtitle = "Open ${APP_NAME} and connect yourself",
                kind = RowKind.Pick,
                selected = current == null,
                focusRequester = if (current == null) req["pk-sel"] else null,
                leading = { AddSquare(it, Ic.Power) },
                trailing = { if (current == null) CheckBadge(it) },
            )
        }
        items(state.connections, key = { it.id }) { c ->
            val needs = state.infoOf(c)?.needsLogin != false
            val cert = c.keyAlias != null || state.infoOf(c)?.clientCert == true
            val ok = !state.asksForLogin(c)
            ListRow(
                c.name,
                {
                    if (!ok) {
                        nav.overlay = Overlay.SignIn(c.id, save = true, forStartup = true)
                    } else {
                        actions.setStartup(c.id)
                        done()
                    }
                },
                subtitle = when {
                    !ok -> "Save its password first"
                    needs -> "Password saved · connects by itself"
                    cert -> "Certificate · connects by itself"
                    else -> "No certificate or password in its file"
                },
                kind = RowKind.Pick,
                selected = c.id == current,
                focusRequester = if (c.id == current) req["pk-sel"] else null,
                leading = { Avatar(lookOf(c), 44f) },
                trailing = {
                    if (!ok) TvIcon(Ic.Lock, 18f, if (it) Tv.dark(0.5f) else Tv.soft(0.5f))
                    if (c.id == current) CheckBadge(it)
                },
            )
        }
    }
}

// ---- Phone pages ------------------------------------------------------------------------

/** Settings › DNS on the phone: the switch and both lists, checked there and here. */
@Composable
private fun dnsPhone(state: VpnUiState, nav: Nav, actions: TunnelActions): dev.shieldvpn.phone.PhoneSession? = rememberPhoneSession(
    "dns", state, actions,
    form = { s ->
        PhoneForm(
            "Settings › DNS", "DNS on the TV", "Where name lookups go while the tunnel is up.",
            listOf(
                PhoneSection(
                    "dns",
                    fields = listOf(
                        PhoneField.Switch("pushed", Schema.words("settings.pushedDns").first, Schema.words("settings.pushedDns").second, s.settings.pushedDns),
                        PhoneField.Lines("dns", "Custom DNS servers", s.settings.customDns, help = "One per line. Used instead of the pushed ones.", placeholder = "1.1.1.1", mirror = true),
                        PhoneField.Lines("search", "Search domains", s.settings.searchDomains, help = "One per line. Short names like nas resolve as nas.home.lan.", placeholder = "home.lan", mirror = true),
                    ),
                    actions = listOf(PhoneAction("save", "Save", primary = true)),
                ),
            ),
        )
    },
    locked = { locked(actions.stateNow(), nav) },
    onAction = { action, v ->
        if (action != "save") return@rememberPhoneSession PhoneReply()
        val outcome = saveCustomDns(actions, nav, v["dns"].orEmpty(), "s-dnscustom")
        if (outcome is Outcome.Done) {
            actions.changeSettings { it.copy(pushedDns = v["pushed"] != "false", searchDomains = dnsServers(v["search"].orEmpty())) }
            saved("dns")
        }
        outcome.toReply()
    },
)

/** The log as the phone downloads it. */
private fun logFile(actions: TunnelActions) = dev.shieldvpn.phone.PhoneFile("$APP_FILE_NAME-log.txt", "text/plain; charset=utf-8", actions.logText().toByteArray())

/** Diagnostics' phone code, pointing at the log. */
fun logSheet() = Overlay.Phone(
    "diag", "Settings › Diagnostics", "Send the log to your phone",
    "Scan the code and press Download the log. It has no keys, usernames or passwords in it.",
    section = "log",
)

/** Settings › Diagnostics on the phone: the log to download, and the last connection test. */
@Composable
private fun diagnosticsPhone(state: VpnUiState, nav: Nav, actions: TunnelActions): dev.shieldvpn.phone.PhoneSession? = rememberPhoneSession(
    "diag", state, actions,
    form = {
        val test = nav.lastTest
        PhoneForm(
            "Settings › Diagnostics", "Diagnostics", "The connection log and the last connection test, for when something does not connect.",
            listOf(
                // The size is left out: the log grows while it is open, and the page would be drawn again at every line.
                PhoneSection(
                    "log", "Connection log", note = "From this session, newest at the bottom. It has no keys, usernames or passwords in it.",
                    fields = listOf(PhoneField.Download("log", "$APP_FILE_NAME-log.txt", 0, label = "Download the log")),
                ),
                PhoneSection(
                    "test", "Last connection test",
                    fields = listOf(
                        if (test != null) PhoneField.Info("checks", rows = test) else PhoneField.Info("checks", text = "No test has run since the app opened. Run one on the TV in Settings › Diagnostics."),
                    ),
                ),
            ),
        )
    },
    download = { id -> if (id == "log") logFile(actions) else null },
    onDownloaded = { _, name -> tvLine = "Sent $name to your phone" },
)

/** Settings › Automation on the phone: each command with this device's profile names, to copy. */
@Composable
private fun automationPhone(state: VpnUiState, actions: TunnelActions): dev.shieldvpn.phone.PhoneSession? = rememberPhoneSession(
    "automation", state, actions,
    form = { s ->
        val fields = buildList {
            if (!s.settings.automation) add(PhoneField.Info("off", text = "Turn on “Let other apps control ${APP_NAME}” on the TV first.", tone = "warn"))
            add(PhoneField.Copy("connect", "Connect the profile on the Connect screen", "adb shell am start -n dev.shieldvpn/.Connect"))
            s.connections.take(8).forEachIndexed { i, c ->
                add(PhoneField.Copy("connect$i", "Connect ${c.name}", "adb shell am start -n dev.shieldvpn/.Connect --es profile ${quoted(c.name)}"))
            }
            add(PhoneField.Copy("disconnect", "Disconnect", "adb shell am start -n dev.shieldvpn/.Disconnect"))
            add(PhoneField.Info("intents", text = "Home automation apps send the same intents: component dev.shieldvpn/.Connect or dev.shieldvpn/.Disconnect, with an optional string extra \"profile\"."))
        }
        PhoneForm("Settings › Automation", "Automation commands", "Copy them for ADB or a home automation app.", listOf(PhoneSection("commands", fields = fields)))
    },
)

/**
 * Settings › Backup on the phone: the passphrase, the download, and a restore. The backup holds
 * every key and password, so the phone gets it only after Back up to your phone on the TV, and a
 * passphrase set already changes only with the current one.
 */
@Composable
private fun backupPhone(state: VpnUiState, nav: Nav, actions: TunnelActions): dev.shieldvpn.phone.PhoneSession? {
    val name = "$APP_FILE_NAME-backup-${java.time.LocalDate.now()}.${Backup.EXTENSION}"
    return rememberPhoneSession(
        "backup", state, actions,
        form = { s -> backupForm(s, nav, name, focus) },
        locked = { locked(actions.stateNow(), nav) },
        onAction = { action, v ->
            val now = actions.stateNow()
            when (action) {
                "setpass" -> {
                    // A first passphrase only when the TV asked for one; a change only with the one set now.
                    if (!now.hasBackupPassphrase && focus != "pass") return@rememberPhoneSession PhoneReply.error(null, "Press Back up to your phone on the TV first.")
                    val outcome = setPassphrase(now, actions, nav, v["pass1"].orEmpty(), v["pass2"].orEmpty(), current = v["old"].orEmpty())
                    if (outcome !is Outcome.Done) return@rememberPhoneSession outcome.toReply()
                    saved(if (now.hasBackupPassphrase) "change" else "pass")
                    if (now.hasBackupPassphrase) {
                        outcome.toReply(clear = listOf("old", "pass1", "pass2"))
                    } else {
                        // A first passphrase goes straight on to the backup, as it does on the TV.
                        point("download")
                        PhoneReply(clear = listOf("pass1", "pass2"), download = "backup" to name)
                    }
                }
                "restore" -> {
                    val file = nav.staged["backup"] ?: return@rememberPhoneSession PhoneReply.error("restorefile", "Choose the backup file again.")
                    val outcome = restoreBackup(actions, nav, file.bytes, v["rpass"].orEmpty(), v["same"] == "replace", "rpass")
                    if (outcome is Outcome.Done) {
                        nav.staged.remove("backup")
                        tvLine = outcome.text.trimEnd('.')
                    }
                    outcome.toReply(clear = listOf("rpass"))
                }
                else -> PhoneReply()
            }
        },
        onUpload = { field, file, bytes ->
            if (actions.inspect(file, bytes) != Inspected.Backup) {
                PhoneReply.error(field, "That isn’t a ${APP_NAME} backup.")
            } else {
                nav.staged["backup"] = StagedFile(file, bytes)
                tvLine = "Received a backup: $file"
                PhoneReply()
            }
        },
        download = { id ->
            when {
                id != "backup" -> null
                focus != "download" -> dev.shieldvpn.phone.PhoneFile.refused(pressOnTv("Back up to your phone"))
                else -> actions.backupFile()?.let { dev.shieldvpn.phone.PhoneFile(name, "application/octet-stream", it) }
            }
        },
        onDownloaded = { _, file ->
            actions.backupDownloaded()
            tvLine = "Downloaded to your phone: $file"
        },
    )
}

private fun backupForm(state: VpnUiState, nav: Nav, name: String, focus: String?): PhoneForm {
    val staged = nav.staged["backup"]
    val sections = buildList {
        if (!state.hasBackupPassphrase) {
            add(
                if (focus == "pass") {
                    PhoneSection(
                        "pass", "Choose a passphrase", note = "Backups are encrypted with it, and restoring one needs it. Keep it somewhere safe.",
                        fields = listOf(
                            PhoneField.Text("pass1", "Passphrase", secret = true, required = true, help = "At least 6 characters."),
                            PhoneField.Text("pass2", "Type it again", secret = true, required = true),
                        ),
                        actions = listOf(PhoneAction("setpass", "Save and download", primary = true, needs = listOf("pass1", "pass2"))),
                    )
                } else {
                    PhoneSection("pass", "Back up the TV", note = "There is no backup passphrase yet. Press Back up to your phone on the TV, then choose one here.")
                },
            )
        } else {
            add(
                if (focus == "download") {
                    PhoneSection(
                        "download", "Back up the TV",
                        note = state.lastBackup?.let { (at, n) -> "The last backup was ${ago(at)}, with $n profile${if (n == 1) "" else "s"}." } ?: "There is no backup yet.",
                        fields = listOf(PhoneField.Download("backup", name, 0, note = "Encrypted with your passphrase: every profile, saved password and setting.", label = "Download the backup")),
                    )
                } else {
                    PhoneSection("download", "Back up the TV", note = "The backup holds every key and password, so it is only sent after you press Back up to your phone on the TV.")
                },
            )
            add(
                PhoneSection(
                    "change", "Change the passphrase", folded = true,
                    fields = listOf(
                        PhoneField.Text("old", "Current passphrase", secret = true, required = true),
                        PhoneField.Text("pass1", "New passphrase", secret = true, required = true, help = "At least 6 characters."),
                        PhoneField.Text("pass2", "Type it again", secret = true, required = true),
                    ),
                    actions = listOf(PhoneAction("setpass", "Change it", primary = true, needs = listOf("old", "pass1", "pass2"))),
                ),
            )
        }
        add(
            PhoneSection(
                "restore", "Restore a backup", note = "From this TV or another one. Its profiles are added; ones here already, exactly the same, are left alone.",
                fields = listOfNotNull(
                    PhoneField.Upload("restorefile", "Choose the backup file", "The .${Backup.EXTENSION} file", ".${Backup.EXTENSION},application/octet-stream,application/json", staged?.let { Staged(it.name, it.bytes.size, "Backup") }),
                    if (staged != null) PhoneField.Text("rpass", "Its passphrase", secret = true, required = true) else null,
                    if (staged != null) PhoneField.Choice("same", "When a profile with the same name is on the TV", listOf("both" to "Keep both", "replace" to "Replace it"), "both") else null,
                ),
                actions = if (staged != null) listOf(PhoneAction("restore", "Restore on the TV", primary = true, needs = listOf("rpass"))) else emptyList(),
            ),
        )
    }
    return PhoneForm("Settings › Backup", "Back up the TV", "Download an encrypted backup of every profile and setting, or restore one.", sections)
}

/** A reason short enough for a check's line. */
private fun firstWords(text: String): String = text.substringBefore(". ").trimEnd('.')

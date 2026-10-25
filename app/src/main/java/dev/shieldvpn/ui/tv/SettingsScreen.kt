package dev.shieldvpn.ui.tv

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.shieldvpn.APP_NAME
import dev.shieldvpn.BuildConfig
import dev.shieldvpn.vpn.AppSettings
import dev.shieldvpn.vpn.LogKind
import dev.shieldvpn.vpn.UpdateState
import dev.shieldvpn.vpn.VpnStatus
import dev.shieldvpn.vpn.VpnUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

enum class Category(val label: String, val icon: Ic, val advanced: Boolean = false) {
    Protection("Protection", Ic.Shield),
    Startup("Startup", Ic.Rocket),
    Apps("Apps", Ic.Apps),
    Diagnostics("Diagnostics", Ic.Pulse),
    Backup("Backup", Ic.Archive),
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
}

private val PickEase = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)

/**
 * Settings: short by default. Picking a value shrinks the categories to an icon rail and
 * slides a choice column in from the right.
 */
@Composable
fun SettingsScreen(state: VpnUiState, nav: Nav, actions: TunnelActions) {
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
            val key = when {
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
    }

    Box(Modifier.fillMaxSize()) {
        val navWidth by animateDpAsState(if (picking) 84.dp else 320.dp, tween(380, easing = PickEase), label = "navW")
        val panelLeft by animateDpAsState(if (picking) 172.dp else 408.dp, tween(380, easing = PickEase), label = "panelL")
        val panelWidth by animateDpAsState(if (picking) 528.dp else 808.dp, tween(380, easing = PickEase), label = "panelW")
        val pickAlpha by animateFloatAsState(if (picking) 1f else 0f, tween(280), label = "pickA")
        val pickShift by animateFloatAsState(if (picking) 0f else 70f, tween(380, easing = PickEase), label = "pickX")

        // Categories.
        Glass(Modifier.offset(64.dp, 124.dp).width(navWidth).height(560.dp)) {
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
                        "Advanced", {
                            val on = !state.settings.advanced
                            actions.changeSettings { it.copy(advanced = on) }
                            if (!on && nav.settingsCat.advanced) nav.settingsCat = Category.Protection
                            nav.toast(if (on) "Advanced settings are shown" else "Advanced settings are hidden")
                        },
                        subtitle = "Show OpenVPN, DNS, automation",
                        focusRequester = req["st-advanced"],
                        trailing = { TvSwitch(state.settings.advanced, it) },
                    )
                }
            }
        }

        // The category's settings. Coming in from the categories lands on the first row.
        Glass(Modifier.offset(panelLeft, 124.dp).width(panelWidth).height(560.dp).enterAt { req["panel-first"] }) {
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
                    TestPanel(state, req) {
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
                        Pick.Startup -> StartupPick(state, nav, actions, req) { closePick("s-sprof") }
                        is Pick.Value -> ValuePick(state, nav, actions, req, pick.key) { closePick("s-${pick.key}") }
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
            .height(40.dp)
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

private class PanelScope(val state: VpnUiState, val nav: Nav, val actions: TunnelActions, val req: Requesters) {
    var first = true

    /** The requester for row [k]. The first row of a panel also answers to "panel-first". */
    fun key(k: String): androidx.compose.ui.focus.FocusRequester {
        if (first) {
            first = false
            req.alias("panel-first", k)
        }
        return req[k]
    }

    /** A change that only takes effect on the next connect says so while a tunnel is up. */
    fun appliesNext() {
        if (state.status.let { it is VpnStatus.Connected || it is VpnStatus.Connecting }) nav.toast("Applies the next time you connect")
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
    val appliesNext: Boolean = true,
)

private val Values = mapOf(
    "pin" to ValueSetting("Protection", "Lock with a PIN", "Choose what needs the PIN. Useful when children use the TV.", listOf("Off", "For settings", "For everything"), { it.pinMode }, { s, v -> s.copy(pinMode = v) }, appliesNext = false),
    "appmode" to ValueSetting("Apps", "Which apps use the VPN", "This decides what the switches below mean.", listOf(AppSettings.APPS_SKIP, AppSettings.APPS_ONLY), { it.appMode }, { s, v -> s.copy(appMode = v) }),
    "verb" to ValueSetting("Diagnostics", "Log detail", "How much the connection log shows.", listOf("3 · normal", "4 · detailed", "1 · quiet"), { it.verb }, { s, v -> s.copy(verb = v) }, appliesNext = false),
    "proto" to ValueSetting("OpenVPN", "Preferred protocol", "Used when a profile lists both UDP and TCP servers.", listOf("From file", "UDP", "TCP"), { it.proto }, { s, v -> s.copy(proto = v) }),
    "timeout" to ValueSetting("OpenVPN", "Connection timeout", "How long to wait before trying the next server.", listOf("30 s", "60 s", "120 s"), { "${it.timeout} s" }, { s, v -> s.copy(timeout = v.substringBefore(' ').toInt()) }),
    "mtu" to ValueSetting("OpenVPN", "Tunnel MTU", "The largest packet sent through the tunnel.", listOf("Auto", "1400", "1300"), { it.mtu }, { s, v -> s.copy(mtu = v) }),
    "tls" to ValueSetting("OpenVPN", "Minimum TLS version", "Servers that only offer older versions are refused.", listOf("TLS 1.2", "TLS 1.3"), { it.tlsMin }, { s, v -> s.copy(tlsMin = v) }),
)

/** How a stored value reads on screen, where it is not the value itself. */
private val ValueLabel = mapOf(
    AppSettings.APPS_SKIP to "All apps, except the ones you choose",
    AppSettings.APPS_ONLY to "Only the apps you choose",
)

/** The shorter form for the value chip on the row. */
private val ValueChipLabel = mapOf(
    AppSettings.APPS_SKIP to "All except chosen",
    AppSettings.APPS_ONLY to "Only chosen",
)

/** The line under each choice (the design's OPT_SUB). */
private val ValueSub = mapOf(
    "Off" to "Anyone can change profiles and settings", "For settings" to "Ask before profiles or settings change", "For everything" to "Ask every time ${APP_NAME} opens",
    AppSettings.APPS_SKIP to "The apps you turn on skip the VPN", AppSettings.APPS_ONLY to "Every other app uses your normal connection",
    "1 · quiet" to "Errors and connection changes only", "3 · normal" to "Recommended", "4 · detailed" to "Everything, for troubleshooting with your VPN admin",
    "From file" to "Use what the profile says", "UDP" to "Faster. The usual choice", "TCP" to "Gets through strict networks",
    "30 s" to "Quick to give up on a dead server", "60 s" to "Recommended", "120 s" to "For slow or distant servers",
    "Auto" to "Recommended", "1400" to "Try this if video stalls", "1300" to "For networks that drop large packets",
    "TLS 1.2" to "Works with almost every server", "TLS 1.3" to "Stricter. Newer servers only",
)

private const val COMMANDS = """Other apps and ADB can control ${APP_NAME} once
"Let other apps control ${APP_NAME}" is on.

Connect the profile on the Connect screen:
  adb shell am start -n dev.shieldvpn/.Connect

Connect a profile by its name:
  adb shell am start -n dev.shieldvpn/.Connect --es profile "Home"

Disconnect:
  adb shell am start -n dev.shieldvpn/.Disconnect

Home automation apps send the same intents:
  component dev.shieldvpn/.Connect or dev.shieldvpn/.Disconnect,
  with an optional string extra "profile"."""

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
private fun Panel(state: VpnUiState, nav: Nav, actions: TunnelActions, req: Requesters, openPick: (Pick) -> Unit) {
    val s = state.settings
    val cat = nav.settingsCat
    val context = LocalContext.current
    val (title, sub) = when (cat) {
        Category.Protection -> "Protection" to "What happens when the tunnel drops, and what may leave the device outside it."
        Category.Startup -> "Startup" to "What ${APP_NAME} does when the device turns on or the network changes."
        Category.Apps -> "Apps" to "Some TV services block VPNs. Let those apps skip it, or send only the apps you choose through the VPN."
        Category.Diagnostics -> "Diagnostics" to "For when something does not connect."
        Category.Backup -> "Backup" to "Move everything to a new device, or keep a copy on your phone."
        Category.OpenVpn -> "OpenVPN" to "Applied on top of every profile. A setting inside the .ovpn file wins unless noted."
        Category.Dns -> "DNS" to "Where name lookups go while the tunnel is up."
        Category.Automation -> "Automation" to "For home automation apps and ADB. Off until you turn it on."
        Category.About -> "About" to "The version, updates, and what ${APP_NAME} keeps and sends."
    }
    PanelHeading(null, title, sub)
    val scope = PanelScope(state, nav, actions, req)
    // Lint misreads produceState with a key: the value is set on every run.
    @Suppress("ProduceStateDoesNotAssignValue")
    val apps by produceState<List<LauncherApp>?>(null, cat) {
        // Only Settings › Apps needs them; on any other page the list and its icons are let go.
        value = if (cat == Category.Apps) withContext(Dispatchers.IO) { actions.launcherApps() } else null
    }
    val pickValue = { key: String -> openPick(Pick.Value(key)) }
    val list = remember(cat) { LazyListState() }
    LazyColumn(
        state = list,
        modifier = Modifier.padding(top = 18.dp).bleed(12.dp).scrollFade(list),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        when (cat) {
            Category.Protection -> {
                toggle(scope, "s-kill", "Kill switch", "Block all traffic while the tunnel is down", s.killSwitch) {
                    actions.changeSettings { it.copy(killSwitch = !it.killSwitch) }
                }
                toggle(scope, "s-lan", "Allow the local network", "Casting and devices at home keep working while connected", s.allowLan) {
                    actions.changeSettings { it.copy(allowLan = !it.allowLan) }
                    scope.appliesNext()
                }
                value(scope, "s-pin", "Lock with a PIN", "Ask for a PIN before profiles or settings change", s.pinMode, selected = nav.pick == Pick.Value("pin")) { pickValue("pin") }
                if (s.advanced) {
                    item { SectionLabel("Advanced") }
                    toggle(scope, "s-ipv6", "Block IPv6 outside the tunnel", "Stops leaks when the server only tunnels IPv4", s.blockIpv6) {
                        actions.changeSettings { it.copy(blockIpv6 = !it.blockIpv6) }
                        scope.appliesNext()
                    }
                    toggle(scope, "s-dnsblock", "Block DNS outside the tunnel", "With the local network allowed, lookups still use the tunnel", s.blockDns) {
                        actions.changeSettings { it.copy(blockDns = !it.blockDns) }
                        scope.appliesNext()
                    }
                }
                value(scope, "s-always", "Always-on VPN", "Set in Android TV system settings", "Open") {
                    if (!actions.openVpnSettings()) nav.toast("This device has no VPN settings screen")
                }
            }
            Category.Startup -> {
                val startup = state.connection(s.startupId)
                toggle(scope, "s-boot", "Connect when the device starts", "Uses the startup profile below", s.boot && startup != null) {
                    if (s.boot && startup != null) actions.changeSettings { it.copy(boot = false) }
                    else if (startup != null) actions.changeSettings { it.copy(boot = true) }
                    else openPick(Pick.Startup)
                }
                value(scope, "s-sprof", "Startup profile", "Must connect without typing a password", startup?.name ?: "None", selected = nav.pick == Pick.Startup) {
                    openPick(Pick.Startup)
                }
                toggle(scope, "s-roam", "Reconnect automatically", "After Wi-Fi changes and router restarts", s.roam) {
                    actions.changeSettings { it.copy(roam = !it.roam) }
                    scope.appliesNext()
                }
                if (s.advanced) {
                    item { SectionLabel("Advanced") }
                    toggle(scope, "s-sleep", "Stay connected while the screen is off", "Keeps the device awake while the tunnel is up", s.stayAwake) {
                        actions.changeSettings { it.copy(stayAwake = !it.stayAwake) }
                        scope.appliesNext()
                    }
                }
            }
            Category.Apps -> {
                value(scope, "s-appmode", "Which apps use the VPN", "${APP_NAME} itself always does", ValueChipLabel[s.appMode] ?: s.appMode, selected = nav.pick == Pick.Value("appmode")) { pickValue("appmode") }
                val found = apps
                val only = s.appMode == AppSettings.APPS_ONLY
                // With "only" and nothing chosen, Tunnel sends every app through the VPN (see applyApps).
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
                                            scope.appliesNext()
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
                if (s.advanced) {
                    item { SectionLabel("Advanced") }
                    toggle(scope, "s-subnets", "Let local subnets bypass the tunnel", "192.168.0.0/16, 172.16.0.0/12 and 10.0.0.0/8 stay on your network", s.allowLan) {
                        actions.changeSettings { it.copy(allowLan = !it.allowLan) }
                        scope.appliesNext()
                    }
                }
            }
            Category.Diagnostics -> {
                action(scope, "s-log", Ic.Log, "Connection log", "${state.log.size} lines from this session") { nav.showLog = true }
                action(scope, "s-test", Ic.Pulse, "Run a connection test", "Checks the tunnel, DNS and IPv6 leaks") { nav.showTest = true }
                val active = state.active
                action(
                    scope, "s-speed", Ic.Gauge, "Speed test",
                    active?.let { a -> state.speeds[a.id]?.let { "${a.name}: ${fmtMbps(it.downMbps)} Mbps down, ${ago(it.at)}" } } ?: "Is the VPN fast enough for 4K?",
                ) {
                    if (active != null) nav.overlay = Overlay.Speed(active.id) else nav.toast("Add a profile first")
                }
                action(scope, "s-send", Ic.Phone, "Send the log to your phone", "Scan a code to download it") { nav.overlay = Overlay.Phone(PhoneKind.Log) }
                if (s.advanced) {
                    item { SectionLabel("Advanced") }
                    value(scope, "s-verb", "Log detail", "How much the log shows", s.verb, selected = nav.pick == Pick.Value("verb")) { pickValue("verb") }
                }
            }
            Category.Backup -> {
                action(scope, "s-bk", Ic.Archive, "Back up to your phone", "An encrypted file with every profile and setting") {
                    if (state.hasBackupPassphrase) {
                        nav.overlay = Overlay.Phone(PhoneKind.Backup)
                    } else {
                        nav.toast("Choose a backup passphrase first")
                        nav.overlay = Overlay.Passphrase(restore = false)
                    }
                }
                action(scope, "s-rs", Ic.Phone, "Restore from a backup", "Opens Import › Restore a backup") {
                    nav.importMethod = ImportMethod.Restore
                    nav.go(Screen.Import)
                }
                value(scope, "s-bkpass", "Backup passphrase", "Needed to restore on another device", if (state.hasBackupPassphrase) "Change" else "Set") {
                    nav.overlay = Overlay.Passphrase(restore = false)
                }
            }
            Category.OpenVpn -> {
                value(scope, "s-proto", "Preferred protocol", "Tried first when a profile lists UDP and TCP", s.proto, selected = nav.pick == Pick.Value("proto")) { pickValue("proto") }
                value(scope, "s-timeout", "Connection timeout", "Move on to the next server after this long", "${s.timeout} s", selected = nav.pick == Pick.Value("timeout")) { pickValue("timeout") }
                value(scope, "s-mtu", "Tunnel MTU", "Lower it if video stalls on some networks", s.mtu, selected = nav.pick == Pick.Value("mtu")) { pickValue("mtu") }
                value(scope, "s-tls", "Minimum TLS version", "Refuse servers that only offer older TLS", s.tlsMin, selected = nav.pick == Pick.Value("tls")) { pickValue("tls") }
                toggle(scope, "s-legacy", "Allow legacy ciphers", "BF-CBC and others removed in OpenVPN 2.6", s.legacyCiphers) {
                    actions.changeSettings { it.copy(legacyCiphers = !it.legacyCiphers) }
                    scope.appliesNext()
                }
                toggle(scope, "s-compress", "Compress what this device sends", "Can leak data (VORACLE). Compression the server sends is always accepted", s.compress) {
                    actions.changeSettings { it.copy(compress = !it.compress) }
                    scope.appliesNext()
                }
            }
            Category.Dns -> {
                toggle(scope, "s-pushed", "Use DNS from the server", "The servers your VPN pushes", s.pushedDns) {
                    actions.changeSettings { it.copy(pushedDns = !it.pushedDns) }
                    scope.appliesNext()
                }
                value(scope, "s-dnscustom", "Custom DNS servers", "Used instead of the pushed ones", s.customDns.joinToString(", ").ifEmpty { "None" }) {
                    nav.overlay = Overlay.Text(TextKind.CustomDns, s.customDns.joinToString(" ")) { text ->
                        val servers = text.split(' ', ',').map { it.trim() }.filter { it.isNotEmpty() }
                        val bad = servers.filterNot(::isIpAddress)
                        if (bad.isNotEmpty()) {
                            nav.toast("${bad.first()} is not an IP address")
                        } else {
                            actions.changeSettings { it.copy(customDns = servers) }
                            scope.appliesNext()
                        }
                    }
                }
                value(scope, "s-dnssearch", "Search domains", "Short names like nas resolve as nas.home.lan", s.searchDomains.joinToString(", ").ifEmpty { "None" }) {
                    nav.overlay = Overlay.Text(TextKind.SearchDomains, s.searchDomains.joinToString(" ")) { text ->
                        actions.changeSettings { it.copy(searchDomains = text.split(' ', ',').map { d -> d.trim() }.filter { d -> d.isNotEmpty() }) }
                        scope.appliesNext()
                    }
                }
            }
            Category.Automation -> {
                toggle(scope, "s-auto", "Let other apps control ${APP_NAME}", "They can connect and disconnect profiles", s.automation) {
                    actions.changeSettings { it.copy(automation = !it.automation) }
                }
                value(scope, "s-cmds", "Commands", "am start -n dev.shieldvpn/.Connect --es profile …", "View") {
                    nav.overlay = Overlay.File("Automation", "Commands", COMMANDS, plain = true)
                }
            }
            Category.About -> {
                value(scope, "s-ver", "Version", "${APP_NAME} and its OpenVPN core", "${actions.versionName} · build ${BuildConfig.VERSION_CODE} · OpenVPN 3") {}
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
                value(scope, "s-upd", "Updates", updateSub, updateValue) {
                    when (update) {
                        is UpdateState.Available -> actions.installUpdate()
                        UpdateState.Checking, is UpdateState.Downloading, is UpdateState.Installing -> Unit
                        else -> actions.checkUpdate()
                    }
                }
                toggle(scope, "s-autoupd", "Check for updates by itself", "Each time ${APP_NAME} opens", s.autoUpdate) {
                    actions.changeSettings { it.copy(autoUpdate = !it.autoUpdate) }
                }
                value(scope, "s-priv", "What ${APP_NAME} stores", "Profiles and passwords, encrypted on this device. No accounts, no analytics", "Details") {
                    nav.overlay = Overlay.File("About", "What ${APP_NAME} stores", PRIVACY, plain = true)
                }
                value(scope, "s-lic", "Open-source licenses", "OpenVPN 3 core (MPL 2.0), OpenSSL, asio, fmt, lz4, xxHash, ZXing", "View") {
                    val text = runCatching { context.assets.open("licenses.txt").bufferedReader().use { it.readText() } }.getOrDefault("The license texts are missing from this build.")
                    nav.overlay = Overlay.File("About", "Open-source licenses", text, plain = true)
                }
                if (nav.confirmReset) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(Tv.Red.copy(alpha = 0.12f))
                                .padding(start = 18.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text("Delete every profile, password and setting?", style = TvType.ui(16f, FontWeight.SemiBold), modifier = Modifier.weight(1f))
                            TvButton("Reset", {
                                nav.confirmReset = false
                                actions.resetAll()
                                nav.toast("Everything was deleted")
                            }, small = true, danger = true)
                            TvButton("Keep", { nav.confirmReset = false }, small = true, focusRequester = req["s-reset-no"])
                        }
                        LaunchedEffect(Unit) { req["s-reset-no"].focusWhenReady() }
                    }
                } else {
                    item {
                        ListRow(
                            "Reset ${APP_NAME}", { nav.confirmReset = true },
                            subtitle = "Delete everything and start over", danger = true,
                            focusRequester = scope.key("s-reset"),
                            leading = { IconSquare(Ic.Trash, it) },
                            trailing = { TvIcon(Ic.Chev, 18f, if (it) Tv.OnFocus else Tv.Ink, stroke = 2.4f) },
                        )
                    }
                }
            }
        }
    }
}

private fun isIpAddress(s: String): Boolean {
    val v4 = s.split('.')
    if (v4.size == 4) return v4.all { it.toIntOrNull()?.let { n -> n in 0..255 } == true }
    return ':' in s && s.all { it.isLetterOrDigit() || it == ':' } && s.count { it == ':' } in 2..7
}

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
    item(key) { ListRow(title, onClick, subtitle = sub, selected = selected, focusRequester = fr, trailing = { ValueChip(value, it) }) }
}

private fun LazyListScope.action(scope: PanelScope, key: String, icon: Ic, title: String, sub: String, onClick: () -> Unit) {
    val fr = scope.key(key)
    item(key) {
        ListRow(
            title, onClick, subtitle = sub, focusRequester = fr,
            leading = { IconSquare(icon, it) },
            trailing = { TvIcon(Ic.Chev, 18f, if (it) Tv.OnFocus else Tv.Ink, stroke = 2.4f) },
        )
    }
}

/** Shown instead of a category while the PIN locks settings. */
@Composable
private fun LockedPanel(nav: Nav, req: Requesters) {
    req.alias("panel-first", "s-unlock")
    PanelHeading(null, "Settings are locked", "Enter the PIN to change settings on this device.")
    Row(Modifier.padding(top = 22.dp)) {
        TvButton("Enter PIN", { nav.overlay = Overlay.Pin() }, icon = Ic.Lock, focusRequester = req["s-unlock"])
    }
}

@Composable
private fun ColumnScope.LogPanel(state: VpnUiState, nav: Nav, actions: TunnelActions, req: Requesters, back: () -> Unit) {
    PanelHeading(null, "Connection log", "OpenVPN output for this session, newest at the bottom.")
    // The row that opened this is gone, so focus starts on Back.
    req.alias("panel-first", "s-log-back")
    LaunchedEffect(Unit) { req["s-log-back"].focusWhenReady() }
    val quiet = state.settings.verb.startsWith("1")
    // The newest line sits at the bottom; when there are more than fit, older ones leave at the top.
    Box(
        modifier = Modifier
            .padding(top = 16.dp)
            .fillMaxWidth()
            .weight(1f)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black.copy(alpha = 0.4f))
            .padding(horizontal = 18.dp, vertical = 16.dp),
        contentAlignment = Alignment.BottomStart,
    ) {
    Column(
        modifier = Modifier.fillMaxWidth().wrapContentHeight(Alignment.Bottom, unbounded = true),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Log detail › quiet keeps only errors and connection changes.
        val lines = state.log.filter { !quiet || it.kind != LogKind.Plain }.takeLast(15)
        if (lines.isEmpty()) Text("The log is empty.", style = TvType.mono(14.5f, color = Tv.soft(0.82f)))
        lines.forEach { line ->
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
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    }
    Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TvButton("Back", back, icon = Ic.Back, focusRequester = req["s-log-back"])
        TvButton("Send to phone", { nav.overlay = Overlay.Phone(PhoneKind.Log) }, icon = Ic.Phone, glass = true)
        TvButton("Clear", {
            actions.clearLog()
            nav.toast("Log cleared")
        }, glass = true)
    }
}

private enum class CheckState { Running, Ok, Warn, Fail }

private class Check(val title: String) {
    var result by mutableStateOf(CheckState.Running)
    var detail by mutableStateOf("Checking…")
}

/** Settings › Diagnostics › Run a connection test: four checks, one after the other. */
@Composable
private fun ColumnScope.TestPanel(state: VpnUiState, req: Requesters, back: () -> Unit) {
    val connected = state.status is VpnStatus.Connected
    var run by remember { mutableIntStateOf(0) }
    val checks = remember(run) {
        listOf(Check("Tunnel is up"), Check("DNS goes through the tunnel"), Check("No IPv6 leak"), Check("Traffic reaches the internet"))
    }
    LaunchedEffect(run) {
        val t = state.tunnel
        val blocksV6 = state.settings.blockIpv6
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
        checks[1].apply {
            if (!connected) {
                result = CheckState.Warn
                detail = "Needs the tunnel"
            } else {
                val ms = withContext(Dispatchers.IO) {
                    runCatching {
                        val start = System.nanoTime()
                        java.net.InetAddress.getByName("example.com")
                        (System.nanoTime() - start) / 1_000_000
                    }.getOrNull()
                }
                val dns = t?.dns?.firstOrNull()?.substringBefore(' ')
                result = if (ms != null && dns != null) CheckState.Ok else CheckState.Fail
                detail = if (ms != null) "${dns ?: "The DNS server"} answers in $ms ms" else "No answer from DNS"
            }
        }
        delay(300)
        checks[2].apply {
            result = if (blocksV6) CheckState.Ok else CheckState.Warn
            detail = if (blocksV6) "IPv6 is blocked outside the tunnel" else "Turn on Block IPv6 in Protection"
        }
        delay(300)
        checks[3].apply {
            if (!connected) {
                result = CheckState.Warn
                detail = "Needs the tunnel"
            } else {
                val answer = withContext(Dispatchers.IO) {
                    val start = System.nanoTime()
                    dev.shieldvpn.vpn.Http.publicIp("https://api.ipify.org")?.let { ip -> ip to (System.nanoTime() - start) / 1_000_000 }
                }
                result = if (answer != null) CheckState.Ok else CheckState.Fail
                detail = answer?.let { (ip, ms) -> "Sites see $ip · $ms ms" } ?: "No answer through the tunnel"
            }
        }
    }
    PanelHeading(null, "Connection test", if (connected) "Testing the tunnel to ${state.active?.name.orEmpty()}." else "Connect first to test the tunnel.")
    req.alias("panel-first", "s-test-back")
    LaunchedEffect(Unit) { req["s-test-back"].focusWhenReady() }
    Column(Modifier.padding(top = 16.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        checks.forEach { c ->
            Row(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Tv.white(0.05f)).padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                when (c.result) {
                    CheckState.Running -> Spinner()
                    CheckState.Ok -> Box(Modifier.size(26.dp).clip(CircleShape).background(Tv.Mint), contentAlignment = Alignment.Center) { TvIcon(Ic.Check, 15f, Color(0xFF06140D), stroke = 3f) }
                    CheckState.Warn -> Box(Modifier.size(26.dp).clip(CircleShape).background(Tv.Amber), contentAlignment = Alignment.Center) { TvIcon(Ic.Bang, 15f, Color(0xFF1A1200), stroke = 3f) }
                    CheckState.Fail -> Box(Modifier.size(26.dp).clip(CircleShape).background(Tv.Red), contentAlignment = Alignment.Center) { TvIcon(Ic.Bang, 15f, Color(0xFF1A0503), stroke = 3f) }
                }
                Text(c.title, style = TvType.ui(17f, FontWeight.SemiBold), modifier = Modifier.weight(1f))
                Text(c.detail, style = TvType.ui(15f, color = Tv.soft(0.62f)), maxLines = 1)
            }
        }
    }
    Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TvButton("Done", back, icon = Ic.Back, focusRequester = req["s-test-back"])
        TvButton("Run again", { run++ }, glass = true)
    }
}

// ---- Choice columns -------------------------------------------------------------------

@Composable
private fun PickHeading(eyebrow: String, title: String, sub: String) {
    PanelHeading(eyebrow, title, sub)
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
                        else -> {
                            actions.changeSettings { v.set(it, option) }
                            if (v.appliesNext && state.status.let { s -> s is VpnStatus.Connected || s is VpnStatus.Connecting }) nav.toast("Applies the next time you connect")
                        }
                    }
                    done()
                },
                subtitle = ValueSub[option],
                kind = RowKind.Pick,
                selected = option == current,
                focusRequester = if (option == current) req["pk-sel"] else null,
                trailing = { if (option == current) CheckBadge(it) },
            )
        }
    }
}

@Composable
private fun StartupPick(state: VpnUiState, nav: Nav, actions: TunnelActions, req: Requesters, done: () -> Unit) {
    PickHeading("Startup", "Startup profile", "${APP_NAME} connects to this profile when the device turns on, before you open the app.")
    val current = if (state.settings.boot) state.settings.startupId else null
    val list = rememberLazyListState()
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
        items(state.connections.size) { i ->
            val c = state.connections[i]
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

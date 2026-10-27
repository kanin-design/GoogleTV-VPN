package render

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.scene.ComposeScene
import androidx.compose.ui.unit.Density
import dev.shieldvpn.ui.tv.Category
import dev.shieldvpn.ui.tv.Col
import dev.shieldvpn.ui.tv.ImportMethod
import dev.shieldvpn.ui.tv.LauncherApp
import dev.shieldvpn.ui.tv.Nav
import dev.shieldvpn.ui.tv.Overlay
import dev.shieldvpn.ui.tv.Screen
import dev.shieldvpn.ui.tv.TunnelActions
import dev.shieldvpn.ui.tv.TunnelApp
import dev.shieldvpn.vpn.AppSettings
import dev.shieldvpn.vpn.ConfigInfo
import dev.shieldvpn.vpn.Connection
import dev.shieldvpn.vpn.LogKind
import dev.shieldvpn.vpn.LogLine
import dev.shieldvpn.vpn.Login
import dev.shieldvpn.vpn.Protocol
import dev.shieldvpn.vpn.Server
import dev.shieldvpn.vpn.SpeedTest
import dev.shieldvpn.vpn.TunnelInfo
import dev.shieldvpn.vpn.VpnKind
import dev.shieldvpn.vpn.VpnStatus
import dev.shieldvpn.vpn.VpnUiState
import kotlinx.coroutines.flow.MutableStateFlow
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import java.lang.reflect.Proxy
import javax.swing.SwingUtilities

/*
 * Draws the TV's screens as PNGs, 1920 × 1080 like the window Android TV gives the app: the app's
 * own TunnelApp over demo profiles, with the remote's focus where each screen puts it.
 */

private val now = System.currentTimeMillis()
private const val H = 3_600_000L
private const val D = 24 * H

/** Four demo profiles, Home connected for three hours, with addresses from the ranges kept for documentation. */
fun demoState(): VpnUiState {
    val servers = mapOf(
        "s1" to Server("s1", "home.example.net", 1194, Protocol.UDP),
        "s2" to Server("s2", "vpn.office.example.com", 443, Protocol.TCP),
        "s3" to Server("s3", "wg.example.org", 51820, Protocol.UDP, VpnKind.WireGuard),
        "s4" to Server("s4", "travel.example.net", 1194, Protocol.UDP),
    )
    fun ovpn(host: String, port: Int, protocol: Protocol, login: Boolean) = ConfigInfo(
        cipher = "AES-256-GCM", tls = "tls-crypt", needsLogin = login, clientCert = !login, cert = null,
        fileHost = host, filePort = port, fileProtocol = protocol, remotes = 1, warnings = emptyList(),
    )
    val infos = mapOf(
        "s1" to ovpn("home.example.net", 1194, Protocol.UDP, login = false),
        "s2" to ovpn("vpn.office.example.com", 443, Protocol.TCP, login = true),
        "s3" to ConfigInfo(
            cipher = null, tls = null, needsLogin = false, clientCert = false, cert = null, fileHost = "wg.example.org",
            filePort = 51820, fileProtocol = Protocol.UDP, remotes = 1, warnings = emptyList(), kind = VpnKind.WireGuard,
        ),
        "s4" to ovpn("travel.example.net", 1194, Protocol.UDP, login = false),
    )
    return VpnUiState(
        connections = listOf(
            Connection("p1", "Home", "s1", pinned = true, lastUsed = now - 3 * H),
            Connection("p2", "Office", "s2", lastUsed = now - 26 * H),
            Connection("p3", "Streaming", "s3", lastUsed = now - 2 * D - H),
            Connection("p4", "Travel", "s4", lastUsed = now - 5 * D - H),
        ),
        servers = servers,
        logins = mapOf("p2" to Login("alex", true), "p3" to Login("", false, publicKey = "mWv2sJ6uPq0h3H5x8yQe1t4n7cBfLkZr9aDgUiOpXsE=")),
        infos = infos,
        activeId = "p1",
        status = VpnStatus.Connected("p1", now - (3 * 3600 + 17 * 60) * 1000L),
        settings = AppSettings(boot = true, startupId = "p1", allowLan = true, apps = setOf("com.example.streaming")),
        tunnel = TunnelInfo(
            address = "10.8.0.6/30", dns = listOf("10.8.0.1"), routes = listOf("All traffic"), mtu = 1500,
            remote = "198.51.100.20", publicIp = "198.51.100.20", cipher = "AES-256-GCM",
        ),
        hasBackupPassphrase = true,
        lastBackup = (now - 3 * D) to 4,
        speeds = mapOf(SpeedTest.DIRECT to SpeedTest.Result(12, 554.0, 38.0, now - H)),
    )
}

private val demoLog = listOf(
    LogLine("15:27:51", "Connecting to Home (home.example.net:1194 UDP)", LogKind.Plain),
    LogLine("15:27:52", "TLS handshake with home.example.net", LogKind.Plain),
    LogLine("15:27:52", "Peer certificate verified: CN=home-server", LogKind.Plain),
    LogLine("15:27:52", "Data channel: AES-256-GCM, tls-crypt on the control channel", LogKind.Plain),
    LogLine("15:27:52", "Tunnel address 10.8.0.6/30, DNS 10.8.0.1", LogKind.Plain),
    LogLine("15:27:52", "Connected", LogKind.Ok),
)

/** Every action does nothing; the few values the screens read come back as the demo has them. */
fun fakeActions(state: VpnUiState): TunnelActions =
    Proxy.newProxyInstance(TunnelActions::class.java.classLoader, arrayOf(TunnelActions::class.java)) { _, m, _ ->
        when (m.name) {
            "getHasFilePicker" -> false
            "getLog" -> MutableStateFlow(demoLog)
            "getVersionName" -> dev.shieldvpn.BuildConfig.VERSION_NAME
            "stateNow" -> state
            "launcherApps" -> listOf(
                LauncherApp("com.example.streaming", "Streaming service", null),
                LauncherApp("com.example.sports", "Sports", null),
                LauncherApp("com.example.music", "Music", null),
                LauncherApp("com.example.store", "App store", null),
            )
            "rawConfig" -> "client\ndev tun\nproto udp\nremote home.example.net 1194\ncipher AES-256-GCM\n"
            "toString" -> "FakeActions"
            "hashCode" -> 0
            "equals" -> false
            else -> when (m.returnType) {
                java.lang.Boolean.TYPE -> false
                Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                String::class.java -> ""
                List::class.java -> emptyList<Any>()
                else -> null
            }
        }
    } as TunnelActions

/** One picture: where the app is, and where focus goes after it lands. */
class Shot(
    val name: String,
    val state: VpnUiState = demoState(),
    val moves: List<FocusDirection> = emptyList(),
    val setup: Nav.() -> Unit = {},
)

/** The README's pictures, under the names it shows them by. */
private val readme = listOf("connect", "profiles-list", "profiles", "import", "settings-apps", "speedtest", "details")

private fun shots(): List<Shot> {
    val demo = demoState()
    val off = demo.copy(status = VpnStatus.Disconnected, tunnel = null)
    return listOf(
        Shot("connect"),
        Shot("connect-card", moves = listOf(FocusDirection.Down, FocusDirection.Right)),
        Shot("connecting", state = demo.copy(status = VpnStatus.Connecting("p1", now - 4_000), tunnel = null)),
        Shot("off", state = off),
        Shot("profiles-list") { go(Screen.Profiles) },
        Shot("profiles") {
            go(Screen.Profiles)
            profFocus = "p1"
            push(Col.Profile("p1"), "pe-name")
        },
        Shot("import") { go(Screen.Import) },
        Shot("settings") { go(Screen.Settings) },
        Shot("settings-apps") {
            settingsCat = Category.Apps
            go(Screen.Settings)
        },
        Shot("speedtest") { overlay = Overlay.Speed("p1") },
        Shot("details") { overlay = Overlay.Details },
    )
}

private object Focus {
    var manager: FocusManager? = null
}

@Composable
private fun Root(state: VpnUiState, actions: TunnelActions) {
    val manager = LocalFocusManager.current
    SideEffect { Focus.manager = manager }
    TunnelApp(state, actions)
}

/** Arguments: the folder to write to, then the pictures to make (all, or the README's when none are named). */
fun main(args: Array<String>) {
    val out = File(args.getOrElse(0) { "screenshots" }).apply { mkdirs() }
    val names = args.drop(1).toSet()
    val all = shots()
    val chosen = when {
        names.isEmpty() -> all.filter { it.name in readme }
        "all" in names -> all
        else -> all.filter { it.name in names }.also { found ->
            (names - found.map { it.name }.toSet()).forEach { println("No picture is called $it. There are: ${all.joinToString { s -> s.name }}") }
        }
    }
    Hooks.address = "192.168.1.20:8080"
    Hooks.speed = SpeedTest.Result(41, 81.0, 22.0, now)
    // Compose for desktop applies snapshot changes on the AWT thread: drawing there too keeps the
    // two from holding its locks at once.
    SwingUtilities.invokeAndWait { chosen.forEach { draw(it, out) } }
    System.exit(0)
}

private fun draw(shot: Shot, out: File) {
    val nav = Nav().apply {
        contentFocus = Screen.Connect
        importMethod = ImportMethod.Phone
    }
    shot.setup(nav)
    Hooks.nav = nav
    val scene = ImageComposeScene(1920, 1080, Density(1f)) { Root(shot.state, fakeActions(shot.state)) }
    var t = 0L
    fun frames(n: Int, stepMs: Long = 50) = repeat(n) {
        scene.render(t)
        t += stepMs * 1_000_000L
        Thread.sleep(2)
    }
    frames(4, 16)
    // The scene starts without the window's focus, so the screen's own request found nothing to
    // land on: give the scene focus, then let the screen ask again.
    (ImageComposeScene::class.java.getDeclaredField("scene").apply { isAccessible = true }.get(scene) as ComposeScene).focusManager.requestFocus()
    frames(2, 16)
    when {
        nav.overlay != null -> nav.overlayRefocus++
        nav.stack.isNotEmpty() -> nav.moveStackFocus(nav.stackFocus ?: "")
        else -> nav.contentFocus = nav.screen
    }
    frames(30)
    for (m in shot.moves) {
        Focus.manager?.moveFocus(m)
        frames(12)
    }
    frames(30)
    File(out, "${shot.name}.png").writeBytes(scene.render(t).encodeToData(EncodedImageFormat.PNG)!!.bytes)
    println("tv: ${shot.name}.png")
    scene.close()
}

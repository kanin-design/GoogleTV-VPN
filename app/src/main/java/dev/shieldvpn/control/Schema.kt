package dev.shieldvpn.control

import dev.shieldvpn.APP_NAME
import dev.shieldvpn.vpn.AppSettings
import dev.shieldvpn.vpn.Connection
import dev.shieldvpn.vpn.VpnKind
import org.json.JSONArray
import org.json.JSONObject

/** When a change takes effect. */
enum class Effect(val word: String) {
    Now("now"),

    /** A tunnel setting while that profile isn't connected. */
    NextConnect("next-connect"),

    /** A tunnel setting while it is: applying reconnects it. */
    Reconnect("reconnect"),
}

/** One choice of a setting: its value, its name, and the line under it. */
data class Option(val value: String, val label: String, val help: String? = null)

/**
 * A setting or a profile's option: its name and help line (the TV's row and the panel say the
 * same), what it may be set to, its check, when it takes effect, and when it needs an OK on the
 * TV. A profile's items have paths below "profiles.<id>.".
 */
class Item(
    val path: String,
    val type: Type,
    val label: String,
    val help: String,
    /** Changes the tunnel: it reconnects to take effect. */
    val tunnel: Boolean = false,
    val advanced: Boolean = false,
    /** Only for profiles of this kind. */
    val kind: VpnKind? = null,
    /** Why the TV is asked first, when it is, for this value. */
    val askTv: ((Any?) -> String?)? = null,
    /** Shown only when this holds, for a profile item. */
    val shows: (CoreState, Connection) -> Boolean = { _, _ -> true },
    val options: (CoreState) -> List<Option> = { emptyList() },
    /** The check by name, for the panel to run as it is typed. */
    val checkName: String? = null,
    val check: (Any?) -> String? = { null },
) {
    enum class Type { Switch, Choice, Text, Number, Lines, Secret, Set }

    /** The value as the panel shows it in a preview: "On", "Always on", "1.1.1.1, 9.9.9.9". */
    fun show(value: Any?, s: CoreState): String = when (type) {
        Type.Switch -> if (value == true) "On" else "Off"
        Type.Choice -> options(s).firstOrNull { it.value == value }?.label ?: (value?.toString()?.ifEmpty { null } ?: "Off")
        Type.Lines -> (value as? List<*>)?.joinToString(", ")?.ifEmpty { null } ?: "None"
        Type.Set -> (value as? List<*>)?.size?.let { n -> if (n == 0) "None" else options(s).filter { it.value in value }.joinToString(", ") { it.label }.ifEmpty { "$n apps" } } ?: "None"
        Type.Secret -> if (value == null) "None" else "A new one"
        Type.Text, Type.Number -> value?.toString()?.ifEmpty { null } ?: "None"
    }

    /** [raw] from the panel's JSON as this item holds it, or null when it is the wrong type. */
    fun read(raw: Any?): Result<Any?> = runCatching {
        when (type) {
            Type.Switch -> raw as Boolean
            Type.Choice, Type.Text -> (raw as String).trim()
            Type.Number -> when (raw) {
                is Number -> raw.toInt()
                is String -> raw.trim()
                else -> error("number")
            }
            Type.Lines, Type.Set -> (raw as JSONArray).let { a -> List(a.length()) { a.getString(it).trim() }.filter { it.isNotEmpty() } }
            // An empty one is none: forgetting a saved password, which the TV is asked about.
            Type.Secret -> if (raw == null || raw == JSONObject.NULL) null else (raw as String).ifEmpty { null }
        }
    }

    fun toJson(s: CoreState): JSONObject = JSONObject()
        .put("path", path).put("type", type.name.lowercase()).put("label", label).put("help", help)
        .put("effect", if (tunnel) Effect.Reconnect.word else Effect.Now.word)
        .put("advanced", advanced)
        .also { o -> kind?.let { o.put("kind", Words.kind(it).lowercase()) } }
        .also { o -> checkName?.let { o.put("check", it) } }
        .also { o -> options(s).takeIf { it.isNotEmpty() }?.let { opts -> o.put("options", JSONArray(opts.map { JSONObject().put("value", it.value).put("label", it.label).put("help", it.help ?: JSONObject.NULL) })) } }
}

/** A part of Settings: its name and line on the TV, and its items. */
class Group(val id: String, val label: String, val help: String, val items: List<Item>, val advanced: Boolean = false)

/**
 * Every setting and profile option, written once. The TV's Settings rows and the panel take their
 * words from here, and [Changes] its checks, effects and when to ask the TV.
 */
object Schema {
    const val ID = "s1"

    private fun choices(vararg o: Pair<String, String?>) = o.map { (v, h) -> Option(v, v, h) }

    // ---- Settings -----------------------------------------------------------------------

    private val pinModes = listOf(
        Option("Off", "Off", "Anyone can change profiles and settings"),
        Option("For settings", "For settings", "Ask before profiles or settings change"),
        Option("For everything", "For everything", "Ask every time $APP_NAME opens"),
    )

    val appModes = listOf(
        Option(AppSettings.APPS_SKIP, "All apps, except the ones you choose", "The apps you turn on skip the VPN"),
        Option(AppSettings.APPS_ONLY, "Only the apps you choose", "Every other app uses your normal connection"),
    )

    /** The apps the panel lists, set by [Control] from the launcher. */
    @Volatile var launcherApps: List<Option> = emptyList()

    val groups: List<Group> = listOf(
        Group(
            "protection", "Protection", "What happens when the tunnel drops, and what may leave the device outside it.",
            listOf(
                Item("settings.killSwitch", Item.Type.Switch, "Kill switch", "Block all traffic while the tunnel is down", askTv = { if (it == false) "turn off the kill switch" else null }),
                Item("settings.allowLan", Item.Type.Switch, "Allow the local network", "Casting and devices at home keep working while connected", tunnel = true, askTv = { if (it == true) "allow the local network outside the tunnel" else null }),
                Item("settings.pinMode", Item.Type.Choice, "Lock with a PIN", "Ask for a PIN before profiles or settings change", options = { pinModes }, askTv = {
                    when (it) {
                        "Off" -> "turn off the PIN lock"
                        "For everything" -> "lock everything with a PIN"
                        else -> "lock profiles and settings with a PIN"
                    }
                }),
                Item("settings.blockIpv6", Item.Type.Switch, "Block IPv6 outside the tunnel", "Stops leaks when the server only tunnels IPv4", tunnel = true, advanced = true, askTv = { if (it == false) "let IPv6 leave outside the tunnel" else null }),
                Item("settings.blockDns", Item.Type.Switch, "Block DNS outside the tunnel", "With the local network allowed, lookups still use the tunnel", tunnel = true, advanced = true, askTv = { if (it == false) "let DNS leave outside the tunnel" else null }),
            ),
        ),
        Group(
            "startup", "Startup", "What $APP_NAME does when the device turns on or the network changes.",
            listOf(
                Item("settings.startup", Item.Type.Choice, "Connect when the device starts", "Connects before you open the app", options = { s ->
                    listOf(Option("", "Off", "Nothing connects by itself")) + s.connections.map { Option(it.id, it.name, null) }
                }),
                Item("settings.roam", Item.Type.Switch, "Reconnect automatically", "After Wi-Fi changes and router restarts", tunnel = true),
                Item("settings.stayAwake", Item.Type.Switch, "Stay connected while the screen is off", "Keeps the device awake while the tunnel is up", tunnel = true, advanced = true),
            ),
        ),
        Group(
            "apps", "Apps", "Some TV services block VPNs. Let those apps skip it, or send only the apps you choose through the VPN.",
            listOf(
                Item("settings.appMode", Item.Type.Choice, "Which apps use the VPN", "$APP_NAME itself always does", tunnel = true, options = { appModes }, askTv = { "change which apps use the VPN" }),
                Item("settings.apps", Item.Type.Set, "Chosen apps", "Turn on the apps the choice above is about", tunnel = true, options = { launcherApps }, askTv = { "change which apps use the VPN" }),
            ),
        ),
        Group(
            "openvpn", "OpenVPN", "Applied on top of every profile. A setting inside the .ovpn file wins unless noted.",
            listOf(
                Item("settings.proto", Item.Type.Choice, "Preferred protocol", "Tried first when a profile lists UDP and TCP", tunnel = true, options = { choices("From file" to "Use what the profile says", "UDP" to "Faster. The usual choice", "TCP" to "Gets through strict networks") }),
                Item("settings.timeout", Item.Type.Choice, "Connection timeout", "Move on to the next server after this long", tunnel = true, options = { listOf(Option("30", "30 s", "Quick to give up on a dead server"), Option("60", "60 s", "Recommended"), Option("120", "120 s", "For slow or distant servers")) }),
                Item("settings.mtu", Item.Type.Choice, "Tunnel MTU", "Lower it if video stalls on some networks", tunnel = true, options = { choices("Auto" to "Recommended", "1400" to "Try this if video stalls", "1300" to "For networks that drop large packets") }),
                Item("settings.tlsMin", Item.Type.Choice, "Minimum TLS version", "Refuse servers that only offer older TLS", tunnel = true, options = { choices("TLS 1.2" to "Works with almost every server", "TLS 1.3" to "Stricter. Newer servers only") }),
                Item("settings.legacyCiphers", Item.Type.Switch, "Allow legacy ciphers", "BF-CBC and others removed in OpenVPN 2.6", tunnel = true),
                Item("settings.compress", Item.Type.Switch, "Compress what this device sends", "Can leak data (VORACLE). Compression the server sends is always accepted", tunnel = true),
            ),
            advanced = true,
        ),
        Group(
            "dns", "DNS", "Where name lookups go while the tunnel is up.",
            listOf(
                Item("settings.pushedDns", Item.Type.Switch, "Use DNS from the server", "The servers your VPN pushes", tunnel = true),
                Item("settings.customDns", Item.Type.Lines, "Custom DNS servers", "Used instead of the pushed ones", tunnel = true, checkName = "dns", check = { v -> (v as? List<*>)?.map { it.toString() }?.let(Validate::dnsProblem) }),
                Item("settings.searchDomains", Item.Type.Lines, "Search domains", "Short names like nas resolve as nas.home.lan", tunnel = true),
            ),
            advanced = true,
        ),
        Group(
            "diagnostics", "Diagnostics", "For when something does not connect.",
            listOf(
                Item("settings.verb", Item.Type.Choice, "Log detail", "How much the log shows", tunnel = true, advanced = true, options = { choices("1 · quiet" to "Errors and connection changes only", "3 · normal" to "Recommended", "4 · detailed" to "Everything, for troubleshooting with your VPN admin") }),
            ),
        ),
        Group(
            "automation", "Automation", "For home automation apps and ADB. Off until you turn it on.",
            listOf(
                Item("settings.automation", Item.Type.Switch, "Let other apps control $APP_NAME", "They can connect and disconnect profiles"),
            ),
            advanced = true,
        ),
        Group(
            "phones", "Phone access", "Paired phones can see and change $APP_NAME from their browser, on your home network.",
            listOf(
                Item("settings.phoneReach", Item.Type.Choice, "When phones can reach the TV", "When the panel answers a paired phone", options = { phoneReach }),
            ),
        ),
        Group(
            "general", "General", "",
            listOf(
                Item("settings.advanced", Item.Type.Switch, "Advanced", "OpenVPN, DNS and automation"),
                Item("settings.autoUpdate", Item.Type.Switch, "Check for updates by itself", "Each time $APP_NAME opens"),
            ),
        ),
    )

    val phoneReach = listOf(
        Option(AppSettings.REACH_OPEN, "While the app is open", "Nothing listens once you leave $APP_NAME"),
        Option(AppSettings.REACH_VPN, "Also while the VPN is on", "Change things from the sofa while you watch"),
    )

    // ---- A profile's options ----------------------------------------------------------------

    private val openVpnOnly: (CoreState, Connection) -> Boolean = { s, c -> s.servers[c.serverId]?.kind != VpnKind.WireGuard }

    val profileItems: List<Item> = listOf(
        Item("name", Item.Type.Text, "Name", Words.NAME_HELP, checkName = "name", check = { Validate.nameProblem(it as String) }),
        Item("host", Item.Type.Text, "Server address", "Use another address than the one in the file", tunnel = true, kind = VpnKind.OpenVpn, checkName = "host",
            shows = { s, c -> openVpnOnly(s, c) && s.infos[c.serverId]?.connectionBlocks != true }, check = { Validate.hostProblem(it as String) }),
        Item("port", Item.Type.Number, "Port", "From 1 to 65535", tunnel = true, kind = VpnKind.OpenVpn, checkName = "port",
            shows = { s, c -> openVpnOnly(s, c) && s.infos[c.serverId]?.connectionBlocks != true }, check = { Validate.portProblem(it.toString()) }),
        Item("proto", Item.Type.Choice, "Protocol", "Override the protocol in the file", tunnel = true, kind = VpnKind.OpenVpn, shows = openVpnOnly,
            options = { choices("From file" to "Use what the profile says", "UDP" to "Faster. The usual choice", "TCP" to "Gets through strict networks") }),
        Item("order", Item.Type.Choice, "Server order", "When the file lists several servers, which one to try first", tunnel = true, kind = VpnKind.OpenVpn,
            shows = { s, c -> openVpnOnly(s, c) && (s.infos[c.serverId]?.remotes ?: 1) > 1 },
            options = { choices("In order" to "Try them as they are written", "Random" to "Spread the load across servers") }),
        Item("killMode", Item.Type.Choice, "Kill switch", "For this profile only. App setting follows Settings › Protection", tunnel = true,
            options = { choices("App setting" to "Follow Settings › Protection", "Always on" to "Block traffic whenever this tunnel is down", "Off" to "Never block for this profile") },
            askTv = { if (it == "Off") "never block traffic for this profile" else null }),
        Item("dnsMode", Item.Type.Choice, "DNS", "For this profile only", tunnel = true,
            options = { choices("App setting" to "The server's DNS, or 1.1.1.1 if it sends none", "Server only" to "Only the DNS servers this server pushes", "Custom" to "Your own DNS servers") }),
        Item("username", Item.Type.Text, "Username", "For signing in to the server", tunnel = true, kind = VpnKind.OpenVpn,
            shows = { s, c -> openVpnOnly(s, c) && s.infos[c.serverId]?.needsLogin == true }, checkName = "username", check = { Validate.usernameProblem(it as String) }),
        Item("password", Item.Type.Secret, "Password", "Saved encrypted on the TV. It never comes back to the phone", tunnel = true, kind = VpnKind.OpenVpn,
            shows = { s, c -> openVpnOnly(s, c) && s.infos[c.serverId]?.needsLogin == true },
            askTv = { if (it == null) "forget a saved password" else null }),
        Item("pinned", Item.Type.Switch, "Pin to Quick connect", "Show it first on the Connect screen"),
        Item("startup", Item.Type.Switch, "Connect when the device starts", "Connects before you open the app"),
    )

    fun profileItem(name: String): Item? = profileItems.firstOrNull { it.path == name }

    fun item(path: String): Item? = groups.flatMap { it.items }.firstOrNull { it.path == path }

    /** A setting's words for a TV row: its label and help. */
    fun words(path: String): Pair<String, String> = item(path)?.let { it.label to it.help } ?: (path to "")

    // ---- Values -------------------------------------------------------------------------

    /** The current value of [path] ("settings.killSwitch", "profiles.<id>.killMode"), as the panel holds it. */
    fun value(s: CoreState, path: String): Any? {
        if (path.startsWith("profiles.")) {
            val id = path.removePrefix("profiles.").substringBefore('.')
            val c = s.connections.firstOrNull { it.id == id } ?: return null
            return profileValue(s, c, path.substringAfter("$id."))
        }
        val st = s.settings
        return when (path) {
            "settings.killSwitch" -> st.killSwitch
            "settings.allowLan" -> st.allowLan
            "settings.pinMode" -> st.pinMode
            "settings.blockIpv6" -> st.blockIpv6
            "settings.blockDns" -> st.blockDns
            "settings.startup" -> st.startupId?.takeIf { st.boot }.orEmpty()
            "settings.roam" -> st.roam
            "settings.stayAwake" -> st.stayAwake
            "settings.appMode" -> st.appMode
            "settings.apps" -> st.apps.sorted()
            "settings.proto" -> st.proto
            "settings.timeout" -> st.timeout.toString()
            "settings.mtu" -> st.mtu
            "settings.tlsMin" -> st.tlsMin
            "settings.legacyCiphers" -> st.legacyCiphers
            "settings.compress" -> st.compress
            "settings.pushedDns" -> st.pushedDns
            "settings.customDns" -> st.customDns
            "settings.searchDomains" -> st.searchDomains
            "settings.verb" -> st.verb
            "settings.automation" -> st.automation
            "settings.phoneReach" -> st.phoneReach
            "settings.advanced" -> st.advanced
            "settings.autoUpdate" -> st.autoUpdate
            else -> null
        }
    }

    fun profileValue(s: CoreState, c: Connection, name: String): Any? {
        val server = s.servers[c.serverId]
        return when (name) {
            "name" -> c.name
            "host" -> server?.host
            "port" -> server?.port
            "proto" -> server?.let { sv -> if (s.infos[c.serverId]?.fileProtocol == sv.protocol) "From file" else sv.protocol.label }
            "order" -> c.order
            "killMode" -> c.killMode
            "dnsMode" -> c.dnsMode
            "username" -> s.logins[c.id]?.username.orEmpty()
            // Never the password itself: only whether one is saved.
            "password" -> if (s.logins[c.id]?.hasPassword == true) "saved" else null
            "pinned" -> c.pinned
            "startup" -> s.settings.boot && s.settings.startupId == c.id
            else -> null
        }
    }

    /** Settings as the panel's state holds them: every setting's value, by path. */
    fun settingsJson(s: CoreState): JSONObject {
        val o = JSONObject()
        groups.flatMap { it.items }.forEach { item -> o.put(item.path.removePrefix("settings."), json(value(s, item.path))) }
        return o
    }

    fun json(v: Any?): Any = when (v) {
        null -> JSONObject.NULL
        is List<*> -> JSONArray(v)
        else -> v
    }

    fun toJson(s: CoreState): JSONObject = JSONObject()
        .put("id", ID)
        .put("groups", JSONArray(groups.map { g -> JSONObject().put("id", g.id).put("label", g.label).put("help", g.help).put("advanced", g.advanced).put("items", JSONArray(g.items.map { it.toJson(s) })) }))
        .put("profile", JSONArray(profileItems.map { it.toJson(s) }))
}

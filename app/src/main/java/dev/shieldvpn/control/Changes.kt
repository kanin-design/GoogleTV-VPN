package dev.shieldvpn.control

import dev.shieldvpn.vpn.Backup
import dev.shieldvpn.vpn.Connection
import dev.shieldvpn.vpn.Inspected
import dev.shieldvpn.vpn.VpnController
import dev.shieldvpn.vpn.VpnKind
import dev.shieldvpn.vpn.VpnStatus
import dev.shieldvpn.vpn.connectionId
import dev.shieldvpn.vpn.isActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * A phone's changes as one set: checked with the same rules as the TV's sheets, shown as a
 * preview, then applied together. Each change names the value the phone saw, so when the TV has
 * changed the same thing meanwhile it is a conflict, not an overwrite.
 *
 * A change set is a list of operations:
 * - {"op":"set","path":"settings.customDns","value":["1.1.1.1"],"from":[]}
 * - {"op":"set","path":"profiles.<id>.killMode","value":"Always on","from":"App setting"}
 * - {"op":"set","path":"lock.pin","value":"1234"} and {"op":"set","path":"backup.passphrase","value":"…","current":"…"}
 * - {"op":"delete","path":"profiles.<id>"}
 * - {"op":"import","file":"f_…","name":"Home","username":"…","password":"…"}
 * - {"op":"update","file":"f_…","path":"profiles.<id>"}
 * - {"op":"restore","file":"f_…","passphrase":"…","settings":true,"replace":false}
 * - {"op":"certificate","file":"f_…","password":"…","path":"profiles.<id>","keep":"file"}
 *
 * Main thread only, like [Control].
 */
object Changes {
    private const val KEEP_MS = 10 * 60_000L
    private const val MAX_OPS = 64

    /** A file a phone sent, waiting to be used by a change: what it is, and its bytes. */
    class Upload(val id: String, val name: String, val bytes: ByteArray, val what: Inspected, val at: Long = System.currentTimeMillis()) {
        override fun toString() = "Upload($id, $name)"
    }

    private val uploads = LinkedHashMap<String, Upload>()

    fun upload(name: String, raw: ByteArray): Upload {
        sweep()
        val (what, bytes) = Detect.inspect(name, raw)
        val u = Upload("f_" + java.util.UUID.randomUUID().toString().take(8), name, bytes, what)
        uploads[u.id] = u
        while (uploads.size > 8) uploads.remove(uploads.keys.first())
        return u
    }

    /** One line of a preview: "Hatlevika › Kill switch: App setting → Always on". */
    class Line(val path: String, val label: String, val from: String, val to: String)

    /** The TV changed [label] since the phone saw it. */
    class Conflict(val path: String, val label: String, val phone: String, val tv: String)

    class Op(val index: Int, val op: String, val path: String, val o: JSONObject) {
        /** The value as the item holds it, once read. */
        var value: Any? = null

        /** The value on the TV when the set was checked, to notice a change before applying. */
        var seen: Any? = null
        var hasSeen = false
    }

    class Preview(
        val id: String,
        val ops: List<Op>,
        val lines: List<Line>,
        val effect: Effect,
        /** What needs an OK on the TV, as "turn off the kill switch". */
        val ask: List<String>,
        val notes: List<String>,
        val problems: Map<String, String>,
        val conflicts: List<Conflict>,
        /** The PIN locks settings: it is typed on the phone to apply. */
        val pin: Boolean,
        /** The profile an Apply and test connects and checks, if one can be tested. */
        val testProfile: String?,
        val expires: Long,
    ) {
        val ok: Boolean get() = problems.isEmpty() && conflicts.isEmpty()

        fun toJson(): JSONObject = JSONObject()
            // Nothing to apply when nothing would change: the phone then offers only Back.
            .put("change", if (ok && lines.isNotEmpty()) id else JSONObject.NULL)
            .put("ok", ok)
            .put("problems", JSONObject(problems))
            .put("conflicts", JSONArray(conflicts.map { JSONObject().put("path", it.path).put("label", it.label).put("phone", it.phone).put("tv", it.tv) }))
            .put("diff", JSONArray(lines.map { JSONObject().put("path", it.path).put("label", it.label).put("from", it.from).put("to", it.to) }))
            .put("effect", effect.word)
            .put("askTv", JSONArray(ask))
            .put("notes", JSONArray(notes))
            .put("pin", pin)
            .put("testable", testProfile != null)
            .put("expires", expires)
    }

    private val pending = LinkedHashMap<String, Preview>()

    private fun sweep() {
        val now = System.currentTimeMillis()
        pending.values.removeAll { it.expires < now }
        uploads.values.removeAll { now - it.at > KEEP_MS }
    }

    // ---- Checking ---------------------------------------------------------------------------

    /** Checks [body]'s operations and makes the preview; [who] is the phone's name, for the TV's question. */
    suspend fun check(body: JSONObject): Preview {
        sweep()
        val s = Control.state.value
        val list = body.optJSONArray("ops") ?: JSONArray()
        val problems = LinkedHashMap<String, String>()
        val conflicts = mutableListOf<Conflict>()
        val lines = mutableListOf<Line>()
        val ask = LinkedHashSet<String>()
        val notes = mutableListOf<String>()
        val ops = mutableListOf<Op>()
        var testProfile: String? = null
        // Profiles whose tunnel changes, and whether a setting for every profile does, or for every OpenVPN one.
        val touched = LinkedHashSet<String>()
        var allTunnels = false
        var allOpenVpn = false
        if (list.length() > MAX_OPS) problems[""] = "That is too many changes at once."
        for (i in 0 until minOf(list.length(), MAX_OPS)) {
            val o = list.optJSONObject(i) ?: continue
            val op = Op(i, o.optString("op"), o.optString("path"), o)
            val key = "ops.$i"
            fun problem(text: String) {
                problems[key] = text
            }
            ops += op
            when (op.op) {
                "set" -> {
                    val target = target(s, op.path)
                    if (target == null) {
                        problem("That isn’t a setting here.")
                        continue
                    }
                    val (item, c) = target
                    val read = item.read(if (o.has("value")) o.get("value") else null)
                    val value = read.getOrNull()
                    if (read.isFailure) {
                        problem("That isn’t a value for ${item.label}.")
                        continue
                    }
                    val choice = item.options(s)
                    if (item.type == Item.Type.Choice && choice.isNotEmpty() && choice.none { it.value == value }) {
                        problem("That isn’t one of the choices for ${item.label}.")
                        continue
                    }
                    val bad = item.check(value)
                    if (bad != null) {
                        problem(bad)
                        continue
                    }
                    op.value = value
                    val now = currentValue(s, op.path)
                    op.seen = now
                    op.hasSeen = true
                    if (o.has("from") && !same(item.read(o.get("from")).getOrNull(), now) && item.type != Item.Type.Secret) {
                        conflicts += Conflict(op.path, labelOf(item, c), item.show(item.read(o.get("from")).getOrNull(), s), item.show(now, s))
                        continue
                    }
                    if (item.type != Item.Type.Secret && same(value, now)) continue
                    lines += Line(op.path, labelOf(item, c), if (item.type == Item.Type.Secret) (if (now == null) "None" else "Saved") else item.show(now, s), item.show(value, s))
                    item.askTv?.invoke(value)?.let { ask += if (c != null) "$it (${c.name})" else it }
                    extraChecks(s, op, item, c, value)?.let { problem(it) }
                    if (item.tunnel) {
                        when {
                            c != null -> touched += c.id
                            item.kind == VpnKind.OpenVpn -> allOpenVpn = true
                            else -> allTunnels = true
                        }
                    }
                }
                "delete" -> {
                    val c = profileOf(s, op.path)
                    if (c == null) {
                        problem("That profile is gone.")
                        continue
                    }
                    lines += Line(op.path, c.name, "", if (s.logins[c.id]?.hasPassword == true) "Deleted, with its saved password" else "Deleted")
                    ask += Words.deleteQuestion(c.name, s.logins[c.id]?.hasPassword == true).removeSuffix("?").replaceFirstChar { it.lowercase() }
                }
                "import" -> {
                    val u = uploads[o.optString("file")]
                    val review = (u?.what as? Inspected.Profile)?.review
                    if (review == null) {
                        problem("Send the file again.")
                        continue
                    }
                    val name = o.optString("name").trim().ifEmpty { review.name }
                    val badName = Validate.nameProblem(name)
                    if (badName != null) {
                        problem(badName)
                        continue
                    }
                    // Saying so when a password is kept: the phone's login goes onto the TV.
                    val saved = (o.optString("password").isNotEmpty() || review.password.isNotEmpty()) && review.needsLogin
                    lines += Line("import", "New profile", "", "$name · ${Words.kind(review.kind)} · ${Words.address(review.host, review.port)}" + if (saved) " · password saved" else "")
                    testProfile = "new"
                }
                "update" -> {
                    val c = profileOf(s, op.path)
                    if (c == null) {
                        problem("That profile is gone.")
                        continue
                    }
                    val u = uploads[o.optString("file")]
                    val review = (u?.what as? Inspected.Profile)?.review
                    if (review == null) {
                        problem("Send the file again.")
                        continue
                    }
                    if (Control.hasFile(c.id, review)) {
                        notes += "${c.name} has this file already. Nothing to update."
                        continue
                    }
                    lines += Line(op.path, "${c.name} › ${Words.fileTitle(s.servers[c.serverId]?.kind).removePrefix("The ")}", "This one", "From ${u!!.name}")
                    touched += c.id
                }
                "restore" -> {
                    val u = uploads[o.optString("file")]
                    if (u?.what !is Inspected.Backup) {
                        problem("Send the backup file again.")
                        continue
                    }
                    val replace = o.optBoolean("replace")
                    val withSettings = o.optBoolean("settings", true)
                    val peeked = withContext(Dispatchers.Default) { Control.peekBackup(u.bytes, o.optString("passphrase"), replace) }
                    val peek = when (peeked) {
                        is Backup.Peeked.Problem -> {
                            problems["$key.passphrase"] = peeked.message
                            continue
                        }
                        is Backup.Peeked.Done -> peeked.peek
                    }
                    // The tunnel that is up can't have its profile taken from under it.
                    val live = VpnController.status.value.connectionId?.takeIf { VpnController.status.value.isActive || VpnController.pausedUntil.value != null }
                    val liveReplaced = peek.replacedIds.firstOrNull { it == live }
                    if (liveReplaced != null) {
                        problems["$key.replace"] = "${s.connections.firstOrNull { it.id == liveReplaced }?.name ?: "That profile"} is connected. Disconnect it first, or keep both."
                        continue
                    }
                    peek.added.forEach { lines += Line("restore", it, "", "Added") }
                    peek.replaced.forEach { lines += Line("restore", it, "This device’s", "Replaced by the backup’s") }
                    val nothingNew = peek.added.isEmpty() && peek.replaced.isEmpty()
                    if (peek.same.isNotEmpty() && !nothingNew) notes += "Here already, left alone: ${peek.same.joinToString(", ")}."
                    if (peek.replaced.isNotEmpty()) ask += "replace ${peek.replaced.joinToString(", ")} with the backup’s"
                    if (withSettings && peek.settings != null) {
                        val after = s.copy(settings = peek.settings)
                        Schema.groups.flatMap { it.items }.forEach { item ->
                            val a = Schema.value(s, item.path)
                            val b = Schema.value(after, item.path)
                            if (!same(a, b) && item.path != "settings.startup") {
                                lines += Line(item.path, item.label, item.show(a, s), item.show(b, after))
                                item.askTv?.invoke(b)?.let { ask += it }
                                if (item.tunnel) if (item.kind == VpnKind.OpenVpn) allOpenVpn = true else allTunnels = true
                            }
                        }
                    }
                    if (nothingNew && lines.none { it.path != "restore" }) notes += "Everything in this backup is here already. Nothing to restore."
                }
                "certificate" -> {
                    val u = uploads[o.optString("file")]
                    if (u?.what !is Inspected.Pkcs12) {
                        problem("Send the certificate again.")
                        continue
                    }
                    val password = o.optString("password")
                    val badPassword = Control.checkP12(u.bytes, password)
                    if (badPassword != null) {
                        problems["$key.password"] = badPassword
                        continue
                    }
                    val c = profileOf(s, op.path) ?: Control.profilesForCertificate(u.bytes, password).singleOrNull()
                    if (c == null) {
                        problems["$key.path"] = "Pick the profile it is for."
                        continue
                    }
                    op.o.put("path", "profiles.${c.id}")
                    lines += Line("profiles.${c.id}", "${c.name} › Certificate", "The file’s", if (o.optString("keep") == "keystore") "In Android’s keystore" else "From ${u.name}")
                    touched += c.id
                }
                else -> problem("The TV doesn’t know that change.")
            }
        }
        // A lock and its new PIN are one question.
        ask.firstOrNull { it.endsWith(" with a PIN") }?.takeIf { "set a new PIN" in ask }?.let { lock ->
            val merged = ask.map { if (it == lock) lock.replace(" with a PIN", " with a new PIN") else it }.filter { it != "set a new PIN" }
            ask.clear()
            ask += merged
        }
        crossChecks(s, ops, problems)
        // As the TV says before taking the startup slot: the profile that holds it loses it.
        val holder = s.connections.firstOrNull { s.settings.boot && it.id == s.settings.startupId }
        val takesStartup = ops.firstOrNull { op ->
            op.hasSeen && !same(op.value, op.seen) &&
                (op.path == "settings.startup" && (op.value as? String)?.isNotEmpty() == true || op.path.endsWith(".startup") && op.value == true)
        }
        if (holder != null && takesStartup != null && takesStartup.value != holder.id && !takesStartup.path.startsWith("profiles.${holder.id}.")) {
            notes += "${holder.name} stops connecting at startup."
        }
        // Said once: an update or a restore that changes nothing has said so already.
        if (lines.isEmpty() && problems.isEmpty() && conflicts.isEmpty() && notes.isEmpty()) notes += "Nothing would change: this is how the TV has it already."
        // When it takes effect: now, or with a reconnect of the tunnel that is up.
        val status = VpnController.status.value
        val liveId = status.connectionId?.takeIf { status.isActive || VpnController.pausedUntil.value != null }
        val live = liveId?.let { id -> s.connections.firstOrNull { it.id == id } }
        // A setting only OpenVPN reads (Log detail, TLS) leaves a WireGuard tunnel alone.
        val liveIsOpenVpn = live?.let { s.servers[it.serverId]?.kind != VpnKind.WireGuard } == true
        val reconnects = live != null && (allTunnels || live.id in touched || allOpenVpn && liveIsOpenVpn) && status !is VpnStatus.Failed
        val effect = when {
            reconnects -> Effect.Reconnect
            allTunnels || allOpenVpn || touched.isNotEmpty() -> Effect.NextConnect
            else -> Effect.Now
        }
        val killOn = live?.let { killSwitchFor(s, it) } == true
        when (effect) {
            Effect.Reconnect -> notes += if (VpnController.pausedUntil.value != null) {
                "${live!!.name} is paused. The changes apply when it comes back."
            } else if (killOn) {
                "${live!!.name} is connected. Applying reconnects it: a few seconds with traffic held back by the kill switch."
            } else {
                "${live!!.name} is connected. Applying reconnects it: a few seconds without the VPN."
            }
            Effect.NextConnect -> notes += if (allOpenVpn && !allTunnels && touched.isEmpty()) "OpenVPN profiles use this the next time they connect." else "Tunnel settings take effect the next time a profile connects."
            Effect.Now -> Unit
        }
        if (lines.any { it.path == "settings.killSwitch" }) notes += "The kill switch changes at once, also on a tunnel that is up."
        if (ops.any { it.op == "delete" && profileOf(s, it.path)?.id == liveId }) notes += "Deleting ${live?.name} disconnects it."
        val pin = s.settings.pinMode != "Off" && s.hasPin
        if (pin) notes += "The TV is locked with a PIN. Type it to apply."
        if (ask.isNotEmpty()) notes += "The TV asks before this happens: press Allow there."
        val test = when {
            testProfile != null -> testProfile
            reconnects -> live?.id
            else -> null
        }
        val p = Preview("ch_" + java.util.UUID.randomUUID().toString().take(8), ops, lines, effect, ask.toList(), notes, problems, conflicts, pin, test, System.currentTimeMillis() + KEEP_MS)
        if (p.ok && lines.isNotEmpty()) {
            pending[p.id] = p
            while (pending.size > 16) pending.remove(pending.keys.first())
        }
        return p
    }

    /** The item a path names, with its profile for a profile's option. */
    private fun target(s: CoreState, path: String): Pair<Item, Connection?>? {
        if (path == "lock.pin") return Item("lock.pin", Item.Type.Secret, "PIN", "4 to 8 digits", askTv = { "set a new PIN" }, check = { v -> if (v is String) Validate.pinProblem(v) else "Type a new PIN" }) to null
        if (path == "backup.passphrase") return Item("backup.passphrase", Item.Type.Secret, "Backup passphrase", "", askTv = { if (it == null) "remove the backup passphrase" else null }, check = { v -> (v as? String)?.let { Validate.passphraseProblem(it, it)?.second } }) to null
        if (path.startsWith("profiles.")) {
            val c = profileOf(s, path) ?: return null
            val item = Schema.profileItem(path.substringAfter("${c.id}.")) ?: return null
            if (!item.shows(s, c)) return null
            return item to c
        }
        return Schema.item(path)?.let { it to null }
    }

    private fun profileOf(s: CoreState, path: String): Connection? {
        val id = path.removePrefix("profiles.").substringBefore('.')
        return s.connections.firstOrNull { it.id == id }
    }

    private fun labelOf(item: Item, c: Connection?) = if (c != null) "${c.name} › ${item.label}" else item.label

    private fun currentValue(s: CoreState, path: String): Any? = when (path) {
        "lock.pin" -> if (s.hasPin) "saved" else null
        "backup.passphrase" -> if (s.hasBackupPassphrase) "saved" else null
        else -> Schema.value(s, path)
    }

    /** What else a single change needs, as the TV's sheet would refuse it. */
    private fun extraChecks(s: CoreState, op: Op, item: Item, c: Connection?, value: Any?): String? = when {
        item.path == "settings.startup" && value is String && value.isNotEmpty() -> {
            val p = s.connections.firstOrNull { it.id == value }
            if (p != null && s.infos[p.serverId]?.needsLogin == true && s.logins[p.id]?.hasPassword != true) "${p.name} can only connect by itself with its password saved. Save it first." else null
        }
        c != null && item.path == "startup" && value == true && s.infos[c.serverId]?.needsLogin == true && s.logins[c.id]?.hasPassword != true ->
            "${c.name} can only connect by itself with its password saved. Save it first."
        item.path == "backup.passphrase" && value != null && s.hasBackupPassphrase && !Control.checkBackupPassphrase(op.o.optString("current")) -> "That isn’t the current passphrase"
        else -> null
    }

    /** Checks across the set: a PIN lock needs a PIN. */
    private fun crossChecks(s: CoreState, ops: List<Op>, problems: MutableMap<String, String>) {
        val mode = ops.firstOrNull { it.op == "set" && it.path == "settings.pinMode" }
        if (mode != null && mode.value != "Off" && !s.hasPin && ops.none { it.op == "set" && it.path == "lock.pin" && it.value != null }) {
            problems["ops.${mode.index}"] = "Choose a PIN to lock with."
        }
    }

    private fun killSwitchFor(s: CoreState, c: Connection) = when (c.killMode) {
        "Always on" -> true
        "Off" -> false
        else -> s.settings.killSwitch
    }

    fun same(a: Any?, b: Any?): Boolean = when {
        a is List<*> && b is List<*> -> a.map { it.toString() } == b.map { it.toString() }
        a is Number && b is Number -> a.toLong() == b.toLong()
        a is Number || b is Number -> a.toString() == b.toString()
        else -> a == b
    }

    // ---- Applying -----------------------------------------------------------------------------

    /** The checked set [id], or null when it has expired or was applied. */
    fun take(id: String): Preview? {
        sweep()
        return pending.remove(id)
    }

    /** Puts [p] back, after a wrong PIN, so the phone can try again. */
    fun putBack(p: Preview) {
        pending[p.id] = p
    }

    /** What the TV changed since [p] was checked, or empty. */
    fun changedSince(p: Preview): List<Conflict> {
        val s = Control.state.value
        return p.ops.filter { it.hasSeen && !same(currentValue(s, it.path), it.seen) }.mapNotNull { op ->
            val (item, c) = target(s, op.path) ?: return@mapNotNull null
            Conflict(op.path, labelOf(item, c), item.show(op.seen, s), item.show(currentValue(s, op.path), s))
        }
    }

    /**
     * Applies [p]'s operations in order. Returns the new profile's id for an import, and what went
     * wrong, if something did part way.
     */
    suspend fun run(p: Preview): Pair<String?, String?> {
        var added: String? = null
        val s = Control.state.value
        // Server and port go together.
        val hosts = p.ops.filter { it.op == "set" && (it.path.endsWith(".host") || it.path.endsWith(".port")) }.groupBy { it.path.substringBeforeLast('.') }
        for ((prefix, list) in hosts) {
            val c = profileOf(s, prefix) ?: continue
            val host = list.firstOrNull { it.path.endsWith(".host") }?.value as? String ?: s.servers[c.serverId]?.host ?: continue
            val port = list.firstOrNull { it.path.endsWith(".port") }?.value?.toString()?.toIntOrNull() ?: s.servers[c.serverId]?.port ?: continue
            Control.setServer(c.id, host, port)
        }
        for (op in p.ops) {
            val o = op.o
            when (op.op) {
                "set" -> if (!op.path.endsWith(".host") && !op.path.endsWith(".port")) set(op.path, op.value)
                "delete" -> profileOf(Control.state.value, op.path)?.let { Control.delete(it.id) }
                "import" -> {
                    val review = (uploads[o.optString("file")]?.what as? Inspected.Profile)?.review ?: return added to "The file is gone. Send it again."
                    val username = o.optString("username").trim().ifEmpty { review.username }
                    val password = o.optString("password").ifEmpty { review.password }
                    added = Control.addProfile(review.copy(name = o.optString("name").trim().ifEmpty { review.name }, username = username, password = password))
                        ?: return null to "It couldn’t be saved."
                }
                "update" -> {
                    val review = (uploads[o.optString("file")]?.what as? Inspected.Profile)?.review ?: return added to "The file is gone. Send it again."
                    val c = profileOf(Control.state.value, op.path) ?: return added to "That profile is gone."
                    Control.updateProfile(c.id, review)
                }
                "restore" -> {
                    val u = uploads[o.optString("file")] ?: return added to "The backup is gone. Send it again."
                    when (val r = Control.restore(u.bytes, o.optString("passphrase"), o.optBoolean("replace"), o.optBoolean("settings", true))) {
                        is Backup.Restored.Problem -> return added to r.message
                        is Backup.Restored.Done -> Unit
                    }
                }
                "certificate" -> {
                    val u = uploads[o.optString("file")] ?: return added to "The certificate is gone. Send it again."
                    val c = profileOf(Control.state.value, o.optString("path")) ?: return added to "That profile is gone."
                    val password = o.optString("password")
                    if (o.optString("keep") == "keystore") {
                        val alias = Control.keepP12(u.bytes, password) ?: return added to "Android could not keep this key"
                        Control.setKeyAlias(c.id, alias)
                    } else {
                        Control.attachP12(u.bytes, password, c.id)?.let { return added to it }
                    }
                }
            }
        }
        return added to null
    }

    /** Sets one value through [Control], as the TV's row would. */
    private fun set(path: String, value: Any?) {
        if (path.startsWith("profiles.")) {
            val c = profileOf(Control.state.value, path) ?: return
            when (path.substringAfter("${c.id}.")) {
                "name" -> Control.rename(c.id, value as String)
                "proto" -> Control.setProtocol(c.id, value as String)
                "order" -> Control.setOrder(c.id, value as String)
                "killMode" -> Control.setKillMode(c.id, value as String)
                "dnsMode" -> Control.setDnsMode(c.id, value as String)
                "username" -> Control.saveLogin(c.id, value as String, null)
                "password" -> if (value == null) Control.forgetPassword(c.id) else Control.saveLogin(c.id, Control.state.value.logins[c.id]?.username.orEmpty(), value as String)
                "pinned" -> Control.setPinned(c.id, value as Boolean)
                "startup" -> if (value == true) Control.setStartup(c.id) else if (Control.state.value.settings.startupId == c.id) Control.setStartup(null)
            }
            return
        }
        when (path) {
            "lock.pin" -> Control.setPin(value as String)
            "backup.passphrase" -> Control.setBackupPassphrase(value as String?)
            "settings.startup" -> Control.setStartup((value as String).ifEmpty { null })
            "settings.pinMode" -> if (value == "Off") Control.setPin(null) else Control.changeSettings { it.copy(pinMode = value as String) }
            else -> Control.changeSettings { st ->
                when (path) {
                    "settings.killSwitch" -> st.copy(killSwitch = value as Boolean)
                    "settings.allowLan" -> st.copy(allowLan = value as Boolean)
                    "settings.blockIpv6" -> st.copy(blockIpv6 = value as Boolean)
                    "settings.blockDns" -> st.copy(blockDns = value as Boolean)
                    "settings.roam" -> st.copy(roam = value as Boolean)
                    "settings.stayAwake" -> st.copy(stayAwake = value as Boolean)
                    "settings.appMode" -> st.copy(appMode = value as String)
                    "settings.apps" -> st.copy(apps = (value as List<*>).map { it.toString() }.toSet())
                    "settings.proto" -> st.copy(proto = value as String)
                    "settings.timeout" -> st.copy(timeout = (value as String).toInt())
                    "settings.mtu" -> st.copy(mtu = value as String)
                    "settings.tlsMin" -> st.copy(tlsMin = value as String)
                    "settings.legacyCiphers" -> st.copy(legacyCiphers = value as Boolean)
                    "settings.compress" -> st.copy(compress = value as Boolean)
                    "settings.pushedDns" -> st.copy(pushedDns = value as Boolean)
                    "settings.customDns" -> st.copy(customDns = (value as List<*>).map { it.toString() })
                    "settings.searchDomains" -> st.copy(searchDomains = (value as List<*>).map { it.toString() })
                    "settings.verb" -> st.copy(verb = value as String)
                    "settings.automation" -> st.copy(automation = value as Boolean)
                    "settings.phoneReach" -> st.copy(phoneReach = value as String)
                    "settings.advanced" -> st.copy(advanced = value as Boolean)
                    "settings.autoUpdate" -> st.copy(autoUpdate = value as Boolean)
                    else -> st
                }
            }
        }
    }

    /** The log's line for what [who] changed: "Pixel 8 set Custom DNS servers to 1.1.1.1". */
    fun logLines(who: String, p: Preview): List<String> = p.lines.map { l ->
        when {
            l.path == "import" -> "$who added the profile ${l.to.substringBefore(" · ")}"
            l.to == "Deleted" -> "$who deleted ${l.label}"
            l.path == "lock.pin" -> "$who changed the PIN"
            l.path == "backup.passphrase" -> if (l.to == "None") "$who removed the backup passphrase" else "$who changed the backup passphrase"
            l.path.endsWith(".password") -> if (l.to == "None") "$who forgot the password of ${l.label.substringBefore(" › ")}" else "$who changed the password of ${l.label.substringBefore(" › ")}"
            l.path == "restore" -> "$who restored ${l.label} from a backup"
            else -> "$who set ${l.label} to ${l.to}"
        }
    }
}

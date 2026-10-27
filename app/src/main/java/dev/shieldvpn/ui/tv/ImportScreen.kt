package dev.shieldvpn.ui.tv

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.shieldvpn.APP_NAME
import dev.shieldvpn.control.Control
import dev.shieldvpn.control.Words
import dev.shieldvpn.phone.PhoneAction
import dev.shieldvpn.phone.PhoneField
import dev.shieldvpn.phone.PhoneForm
import dev.shieldvpn.phone.PhoneReply
import dev.shieldvpn.phone.PhoneSection
import dev.shieldvpn.phone.PhoneServer
import dev.shieldvpn.phone.PhoneSession
import dev.shieldvpn.phone.PhoneStatus
import dev.shieldvpn.phone.Staged
import dev.shieldvpn.vpn.Backup
import dev.shieldvpn.vpn.Connection
import dev.shieldvpn.vpn.ImportReview
import dev.shieldvpn.vpn.Inspected
import dev.shieldvpn.vpn.ServerStore
import dev.shieldvpn.vpn.VpnKind
import dev.shieldvpn.vpn.VpnUiState
import dev.shieldvpn.vpn.isActive

enum class ImportMethod(val icon: Ic, val title: String, val sub: String) {
    Phone(Ic.Phone, "From your phone", "A profile, backup or certificate"),
    Files(Ic.Usb, "USB drive or Downloads", "Files on this device"),
    Link(Ic.Link, "From a link", "An HTTPS address, typed here"),
    Restore(Ic.Archive, "Restore a backup", "From a USB drive, or your phone"),
    Certs(Ic.Cert, "Certificates", "A .p12 file or the device keystore"),
}

/** Where focus starts on the review: Back on a file that can't be used, otherwise Save. */
private fun reviewFocus(review: ImportReview): String = if (review.problem != null) "rv-back" else "rv-save"

/** Import: how the file gets here on the left, and what happens next on the right. Every file is reviewed first. */
@Composable
fun ImportScreen(state: VpnUiState, nav: Nav, actions: TunnelActions, connect: (String) -> Unit) {
    val req = remember { Requesters() }
    SideEffect {
        val review = state.review
        nav.entry = req[if (review == null) "m-${nav.importMethod.name}" else reviewFocus(review)]
    }
    LaunchedEffect(nav.contentFocus) {
        if (nav.contentFocus == Screen.Import) {
            val review = state.review
            req[if (review == null) "m-${nav.importMethod.name}" else reviewFocus(review)].focusWhenReady()
            nav.contentFocus = null
        }
    }
    LaunchedEffect(state.review) {
        val review = state.review ?: return@LaunchedEffect
        req[reviewFocus(review)].focusWhenReady()
    }
    // The phone session belongs to Import, not to its panel: it keeps its code while a file is
    // reviewed, so the phone's page goes on working after Save or Cancel and can send another.
    val session = rememberImportSession(state, nav, actions)

    Box(Modifier.fillMaxSize()) {
        Glass(Modifier.padding(start = 64.dp, top = 124.dp, bottom = 36.dp).width(400.dp)) {
            Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 22.dp)) {
                Box(Modifier.padding(horizontal = 16.dp)) { PanelHeading("Import", "Add a profile", "A profile, a backup or a certificate.") }
                val list = rememberLazyListState()
                LazyColumn(
                    state = list,
                    modifier = Modifier
                        .padding(top = 14.dp)
                        .weight(1f)
                        .bleed(12.dp)
                        .scrollFade(list)
                        .enterAt { req["m-${nav.importMethod.name}"] },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ImportMethod.entries.forEach { m -> item(m.name) { MethodRow(m, state, nav, actions, req, session) } }
                }
                Spacer(Modifier.height(10.dp))
                Text("Every file is checked before it is saved.", style = TvType.ui(14f, color = Tv.soft(0.55f)), modifier = Modifier.padding(horizontal = 16.dp))
            }
        }
        val review = state.review
        Glass(
            Modifier
                .padding(start = 488.dp, end = 64.dp, top = 124.dp, bottom = 36.dp)
                .fillMaxSize()
                .enterAt { req[if (review == null) "im-entry" else reviewFocus(review)] },
        ) {
            Column(Modifier.fillMaxSize().padding(horizontal = 34.dp, vertical = 30.dp)) {
                when {
                    review != null && review.problem != null -> ProblemPanel(review, nav, actions, req)
                    review != null -> ReviewPanel(state, review, nav, actions, req)
                    else -> MethodPanel(nav.importMethod, state, nav, actions, req, session)
                }
            }
        }
    }
}

@Composable
private fun MethodRow(m: ImportMethod, state: VpnUiState, nav: Nav, actions: TunnelActions, req: Requesters, session: PhoneSession?) {
    // Without a file picker there is nothing to pick from, so the row says so and focus passes it by.
    val usable = m != ImportMethod.Files || actions.hasFilePicker
    ListRow(
        m.title,
        { if (m == ImportMethod.Files) actions.pickFile() else req["im-entry"].tryFocus() },
        subtitle = when {
            !usable -> "This device has no file picker"
            // Only what this device can do: without a file picker, a backup comes from the phone.
            m == ImportMethod.Restore && !actions.hasFilePicker -> "From your phone"
            else -> m.sub
        },
        kind = RowKind.Method,
        selected = nav.importMethod == m,
        enabled = usable,
        focusRequester = req["m-${m.name}"],
        onFocus = {
            if (nav.importMethod != m) {
                nav.importMethod = m
                dropReview(nav, actions, session)
                // Another way in is another task: what the last one said goes.
                session?.newTask()
            }
        },
        leading = { IconSquare(m.icon, it, size = 44f, radius = 13f) },
    )
}

@Composable
private fun ColumnScope.MethodPanel(m: ImportMethod, state: VpnUiState, nav: Nav, actions: TunnelActions, req: Requesters, session: PhoneSession?) {
    val picker = actions.hasFilePicker
    val fromPhone = {
        nav.importMethod = ImportMethod.Phone
        nav.contentFocus = Screen.Import
    }
    when (m) {
        ImportMethod.Files -> {
            PanelHeading("Import › On this device", "Pick a file", "Android’s file picker opens. Choose the .ovpn, .conf, .p12 or backup file from Downloads or a USB drive.")
            Row(Modifier.padding(top = 26.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvButton("Choose a file", actions::pickFile, icon = Ic.File, focusRequester = req["im-entry"])
            }
            Steps(
                listOf(
                    "Save the file in Downloads, or on a USB drive in this device",
                    "Pick it in the file picker",
                    "Check it here before it is saved",
                ),
                Modifier.padding(top = 28.dp),
            )
        }
        ImportMethod.Phone -> PhonePanel(nav, actions, req, session)
        ImportMethod.Link -> LinkPanel(nav, actions, req, session)
        ImportMethod.Restore -> {
            PanelHeading("Import", "Restore a backup", "A backup holds every profile, saved password and setting, encrypted with the passphrase chosen when it was made.")
            Column(Modifier.padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ListRow(
                    "Send it from your phone", fromPhone,
                    subtitle = "Choose the backup file there and type its passphrase",
                    focusRequester = req["im-entry"],
                    leading = { IconSquare(Ic.Phone, it) },
                    trailing = { Chevron(it) },
                )
                ListRow(
                    "Pick the backup on a USB drive", actions::pickFile,
                    subtitle = if (picker) "${APP_NAME} asks for the passphrase next" else "This device has no file picker",
                    enabled = picker,
                    leading = { IconSquare(Ic.Usb, it) },
                    trailing = { if (picker) Chevron(it) },
                )
            }
        }
        ImportMethod.Certs -> {
            PanelHeading("Import", "Certificates", "For profiles that keep the certificate and key outside the .ovpn file.")
            Column(Modifier.padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ListRow(
                    "Send the .p12 from your phone", fromPhone,
                    subtitle = "Its password and the profile it is for are chosen there",
                    focusRequester = req["im-entry"],
                    leading = { IconSquare(Ic.Phone, it) },
                    trailing = { Chevron(it) },
                )
                ListRow(
                    "Pick a .p12 file on a USB drive", actions::pickFile,
                    subtitle = if (picker) "Or from Downloads" else "This device has no file picker",
                    enabled = picker,
                    leading = { IconSquare(Ic.Usb, it) },
                    trailing = { if (picker) Chevron(it) },
                )
                ListRow(
                    "Use a certificate installed on this device", {
                        actions.chooseKeyAlias { alias ->
                            if (alias != null) {
                                nav.overlay = Overlay.PickProfile("Use it for which profile?", "The profile signs in with $alias. Its own certificate is kept, unused.", eyebrow = "Import › Certificates") { id ->
                                    actions.setKeyAlias(id, alias)
                                    nav.toast("${state.connection(id)?.name} now uses ${dev.shieldvpn.vpn.Certificates.label(alias)}")
                                }
                            } else {
                                nav.toast(NO_CERT_PICKED)
                            }
                        }
                    },
                    subtitle = "Pick one from Android’s keystore",
                    leading = { IconSquare(Ic.Lock, it) },
                    trailing = { Chevron(it) },
                )
            }
        }
    }
}

/** From your phone: the code is on screen and the TV listens while Import is open. */
@Composable
private fun ColumnScope.PhonePanel(nav: Nav, actions: TunnelActions, req: Requesters, session: PhoneSession?) {
    PanelHeading("Import", "Send it from your phone")
    PhoneBlock(
        session, PhoneBlockSize.Panel, Modifier.padding(top = 22.dp),
        steps = listOf("Scan the code with your phone’s camera", "Pick the file, paste its text or type a link", "Check it here before it is saved"),
    )
    Spacer(Modifier.weight(1f))
    // Something waits on the phone's page; it can be finished on the TV instead.
    val item = nav.importItem
    // A file that arrives takes focus to Continue here, one press from its review.
    LaunchedEffect(item) { if (item != null && nav.overlay == null) req["im-entry"].focusWhenReady() }
    if (item != null) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(TvRadius.Medium.dp)).background(Tv.white(0.05f)).padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                when (item) {
                    is ImportItem.Profile -> "${item.review.name} is on your phone’s page: name it and send it there, or check it here."
                    is ImportItem.Backup -> if (Backup.encrypted(item.bytes)) "A backup is on your phone’s page: type its passphrase there, or here." else "A backup is on your phone’s page: restore it there, or here."
                    is ImportItem.Pkcs12 -> "A certificate is on your phone’s page: type its password there, or here."
                },
                style = TvType.ui(15f, FontWeight.SemiBold),
                modifier = Modifier.weight(1f),
            )
            TvButton("Continue here", {
                nav.importItem = null
                when (item) {
                    is ImportItem.Profile -> actions.openReview(item.review)
                    is ImportItem.Backup -> actions.receive(item.name, item.bytes)
                    is ImportItem.Pkcs12 -> actions.receive(item.name, item.bytes)
                }
            }, small = true, focusRequester = req["im-entry"])
        }
    }
}

/** The one sheet for a link: its address, and a login when the link needs one. */
fun linkSpec(nav: Nav, actions: TunnelActions): FormSpec = FormSpec(
    eyebrow = "Import › From a link",
    title = "Download from a link",
    sub = "The address of the .ovpn file, and a username and password if the link asks for them. They are used for the download and not kept.",
    fields = listOf(
        FormField("url", "Link", nav.linkUrl.ifEmpty { "https://" }, mono = true, max = 2048),
        FormField("luser", "Username", nav.linkUser, optional = true),
        FormField("lpass", "Password", secret = true, optional = true),
    ),
    primary = "Download and review",
    primaryIcon = Ic.Globe,
    busyLabel = "Downloading…",
    phone = "import",
    phoneSection = "link",
) { v, _ -> downloadLink(actions, nav, cleanLink(v["url"].orEmpty()), v["luser"].orEmpty(), v["lpass"].orEmpty()).toForm() }

/** The typed link, without the "https://" the field starts with when a whole address was typed after it. */
private fun cleanLink(url: String): String = url.trim().replace(Regex("^https://(?=[A-Za-z][A-Za-z0-9+.-]*://)"), "")

/** From a link, typed on the TV; the phone's page has a Link tab for typing it there. */
@Composable
private fun ColumnScope.LinkPanel(nav: Nav, actions: TunnelActions, req: Requesters, session: PhoneSession?) {
    PanelHeading("Import", "Download from a link", "${APP_NAME} downloads the file and shows it to you before saving.")
    InputBox(
        "Profile link", nav.linkUrl.ifEmpty { "https://" }, editing = false,
        onClick = { nav.overlay = Overlay.Form(linkSpec(nav, actions)) },
        mono = true,
        modifier = Modifier.padding(top = 22.dp),
        focusRequester = req["im-entry"],
    )
    if (nav.linkUser.isNotEmpty()) {
        Text("With the username ${nav.linkUser}", style = TvType.ui(15f, color = Tv.soft(0.62f)), modifier = Modifier.padding(top = 8.dp, start = 4.dp))
    }
    Spacer(Modifier.weight(1f))
    PhoneBlock(session, PhoneBlockSize.Compact, title = "Or type it on your phone: choose Link there")
}

/** .spin: a small spinning ring. */
@Composable
fun Spinner(focused: Boolean = false, size: Float = 22f) {
    val turn by rememberInfiniteTransition(label = "spin").animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "turn")
    Canvas(Modifier.size(size.dp).graphicsLayer { rotationZ = turn }) {
        val w = 3.dp.toPx()
        drawCircle(if (focused) Tv.dark(0.2f) else Tv.white(0.2f), radius = (this.size.minDimension - w) / 2, style = Stroke(w))
        drawArc(if (focused) Color(0xFF0B6B45) else Tv.Mint, -90f, 90f, false, topLeft = Offset(w / 2, w / 2), size = Size(this.size.width - w, this.size.height - w), style = Stroke(w, cap = StrokeCap.Round))
    }
}

@Composable
private fun Steps(steps: List<String>, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        steps.forEachIndexed { i, s ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(28.dp).clip(CircleShape).background(Tv.white(0.12f)), contentAlignment = Alignment.Center) {
                    Text("${i + 1}", style = TvType.ui(14f, FontWeight.SemiBold))
                }
                Text(s, style = TvType.ui(17f, color = Tv.soft(0.86f)))
            }
        }
    }
}

/** .warn: a tinted line with an icon. */
@Composable
fun Notice(icon: Ic, text: String, amber: Boolean = false, bad: Boolean = false, modifier: Modifier = Modifier) {
    val tint = when {
        bad -> Tv.Red
        amber -> Tv.Amber
        else -> Tv.Mint
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(TvRadius.Medium.dp))
            .background(tint.copy(alpha = 0.13f))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TvIcon(icon, 20f, tint, stroke = 3f)
        Text(text, style = TvType.ui(15f, FontWeight.Medium))
    }
}

@Composable
private fun ProblemPanel(review: ImportReview, nav: Nav, actions: TunnelActions, req: Requesters) {
    // No file was opened at all: this TV has no file picker, or the file could not be read.
    val noFile = review.fileName.isEmpty()
    PanelHeading(
        if (noFile) "Import › On this device" else "Import › ${review.fileName}",
        if (noFile) "No file picker on this device" else "This isn’t an OpenVPN profile",
        review.problem,
    )
    Row(Modifier.padding(top = 26.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (noFile) {
            TvButton("Send it from your phone", {
                actions.cancelReview()
                nav.importMethod = ImportMethod.Phone
                nav.contentFocus = Screen.Import
            }, icon = Ic.Phone, focusRequester = req["rv-back"])
        } else {
            TvButton("Pick another file", {
                actions.cancelReview()
                actions.pickFile()
            }, icon = Ic.File, focusRequester = req["rv-back"])
        }
        TvButton("Cancel", actions::cancelReview, glass = true)
    }
}

/** The file has a client certificate of its own. */
fun reviewHasCert(r: ImportReview): Boolean =
    "<cert>" in r.text || "<pkcs12>" in r.text || r.text.lineSequence().any { it.trim().startsWith("cert ") || it.trim().startsWith("pkcs12 ") }

/** How the reviewed profile signs in, for both the TV and the phone. */
fun reviewSignIn(r: ImportReview): String {
    val cert = reviewHasCert(r)
    return when {
        r.kind == VpnKind.WireGuard -> "Key in the profile"
        r.loginFromPhone && cert -> "Certificate + password · ${r.username}, from your phone"
        r.loginFromPhone -> "Username + password · ${r.username}, from your phone"
        r.needsLogin && cert -> "Certificate + password"
        r.needsLogin -> "Username + password"
        cert -> "Certificate"
        else -> "Not in the file"
    }
}

/**
 * Saves the profile under review and shows it: on Connect with its Connect button focused, or
 * in Profiles while a tunnel is up, so nothing switches away from it. Returns its name.
 */
/**
 * The review is let go on the TV, and that is said there and on the phone that sent it, so
 * nothing goes without a word.
 */
fun dropReview(nav: Nav, actions: TunnelActions, session: PhoneSession?) {
    val r = actions.stateNow().review ?: return
    actions.cancelReview()
    if (r.problem != null) return
    nav.toast(Words.notSaved(r.name))
    // The line under the code shows what the phone did, with a tick: a file let go isn't that.
    session?.tvLine = null
    session?.say(PhoneStatus.error("${r.name} wasn’t saved: it was cancelled on the TV. Send it again to add it."))
    session?.rebuild?.invoke()
}

/** Puts the file under review in place of [into]'s, which keeps its name and settings. */
fun updateImport(nav: Nav, actions: TunnelActions, session: PhoneSession?, into: Connection): Boolean {
    val review = actions.stateNow().review ?: return false
    val drops = review.username.isEmpty() && Control.dropsPassword(into.id, review.text)
    if (!Control.updateProfile(into.id, review)) return false
    actions.cancelReview()
    nav.importItem = null
    session?.tvLine = "Updated ${into.name}"
    session?.holdFor(PhoneServer.KEEP_MS)
    val dropped = if (drops) " The new file trusts other servers than the old one, so its saved password is removed. Type it again to connect." else ""
    session?.say(PhoneStatus.done("${into.name} is updated on the TV with the new file.$dropped"))
    session?.rebuild?.invoke()
    nav.toast(if (drops) "Updated ${into.name}. Type its password again: the new file trusts other servers." else "Updated ${into.name}")
    nav.go(Screen.Profiles, focusContent = false)
    nav.profFocus = into.id
    nav.moveStackFocus("pl-${into.id}")
    return true
}

fun saveImport(nav: Nav, actions: TunnelActions, session: PhoneSession?): String? {
    val before = actions.stateNow()
    val name = before.review?.name ?: return null
    val live = before.status.isActive || before.pausedUntil != null
    val id = actions.saveReview() ?: return null
    nav.importSaved = name
    nav.importItem = null
    // The TV leaves Import; the phone's page stays and says the profile was saved, not that the TV moved on.
    session?.tvLine = "Saved $name"
    session?.holdFor(PhoneServer.KEEP_MS)
    session?.say(null)
    session?.rebuild?.invoke()
    if (live) {
        nav.go(Screen.Profiles, focusContent = false)
        nav.profFocus = id
        nav.moveStackFocus("pl-$id")
    } else {
        nav.go(Screen.Connect)
    }
    nav.toast("Saved $name")
    return name
}

@Composable
private fun ColumnScope.ReviewPanel(state: VpnUiState, review: ImportReview, nav: Nav, actions: TunnelActions, req: Requesters) {
    // Pasted text has no file: the eyebrow doesn't make one up.
    PanelHeading("Import › Review · " + if (review.fileName == "pasted.ovpn") "pasted text" else review.fileName, review.name)
    Box(Modifier.padding(top = 12.dp)) {
        DetailGrid(listOf("Server" to Words.serverLine(review.host, review.port, review.protocol), "Sign-in" to reviewSignIn(review)), columns = 1)
    }
    review.note?.let { Text("$it.", style = TvType.ui(15f, color = Tv.soft(0.62f)), modifier = Modifier.padding(top = 10.dp, start = 4.dp)) }
    val wireGuard = review.kind == VpnKind.WireGuard
    // A file that can't sign in anywhere gets no tick: neither a certificate nor a login is in it.
    val canSignIn = wireGuard || review.needsLogin || reviewHasCert(review)
    when {
        !canSignIn -> Notice(
            Ic.Bang,
            "This profile has no way to sign in: no certificate and no password. After saving, send its .p12 from your phone, or ask for a file with a login." +
                (review.warnings.takeIf { it.isNotEmpty() }?.joinToString(" ", prefix = " ") ?: ""),
            amber = true, modifier = Modifier.padding(top = 14.dp),
        )
        review.warnings.isNotEmpty() -> Notice(Ic.Bang, review.warnings.joinToString(" "), amber = true, modifier = Modifier.padding(top = 14.dp))
        else -> {
            val note = when {
                wireGuard -> " The private key is saved, encrypted, and taken out of the file."
                review.needsLogin && review.username.isEmpty() -> " Asks for a username and password when you connect."
                review.loginFromPhone -> " The login from your phone is saved with it, encrypted."
                review.needsLogin -> " The login in the file is saved, encrypted, and taken out of the file."
                else -> ""
            }
            Notice(Ic.Check, "Looks good.$note", modifier = Modifier.padding(top = 14.dp))
        }
    }
    // Many servers also want a client certificate; say where to add one if the file has none.
    if (!wireGuard && canSignIn && !reviewHasCert(review)) {
        Text(
            "There is no client certificate in this file. If the server asks for one, send the .p12 from your phone, or use Import › Certificates.",
            style = TvType.ui(15f, color = Tv.soft(0.62f)),
            modifier = Modifier.padding(top = 10.dp, start = 4.dp),
        )
    }
    Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ListRow(
            "Name",
            {
                nav.overlay = Overlay.Form(
                    FormSpec(
                        "Import › Review", "Name", "${Words.NAME_HELP}.",
                        listOf(FormField("name", "Name", review.name)), primary = "Save", phone = "import", phoneSection = "review",
                    ) { v, _ ->
                        val name = v["name"].orEmpty().trim()
                        if (name.isEmpty()) {
                            FormResult.Problems("name", "Type a name")
                        } else {
                            actions.renameReview(name)
                            nav.flash("rv-name")
                            FormResult.Done
                        }
                    },
                )
            },
            subtitle = if (state.connections.any { it.name == review.name }) "A profile with this name exists. Saving adds another" else "You can change it later",
            flash = nav.flashKey == "rv-name",
            trailing = { ValueChip(review.name, it) },
        )
        ListRow(
            "The file itself", {
                nav.overlay = Overlay.File("Import › ${review.fileName}", review.name, ServerStore.hideSecrets(review.text))
            },
            subtitle = if (review.kind == VpnKind.WireGuard) "Addresses, peers, endpoint; the key stays hidden" else "Raw config, ciphers, certificates",
            leading = { IconSquare(Ic.File, it) },
            trailing = { Chevron(it) },
        )
    }
    Spacer(Modifier.weight(1f))
    Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val session = phoneSession("import")
        // A file for a server that has a profile already updates that one, unless it should be a second.
        val same = remember(review) { Control.sameServer(review) }
        if (same != null) {
            TvButton("Update ${same.name}", { nav.guard(state) { updateImport(nav, actions, session, same) } }, icon = Ic.Swap, focusRequester = req["rv-save"])
            TvButton("Add as a new one", { nav.guard(state) { saveImport(nav, actions, session) } }, glass = true)
        } else {
            TvButton("Save", { nav.guard(state) { saveImport(nav, actions, session) } }, icon = Ic.Check, focusRequester = req["rv-save"])
        }
        TvButton("Cancel", {
            dropReview(nav, actions, session)
            nav.contentFocus = Screen.Import
        }, glass = true)
    }
}

// ---- The phone's Import page ---------------------------------------------------------

// A picture too: a QR code from a WireGuard server's app, photographed or saved as a screenshot.
private val ACCEPT = ".ovpn,.conf,.zip,.p12,.pfx,.${Backup.EXTENSION},text/plain,application/x-pkcs12,application/zip,application/octet-stream,image/*"

private fun kindOf(item: ImportItem): String = when (item) {
    is ImportItem.Profile -> if (item.review.kind == VpnKind.WireGuard) "WireGuard profile" else "OpenVPN profile"
    is ImportItem.Backup -> "Backup"
    is ImportItem.Pkcs12 -> "Certificate"
}

/**
 * Import's page on the phone: a file, pasted text or a link; then what that turned out to be
 * asks for what it needs (a name and login, a backup's passphrase, a certificate's password and
 * profile); and the review, saved on either side.
 */
fun importForm(state: VpnUiState, nav: Nav): PhoneForm {
    val review = state.review?.takeIf { it.problem == null }
    if (review != null) {
        return PhoneForm(
            "Import", "Check it, then save it", "The TV shows the same. Save it here or on the TV.",
            listOf(
                PhoneSection(
                    "review", review.fileName.ifEmpty { "The profile" },
                    fields = listOf(
                        PhoneField.Info(
                            "facts",
                            rows = listOf("Server" to Words.serverLine(review.host, review.port, review.protocol), "Sign-in" to reviewSignIn(review)),
                            text = (listOfNotNull(review.note?.let { "$it." }) + review.warnings).joinToString(" ").ifEmpty { null },
                            tone = if (review.warnings.isEmpty()) null else "warn",
                        ),
                        PhoneField.Text(
                            "name", "Name", review.name, required = true, mirror = true,
                            help = if (state.connections.any { it.name == review.name }) "A profile with this name is on the TV already. Saving adds another." else Words.NAME_HELP_PHONE,
                        ),
                    ),
                    // The same choices as the TV: a file for a server that has a profile already updates it.
                    actions = Control.sameServer(review)?.let { same ->
                        listOf(
                            PhoneAction("update", "Update ${same.name}", primary = true),
                            PhoneAction("save", "Add as a new one", needs = listOf("name")),
                            PhoneAction("cancel", "Don’t save it"),
                        )
                    } ?: listOf(PhoneAction("save", "Save on the TV", primary = true, needs = listOf("name")), PhoneAction("cancel", "Don’t save it")),
                ),
            ),
        )
    }
    val item = nav.importItem
    val saved = nav.importSaved
    if (saved != null && item == null) {
        return PhoneForm(
            "Import", "Added to the TV", "It is saved on the TV.",
            listOf(
                PhoneSection(
                    "saved",
                    fields = listOf(PhoneField.Info("done", text = "Saved on the TV as $saved. Connect from the TV.", tone = "ok")),
                    actions = listOf(PhoneAction("again", "Add another")),
                ),
            ),
        )
    }
    val pasted = item is ImportItem.Profile && item.pasted
    val sections = mutableListOf(
        PhoneSection("how", fields = listOf(PhoneField.Choice("mode", null, listOf("file" to "File", "paste" to "Paste", "link" to "Link"), if (pasted) "paste" else "file"))),
        PhoneSection(
            "file", showWhen = "mode" to "file",
            fields = listOf(PhoneField.Upload("file", "Choose a file", "A .ovpn or .conf, a .zip, a QR code picture, a .p12 or .pfx, or a backup", ACCEPT, item?.takeIf { !pasted }?.let { Staged(it.name, it.size, kindOf(it)) })),
        ),
        PhoneSection(
            "paste", showWhen = "mode" to "paste",
            fields = listOf(PhoneField.Paste("paste", "The profile’s text", "client\ndev tun\nremote vpn.example.net 1194", item?.takeIf { pasted }?.let { Staged(it.name, it.size, kindOf(it)) })),
        ),
        PhoneSection(
            "link", showWhen = "mode" to "link", note = "The TV downloads it and shows it to you before saving.",
            fields = listOf(
                PhoneField.Text("url", "Link", "", input = "url", max = 2048, placeholder = "https://vpn.example.net/home.ovpn", required = true, mirror = true),
                PhoneField.Text("luser", "Username, if the link asks for one", mirror = true),
                PhoneField.Text("lpass", "Password, if the link asks for one", secret = true, help = "Used once for the download and not kept.", mirror = true),
            ),
            actions = listOf(PhoneAction("download", "Download on the TV", primary = true, needs = listOf("url"))),
        ),
    )
    when (item) {
        is ImportItem.Profile -> {
            val asks = item.review.needsLogin && item.review.username.isEmpty()
            sections += PhoneSection(
                "profile", if (item.pasted) "The pasted profile" else item.name,
                fields = listOfNotNull(
                    PhoneField.Text("name", "Name", item.review.name, required = true, mirror = true, help = Words.NAME_HELP_PHONE),
                    if (asks) PhoneField.Text("user", "Username") else null,
                    if (asks) {
                        PhoneField.Text(
                            "pw", "Password", secret = true,
                            help = "It asks for a username and password. They are saved with it on the TV, encrypted. Leave both empty to type them on the TV when you connect.",
                        )
                    } else {
                        null
                    },
                ),
                actions = listOf(PhoneAction("send", "Send to the TV", primary = true, needs = listOf("name"))),
            )
        }
        is ImportItem.Backup -> {
            // A backup made without a password restores without one.
            val locked = Backup.encrypted(item.bytes)
            sections += PhoneSection(
                "backup", "Restore this backup", note = "It holds the profiles, saved passwords and settings of the device it came from.",
                fields = listOfNotNull(
                    if (locked) PhoneField.Text("passphrase", "Its passphrase", secret = true, required = true) else null,
                    PhoneField.Choice("same", "When a profile with the same name is on the TV", listOf("both" to "Keep both", "replace" to "Replace it"), "both"),
                ),
                actions = listOf(PhoneAction("restore", "Restore on the TV", primary = true, needs = if (locked) listOf("passphrase") else emptyList())),
            )
        }
        is ImportItem.Pkcs12 -> sections += if (state.connections.isEmpty()) {
            PhoneSection("p12", "Add this certificate", fields = listOf(PhoneField.Info("none", text = "There is no profile on the TV yet. Add the profile first, then its certificate.", tone = "warn")))
        } else {
            PhoneSection("p12", "Add this certificate", fields = p12Fields(state, null), actions = listOf(PhoneAction("attach", "Add the certificate", primary = true, needs = listOf("p12pass"))))
        }
        null -> Unit
    }
    return PhoneForm("Import", "Add to the TV", "Send a profile, a backup or a certificate. The TV checks it before anything is saved.", sections)
}

/** Import's phone session, for the Import screen and for Connect when there is no profile yet. */
@Composable
fun rememberImportSession(state: VpnUiState, nav: Nav, actions: TunnelActions): PhoneSession? = rememberPhoneSession(
    "import", state, actions,
    form = { s -> importForm(s, nav) },
    // "Add another" goes on working after a save has taken the TV to Connect.
    keepsWorking = true,
    onUpload = { field, name, bytes -> importUpload(this, nav, actions, field, name, bytes) },
    onAction = { action, v -> importAction(this, nav, actions, action, v) },
)

private fun importUpload(session: PhoneSession, nav: Nav, actions: TunnelActions, field: String, name: String, bytes: ByteArray): PhoneReply {
    val label = if (field == "paste") "pasted text" else name
    return when (val found = actions.inspect(name, bytes)) {
        is Inspected.Problem -> PhoneReply.error(field, found.message)
        is Inspected.Profile -> {
            nav.importItem = ImportItem.Profile(name, bytes.size, found.review, field == "paste")
            nav.importSaved = null
            session.tvLine = "Received $label"
            PhoneReply()
        }
        Inspected.Backup -> {
            nav.importItem = ImportItem.Backup(name, bytes)
            nav.importSaved = null
            session.tvLine = "Received a backup: $name"
            PhoneReply()
        }
        Inspected.Pkcs12 -> {
            nav.importItem = ImportItem.Pkcs12(name, bytes)
            nav.importSaved = null
            session.tvLine = "Received a certificate: $name"
            PhoneReply()
        }
    }
}

private suspend fun importAction(session: PhoneSession, nav: Nav, actions: TunnelActions, action: String, v: Map<String, String>): PhoneReply {
    val state = actions.stateNow()
    val isLocked = locked(state, nav)
    return when (action) {
        "send" -> {
            val item = nav.importItem as? ImportItem.Profile ?: return PhoneReply.error(null, "The TV doesn’t have the file any more. Send it again.")
            val name = v["name"].orEmpty().trim()
            val user = v["user"].orEmpty().trim()
            val pw = v["pw"].orEmpty()
            when {
                name.isEmpty() -> PhoneReply.error("name", "Type a name")
                user.isEmpty() != pw.isEmpty() -> PhoneReply.error(if (user.isEmpty()) "user" else "pw", "Type both the username and the password, or leave both empty.")
                else -> {
                    var review = item.review.copy(name = name)
                    if (user.isNotEmpty()) review = review.copy(username = user, password = pw, loginFromPhone = true)
                    nav.importItem = null
                    actions.openReview(review)
                    session.tvLine = "${review.name} is on the TV to check"
                    PhoneReply(PhoneStatus.waiting("On the TV now. Check it, then save it here or on the TV."), clear = listOf("pw"))
                }
            }
        }
        "download" -> {
            val outcome = downloadLink(actions, nav, v["url"].orEmpty(), v["luser"].orEmpty(), v["lpass"].orEmpty())
            if (outcome is Outcome.Done) session.saved("link")
            if (outcome is Outcome.Done) PhoneReply(PhoneStatus.waiting(outcome.text), clear = listOf("lpass")) else outcome.toReply()
        }
        "save" -> {
            val review = state.review ?: return PhoneReply.error(null, "There is nothing on the TV to save. Send it again.")
            val name = v["name"].orEmpty().trim()
            when {
                name.isEmpty() -> PhoneReply.error("name", "Type a name")
                isLocked -> PhoneReply.error(null, LOCKED_ON_TV)
                else -> {
                    if (name != review.name) actions.renameReview(name)
                    saveImport(nav, actions, session)
                    session.saved("review")
                    PhoneReply(holdMs = PhoneServer.KEEP_MS)
                }
            }
        }
        "update" -> {
            val review = state.review ?: return PhoneReply.error(null, "There is nothing on the TV to save. Send it again.")
            val same = Control.sameServer(review) ?: return PhoneReply.error(null, "That profile is gone. Save it as a new one.")
            when {
                isLocked -> PhoneReply.error(null, LOCKED_ON_TV)
                updateImport(nav, actions, session, same) -> {
                    session.saved("review")
                    PhoneReply(holdMs = PhoneServer.KEEP_MS)
                }
                else -> PhoneReply.error(null, "The TV couldn’t update ${same.name}.")
            }
        }
        "cancel" -> {
            state.review?.let { nav.toast(Words.notSaved(it.name)) }
            actions.cancelReview()
            PhoneReply()
        }
        "restore" -> {
            val item = nav.importItem as? ImportItem.Backup ?: return PhoneReply.error(null, "The TV doesn’t have the backup any more. Send it again.")
            if (isLocked) return PhoneReply.error(null, LOCKED_ON_TV)
            val outcome = restoreBackup(actions, nav, item.bytes, v["passphrase"].orEmpty(), v["same"] == "replace", "passphrase")
            if (outcome is Outcome.Done) {
                nav.importItem = null
                session.tvLine = outcome.text.trimEnd('.')
            }
            outcome.toReply(clear = listOf("passphrase"), holdMs = PhoneServer.KEEP_MS)
        }
        "attach" -> {
            val item = nav.importItem as? ImportItem.Pkcs12 ?: return PhoneReply.error(null, "The TV doesn’t have the certificate any more. Send it again.")
            if (isLocked) return PhoneReply.error(null, LOCKED_ON_TV)
            val outcome = attachCertificate(state, actions, nav, item.bytes, v, null)
            if (outcome is Outcome.Done) {
                nav.importItem = null
                session.tvLine = "Added the certificate to ${state.connection(v["target"])?.name ?: "the profile"}"
            }
            outcome.toReply(clear = listOf("p12pass"), holdMs = PhoneServer.KEEP_MS)
        }
        "again" -> {
            nav.importSaved = null
            PhoneReply()
        }
        else -> PhoneReply()
    }
}

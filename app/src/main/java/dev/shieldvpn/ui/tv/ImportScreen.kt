package dev.shieldvpn.ui.tv

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import dev.shieldvpn.APP_NAME
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.shieldvpn.vpn.ImportReview
import dev.shieldvpn.vpn.ServerStore
import dev.shieldvpn.vpn.VpnUiState
import dev.shieldvpn.vpn.isActive

enum class ImportMethod(val icon: Ic, val title: String, val sub: String, val more: Boolean = false) {
    Phone(Ic.Phone, "From your phone", "Scan a code, pick the file there"),
    Files(Ic.Usb, "USB drive or Downloads", "Files on this device"),
    Link(Ic.Link, "From a link", "An HTTPS address"),
    Restore(Ic.Archive, "Restore a backup", "Everything from another device"),
    Certs(Ic.Cert, "Certificates", ".p12 file or the device keystore", more = true),
    Paste(Ic.Paste, "Paste config text", "Type or paste it on your phone", more = true),
}

/**
 * Where focus starts on the review: Back on a file that can't be used; Save while a tunnel is up,
 * so one OK doesn't switch away from it; otherwise Save and connect.
 */
private fun reviewFocus(state: VpnUiState, review: ImportReview): String = when {
    review.problem != null -> "rv-back"
    state.status.isActive -> "rv-save"
    else -> "rv-connect"
}

/** Import: how the file gets here on the left, and what happens next on the right. Every file is reviewed first. */
@Composable
fun ImportScreen(state: VpnUiState, nav: Nav, actions: TunnelActions, connect: (String) -> Unit) {
    val req = remember { Requesters() }
    SideEffect {
        val review = state.review
        nav.entry = req[if (review == null) "m-${nav.importMethod.name}" else reviewFocus(state, review)]
    }
    LaunchedEffect(nav.contentFocus) {
        if (nav.contentFocus == Screen.Import) {
            val review = state.review
            val key = if (review == null) "m-${nav.importMethod.name}" else reviewFocus(state, review)
            req[key].focusWhenReady()
            nav.contentFocus = null
        }
    }
    LaunchedEffect(state.review) {
        val review = state.review ?: return@LaunchedEffect
        req[reviewFocus(state, review)].focusWhenReady()
    }
    // The phone session belongs to Import, not to its panel: it keeps its code while a file is
    // reviewed, so the phone's page still works after Save or Cancel and can send another.
    val phonePage = remember {
        dev.shieldvpn.phone.PhoneBridge.Page.Upload(
            "Send a profile to the TV", "Pick the .ovpn file, or paste its text. The TV shows it to you before it is saved.",
            ".ovpn,.conf,.p12,.pfx,.${dev.shieldvpn.vpn.Backup.EXTENSION},text/plain,application/x-pkcs12,application/octet-stream", paste = true, login = true,
        )
    }
    val phoneBridge = rememberPhoneBridge(ImportMethod.Phone, if (nav.importMethod == ImportMethod.Phone) phonePage else null) { got ->
        actions.receive(got.name, got.bytes, got.username, got.password)
    }

    Box(Modifier.fillMaxSize()) {
        Glass(Modifier.padding(start = 64.dp, top = 124.dp, bottom = 36.dp).width(400.dp)) {
            Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 22.dp)) {
                Text("Add a profile", style = TvType.display(30f), modifier = Modifier.padding(horizontal = 8.dp))
                Text("A profile, a backup or a certificate.", style = TvType.ui(15f, color = Tv.soft(0.65f)), modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 14.dp))
                // A list, so the extra ways scroll into view when More ways opens them.
                val list = rememberLazyListState()
                LazyColumn(
                    state = list,
                    modifier = Modifier
                        .weight(1f)
                        .bleed(12.dp)
                        .scrollFade(list)
                        .enterAt {
                            if (nav.importMethod.more && !nav.importMore) req["m-more"] else req["m-${nav.importMethod.name}"]
                        },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ImportMethod.entries.filter { !it.more }.forEach { m -> item(m.name) { MethodRow(m, state, nav, actions, req) } }
                    item("more") {
                        ListRow(
                            "More ways", { nav.importMore = !nav.importMore },
                            subtitle = "Certificates, pasted text",
                            focusRequester = req["m-more"],
                            trailing = { focused ->
                                val turn by animateFloatAsState(if (nav.importMore) 90f else 0f, tween(200), label = "chev")
                                Box(Modifier.graphicsLayer { rotationZ = turn }) { TvIcon(Ic.Chev, 20f, if (focused) Tv.OnFocus else Tv.Ink, stroke = 2.4f) }
                            },
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                    if (nav.importMore) {
                        ImportMethod.entries.filter { it.more }.forEach { m ->
                            item(m.name) { Box(Modifier.animateItem()) { MethodRow(m, state, nav, actions, req) } }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text("Every file is checked before it is saved.", style = TvType.ui(14f, color = Tv.soft(0.55f)), modifier = Modifier.padding(horizontal = 8.dp))
            }
        }
        val review = state.review
        Glass(
            Modifier
                .padding(start = 488.dp, end = 64.dp, top = 124.dp, bottom = 36.dp)
                .fillMaxSize()
                .enterAt { req[if (review == null) "im-entry" else reviewFocus(state, review)] },
        ) {
            Column(Modifier.fillMaxSize().padding(horizontal = 34.dp, vertical = 30.dp)) {
                when {
                    review != null && review.problem != null -> ProblemPanel(review, nav, actions, req)
                    review != null -> ReviewPanel(state, review, nav, actions, req, connect)
                    else -> MethodPanel(nav.importMethod, state, nav, actions, req, phoneBridge)
                }
            }
        }
    }
}

@Composable
private fun MethodRow(m: ImportMethod, state: VpnUiState, nav: Nav, actions: TunnelActions, req: Requesters) {
    ListRow(
        m.title,
        {
            if (m == ImportMethod.Files) actions.pickFile() else req["im-entry"].tryFocus()
        },
        subtitle = m.sub,
        kind = RowKind.Method,
        selected = nav.importMethod == m,
        focusRequester = req["m-${m.name}"],
        onFocus = {
            if (nav.importMethod != m) {
                nav.importMethod = m
                if (state.review != null) actions.cancelReview()
            }
        },
        leading = { IconSquare(m.icon, it, size = 44f, radius = 13f) },
    )
}

@Composable
private fun ColumnScope.MethodPanel(m: ImportMethod, state: VpnUiState, nav: Nav, actions: TunnelActions, req: Requesters, phone: dev.shieldvpn.phone.PhoneBridge?) {
    when (m) {
        ImportMethod.Files -> {
            PanelHeading("On this device", "Pick a file", "Android’s file picker opens. Choose the .ovpn or .conf file from Downloads or a USB drive.")
            Row(Modifier.padding(top = 26.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvButton("Choose a file", actions::pickFile, icon = Ic.File, focusRequester = req["im-entry"])
            }
            Steps(
                listOf(
                    "Save the .ovpn file in Downloads, or on a USB drive in this device",
                    "Pick it in the file picker",
                    "Check it here before it is saved",
                ),
                Modifier.padding(top = 28.dp),
            )
        }
        ImportMethod.Phone -> PhonePanel(req, phone)
        ImportMethod.Link -> LinkPanel(nav, actions, req)
        ImportMethod.Restore -> {
            PanelHeading("Moving to a new device", "Restore a backup", "A backup holds every profile, saved password and setting, encrypted with the passphrase you chose when you made it.")
            Column(Modifier.padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ListRow(
                    "Send the backup from your phone", { nav.overlay = Overlay.Phone(PhoneKind.Restore) },
                    subtitle = "Scan a code and pick the .${dev.shieldvpn.vpn.Backup.EXTENSION} file",
                    focusRequester = req["im-entry"],
                    leading = { IconSquare(Ic.Phone, it) },
                    trailing = { TvIcon(Ic.Chev, 18f, if (it) Tv.OnFocus else Tv.Ink, stroke = 2.4f) },
                )
                ListRow(
                    "Pick the backup on a USB drive", actions::pickFile,
                    subtitle = "${APP_NAME} asks for the passphrase next",
                    leading = { IconSquare(Ic.Usb, it) },
                    trailing = { TvIcon(Ic.Chev, 18f, if (it) Tv.OnFocus else Tv.Ink, stroke = 2.4f) },
                )
            }
        }
        ImportMethod.Certs -> {
            PanelHeading("More ways", "Certificates", "For profiles that keep the certificate and key outside the .ovpn file.")
            Column(Modifier.padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ListRow(
                    "Send a .p12 file from your phone", { nav.overlay = Overlay.Phone(PhoneKind.P12) },
                    subtitle = "Then enter its password and pick the profile",
                    focusRequester = req["im-entry"],
                    leading = { IconSquare(Ic.Phone, it) },
                    trailing = { TvIcon(Ic.Chev, 18f, if (it) Tv.OnFocus else Tv.Ink, stroke = 2.4f) },
                )
                ListRow(
                    "Pick a .p12 file on a USB drive", actions::pickFile,
                    subtitle = "Or from Downloads",
                    leading = { IconSquare(Ic.Usb, it) },
                    trailing = { TvIcon(Ic.Chev, 18f, if (it) Tv.OnFocus else Tv.Ink, stroke = 2.4f) },
                )
                ListRow(
                    "Use a certificate installed on this device", {
                        actions.chooseKeyAlias { alias ->
                            if (alias != null) {
                                nav.overlay = Overlay.PickProfile("Use it for which profile?", "The profile signs in with $alias. Its own certificate is kept, unused.") { id ->
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
                    trailing = { TvIcon(Ic.Chev, 18f, if (it) Tv.OnFocus else Tv.Ink, stroke = 2.4f) },
                )
            }
        }
        ImportMethod.Paste -> {
            PanelHeading("More ways", "Paste config text", "Useful when your provider shows the config as text instead of a file.")
            Column(Modifier.padding(top = 20.dp)) {
                ListRow(
                    "Paste it on your phone", { nav.overlay = Overlay.Phone(PhoneKind.Paste) },
                    subtitle = "Scan a code, paste the text, send",
                    focusRequester = req["im-entry"],
                    leading = { IconSquare(Ic.Phone, it) },
                    trailing = { TvIcon(Ic.Chev, 18f, if (it) Tv.OnFocus else Tv.Ink, stroke = 2.4f) },
                )
            }
        }
    }
}

/** From your phone: the code is on screen and the TV listens while this panel is open. */
@Composable
private fun ColumnScope.PhonePanel(req: Requesters, bridge: dev.shieldvpn.phone.PhoneBridge?) {
    PanelHeading("From your phone", "Send it from your phone")
    Box(Modifier.padding(top = 22.dp)) {
        PhoneCode(
            bridge, 196f,
            steps = listOf("Scan the code with your phone’s camera", "Pick the .ovpn file, or paste its text", "Check it here before it is saved"),
        )
    }
    Spacer(Modifier.weight(1f))
    ListRow(
        "Waiting for your phone…", {},
        subtitle = "The file appears here as soon as it is sent",
        focusRequester = req["im-entry"],
        leading = { Spinner(it) },
    )
}

/** From a link: an address, and a login when the link needs one. */
@Composable
private fun ColumnScope.LinkPanel(nav: Nav, actions: TunnelActions, req: Requesters) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    PanelHeading("From a link", "Download from a link", "${APP_NAME} downloads the file and shows it to you before saving.")
    InputBox(
        "Profile link", nav.linkUrl.ifEmpty { "https://" }, editing = false,
        onClick = { nav.overlay = Overlay.Text(TextKind.LinkUrl, nav.linkUrl.ifEmpty { "https://" }) { nav.linkUrl = it } },
        mono = true,
        modifier = Modifier.padding(top = 22.dp),
        focusRequester = req["im-entry"],
    )
    ListRow(
        "The link needs a username and password", { nav.linkLogin = !nav.linkLogin },
        subtitle = "${APP_NAME} sends them with the download, and does not keep them",
        modifier = Modifier.padding(top = 14.dp),
        trailing = { TvSwitch(nav.linkLogin, it) },
    )
    if (nav.linkLogin) {
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            InputBox("Username", nav.linkUser, editing = false, onClick = {
                nav.overlay = Overlay.Text(TextKind.LinkUser, nav.linkUser) { nav.linkUser = it }
            }, modifier = Modifier.weight(1f))
            InputBox("Password", nav.linkPass, editing = false, secret = true, onClick = {
                nav.overlay = Overlay.Text(TextKind.LinkPass, nav.linkPass) { nav.linkPass = it }
            }, modifier = Modifier.weight(1f))
        }
    }
    Spacer(Modifier.weight(1f))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TvButton(if (busy) "Downloading…" else "Download and review", {
            if (!busy) {
                busy = true
                scope.launch {
                    val problem = actions.download(nav.linkUrl, nav.linkUser.takeIf { nav.linkLogin }, nav.linkPass.takeIf { nav.linkLogin })
                    if (problem != null) nav.toast(problem) else nav.linkPass = ""
                    busy = false
                }
            }
        }, icon = Ic.Globe)
    }
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
                    Text("${i + 1}", style = TvType.ui(14f, FontWeight.ExtraBold))
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
            .clip(RoundedCornerShape(16.dp))
            .background(tint.copy(alpha = 0.13f))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TvIcon(icon, 20f, tint, stroke = 3f)
        Text(text, style = TvType.ui(15.5f, FontWeight.SemiBold))
    }
}

@Composable
private fun ProblemPanel(review: ImportReview, nav: Nav, actions: TunnelActions, req: Requesters) {
    // No file was opened at all: this TV has no file picker, or the file could not be read.
    val noFile = review.fileName.isEmpty()
    PanelHeading(
        if (noFile) "On this device" else "Can’t import ${review.fileName}",
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
            }, icon = Ic.Back, focusRequester = req["rv-back"])
        }
        TvButton("Cancel", actions::cancelReview, glass = true)
    }
}

@Composable
private fun ColumnScope.ReviewPanel(
    state: VpnUiState,
    review: ImportReview,
    nav: Nav,
    actions: TunnelActions,
    req: Requesters,
    connect: (String) -> Unit,
) {
    val hasClientCert = "<cert>" in review.text || "<pkcs12>" in review.text ||
        review.text.lineSequence().any { it.trim().startsWith("cert ") || it.trim().startsWith("pkcs12 ") }
    Text("REVIEW · ${review.fileName}".uppercase(), style = TvType.eyebrow(13.5f, 0.55f), maxLines = 1)
    Column(Modifier.padding(top = 12.dp)) {
        Text(review.name, style = TvType.display(34f), maxLines = 1)
        Spacer(Modifier.size(12.dp))
        DetailGrid(
            listOf(
                "Server" to "${review.host} · ${review.protocol.label} ${review.port}",
                "Sign-in" to when {
                    review.loginFromPhone && hasClientCert -> "Certificate + password · ${review.username}, from your phone"
                    review.loginFromPhone -> "Username + password · ${review.username}, from your phone"
                    review.needsLogin && hasClientCert -> "Certificate + password"
                    review.needsLogin -> "Username + password"
                    hasClientCert -> "Certificate"
                    else -> "Not in the file"
                },
            ),
            columns = 1,
        )
    }
    if (review.warnings.isNotEmpty()) {
        Notice(Ic.Bang, review.warnings.joinToString(" "), amber = true, modifier = Modifier.padding(top = 14.dp))
    } else {
        val note = when {
            review.needsLogin && review.username.isEmpty() -> " Asks for a username and password when you connect."
            review.loginFromPhone -> " The login from your phone is saved with it, encrypted."
            review.needsLogin -> " The login in the file is saved, encrypted, and taken out of the file."
            else -> ""
        }
        Notice(Ic.Check, "Looks good.$note", modifier = Modifier.padding(top = 14.dp))
    }
    // Many servers also want a client certificate; say where to add one if the file has none.
    if (!hasClientCert) {
        Text(
            "There is no client certificate in this file. If the server asks for one, add it in More ways › Certificates.",
            style = TvType.ui(15f, color = Tv.soft(0.62f)),
            modifier = Modifier.padding(top = 10.dp, start = 4.dp),
        )
    }
    Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ListRow(
            "Name", { nav.overlay = Overlay.Text(TextKind.ReviewName, review.name) { actions.renameReview(it) } },
            subtitle = if (state.connections.any { it.name == review.name }) "A profile with this name exists. Saving adds another" else "You can change it later",
            trailing = { ValueChip(review.name, it) },
        )
        ListRow(
            "The file itself", {
                nav.overlay = Overlay.File(review.fileName, review.name, ServerStore.hideSecrets(review.text))
            },
            subtitle = "Raw config, ciphers, certificates",
            trailing = { ValueChip("View", it) },
        )
    }
    Spacer(Modifier.weight(1f))
    Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TvButton("Save and connect", {
            nav.guard(state) {
                val id = actions.saveReview()
                if (id != null) {
                    nav.go(Screen.Connect)
                    nav.toast("Saved ${review.name}")
                    connect(id)
                }
            }
        }, icon = Ic.Power, focusRequester = req["rv-connect"])
        TvButton("Save", {
            nav.guard(state) {
                val id = actions.saveReview()
                if (id != null) {
                    nav.go(Screen.Profiles, focusContent = false)
                    nav.profFocus = id
                    nav.moveStackFocus("pl-$id")
                    nav.toast("Saved ${review.name}")
                }
            }
        }, glass = true, focusRequester = req["rv-save"])
        TvButton("Cancel", {
            actions.cancelReview()
            nav.contentFocus = Screen.Import
        }, glass = true)
    }
}

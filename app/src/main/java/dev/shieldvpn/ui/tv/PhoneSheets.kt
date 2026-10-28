package dev.shieldvpn.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.shieldvpn.APP_NAME
import dev.shieldvpn.control.PinTries
import dev.shieldvpn.phone.PhoneField
import dev.shieldvpn.phone.PhoneOption
import dev.shieldvpn.vpn.Backup
import dev.shieldvpn.vpn.Certificates
import dev.shieldvpn.vpn.Connection
import dev.shieldvpn.vpn.VpnUiState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Android's chooser returns no certificate when none are installed, or when it is closed. */
const val NO_CERT_PICKED = "No certificate picked. To add one, send its .p12 file and keep its key on this device"

/** A screen's phone code, big, for a task done on the phone: back up, edit a profile, send a file. */
@Composable
fun PhoneSheet(req: Requesters, o: Overlay.Phone, close: () -> Unit) {
    val session = phoneSession(o.key)
    DisposableEffect(session, o.section) {
        session?.point(o.section)
        onDispose { if (session?.focus == o.section) session?.point(null) }
    }
    // As wide as the code and its steps, so no half of the sheet stays empty.
    Sheet(TvSize.SheetMedium) {
        PanelHeading(o.eyebrow, o.title, o.lede, titleSize = 32f)
        Box(Modifier.padding(top = 24.dp)) { PhoneBlock(session, PhoneBlockSize.Panel, steps = o.steps) }
        Row(Modifier.padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvButton("Done", close, icon = Ic.Check, focusRequester = req["ov-first"])
            o.extra?.let { (label, run) -> TvButton(label, run, glass = true) }
        }
    }
}

/** Asks for the PIN, or sets a new one with a second entry to confirm. What is wrong shows under the dots. */
@Composable
fun PinSheet(nav: Nav, actions: TunnelActions, req: Requesters, o: Overlay.Pin, close: () -> Unit) {
    var digits by remember { mutableStateOf("") }
    var first by remember { mutableStateOf<String?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var wait by remember { mutableLongStateOf(PinTries.secondsLeft()) }
    // Counts down while a wait is on, and then lets the next try through.
    LaunchedEffect(wait > 0) {
        while (wait > 0) {
            problem = "Too many wrong PINs. Try again in $wait s"
            delay(1000)
            wait = PinTries.secondsLeft()
        }
        if (problem?.startsWith("Too many") == true) problem = null
    }
    val title = when {
        !o.setting -> "Type your PIN"
        first != null -> "Type it again"
        o.mode == null -> "Choose a new PIN"
        else -> "Choose a PIN"
    }
    val submit: () -> Unit = {
        problem = null
        when {
            !o.setting && PinTries.secondsLeft() > 0 -> {
                digits = ""
                wait = PinTries.secondsLeft()
            }
            digits.length < 4 -> problem = "The PIN has 4 to 8 digits"
            // Counted with the PINs typed on a phone: one count for every way in.
            !o.setting -> if (PinTries.attempt(digits, actions::checkPin) == null) {
                nav.unlocked = true
                close()
                o.then?.invoke()
            } else {
                digits = ""
                wait = PinTries.secondsLeft()
                if (wait == 0L) problem = "That isn’t the PIN. Try again"
            }
            first == null -> {
                first = digits
                digits = ""
            }
            first == digits -> {
                actions.setPin(digits)
                o.mode?.let { m -> actions.changeSettings { it.copy(pinMode = m) } }
                nav.unlocked = true
                nav.flash("s-pin")
                close()
                // The value column that opened this is gone: back to the row, not the category.
                if (nav.screen == Screen.Settings) {
                    nav.settingsReturn = "s-pin"
                    nav.contentFocus = Screen.Settings
                }
            }
            else -> {
                first = null
                digits = ""
                problem = "The two were different. Choose it again"
            }
        }
    }
    TypeInto(nav, { digits = digits.dropLast(1) }, onEnter = { submit(); true }) { ch ->
        if (ch.isDigit() && digits.length < 8) {
            digits += ch
            problem = null
        }
    }
    Sheet(TvSize.SheetSmall) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 8.dp)) {
            Text(title, style = TvType.display(32f))
            Text(
                if (o.setting) "4 to 8 digits. Without it, Connect still works." else "Profiles and settings are locked on this device.",
                style = TvType.ui(15f, color = Tv.soft(0.7f)).copy(textAlign = TextAlign.Center),
                modifier = Modifier.padding(top = 8.dp),
            )
            Row(Modifier.padding(top = 22.dp, bottom = if (problem != null) 8.dp else 22.dp).height(18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                repeat(maxOf(4, digits.length)) { i ->
                    Box(Modifier.size(16.dp).clip(CircleShape).background(if (i < digits.length) Tv.Ink else if (problem != null) Tv.Red.copy(alpha = 0.5f) else Tv.white(0.18f)))
                }
            }
            problem?.let { Text(it, style = TvType.ui(15f, FontWeight.SemiBold, Tv.Red), modifier = Modifier.padding(bottom = 16.dp)) }
            val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0", "OK")
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                keys.chunked(3).forEach { line ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        line.forEach { k ->
                            TvButton(
                                k,
                                {
                                    when (k) {
                                        "⌫" -> digits = digits.dropLast(1)
                                        "OK" -> submit()
                                        else -> if (digits.length < 8) {
                                            digits += k
                                            problem = null
                                        }
                                    }
                                },
                                modifier = Modifier.width(110.dp),
                                glass = k != "OK",
                                focusRequester = if (k == "5") req["ov-first"] else null,
                            )
                        }
                    }
                }
            }
            if (!o.locksApp) {
                Row(Modifier.padding(top = 16.dp)) {
                    TvButton("Cancel", close, glass = true, small = true)
                }
            }
        }
    }
}

/** A .p12 that opened: the profile it goes into, or the device's keystore. */
@Composable
fun P12TargetSheet(state: VpnUiState, nav: Nav, actions: TunnelActions, req: Requesters, o: Overlay.P12Target, close: () -> Unit) {
    var replacing by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { req["p12-first"].focusWhenReady() }
    Sheet(TvSize.SheetLarge) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            PanelHeading("Import › Certificates", "Add the certificate to a profile", "The certificate and key go into the profile’s file, in place of any it had.", titleSize = 32f)
            val list = rememberLazyListState()
            LazyColumn(
                state = list,
                modifier = Modifier.heightIn(max = 470.dp).bleed(28.dp).scrollFade(list),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(state.connections, key = { _, c -> c.id }) { i, c ->
                    val hasCert = state.infoOf(c)?.cert != null
                    val attach: () -> Unit = {
                        scope.launch {
                            val problem = actions.attachP12(o.password, c.id)
                            if (problem != null) {
                                nav.toast(problem)
                            } else {
                                nav.toast("Certificate added to ${c.name}")
                                close()
                            }
                        }
                    }
                    // A profile's own certificate is only replaced after it says so, and Keep is focused.
                    if (replacing == c.id) {
                        ConfirmBar(
                            "Replace the certificate and key in ${c.name}?", "Replace", req["p12-keep"],
                            onConfirm = attach,
                            onKeep = {
                                replacing = null
                                nav.refocus(req[if (i == 0) "p12-first" else "p12-${c.id}"])
                            },
                            icon = Ic.Swap,
                        )
                    } else {
                        ListRow(
                            c.name,
                            { if (hasCert) replacing = c.id else attach() },
                            subtitle = if (hasCert) "Has its own certificate, which this one would replace" else state.serverOf(c)?.host,
                            focusRequester = if (i == 0) req["p12-first"] else req["p12-${c.id}"],
                            leading = { Avatar(lookOf(c), 40f) },
                        )
                    }
                }
                item {
                    ListRow(
                        "Keep the key in this device’s keystore instead", {
                            scope.launch {
                                val alias = actions.keepP12(o.password)
                                if (alias == null) {
                                    nav.toast("Android could not keep this key")
                                } else {
                                    nav.overlay = Overlay.PickProfile(
                                        "Use it for which profile?", "The key stays in the device’s keystore and can’t be copied off it.",
                                        cancelled = { Certificates.forget(alias) },
                                    ) { id ->
                                        actions.setKeyAlias(id, alias)
                                        nav.toast("${state.connection(id)?.name} now uses ${Certificates.label(alias)}")
                                    }
                                }
                            }
                        },
                        subtitle = "The key can sign in but can’t be copied off the device. Pick the profile next",
                        focusRequester = if (state.connections.isEmpty()) req["p12-first"] else null,
                        leading = { IconSquare(Ic.Cert, it) },
                    )
                }
            }
            Row {
                TvButton("Cancel", {
                    actions.cancelP12()
                    close()
                }, glass = true)
            }
        }
    }
}

/** A list of profiles to pick one from. */
@Composable
fun PickProfileSheet(state: VpnUiState, req: Requesters, o: Overlay.PickProfile, close: () -> Unit) {
    Sheet(TvSize.SheetMedium) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            PanelHeading(o.eyebrow, o.title, o.sub, titleSize = 32f)
            val list = rememberLazyListState()
            LazyColumn(
                state = list,
                modifier = Modifier.heightIn(max = 520.dp).bleed(28.dp).scrollFade(list),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(state.connections, key = { _, c -> c.id }) { i, c ->
                    ListRow(
                        c.name,
                        {
                            close()
                            o.picked(c.id)
                        },
                        subtitle = state.serverOf(c)?.host,
                        focusRequester = if (i == 0) req["ov-first"] else null,
                        leading = { Avatar(lookOf(c), 40f) },
                    )
                }
            }
            if (state.connections.isEmpty()) {
                Text("Add a profile first.", style = TvType.ui(17f, color = Tv.soft(0.7f)))
            }
            TvButton("Cancel", {
                o.cancelled?.invoke()
                close()
            }, glass = true, focusRequester = if (state.connections.isEmpty()) req["ov-first"] else null)
        }
    }
}

// ---- Shared forms -----------------------------------------------------------------

/** The Backup screen's phone code, pointing at the download. */
fun backupSheet(): Overlay.Phone = Overlay.Phone(
    key = "backup",
    eyebrow = "Settings › Backup",
    title = "Back up to your phone",
    lede = "Scan the code, type a password for this backup if you want one, and press Download. Restoring it needs the same password.",
    section = "download",
)

/** The line after a restore. */
fun restoredText(r: Backup.Restored.Done): String {
    fun profiles(count: Int) = "$count profile${if (count == 1) "" else "s"}"
    if (r.profiles == 0 && r.replaced == 0) {
        return when (r.already) {
            0 -> "The settings are restored"
            1 -> "The profile was here already. The settings are restored"
            else -> "All ${r.already} profiles were here already. The settings are restored"
        }
    }
    val parts = mutableListOf<String>()
    if (r.profiles > 0) parts += "Restored ${profiles(r.profiles)}"
    if (r.replaced > 0) parts += (if (parts.isEmpty()) "Replaced " else "replaced ") + profiles(r.replaced)
    if (r.already > 0) parts += if (r.already == 1) "1 was here already" else "${r.already} were here already"
    return parts.joinToString(", ")
}

/** A backup picked on the TV: its password, when it was made with one ([encrypted]). */
fun restoreSpec(nav: Nav, actions: TunnelActions, encrypted: Boolean = true): FormSpec = FormSpec(
    eyebrow = "Import › Restore",
    title = "Restore a backup",
    sub = (if (encrypted) "Type the backup’s password. " else "") + "The backup’s profiles are added to this TV. Profiles already here are skipped.",
    fields = if (encrypted) listOf(FormField("passphrase", "Password", secret = true)) else emptyList(),
    primary = "Restore",
    busyLabel = "Restoring…",
    toggle = FormToggle("Replace", "Profiles with the same name", false),
    cancelled = {
        actions.cancelRestore()
        nav.toast("The backup was not restored")
    },
) { v, replace -> restoreBackup(actions, nav, null, v["passphrase"].orEmpty(), replace, "passphrase").toForm() }

/** A .p12 picked on the TV: its password first, then the profile it goes into. */
fun p12PasswordSpec(nav: Nav, actions: TunnelActions): FormSpec = FormSpec(
    eyebrow = "Import › Certificates",
    title = "Certificate password",
    sub = "The password the .p12 file was exported with, used once and not kept.",
    fields = listOf(FormField("p12pass", "Password", secret = true)),
    primary = "Open",
    primaryIcon = Ic.Lock,
    cancelled = {
        actions.cancelP12()
        nav.toast("The certificate was not added")
    },
) { v, _ ->
    val password = v["p12pass"].orEmpty()
    actions.checkP12(password)?.let { FormResult.Problems("p12pass", it) } ?: FormResult.Open(Overlay.P12Target(password))
}

/** The phone fields for a .p12: its password, the profile it is for (unless [only] is given), and where its key goes. */
fun p12Fields(state: VpnUiState, only: Connection?): List<PhoneField> = listOfNotNull(
    PhoneField.Text("p12pass", "Its password", secret = true, required = true, help = "The password the file was exported with, used once and not kept."),
    if (only == null) {
        PhoneField.Select(
            "target", "Use it for",
            // The TV finds the profile from who issued the certificate; picking one is for a tie.
            listOf(PhoneOption("", "The profile it belongs to", sub = "The TV finds it from who issued the certificate")) + state.connections.filter { state.infoOf(it)?.kind != dev.shieldvpn.vpn.VpnKind.WireGuard }.map { c ->
                val has = state.infoOf(c)?.cert != null
                PhoneOption(
                    c.id, c.name,
                    sub = if (has) "Has its own certificate, which this one replaces" else state.serverOf(c)?.host,
                    confirm = if (has) "${c.name} has its own certificate and key. Replace them?" else null,
                )
            },
            "",
        )
    } else {
        null
    },
    // Said from the phone's side, where "this device" would be the phone.
    PhoneField.Choice("keep", "Keep the key", listOf("file" to "In the profile", "keystore" to "In the TV’s keystore"), "file"),
    PhoneField.Info("keepnote", text = "In the TV’s keystore, the key signs in but can’t be copied off the TV, and ${APP_NAME}’s backups don’t hold it."),
)

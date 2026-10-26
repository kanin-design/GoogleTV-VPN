package dev.shieldvpn.ui.tv

import dev.shieldvpn.phone.PhoneReply
import dev.shieldvpn.phone.PhoneStatus
import dev.shieldvpn.vpn.Backup
import dev.shieldvpn.vpn.Connection
import dev.shieldvpn.vpn.VpnUiState

/*
 * Each task a person can do from a TV sheet or from a phone page, written once: its checks, its
 * effect and its words. The sheet and the page only turn the outcome into what they show.
 */

/** What a task came to. */
sealed interface Outcome {
    /** It is done; [text] says so on the phone. */
    data class Done(val text: String) : Outcome

    /** What is wrong, by field; an empty field name is about the whole task. */
    data class Problems(val errors: Map<String, String>) : Outcome {
        constructor(field: String, text: String) : this(mapOf(field to text))
    }
}

/** For a TV sheet: done closes it, problems show under their fields. */
fun Outcome.toForm(): FormResult = when (this) {
    is Outcome.Done -> FormResult.Done
    is Outcome.Problems -> FormResult.Problems(errors)
}

/** For a phone page: done shows its line, problems show under their fields or as the page's status. */
fun Outcome.toReply(clear: List<String> = emptyList(), holdMs: Long = 0): PhoneReply = when (this) {
    is Outcome.Done -> PhoneReply(PhoneStatus.done(text), clear = clear, holdMs = holdMs)
    is Outcome.Problems -> {
        val whole = errors[""]
        PhoneReply(whole?.let { PhoneStatus.error(it) }, errors = errors - "", holdMs = 0)
    }
}

/** The PIN locks profiles and settings, and it has not been typed in this visit. */
fun locked(state: VpnUiState, nav: Nav): Boolean = state.settings.pinMode != "Off" && state.hasPin && !nav.unlocked

// ---- Sign-in ------------------------------------------------------------------------

/** What is missing from a username and password, or null. With [keepsSaved], an empty password keeps the saved one. */
fun loginProblems(user: String, pw: String, keepsSaved: Boolean = false): Outcome.Problems? = when {
    user.isBlank() -> Outcome.Problems("user", "Type the username")
    pw.isEmpty() && !keepsSaved -> Outcome.Problems("pw", "Type the password")
    else -> null
}

/** Saves the login of [c]; for [forStartup], it then connects by itself when the device starts. */
fun saveLogin(actions: TunnelActions, nav: Nav, c: Connection, user: String, pw: String, forStartup: Boolean = false, keepsSaved: Boolean = false): Outcome {
    loginProblems(user, pw, keepsSaved)?.let { return it }
    actions.saveLogin(c.id, user.trim(), pw.ifEmpty { null })
    var instead: String? = null
    if (forStartup) {
        instead = startAtBoot(actions.stateNow(), actions, nav, c)
        nav.pick = null
        nav.flash("s-boot")
        nav.flash("pe-startup")
    } else {
        nav.flash("sl-login")
    }
    return Outcome.Done(if (forStartup) "Saved. ${c.name} connects by itself when the TV starts${instead?.let { ", instead of $it" }.orEmpty()}." else "Saved on the TV.")
}

/** Makes [c] the profile that connects when the device starts, and says which one stops, if another did. */
fun startAtBoot(state: VpnUiState, actions: TunnelActions, nav: Nav, c: Connection): String? {
    val before = state.settings.startupId?.takeIf { state.settings.boot && it != c.id }?.let { state.connection(it)?.name }
    actions.setStartup(c.id)
    before?.let { nav.toast("${c.name} connects at startup now, instead of $it") }
    return before
}

// ---- Profiles ---------------------------------------------------------------------------

fun renameProfile(actions: TunnelActions, nav: Nav, c: Connection, name: String): Outcome {
    val clean = name.trim()
    if (clean.isEmpty()) return Outcome.Problems("name", "A profile needs a name")
    if (clean != c.name) actions.rename(c.id, clean)
    nav.flash("pe-name")
    return Outcome.Done("Saved on the TV.")
}

/** What is wrong with a server address and port, by field, or null. */
fun serverProblems(host: String, port: String): Outcome.Problems? {
    val errors = buildMap {
        if (host.isEmpty() || host.any { it.isWhitespace() }) put("host", "Type the server’s address")
        if (port.toIntOrNull()?.let { it in 1..65535 } != true) put("port", "A number from 1 to 65535")
    }
    return if (errors.isEmpty()) null else Outcome.Problems(errors)
}

fun setServer(actions: TunnelActions, nav: Nav, c: Connection, host: String, port: String): Outcome {
    serverProblems(host.trim(), port.trim())?.let { return it }
    actions.setServer(c.id, host.trim(), port.trim().toInt())
    nav.flash("pa-Server")
    return Outcome.Done("Saved on the TV.")
}

/** Saves the custom DNS servers typed in [text]; empty clears them. */
fun saveCustomDns(actions: TunnelActions, nav: Nav, text: String, flash: String): Outcome {
    dnsProblem(text)?.let { return Outcome.Problems("dns", it) }
    actions.changeSettings { it.copy(customDns = dnsServers(text)) }
    nav.flash(flash)
    return Outcome.Done("Saved on the TV.")
}

// ---- Backup -----------------------------------------------------------------------------

/** What is wrong with a new passphrase typed twice, by field, or null. */
fun passphraseProblems(first: String, again: String, firstId: String = "pass1", againId: String = "pass2"): Outcome.Problems? = when {
    first.length < 6 -> Outcome.Problems(firstId, "At least 6 characters")
    first != again -> Outcome.Problems(againId, "Not the same as the first. Type it again")
    else -> null
}

/**
 * Sets the backup passphrase to [first], typed twice. From a phone, a passphrase that is set
 * already is only changed with the [current] one, so a phone with the code can't take it over.
 */
fun setPassphrase(state: VpnUiState, actions: TunnelActions, nav: Nav, first: String, again: String, current: String? = null): Outcome {
    if (current != null && state.hasBackupPassphrase && !actions.checkBackupPassphrase(current)) {
        return Outcome.Problems("old", "That isn’t the current passphrase")
    }
    passphraseProblems(first, again)?.let { return it }
    actions.setBackupPassphrase(first)
    nav.flash("s-bkpass")
    return Outcome.Done(if (state.hasBackupPassphrase) "The passphrase is changed." else "The passphrase is set.")
}

/**
 * Restores a backup: the one waiting on the TV when [bytes] is null. The TV shows the profiles
 * afterwards. The passphrase's field on the asking side is [field].
 */
suspend fun restoreBackup(actions: TunnelActions, nav: Nav, bytes: ByteArray?, passphrase: String, replace: Boolean, field: String): Outcome {
    val r = if (bytes == null) actions.restore(passphrase, replace) else actions.restore(bytes, passphrase, replace)
    return when (r) {
        is Backup.Restored.Done -> {
            val text = restoredText(r)
            nav.toast(text)
            nav.go(Screen.Profiles)
            Outcome.Done("$text.")
        }
        is Backup.Restored.Problem -> Outcome.Problems(field, r.message)
    }
}

// ---- Certificates -------------------------------------------------------------------------

/**
 * Adds the .p12 in [bytes] to profile [id] (or the one picked in [values]), in its file or, when
 * [values] say so, in this device's keystore.
 */
suspend fun attachCertificate(state: VpnUiState, actions: TunnelActions, nav: Nav, bytes: ByteArray, values: Map<String, String>, id: String?): Outcome {
    val password = values["p12pass"].orEmpty()
    val target = id ?: values["target"]?.takeIf { it.isNotEmpty() } ?: return Outcome.Problems("target", "Pick the profile it is for")
    val c = state.connection(target) ?: return Outcome.Problems("target", "That profile is gone")
    actions.checkP12(bytes, password)?.let { return Outcome.Problems("p12pass", it) }
    if (values["keep"] == "keystore") {
        val alias = actions.keepP12(bytes, password) ?: return Outcome.Problems("p12pass", "Android could not keep this key")
        actions.setKeyAlias(target, alias)
    } else {
        actions.attachP12(bytes, password, target)?.let { return Outcome.Problems("p12pass", it) }
    }
    nav.toast("Certificate added to ${c.name}")
    nav.flash("sl-p12")
    return Outcome.Done("Added to ${c.name} on the TV.")
}

// ---- Import -------------------------------------------------------------------------------

/** Downloads a profile from a link; it then waits on the Import screen for its review. */
suspend fun downloadLink(actions: TunnelActions, nav: Nav, url: String, user: String, pass: String): Outcome {
    val clean = url.trim()
    val name = user.trim()
    nav.linkUrl = clean
    nav.linkUser = name
    val problem = actions.download(clean, name.ifEmpty { null }, pass.takeIf { name.isNotEmpty() })
    return if (problem != null) Outcome.Problems("url", problem) else Outcome.Done("Downloaded. Check it, then save it here or on the TV.")
}

/** Said on a phone page that wants a file holding keys before the TV asked for it. */
fun pressOnTv(row: String) = "Press $row on the TV first. Then the file can be downloaded here."

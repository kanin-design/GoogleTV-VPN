package dev.shieldvpn.ui.tv

import dev.shieldvpn.control.Control
import dev.shieldvpn.control.Validate
import dev.shieldvpn.control.Words
import dev.shieldvpn.phone.PhoneReply
import dev.shieldvpn.phone.PhoneStatus
import dev.shieldvpn.vpn.Backup
import dev.shieldvpn.vpn.Connection
import dev.shieldvpn.vpn.VpnUiState
import dev.shieldvpn.vpn.connectionId
import dev.shieldvpn.vpn.isActive

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
fun loginProblems(user: String, pw: String, keepsSaved: Boolean = false): Outcome.Problems? =
    Validate.usernameProblem(user)?.let { Outcome.Problems("user", it) }
        ?: Validate.passwordProblem(pw).takeIf { !keepsSaved }?.let { Outcome.Problems("pw", it) }

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
    Validate.nameProblem(clean)?.let { return Outcome.Problems("name", it) }
    if (clean != c.name) actions.rename(c.id, clean)
    nav.flash("pe-name")
    return Outcome.Done("Saved on the TV.")
}

/** What is wrong with a server address and port, by field, or null. */
fun serverProblems(host: String, port: String): Outcome.Problems? {
    val errors = buildMap {
        Validate.hostProblem(host)?.let { put("host", it) }
        Validate.portProblem(port)?.let { put("port", it) }
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

/** What is wrong with a new password typed twice, by field, or null. */
fun passphraseProblems(first: String, again: String, firstId: String = "pass1", againId: String = "pass2"): Outcome.Problems? =
    Validate.passphraseProblem(first, again)?.let { (which, text) -> Outcome.Problems(if (which == 0) firstId else againId, text) }

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
            // From Import, the new profiles are what comes next; from Settings › Backup the TV stays there.
            if (nav.screen == Screen.Import && r.profiles > 0) nav.go(Screen.Profiles)
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
    actions.checkP12(bytes, password)?.let { return Outcome.Problems("p12pass", it) }
    // Without a choice, the profile whose CA issued the certificate is the one; only a tie or no match asks.
    val target = id ?: values["target"]?.takeIf { it.isNotEmpty() } ?: run {
        val fits = Control.profilesForCertificate(bytes, password)
        when (fits.size) {
            1 -> fits.single().id
            0 -> return Outcome.Problems("target", "No profile here trusts who issued this certificate. Pick the profile this certificate is for.")
            else -> return Outcome.Problems("target", "${fits.joinToString(" and ") { it.name }} could both use it. Pick one.")
        }
    }
    val c = state.connection(target) ?: return Outcome.Problems("target", "That profile is gone")
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

/** The delete bar's question for [c], with what happens to startup. */
fun deleteQuestion(state: VpnUiState, c: Connection): String {
    val isStartup = state.settings.boot && state.settings.startupId == c.id
    val live = state.status.connectionId == c.id && (state.status.isActive || state.pausedUntil != null)
    return Words.deleteQuestion(c.name, state.logins[c.id]?.hasPassword == true, isStartup, if (isStartup) Control.startupFallback(c.id)?.name else null, connected = live)
}

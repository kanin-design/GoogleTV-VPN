package dev.shieldvpn.control

import dev.shieldvpn.vpn.ConfigInfo
import dev.shieldvpn.vpn.Connection
import dev.shieldvpn.vpn.Login
import dev.shieldvpn.vpn.Protocol
import dev.shieldvpn.vpn.VpnKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WordsTest {

    private val now = 1_000_000_000_000L
    private val c = Connection("p1", "Home", "s1")

    private fun info(needsLogin: Boolean, clientCert: Boolean = false, kind: VpnKind = VpnKind.OpenVpn) = ConfigInfo(
        cipher = null, tls = null, needsLogin = needsLogin, clientCert = clientCert, cert = null,
        fileHost = "vpn.example.net", filePort = 1194, fileProtocol = Protocol.UDP, remotes = 1, warnings = emptyList(), kind = kind,
    )

    @Test fun oneWordingForNotConnected() {
        // The current profile and the others say the same when nothing runs.
        val never = ProfileStatus.of(c, null, asksForLogin = false, now = now)
        assertEquals("Never connected", never.line)
        val used = ProfileStatus.of(c.copy(lastUsed = now - 3 * 3_600_000L), null, asksForLogin = false, now = now)
        assertEquals("Last connected 3 h ago", used.line)
        assertEquals("Not connected", used.word)
        assertEquals("Asks for a password", ProfileStatus.of(c, null, asksForLogin = true, now = now).line)
    }

    @Test fun failuresNameTheirReason() {
        assertEquals("Failed: Server did not respond", ProfileStatus.of(c.copy(lastError = "Server did not respond"), null, false, now).line)
        assertEquals("Failed: Wrong username or password", ProfileStatus.of(c, ProfileStatus.Live.Failed("Wrong username or password"), false, now).line)
        assertEquals("Connected", ProfileStatus.of(c, ProfileStatus.Live.Connected, false, now).line)
        assertEquals("Connecting…", ProfileStatus.of(c, ProfileStatus.Live.Connecting, false, now).line)
    }

    @Test fun signInIsOnePairOfWords() {
        val saved = Words.signIn(info(needsLogin = true), Login("home", true), null)
        assertEquals("Password saved for home", saved.summary)
        assertEquals("Password", saved.method)
        val asks = Words.signIn(info(needsLogin = true), Login("", false), null)
        assertEquals("Asks for a password", asks.summary)
        assertEquals("Password", asks.method)
        assertEquals("Certificate in the file", Words.signIn(info(needsLogin = false, clientCert = true), null, null).summary)
        assertEquals("Key in the profile", Words.signIn(info(needsLogin = false, kind = VpnKind.WireGuard), null, null).summary)
        // The chip never repeats the line.
        listOf(saved, asks).forEach { assertTrue(it.summary != it.method) }
    }

    @Test fun oneServerFormat() {
        assertEquals("vpn.example.net:1194", Words.address("vpn.example.net", 1194))
        assertEquals("[2001:db8::1]:51820", Words.address("2001:db8::1", 51820))
        assertEquals("vpn.example.net:1194 · UDP · AES-128-CBC", Words.serverLine("vpn.example.net", 1194, Protocol.UDP, "AES-128-CBC"))
    }

    @Test fun deleteSaysWhatGoes() {
        assertEquals("Delete Home?", Words.deleteQuestion("Home", hasPassword = false))
        assertEquals("Delete Home and its saved password?", Words.deleteQuestion("Home", hasPassword = true))
        assertEquals("Delete Home? Cabin connects at startup instead.", Words.deleteQuestion("Home", false, isStartup = true, startupGoesTo = "Cabin"))
        assertEquals("Delete Home? Nothing connects at startup then.", Words.deleteQuestion("Home", false, isStartup = true))
    }

    @Test fun backupLine() {
        assertEquals("Not backed up yet", Words.lastBackup(null, now))
        assertEquals("Last backup 4 h ago, with 1 profile", Words.lastBackup(now - 4 * 3_600_000L to 1, now))
    }
}

package dev.shieldvpn.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class WireGuardFileTest {

    // Made-up keys: any 32 bytes are a valid key to the parser.
    private fun key(seed: Int) = Base64.getEncoder().encodeToString(ByteArray(32) { (it * 7 + seed).toByte() })

    private val priv = key(1)
    private val server = key(2)
    private val server2 = key(3)
    private val psk = key(4)

    private fun ok(text: String) = WireGuardFile.review(text) as WireGuardFile.Review.Ok
    private fun problem(text: String) = (WireGuardFile.review(text) as WireGuardFile.Review.Problem).message

    private val provider = """
        [Interface]
        # Key for my-tv
        PrivateKey = $priv
        Address = 10.2.0.2/32, fd00::2/128
        DNS = 10.2.0.1

        [Peer]
        PublicKey = $server
        AllowedIPs = 0.0.0.0/0, ::/0
        Endpoint = 203.0.113.10:51820
    """.trimIndent()

    @Test fun providerFile() {
        val r = ok(provider)
        assertEquals("203.0.113.10", r.facts.host)
        assertEquals(51820, r.facts.port)
        assertEquals(listOf("10.2.0.2/32", "fd00::2/128"), r.facts.addresses)
        assertEquals(listOf("10.2.0.1"), r.facts.dns)
        assertTrue(r.facts.fullTunnel)
        assertEquals(1, r.facts.peers)
        assertTrue(r.warnings.isEmpty())
        assertEquals(priv, r.secrets.privateKey)
        assertFalse(r.text.contains(priv))
        assertFalse(r.text.contains("PrivateKey"))
        assertTrue(r.text.contains("# Key for my-tv"))
    }

    @Test fun routerFileWithScriptsAndPresharedKey() {
        val text = """
            [Interface]
            PrivateKey = $priv
            Address = 192.168.200.2/24
            DNS = 192.168.1.1, home.lan
            PostUp = iptables -A FORWARD -i %i -j ACCEPT
            PostDown = iptables -D FORWARD -i %i -j ACCEPT

            [Peer]
            PublicKey = $server
            PresharedKey = $psk
            AllowedIPs = 0.0.0.0/0
            Endpoint = vpn.example.net:51820
            PersistentKeepalive = 25
        """.trimIndent()
        val r = ok(text)
        assertEquals("vpn.example.net", r.facts.host)
        assertEquals(25, r.facts.keepalive)
        assertEquals(listOf("192.168.1.1"), r.facts.dns)
        assertEquals(listOf("home.lan"), r.facts.searchDomains)
        assertEquals(mapOf(server to psk), r.secrets.presharedKeys)
        assertFalse(r.text.contains(psk))
        assertTrue(r.warnings.single().contains("PostUp, PostDown"))
    }

    @Test fun secretsGoBackInTheirPlaces() {
        val text = """
            [Interface]
            PrivateKey = $priv
            Address = 10.0.0.2/32
            [Peer]
            PublicKey = $server
            PresharedKey = $psk
            AllowedIPs = 0.0.0.0/0
            Endpoint = 198.51.100.1:51820
            [Peer]
            PublicKey = $server2
            AllowedIPs = 10.9.0.0/16
            Endpoint = 198.51.100.2:51820
        """.trimIndent()
        val r = ok(text)
        val back = WireGuardFile.withSecrets(r.text, r.secrets)
        val lines = back.lines().map { it.trim() }
        assertEquals("PrivateKey = $priv", lines[lines.indexOf("[Interface]") + 1])
        assertEquals("PresharedKey = $psk", lines[lines.indexOf("PublicKey = $server") + 1])
        assertEquals(1, lines.count { it.startsWith("PresharedKey") })
        // The round trip reads the same as the original.
        assertEquals(r.facts, ok(back).facts)
        assertEquals(r.secrets, ok(back).secrets)
    }

    @Test fun severalServers() {
        val text = provider + "\n\n[Peer]\nPublicKey = $server2\nAllowedIPs = 0.0.0.0/0\nEndpoint = 203.0.113.11:51821\n"
        val r = ok(text)
        assertEquals(2, r.facts.peers)
        assertEquals("203.0.113.10", r.facts.host)
        assertTrue(r.warnings.any { it.contains("2 servers") })
    }

    @Test fun splitTunnel() {
        val r = ok(provider.replace("AllowedIPs = 0.0.0.0/0, ::/0", "AllowedIPs = 10.0.0.0/24"))
        assertFalse(r.facts.fullTunnel)
        assertTrue(r.warnings.any { it.startsWith("Only 10.0.0.0/24 goes through the VPN") })
    }

    @Test fun ipv6Endpoint() {
        val r = ok(provider.replace("Endpoint = 203.0.113.10:51820", "Endpoint = [2001:db8::1]:51820"))
        assertEquals("2001:db8::1", r.facts.host)
        val moved = WireGuardFile.withEndpoint(r.text, "2001:db8::2", 443)
        assertTrue(moved.contains("Endpoint = [2001:db8::2]:443"))
        assertEquals("2001:db8::2" to 443, WireGuardFile.endpoint(moved))
    }

    @Test fun endpointChangeTouchesOnlyTheFirstServer() {
        val text = provider + "\n[Peer]\nPublicKey = $server2\nAllowedIPs = 0.0.0.0/0\nEndpoint = 203.0.113.11:51821\n"
        val moved = WireGuardFile.withEndpoint(text, "vpn.example.org", 51000)
        assertTrue(moved.contains("Endpoint = vpn.example.org:51000"))
        assertTrue(moved.contains("Endpoint = 203.0.113.11:51821"))
    }

    @Test fun badKeys() {
        assertTrue(problem(provider.replace("PublicKey = $server", "PublicKey = notakey")).contains("PublicKey"))
        assertTrue(problem(provider.replace("PrivateKey = $priv", "PrivateKey = ${priv.dropLast(4)}")).contains("PrivateKey"))
    }

    @Test fun missingParts() {
        assertTrue(problem(provider.replace("Endpoint = 203.0.113.10:51820\n", "").replace("Endpoint = 203.0.113.10:51820", "")).contains("no Endpoint"))
        assertTrue(problem(provider.lines().filterNot { it.startsWith("PrivateKey") }.joinToString("\n")).contains("no PrivateKey"))
        assertTrue(problem(provider.lines().filterNot { it.startsWith("Address") }.joinToString("\n")).contains("no Address"))
        assertTrue(problem(provider.substringBefore("[Peer]")).contains("no [Peer]"))
        assertTrue(problem(provider.replace(":51820", "")).contains("needs an address and a port"))
    }

    @Test fun detection() {
        assertTrue(WireGuardFile.looksLike(provider))
        assertTrue(WireGuardFile.looksLike(provider.replace("[Interface]", "  [interface]  # this TV")))
        val openvpn = "client\ndev tun\nproto udp\nremote vpn.example.net 1194\n# [Interface] in a comment\n<ca>\n-----BEGIN CERTIFICATE-----\n-----END CERTIFICATE-----\n</ca>\n"
        assertFalse(WireGuardFile.looksLike(openvpn))
    }

    @Test fun keysAndSectionsIgnoreCase() {
        val r = ok(provider.replace("PrivateKey", "privatekey").replace("[Peer]", "[PEER]").replace("Endpoint", "endpoint"))
        assertEquals(priv, r.secrets.privateKey)
        assertEquals(51820, r.facts.port)
    }

    @Test fun hidingKeysForDisplay() {
        val shown = WireGuardFile.hideSecrets(provider)
        assertFalse(shown.contains(priv))
        assertTrue(shown.contains("PrivateKey = (hidden)"))
        assertTrue(shown.contains("PublicKey = $server"))
    }

    @Test fun secretsNeverPrint() {
        val r = ok(provider)
        assertFalse(r.secrets.toString().contains(priv))
        assertFalse(r.toString().contains(priv))
    }

    @Test fun badNumbers() {
        assertTrue(problem(provider.replace("DNS = 10.2.0.1", "MTU = 70000")).contains("MTU"))
        assertNull(WireGuardFile.endpoint("[Interface]\nPrivateKey = $priv\n"))
    }
}

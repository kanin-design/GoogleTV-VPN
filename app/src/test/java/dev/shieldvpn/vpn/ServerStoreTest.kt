package dev.shieldvpn.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ServerStoreTest {

    private fun file(ca: String, vararg extra: String) = (
        listOf("client", "dev tun", "remote vpn.example.com 1194", "auth-user-pass") + extra +
            listOf("<ca>", "-----BEGIN CERTIFICATE-----", ca, "-----END CERTIFICATE-----", "</ca>")
        ).joinToString("\n")

    @Test fun sameTrustAcrossServersAndLayout() {
        val a = file("MIIBaaaa", "remote-cert-tls server")
        // Another server, another port and the lines in another order and spacing: the same servers are believed.
        val b = file("MIIB\naaaa", "remote-cert-tls   server").replace("vpn.example.com 1194", "vpn2.example.com 443")
        assertEquals(ServerStore.trust(a), ServerStore.trust(b))
    }

    @Test fun anotherCaOrWeakerChecksDiffer() {
        val a = file("MIIBaaaa", "remote-cert-tls server", "verify-x509-name server.example.com name")
        assertNotEquals(ServerStore.trust(a), ServerStore.trust(file("MIIBbbbb", "remote-cert-tls server", "verify-x509-name server.example.com name")))
        assertNotEquals(ServerStore.trust(a), ServerStore.trust(file("MIIBaaaa", "remote-cert-tls server")))
        assertNotEquals(ServerStore.trust(a), ServerStore.trust(file("MIIBaaaa", "verify-x509-name server.example.com name")))
    }
}

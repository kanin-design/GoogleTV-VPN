package dev.shieldvpn.remote

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SealTest {

    private fun hex(s: String): ByteArray = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

    @Test
    fun aeadMatchesRfc8439() {
        val key = ByteArray(32) { (0x80 + it).toByte() }
        val nonce = hex("07000000 40414243 44454647")
        val aad = hex("50515253 c0c1c2c3 c4c5c6c7")
        val plain = "Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, sunscreen would be it.".toByteArray()
        val sealed = Seal.aead(true, key, nonce, aad, plain)!!
        assertEquals(
            "d31a8d34648e60db7b86afbc53ef7ec2a4aded51296e08fea9e2b5a736ee62d63dbea45e8ca9671282fafb69da92728b1a71de0a9e060b2905d6a5b67ecd3b3692ddbd7f2d778b8c9803aee328091b58fab324e4fad675945585808b4831d7bc3ff4def08e4b7a9de576d26586cec64b6116" +
                "1ae10b594f09e26a7e902ecbd0600691",
            sealed.hex(),
        )
        assertArrayEquals(plain, Seal.aead(false, key, nonce, aad, sealed))
    }

    @Test
    fun hkdfMatchesRfc5869() {
        val okm = Seal.hkdf(ByteArray(22) { 0x0b }, hex("000102030405060708090a0b0c"), hex("f0f1f2f3f4f5f6f7f8f9"), 42)
        assertEquals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865", okm.hex())
        // Test case 3: no salt, no info.
        val noSalt = Seal.hkdf(ByteArray(22) { 0x0b }, null, ByteArray(0), 42)
        assertEquals("8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8", noSalt.hex())
    }

    @Test
    fun sealedBodyOpensOnlyForItsRequest() {
        val keys = Seal.Keys.of(Seal.randomBytes(32))
        val aad = "POST /api/v2/act".toByteArray()
        val body = """{"action":"connect"}""".toByteArray()
        val sealed = Seal.seal(keys.up, Seal.UP, 7, aad, body)
        assertArrayEquals(body, Seal.open(keys.up, Seal.UP, 7, aad, sealed))
        // Another counter, another path, the other direction, the other key, a changed byte.
        assertNull(Seal.open(keys.up, Seal.UP, 8, aad, sealed))
        assertNull(Seal.open(keys.up, Seal.UP, 7, "POST /api/v2/changes".toByteArray(), sealed))
        assertNull(Seal.open(keys.up, Seal.DOWN, 7, aad, sealed))
        assertNull(Seal.open(keys.down, Seal.UP, 7, aad, sealed))
        val tampered = sealed.copyOf().also { it[3] = (it[3].toInt() xor 1).toByte() }
        assertNull(Seal.open(keys.up, Seal.UP, 7, aad, tampered))
        assertNull(Seal.open(keys.up, Seal.UP, 7, aad, ByteArray(5)))
    }

    @Test
    fun twoPagesOnOneCounterNeverShareANonce() {
        val keys = Seal.Keys.of(Seal.randomBytes(32))
        val aad = "POST /api/v2/events".toByteArray()
        val body = """{"after":3}""".toByteArray()
        val a = Seal.seal(keys.up, Seal.UP, 41, aad, body, lane = 0x123456)
        val b = Seal.seal(keys.up, Seal.UP, 41, aad, body, lane = 0xABCDEF)
        assertFalse(a.contentEquals(b))
        assertFalse(Seal.nonce(Seal.UP, 41, 0x123456).contentEquals(Seal.nonce(Seal.UP, 41, 0xABCDEF)))
        assertArrayEquals(body, Seal.open(keys.up, Seal.UP, 41, aad, a, lane = 0x123456))
        assertNull(Seal.open(keys.up, Seal.UP, 41, aad, a, lane = 0xABCDEF))
    }

    @Test
    fun directionsHaveTheirOwnKeys() {
        val secret = Seal.randomBytes(32)
        val a = Seal.Keys.of(secret)
        val b = Seal.Keys.of(secret)
        val pair = Seal.Keys.of(secret, "pair")
        assertArrayEquals(a.up, b.up)
        assertFalse(a.up.contentEquals(a.down))
        assertFalse(a.up.contentEquals(pair.up))
    }

    @Test
    fun windowRefusesReplaysAndOldCounters() {
        val w = Seal.Window(0, 0)
        assertTrue(w.fresh(1)); w.mark(1)
        assertFalse(w.fresh(1))
        // Out of order within the window: each taken once.
        assertTrue(w.fresh(5)); w.mark(5)
        assertTrue(w.fresh(3)); w.mark(3)
        assertFalse(w.fresh(3))
        assertTrue(w.fresh(4)); w.mark(4)
        // Far ahead; then the old ones are too old.
        assertTrue(w.fresh(200)); w.mark(200)
        assertFalse(w.fresh(5))
        assertFalse(w.fresh(136))
        assertTrue(w.fresh(137))
        assertFalse(w.fresh(0))
        assertFalse(w.fresh(-4))
    }

    @Test
    fun windowAfterRestartRefusesEverythingBelowTheReserve() {
        // Loaded from devices.json: nothing at or under the reserved counter is fresh.
        val w = Seal.Window(1000)
        assertFalse(w.fresh(1000))
        assertFalse(w.fresh(990))
        assertTrue(w.fresh(1001))
    }
}

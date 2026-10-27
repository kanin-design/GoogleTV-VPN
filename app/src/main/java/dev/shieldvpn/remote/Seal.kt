package dev.shieldvpn.remote

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * What keeps the panel's requests and answers private on a shared Wi-Fi: each body is encrypted
 * and authenticated with ChaCha20-Poly1305 under a key only the TV and one paired phone hold.
 *
 * Each direction has its own key, made with HKDF-SHA256 from the device key (or, while pairing,
 * from the secret in the QR code). The nonce is the direction (0 phone to TV, 1 TV to phone),
 * the page's lane and the request's counter; the method and path are the associated data, so a
 * sealed body only opens for the request it was made for. The page does the same in panel.js.
 *
 * The lane is three random bytes each page picks when it loads. Two tabs of one phone can take the
 * same counter at the same moment; with their own lanes their nonces still differ, so one key and
 * nonce never seal two different bodies. The TV takes each counter once, whatever the lane.
 */
object Seal {
    const val KEY_BYTES = 32
    const val UP = 0
    const val DOWN = 1

    private val random = SecureRandom()

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also(random::nextBytes)

    /** The two keys for [secret]: "ovpnc up v1" and "ovpnc down v1", or the pairing's. */
    class Keys(val up: ByteArray, val down: ByteArray) {
        companion object {
            fun of(secret: ByteArray, purpose: String = ""): Keys {
                val p = if (purpose.isEmpty()) "" else "$purpose "
                return Keys(hkdf(secret, null, "ovpnc ${p}up v1".toByteArray(), KEY_BYTES), hkdf(secret, null, "ovpnc ${p}down v1".toByteArray(), KEY_BYTES))
            }
        }
    }

    /** A page's lane: three bytes. */
    const val LANES = 1 shl 24

    fun nonce(direction: Int, counter: Long, lane: Int = 0): ByteArray {
        val n = ByteArray(12)
        n[0] = direction.toByte()
        n[1] = (lane ushr 16).toByte()
        n[2] = (lane ushr 8).toByte()
        n[3] = lane.toByte()
        for (i in 0 until 8) n[4 + i] = (counter ushr (56 - 8 * i)).toByte()
        return n
    }

    fun seal(key: ByteArray, direction: Int, counter: Long, aad: ByteArray, plain: ByteArray, lane: Int = 0): ByteArray =
        run(Cipher.ENCRYPT_MODE, key, nonce(direction, counter, lane), aad, plain)

    /** The plain bytes, or null when the seal is broken, the key is wrong or it was made for another request. */
    fun open(key: ByteArray, direction: Int, counter: Long, aad: ByteArray, sealed: ByteArray, lane: Int = 0): ByteArray? =
        if (sealed.size < 16) null else runCatching { run(Cipher.DECRYPT_MODE, key, nonce(direction, counter, lane), aad, sealed) }.getOrNull()

    /** RFC 8439's AEAD with a raw 12-byte nonce, as a test vector needs it. */
    fun aead(encrypt: Boolean, key: ByteArray, nonce: ByteArray, aad: ByteArray, input: ByteArray): ByteArray? =
        runCatching { run(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, key, nonce, aad, input) }.getOrNull()

    private fun run(mode: Int, key: ByteArray, nonce: ByteArray, aad: ByteArray, input: ByteArray): ByteArray {
        val c = cipher()
        c.init(mode, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce))
        c.updateAAD(aad)
        return c.doFinal(input)
    }

    // Android names it the first way (from Android 9); the JVM the tests run on, the second.
    private fun cipher(): Cipher = runCatching { Cipher.getInstance("ChaCha20/Poly1305/NoPadding") }.getOrElse { Cipher.getInstance("ChaCha20-Poly1305") }

    /** HKDF-SHA256 (RFC 5869). A null [salt] is a string of zeros, as the RFC says. */
    fun hkdf(ikm: ByteArray, salt: ByteArray?, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt?.takeIf { it.isNotEmpty() } ?: ByteArray(32), "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val out = ByteArray(length)
        var block = ByteArray(0)
        var done = 0
        var i = 1
        while (done < length) {
            mac.update(block)
            mac.update(info)
            mac.update(i++.toByte())
            block = mac.doFinal()
            val n = minOf(block.size, length - done)
            System.arraycopy(block, 0, out, done, n)
            done += n
        }
        return out
    }

    /**
     * The counters a device has used, so a request heard on the Wi-Fi can't be sent again. A page
     * has a few requests out at once, which can arrive out of order: a counter up to 63 below the
     * highest is still taken once. Not thread-safe; the caller holds the device's lock.
     */
    class Window(var top: Long, private var seen: Long = -1L) {
        fun fresh(n: Long): Boolean = when {
            n <= 0 -> false
            n > top -> true
            top - n >= 64 -> false
            else -> (seen ushr (top - n).toInt()) and 1L == 0L
        }

        /** Marks [n] as used; call only after its seal opened. */
        fun mark(n: Long) {
            if (n > top) {
                val shift = n - top
                seen = if (shift >= 64) 1L else (seen shl shift.toInt()) or 1L
                top = n
            } else {
                seen = seen or (1L shl (top - n).toInt())
            }
        }
    }

    fun b64(b: ByteArray): String = java.util.Base64.getEncoder().encodeToString(b)

    fun unb64(s: String): ByteArray? = runCatching { java.util.Base64.getDecoder().decode(s) }.getOrNull()

    fun b64url(b: ByteArray): String = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(b)
}

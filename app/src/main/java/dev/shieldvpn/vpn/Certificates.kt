package dev.shieldvpn.vpn

import android.content.Context
import android.security.KeyChain
import android.security.keystore.KeyProperties
import android.security.keystore.KeyProtection
import android.util.Base64
import dev.shieldvpn.APP_NAME
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import javax.crypto.Cipher

/**
 * Client certificates that live outside the .ovpn file: a .p12 file that is copied into a
 * profile, or a certificate installed on the TV, whose key never leaves Android's keystore.
 */
object Certificates {

    /** A client certificate and key as the .ovpn file carries them. */
    /** [issuer] is who signed the certificate, to find the profile whose CA it belongs to. */
    class Bundle(val certPem: String, val keyPem: String, val caPem: String?, val subject: String, val issuer: String = "") {
        override fun toString() = "Bundle($subject)"
    }

    sealed interface Read {
        class Done(val bundle: Bundle) : Read
        class Problem(val message: String) : Read
    }

    /** A .p12 or .pfx file, by its name or by looking like one (binary, starting with an ASN.1 sequence). */
    fun isPkcs12(name: String, bytes: ByteArray): Boolean {
        val n = name.lowercase()
        if (n.endsWith(".p12") || n.endsWith(".pfx")) return true
        if (bytes.size < 4 || bytes[0] != 0x30.toByte()) return false
        return bytes.take(256).count { it < 9 || it in 14..31 } > 8
    }

    fun readPkcs12(bytes: ByteArray, password: String): Read {
        val store = try {
            KeyStore.getInstance("PKCS12").apply { load(ByteArrayInputStream(bytes), password.toCharArray()) }
        } catch (e: Exception) {
            val wrong = e.message.orEmpty().contains("password", ignoreCase = true) || e.cause?.message.orEmpty().contains("mac", ignoreCase = true)
            return Read.Problem(if (wrong) "Wrong password for this .p12 file." else "${APP_NAME} could not open this .p12 file. Export it again with a password, or with “legacy” encryption.")
        }
        val alias = store.aliases().toList().firstOrNull { store.isKeyEntry(it) }
            ?: return Read.Problem("This .p12 file has no private key, so it can’t sign you in.")
        val key = runCatching { store.getKey(alias, password.toCharArray()) as? PrivateKey }.getOrNull()
            ?: return Read.Problem("${APP_NAME} could not read the key in this .p12 file.")
        val chain = store.getCertificateChain(alias)?.filterIsInstance<X509Certificate>().orEmpty()
        val leaf = chain.firstOrNull() ?: return Read.Problem("This .p12 file has no certificate.")
        val ca = chain.drop(1).takeIf { it.isNotEmpty() }?.joinToString("\n") { pem("CERTIFICATE", it.encoded) }
        return Read.Done(Bundle(pem("CERTIFICATE", leaf.encoded), pem("PRIVATE KEY", key.encoded), ca, leaf.subjectX500Principal.name, leaf.issuerX500Principal.name))
    }

    /** Aliases of keys the app keeps in Android's keystore itself, as opposed to ones installed on the TV. */
    // Never changes with the app's name: keys kept under it must still be found.
    private const val OWN = "tunnel:"

    /**
     * Moves the key and certificates of a .p12 into Android's keystore, where the key can sign
     * but never be read back, not even by the app. Returns the alias to use, or null if
     * [password] does not open [bytes]. This works on every TV; many TVs have no screen for
     * installing certificates system-wide.
     */
    fun keepInKeystore(bytes: ByteArray, password: String): String? = runCatching {
        val source = KeyStore.getInstance("PKCS12").apply { load(ByteArrayInputStream(bytes), password.toCharArray()) }
        val name = source.aliases().toList().first { source.isKeyEntry(it) }
        val key = source.getKey(name, password.toCharArray()) as PrivateKey
        val chain = source.getCertificateChain(name)
        val leaf = chain.first() as X509Certificate
        val cn = Regex("CN=([^,]+)").find(leaf.subjectX500Principal.name)?.groupValues?.get(1)?.trim() ?: "certificate"
        val id = "$cn-" + leaf.serialNumber.toString(16).takeLast(6)
        val protection = if (key.algorithm == "EC") {
            KeyProtection.Builder(KeyProperties.PURPOSE_SIGN).setDigests(*DIGESTS).build()
        } else {
            // Raw RSA (no padding) as well, for PSS signatures, which are built here.
            KeyProtection.Builder(KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_DECRYPT)
                .setDigests(*DIGESTS)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1, KeyProperties.SIGNATURE_PADDING_RSA_PSS)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        }
        keystore().setEntry(id, KeyStore.PrivateKeyEntry(key, chain), protection)
        OWN + id
    }.getOrNull()

    private val DIGESTS = arrayOf(
        KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA1, KeyProperties.DIGEST_SHA256,
        KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512,
    )

    private fun keystore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    /** The key and certificate chain for [alias]: one the app keeps, or one installed on the TV. */
    fun entry(context: Context, alias: String): Pair<PrivateKey, List<X509Certificate>>? = runCatching {
        if (alias.startsWith(OWN)) {
            val e = keystore().getEntry(alias.removePrefix(OWN), null) as KeyStore.PrivateKeyEntry
            e.privateKey to e.certificateChain.filterIsInstance<X509Certificate>()
        } else {
            val key = KeyChain.getPrivateKey(context, alias) ?: return@runCatching null
            key to KeyChain.getCertificateChain(context, alias)!!.toList()
        }
    }.getOrNull()

    /** Removes a key the app kept, once no profile uses it. Keys installed on the TV are left alone. */
    fun forget(alias: String) {
        if (alias.startsWith(OWN)) runCatching { keystore().deleteEntry(alias.removePrefix(OWN)) }
    }

    /** How an alias reads on screen. */
    fun label(alias: String): String =
        if (alias.startsWith(OWN)) alias.removePrefix(OWN).substringBeforeLast('-') + " (kept by ${APP_NAME})" else alias

    /** The config with this certificate and key inside it, replacing any it had. The CA is added only if missing. */
    fun attach(config: String, bundle: Bundle): String {
        var text = withoutClientCertificate(config).trimEnd()
        text += "\n<cert>\n${bundle.certPem.trim()}\n</cert>\n<key>\n${bundle.keyPem.trim()}\n</key>\n"
        if (bundle.caPem != null && "<ca>" !in text && text.lineSequence().none { it.trim().startsWith("ca ") }) {
            text += "<ca>\n${bundle.caPem.trim()}\n</ca>\n"
        }
        return text
    }

    /** The config without its own client certificate, for a profile that signs with one from the TV. */
    fun withoutClientCertificate(config: String): String =
        config
            .replace(Regex("<(cert|key|pkcs12)>[\\s\\S]*?</\\1>\\s*"), "")
            .lineSequence()
            .filterNot { it.trim().split(Regex("\\s+")).first() in setOf("cert", "key", "pkcs12") }
            .joinToString("\n")

    fun pem(type: String, der: ByteArray): String =
        "-----BEGIN $type-----\n" + Base64.encodeToString(der, Base64.NO_WRAP).chunked(64).joinToString("\n") + "\n-----END $type-----"

    /**
     * Signs [data] for the OpenVPN core with a key that stays in the keystore. [algorithm],
     * [hashalg] and [saltlen] are as the core sends them (see ExternalPKISignRequest in
     * ovpncli.hpp). With a hash algorithm, [data] is the message; without one, its digest.
     */
    fun sign(key: PrivateKey, publicKey: PublicKey, data: ByteArray, algorithm: String, hashalg: String, saltlen: String): ByteArray {
        val md = javaDigestName(hashalg)
        return when (algorithm) {
            "ECDSA" -> Signature.getInstance(if (md == null) "NONEwithECDSA" else "${md.replace("-", "")}withECDSA").run {
                initSign(key)
                update(data)
                sign()
            }
            "RSA_PKCS1_PADDING" -> Signature.getInstance(if (md == null) "NONEwithRSA" else "${md.replace("-", "")}withRSA").run {
                initSign(key)
                update(data)
                sign()
            }
            "RSA_NO_PADDING" -> rawRsa(key, data, publicKey)
            "RSA_PKCS1_PSS_PADDING" -> {
                // Keystore keys sign raw RSA, so the PSS encoding is done here (RFC 8017, 9.1.1).
                val hash = md ?: "SHA-256"
                val digest = if (md != null) MessageDigest.getInstance(hash).digest(data) else data
                val modBits = (publicKey as RSAPublicKey).modulus.bitLength()
                rawRsa(key, pssEncode(digest, hash, saltlen, modBits - 1), publicKey)
            }
            else -> error("Unsupported signature: $algorithm")
        }
    }

    private fun rawRsa(key: PrivateKey, data: ByteArray, publicKey: PublicKey): ByteArray {
        val k = ((publicKey as RSAPublicKey).modulus.bitLength() + 7) / 8
        val input = if (data.size < k) ByteArray(k - data.size) + data else data
        return Cipher.getInstance("RSA/ECB/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key)
            doFinal(input)
        }
    }

    private fun pssEncode(mHash: ByteArray, hash: String, saltlen: String, emBits: Int): ByteArray {
        val hLen = mHash.size
        val emLen = (emBits + 7) / 8
        val sLen = when (saltlen) {
            "", "digest" -> hLen
            "max", "auto" -> emLen - hLen - 2
            else -> saltlen.toIntOrNull() ?: hLen
        }
        val salt = ByteArray(sLen).also { SecureRandom().nextBytes(it) }
        val h = MessageDigest.getInstance(hash).run {
            update(ByteArray(8))
            update(mHash)
            update(salt)
            digest()
        }
        val db = ByteArray(emLen - hLen - 1)
        db[emLen - sLen - hLen - 2] = 0x01
        System.arraycopy(salt, 0, db, db.size - sLen, sLen)
        val mask = mgf1(h, db.size, hash)
        for (i in db.indices) db[i] = (db[i].toInt() xor mask[i].toInt()).toByte()
        db[0] = (db[0].toInt() and (0xFF ushr (8 * emLen - emBits))).toByte()
        return db + h + byteArrayOf(0xBC.toByte())
    }

    private fun mgf1(seed: ByteArray, length: Int, hash: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var counter = 0
        while (out.size() < length) {
            val c = byteArrayOf((counter ushr 24).toByte(), (counter ushr 16).toByte(), (counter ushr 8).toByte(), counter.toByte())
            out.write(MessageDigest.getInstance(hash).run { update(seed); update(c); digest() })
            counter++
        }
        return out.toByteArray().copyOf(length)
    }

    /** OpenSSL's digest names ("SHA256", "SHA2-256") as Java's ("SHA-256"), or null for none. */
    private fun javaDigestName(openssl: String): String? {
        val n = openssl.uppercase().replace("SHA2-", "SHA").replace("-", "")
        return when (n) {
            "" -> null
            "SHA1" -> "SHA-1"
            "SHA224", "SHA256", "SHA384", "SHA512" -> "SHA-" + n.removePrefix("SHA")
            else -> error("Unsupported hash: $openssl")
        }
    }

    /** The subjects of the CA certificates in a profile's <ca> block, as X.500 names. */
    fun caSubjects(config: String): List<String> {
        val block = Regex("<ca>([\\s\\S]*?)</ca>").find(config)?.groupValues?.get(1) ?: return emptyList()
        return runCatching {
            java.security.cert.CertificateFactory.getInstance("X.509")
                .generateCertificates(ByteArrayInputStream(block.trim().toByteArray()))
                .filterIsInstance<X509Certificate>()
                .map { javax.security.auth.x500.X500Principal(it.subjectX500Principal.encoded).name }
        }.getOrDefault(emptyList())
    }
}

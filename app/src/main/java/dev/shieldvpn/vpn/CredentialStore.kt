package dev.shieldvpn.vpn

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keeps a login for each server. Usernames are stored as plain text; passwords
 * are encrypted with a key held in the Android Keystore. A WireGuard profile's keys are
 * kept the same way, as its login.
 */
object CredentialStore {

    private const val PREFS = "shieldvpn_credentials"
    private const val KEY_ALIAS = "shieldvpn_credentials_key"
    private const val GCM_TAG_BITS = 128

    // Keys from before each server had its own login.
    private const val LEGACY_USER = "username"
    private const val LEGACY_PASS = "password"

    data class Credentials(val username: String, val password: String) {
        // So a log line or a crash report never carries the password.
        override fun toString() = "Credentials($username, password hidden)"
    }

    // The keystore key, looked up once: each lookup is a call into keystore2.
    @Volatile private var cachedKey: SecretKey? = null

    fun load(context: Context, profileId: String): Credentials {
        val prefs = prefs(context)
        val username = prefs.getString(userKey(profileId), null).orEmpty()
        val password = prefs.getString(passKey(profileId), null)?.let(::decrypt).orEmpty()
        return Credentials(username, password)
    }

    /** The username alone: no decrypting, for lists that only show it. */
    fun username(context: Context, profileId: String): String =
        prefs(context).getString(userKey(profileId), null).orEmpty()

    /**
     * Whether a password is saved, without decrypting it. One that no longer decrypts still counts;
     * Connect then finds it empty and asks for the login.
     */
    fun hasPassword(context: Context, profileId: String): Boolean =
        !prefs(context).getString(passKey(profileId), null).isNullOrEmpty()

    fun save(context: Context, profileId: String, username: String, password: String) {
        prefs(context).edit()
            .putString(userKey(profileId), username)
            .putString(passKey(profileId), encrypt(password))
            .apply()
    }

    /** A secret of the app's own, such as the backup passphrase, kept like a password. */
    fun putSecret(context: Context, name: String, value: String?) {
        val edit = prefs(context).edit()
        if (value == null) edit.remove("secret.$name") else edit.putString("secret.$name", encrypt(value))
        edit.apply()
    }

    fun secret(context: Context, name: String): String? = prefs(context).getString("secret.$name", null)?.let(::decrypt)

    fun hasSecret(context: Context, name: String): Boolean = prefs(context).contains("secret.$name")

    /** A WireGuard profile's keys, or null when none are saved or they can't be read. */
    fun wireGuard(context: Context, profileId: String): WireGuardFile.Secrets? {
        val text = prefs(context).getString(wgKey(profileId), null)?.let(::decrypt)?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching {
            val json = JSONObject(text)
            val psk = json.optJSONObject("presharedKeys")
            WireGuardFile.Secrets(json.getString("privateKey"), psk?.keys()?.asSequence()?.associateWith { psk.getString(it) }.orEmpty())
        }.getOrNull()
    }

    fun saveWireGuard(context: Context, profileId: String, secrets: WireGuardFile.Secrets) {
        val json = JSONObject().put("privateKey", secrets.privateKey).put("presharedKeys", JSONObject(secrets.presharedKeys))
        prefs(context).edit().putString(wgKey(profileId), encrypt(json.toString())).apply()
    }

    fun copy(context: Context, fromId: String, toId: String) {
        val login = load(context, fromId)
        save(context, toId, login.username, login.password)
        wireGuard(context, fromId)?.let { saveWireGuard(context, toId, it) }
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }

    fun remove(context: Context, profileId: String) {
        prefs(context).edit()
            .remove(userKey(profileId))
            .remove(passKey(profileId))
            .remove(wgKey(profileId))
            .apply()
    }

    /** Moves the single login from before per-server logins onto [profileId]. Does nothing after that. */
    fun adoptLegacy(context: Context, profileId: String) {
        val prefs = prefs(context)
        if (!prefs.contains(LEGACY_USER)) return
        prefs.edit()
            .putString(userKey(profileId), prefs.getString(LEGACY_USER, null))
            .putString(passKey(profileId), prefs.getString(LEGACY_PASS, null))
            .remove(LEGACY_USER)
            .remove(LEGACY_PASS)
            .apply()
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun userKey(profileId: String) = "user.$profileId"

    private fun passKey(profileId: String) = "pass.$profileId"

    private fun wgKey(profileId: String) = "wg.$profileId"

    /** Returns "iv:ciphertext", both parts Base64. */
    private fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val data = Base64.encodeToString(cipher.doFinal(plain.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        return "$iv:$data"
    }

    /** Returns an empty password if the stored value cannot be read, so the user can type it again. */
    private fun decrypt(stored: String): String {
        if (stored.isEmpty()) return ""
        return runCatching {
            val (iv, data) = stored.split(":", limit = 2)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                key(),
                GCMParameterSpec(GCM_TAG_BITS, Base64.decode(iv, Base64.DEFAULT)),
            )
            String(cipher.doFinal(Base64.decode(data, Base64.DEFAULT)), Charsets.UTF_8)
        }.getOrDefault("")
    }

    private fun key(): SecretKey = cachedKey ?: synchronized(this) { cachedKey ?: loadKey().also { cachedKey = it } }

    private fun loadKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }
}

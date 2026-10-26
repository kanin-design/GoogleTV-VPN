package dev.shieldvpn.vpn

/**
 * Thin JNI wrapper around the OpenVPN 3 core. Each call takes the native
 * handle returned by [create]. Functions that return a String return "" on
 * success and the error text otherwise.
 */
object OpenVpnEngine {

    init {
        System.loadLibrary("shieldvpn")
    }

    @JvmStatic external fun create(callbacks: NativeCallbacks): Long

    /** [options] holds "key=value" lines: proto, timeout, tlsMin, legacy, compress, epki, nocert. */
    fun evaluate(handle: Long, config: String, options: String): String =
        evaluate(handle, config.toByteArray(), options.toByteArray())

    @JvmStatic external fun needsCredentials(handle: Long): Boolean

    fun provideCredentials(handle: Long, username: String, password: String): String =
        provideCredentials(handle, username.toByteArray(), password.toByteArray())

    // Text crosses as UTF-8 bytes, which the core sends on as they are.
    @JvmStatic private external fun evaluate(handle: Long, config: ByteArray, options: ByteArray): String

    @JvmStatic private external fun provideCredentials(handle: Long, username: ByteArray, password: ByteArray): String

    /** Blocks until the tunnel ends. Run on a background thread. */
    @JvmStatic external fun connect(handle: Long): String

    @JvmStatic external fun stop(handle: Long)

    /** Bytes received and sent over the transport, in that order. */
    @JvmStatic external fun stats(handle: Long): LongArray

    @JvmStatic external fun reconnect(handle: Long, seconds: Int)

    @JvmStatic external fun destroy(handle: Long)
}

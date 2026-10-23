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

    /** [options] holds "key=value" lines: proto, timeout, tlsMin, legacy, compress. */
    @JvmStatic external fun evaluate(handle: Long, config: String, options: String): String

    @JvmStatic external fun needsCredentials(handle: Long): Boolean

    @JvmStatic external fun provideCredentials(handle: Long, username: String, password: String): String

    /** Blocks until the tunnel ends. Run on a background thread. */
    @JvmStatic external fun connect(handle: Long): String

    @JvmStatic external fun stop(handle: Long)

    /** Bytes received and sent over the transport, in that order. */
    @JvmStatic external fun stats(handle: Long): LongArray

    @JvmStatic external fun reconnect(handle: Long, seconds: Int)

    @JvmStatic external fun destroy(handle: Long)
}

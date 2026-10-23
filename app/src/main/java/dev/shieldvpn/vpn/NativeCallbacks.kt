package dev.shieldvpn.vpn

/**
 * Methods the native OpenVPN core calls back into. The names and signatures
 * must match the JNI lookups in app/src/main/cpp/shield_jni.cpp.
 */
interface NativeCallbacks {
    fun onEvent(name: String, info: String, error: Boolean)
    fun onLog(text: String)

    fun newBuilder(): Boolean
    fun setRemoteAddress(address: String, ipv6: Boolean): Boolean
    fun addAddress(address: String, prefix: Int, ipv6: Boolean): Boolean
    fun addRoute(address: String, prefix: Int, ipv6: Boolean): Boolean
    fun setMtu(mtu: Int): Boolean
    fun setSessionName(name: String): Boolean
    fun addDns(address: String): Boolean
    fun establish(): Int
    fun protectSocket(fd: Int, remote: String, ipv6: Boolean): Boolean
    fun teardown(disconnect: Boolean)

    /** A certificate installed on the TV: its PEM, the chain above it, and signatures with its key. */
    fun pkiCertificate(alias: String): String?
    fun pkiChain(alias: String): String?
    fun pkiSign(alias: String, data: String, algorithm: String, hashalg: String, saltlen: String): String?
}

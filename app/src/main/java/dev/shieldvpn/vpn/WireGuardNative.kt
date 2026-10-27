package dev.shieldvpn.vpn

/**
 * wireguard-go, through the small bridge in cpp/wg_jni.c. Only the VPN's process loads it. The
 * device state from [config] holds the private key: read what is needed and drop it.
 */
internal object WireGuardNative {

    /** Both libraries loaded and wireguard-go's functions found. */
    val available: Boolean by lazy {
        runCatching {
            System.loadLibrary("wg-go")
            System.loadLibrary("shieldwg")
            ready()
        }.getOrDefault(false)
    }

    @JvmStatic private external fun ready(): Boolean

    /** Returns a handle of 0 or more, or -1. wireguard-go owns [tunFd] from now on. */
    @JvmStatic external fun turnOn(ifname: String, tunFd: Int, settings: ByteArray): Int

    @JvmStatic external fun turnOff(handle: Int)

    @JvmStatic external fun socketV4(handle: Int): Int

    @JvmStatic external fun socketV6(handle: Int): Int

    @JvmStatic external fun config(handle: Int): ByteArray?

    @JvmStatic external fun version(): String?
}

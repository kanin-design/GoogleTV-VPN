package dev.shieldvpn

/**
 * Work that is built but not shown yet. Each is a build flag, not a switch in the app: with it off,
 * `if (Features.X)` is false when compiling, so that code is left out of the APK.
 */
object Features {
    /** WireGuard profiles: only in builds made with -Pwireguard=true. */
    const val WIREGUARD = BuildConfig.WIREGUARD
}

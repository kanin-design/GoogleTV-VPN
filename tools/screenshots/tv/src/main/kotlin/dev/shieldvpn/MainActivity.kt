package dev.shieldvpn

/** Stands in for the app's, which is left out: it needs AndroidX's activity library. */
class MainActivity : android.app.Activity() {
    companion object {
        @Volatile var screens = 0
    }
}

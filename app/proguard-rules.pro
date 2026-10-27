# The OpenVPN core calls back into Kotlin by method name (shield_jni.cpp), so keep those names.
-keep interface dev.shieldvpn.vpn.NativeCallbacks { *; }
-keepclassmembers class * implements dev.shieldvpn.vpn.NativeCallbacks { *; }
-keepclasseswithmembernames class dev.shieldvpn.vpn.OpenVpnEngine { native <methods>; }
# The WireGuard bridge (cpp/wg_jni.c) is found by these names too.
-keepclasseswithmembernames class dev.shieldvpn.vpn.WireGuardNative { native <methods>; }

# Release builds drop debug and verbose log lines: the core's log goes to the app's own log view.
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}

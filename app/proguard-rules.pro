# The OpenVPN core calls back into Kotlin by method name (shield_jni.cpp), so keep those names.
-keep interface dev.shieldvpn.vpn.NativeCallbacks { *; }
-keepclassmembers class * implements dev.shieldvpn.vpn.NativeCallbacks { *; }
-keepclasseswithmembernames class dev.shieldvpn.vpn.OpenVpnEngine { native <methods>; }

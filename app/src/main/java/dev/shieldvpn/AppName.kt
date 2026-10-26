package dev.shieldvpn

/**
 * The app's name, wherever a person reads it. It is a working name: it comes from appName in
 * gradle.properties, which also names the app on the TV's home screen.
 */
const val APP_NAME: String = BuildConfig.APP_NAME

/** The name in file names: lowercase letters and digits, as in "openvpnclient-log.txt". */
val APP_FILE_NAME: String = APP_NAME.lowercase().filter { it.isLetterOrDigit() }.ifEmpty { "vpn" }

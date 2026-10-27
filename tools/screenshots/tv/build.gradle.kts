import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

/*
 * Draws the TV's screens with Compose for desktop: the app's own code from app/src/main/java, with
 * the few changes below that let it run on a computer, on Skia (which Android draws with too), in
 * Android TV's fonts. tools/screenshots/shoot.sh runs it.
 *
 * Everything comes from Maven Central. Compose for desktop 1.6.0-beta02 is the last whose JVM
 * libraries all do; later ones take androidx.collection from Google's Maven. For a network that
 * limits Central, pass a mirror: -PmavenMirror=https://… (or ORG_GRADLE_PROJECT_mavenMirror).
 */

plugins {
    kotlin("jvm") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
    application
}

repositories {
    providers.gradleProperty("mavenMirror").orNull?.let { maven(it) }
    mavenCentral()
}

val repo: File = rootDir.resolve("../../..").canonicalFile
val app: File = repo.resolve("app")

// Android's framework classes (Robolectric's build of Android 14), for the app's code to compile and
// for the few it touches here, such as org.json. WireGuard's key classes come from its library.
val androidAll: Configuration by configurations.creating { isTransitive = false }
val wireguardAar: Configuration by configurations.creating { isTransitive = false }

// The Skia library for this computer.
val skiko = run {
    val os = System.getProperty("os.name").lowercase()
    val arm = System.getProperty("os.arch").let { it == "aarch64" || it == "arm64" }
    when {
        "mac" in os -> if (arm) "macos-arm64" else "macos-x64"
        "windows" in os -> "windows-x64"
        else -> if (arm) "linux-arm64" else "linux-x64"
    }
}

val cmp = "1.6.0-beta02"
dependencies {
    implementation("org.jetbrains.compose.runtime:runtime-desktop:$cmp")
    implementation("org.jetbrains.compose.foundation:foundation-desktop:$cmp")
    implementation("org.jetbrains.compose.ui:ui-desktop:$cmp")
    implementation("org.jetbrains.compose.animation:animation-desktop:$cmp")
    implementation("org.jetbrains.skiko:skiko-awt-runtime-$skiko:0.7.90")
    implementation("com.google.zxing:core:3.5.3")
    androidAll("org.robolectric:android-all:14-robolectric-10818077")
    wireguardAar("com.wireguard.android:tunnel:1.0.20260102@aar")
}

/** The framework's classes alone: its copies of java.* and its resources stay out. */
val androidFramework by tasks.registering(Jar::class) {
    archiveFileName.set("android-framework.jar")
    destinationDirectory.set(layout.buildDirectory.dir("libs"))
    from({ zipTree(androidAll.singleFile) }) { exclude("java/**", "javax/**", "res/**", "assets/**", "resources.arsc", "META-INF/**") }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

val wireguardJar by tasks.registering(Copy::class) {
    from({ zipTree(wireguardAar.singleFile) }) {
        include("classes.jar")
        rename { "wireguard-tunnel.jar" }
    }
    into(layout.buildDirectory.dir("libs"))
}

dependencies {
    implementation(files(androidFramework))
    implementation(files(layout.buildDirectory.file("libs/wireguard-tunnel.jar")).builtBy(wireguardJar))
}

/*
 * The changes to the app's code, by file. An import is replaced as a whole line, anything else
 * where it is found in a line. The copy fails when one no longer applies, so a change to the app
 * can't quietly make the pictures wrong.
 */
val patches: Map<String, List<Pair<String, String>>> = mapOf(
    // Mesh and the background draw through Android's Canvas; here through Skia (Canvas.kt).
    "ui/tv/Mesh.kt" to listOf(
        "import android.graphics.Paint" to "import render.Paint",
        "import android.graphics.PorterDuff" to "import render.PorterDuff",
        "import android.graphics.PorterDuffXfermode" to "import render.PorterDuffXfermode",
        "import androidx.compose.ui.graphics.nativeCanvas" to "import render.nativeCanvas",
    ),
    "ui/tv/Backdrop.kt" to listOf(
        "import androidx.compose.ui.graphics.nativeCanvas" to "import render.nativeCanvas",
        "import android.animation.ValueAnimator" to "import render.ValueAnimator",
    ),
    // Android TV's fonts, from files: Compose for desktop has no Roboto behind FontFamily.SansSerif.
    "ui/tv/Tokens.kt" to listOf(
        ": FontFamily = FontFamily.SansSerif" to ": FontFamily = render.Fonts.Sans",
        ": FontFamily = FontFamily.Monospace" to ": FontFamily = render.Fonts.Mono",
    ),
    "ui/tv/Components.kt" to listOf(
        // A desktop key event has no Android input device and no repeat count.
        "nativeKeyEvent.device?.keyboardType == android.view.InputDevice.KEYBOARD_TYPE_ALPHABETIC" to "false",
        // Compose for desktop 1.6 drops a focus request that passes one of these redirects, so no
        // screen could put focus where it starts. On Android they only steer the remote into a group.
        "focusProperties { enter = { target()?.takeIf { it.isOnScreen } ?: FocusRequester.Default } }.focusGroup()" to "focusGroup()",
    ),
    "ui/tv/TunnelApp.kt" to listOf(
        "e.nativeKeyEvent.repeatCount == 1" to "false",
        // The renderer says where the app is.
        "val nav = remember { Nav().apply { contentFocus = Screen.Connect } }" to
            "val nav = remember { render.Hooks.nav ?: Nav().apply { contentFocus = Screen.Connect } }",
    ),
    // An address for the code on Import, which needs a home network.
    "phone/PhoneServer.kt" to listOf(
        "val address: String? get() = HttpServer.address" to "val address: String? get() = render.Hooks.address ?: HttpServer.address",
    ),
    // A result at once, instead of a test against Cloudflare.
    "vpn/SpeedTest.kt" to listOf(
        "suspend fun run(report: (Phase, Double) -> Unit): Result {" to
            "suspend fun run(report: (Phase, Double) -> Unit): Result {\n" +
            "        render.Hooks.speed?.let { r -> report(Phase.Ping, r.pingMs.toDouble()); report(Phase.Down, r.downMbps); report(Phase.Up, r.upMbps); return r }",
    ),
)

val appSources by tasks.registering(Sync::class) {
    from(app.resolve("src/main/java")) {
        // Both need AndroidX libraries only on Google's Maven; the renderer stands in for them.
        exclude("dev/shieldvpn/MainActivity.kt", "dev/shieldvpn/vpn/VpnViewModel.kt")
    }
    into(layout.buildDirectory.dir("app-src"))
    // There is no Android main looper here: posts to it go nowhere (MainThread.kt).
    filter { it.replace("Handler(Looper.getMainLooper())", "render.MainThread") }
    patches.forEach { (file, changes) ->
        filesMatching("dev/shieldvpn/$file") {
            filter { line -> changes.fold(line) { l, (from, to) -> if (from.startsWith("import ")) (if (l == from) to else l) else l.replace(from, to) } }
        }
    }
    inputs.property("patches", patches.toString())
    doLast {
        patches.forEach { (file, changes) ->
            val text = destinationDir.resolve("dev/shieldvpn/$file").readText()
            changes.forEach { (from, to) ->
                val first = to.lineSequence().first()
                val applied = if (from.startsWith("import ")) text.lineSequence().any { it == first } else first in text
                check(applied) { "tools/screenshots: \"$from\" is no longer in $file. Update the patches in tools/screenshots/tv/build.gradle.kts." }
            }
        }
    }
}

/** BuildConfig as the app's build makes it: the name from gradle.properties, the version from app/build.gradle.kts. */
val buildConfig by tasks.registering {
    val out = layout.buildDirectory.dir("generated/buildconfig")
    val appName = Properties().apply { repo.resolve("gradle.properties").inputStream().use { load(it) } }.getProperty("appName")
    val versionName = Regex("""versionName = "([^"]+)"""").find(app.resolve("build.gradle.kts").readText())?.groupValues?.get(1) ?: "0"
    inputs.property("appName", appName)
    inputs.property("versionName", versionName)
    outputs.dir(out)
    doLast {
        val file = out.get().file("dev/shieldvpn/BuildConfig.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            |package dev.shieldvpn
            |
            |object BuildConfig {
            |    const val DEBUG = true
            |    const val VERSION_CODE = 1
            |    const val VERSION_NAME = "$versionName"
            |    const val COMMIT = "screenshots"
            |    const val APP_NAME = "$appName"
            |    const val WIREGUARD = true
            |}
            |
            """.trimMargin(),
        )
    }
}

sourceSets.main {
    kotlin.srcDirs(appSources, buildConfig)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.addAll(
            "-nowarn",
            "-opt-in=androidx.compose.animation.ExperimentalAnimationApi",
            "-opt-in=androidx.compose.ui.ExperimentalComposeUiApi",
            "-opt-in=androidx.compose.ui.InternalComposeUiApi",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
        )
    }
}

application {
    mainClass.set("render.MainKt")
    applicationDefaultJvmArgs = listOf(
        "-Xmx3g",
        "-Djava.awt.headless=true",
        "-Dskiko.renderApi=SOFTWARE",
        "-Drender.fonts=${rootDir.resolve("../build/fonts").canonicalPath}",
    )
}

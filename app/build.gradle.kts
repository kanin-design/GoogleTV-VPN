plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The build number: the commits on the checked-out branch, so every published build is higher than
// the one before, even under the same version name. It is the versionCode, and updates compare it.
// -PbuildNumber=N overrides it, to test an update.
fun git(vararg args: String): String? = runCatching {
    providers.exec { commandLine("git", *args) }.standardOutput.asText.get().trim().ifEmpty { null }
}.getOrNull()
val buildNumber = providers.gradleProperty("buildNumber").orNull?.toInt() ?: git("rev-list", "--count", "HEAD")?.toInt() ?: 1
val buildCommit = git("rev-parse", "--short=7", "HEAD") ?: "unknown"

android {
    namespace = "dev.shieldvpn"
    compileSdk = 34
    ndkVersion = "26.1.10909125"

    defaultConfig {
        applicationId = "dev.shieldvpn"
        minSdk = 28
        targetSdk = 34
        versionCode = buildNumber
        versionName = "0.1.2"
        buildConfigField("String", "COMMIT", "\"$buildCommit\"")

        // The name people see: appName in gradle.properties. Code reads it as APP_NAME (AppName.kt).
        val appName = providers.gradleProperty("appName").get()
        resValue("string", "app_name", appName)
        buildConfigField("String", "APP_NAME", "\"$appName\"")

    }

    // One APK per ABI, so each carries only its own native library: 64-bit ARM (the Shield and
    // most TVs) and 32-bit ARM (TVs and sticks with a 32-bit Android, such as the Chromecast with
    // Google TV). OpenSSL is built for each (third_party/OPENSSL-BUILD.md).
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
        }
    }

    buildTypes {
        // Release: code shrinking cuts the app and its memory. It is signed with the debug key,
        // so it installs over a debug build on a TV without losing profiles.
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    externalNativeBuild {
        ndkBuild {
            path = file("src/main/cpp/Android.mk")
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")

    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    // QR codes for sending files between the TV and a phone.
    implementation("com.google.zxing:core:3.5.3")
}

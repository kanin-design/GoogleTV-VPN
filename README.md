# Tunnel

An OpenVPN client for Android TV and Google TV, made for the remote. ("Tunnel" is a working name.)

![Tunnel on the TV's home screen](app/src/main/res/drawable-xxxhdpi/banner.png)

![Connected, with Quick connect below](docs/screenshots/connect.png)

## What it does

- **Connects with one press.** Connect shows the current profile, large, with the button you most likely want already focused. Quick connect cards below switch profiles. The background shows where your traffic goes: green from this device through your VPN to the internet while connected, yellow while it connects, and red straight to the internet when off.
- **Imports profiles without typing.** Scan a QR code with your phone and pick or paste the `.ovpn` file there, or use a USB drive, Downloads or an HTTPS link. Every file is checked before it is saved, and a login inside the file is moved out of it and encrypted.
- **Client certificates.** A `.p12` or `.pfx` file goes into the profile, or into Android's keystore, where the key can sign in but can't be copied off the device. A certificate already installed on the device works too.
- **Protection.** A kill switch, IPv6 and DNS leak blocking, and the choice to keep casting and devices at home reachable.
- **Per-app routing.** Let streaming services that block VPNs skip it, or send only the apps you choose through it.
- **Speed test.** Ping, download and upload through the VPN, on a speedometer, with whether it is fast enough for 4K or HD and how much of your plain connection the VPN keeps.
- **Everyday controls.** Pause for 15 minutes, connect when the device starts, and reconnect by itself after Wi-Fi changes. There are a connection test, a connection log and connection details.
- **Backups.** One encrypted file (PBKDF2 and AES-GCM) moves every profile, saved password and setting to a new device through your phone.
- **Updates.** Once a day it looks on GitHub for a newer build, by build number, so a build republished under the same version counts too. Settings › About downloads the APK for the device, checks its SHA-256 and installs it; Android asks you to confirm.
- **PIN lock** for settings or the whole app, and **automation intents** for home automation apps and ADB.
- **Remote, keyboard or mouse.** Everything works with the remote; a keyboard types into fields, and a mouse can click anything.
- **Light on the device.** The VPN runs in its own small process (about 15 MB, measured on the Android TV emulator). The screens close themselves ten minutes after you leave them, so the device gets that memory back while you watch.

## Screenshots

| | |
|---|---|
| ![Profiles](docs/screenshots/profiles-list.png) | ![A profile's settings](docs/screenshots/profiles.png) |
| Profiles, with the one in focus shown large | A profile: name, icon, sign-in, startup and more |
| ![Import from a phone](docs/screenshots/import.png) | ![Settings › Apps](docs/screenshots/settings-apps.png) |
| Import: scan the code and send the file from your phone | Settings › Apps: which apps skip the VPN |
| ![Speed test](docs/screenshots/speedtest.png) | ![Connection details](docs/screenshots/details.png) |
| Speed test through the VPN | Connection details |

The screenshots use demo profiles on the Android TV emulator. The speed test is a real run through a home OpenVPN server about 250 ms away.

## Install

Download the APK from [Releases](https://github.com/kanin-design/GoogleTV-VPN/releases) and sideload it: with `adb install`, or with a downloader app on the device.

```
adb install tunnel-0.1.0-beta-arm64.apk
```

It needs Android TV or Google TV on Android 9 or newer, on ARM. There is one APK for each kind of chip: `arm64` for 64-bit Android, such as the NVIDIA Shield, and `armv7` for 32-bit Android, which some Google TV devices run, the Chromecast with Google TV among them. If you are not sure, `adb shell getprop ro.product.cpu.abi` says which: `arm64-v8a` or `armeabi-v7a`.

Later builds install from the app, in Settings › About › Updates. The first time, Android asks you to allow Tunnel to install apps.

This is a beta, signed with a development key. A later build signed with a release key will need the beta uninstalled first. Back up your profiles before that (Settings › Backup).

## Build

You need JDK 17, the Android SDK (platform 34) and the NDK 26.1.10909125.

```
git clone --recursive https://github.com/kanin-design/GoogleTV-VPN.git
cd GoogleTV-VPN
```

The libraries are submodules in `third_party/`, pinned to the commits the app is built with. OpenSSL is built once into `third_party/openssl-install`, as described in [third_party/OPENSSL-BUILD.md](third_party/OPENSSL-BUILD.md). Then:

```
JAVA_HOME=/path/to/jdk-17 ./gradlew assembleRelease
```

The APKs are `app-arm64-v8a-release.apk` and `app-armeabi-v7a-release.apk` in `app/build/outputs/apk/release/`. Release builds are shrunk with R8 and signed with your debug key, so they install over a debug build without losing profiles.

Each build is numbered by the commits on its branch (`git rev-list --count HEAD`). The number is the APKs' `versionCode`, Settings › About shows it, and the update check compares it. To publish a release, from a clean checkout:

```
tools/release.sh v0.1.0-beta
```

It builds both APKs, writes `update.json` (the version, build number, commit, and each APK's name, size and SHA-256) and uploads all three to that GitHub release with `gh`. `--dry-run` builds without uploading. The app finds updates through `update.json`, so every release needs it.

To rename the app, change `appName` in `gradle.properties`. Every screen, the notification and the home-screen label read it.

The TV banner and the launcher icon are a placeholder, "VPN" on a teal gradient, drawn by `tools/banner.py` with a heavy wide face such as Archivo Expanded Black (SIL Open Font License):

```
python3 tools/banner.py Archivo-ExpandedBlack.ttf
```

## How it is built

- The [OpenVPN 3](https://github.com/OpenVPN/openvpn3) core in C++ with OpenSSL 3.5, through JNI (`app/src/main/cpp`).
- Jetpack Compose on Compose's foundation only, without the Material library.
- Drawn at the screen's full resolution, never upscaled. Android TV gives every app a 1920 × 1080 window, even on a 4K TV, and the TV scales that to 4K, as it does the home screen and every other app. Tunnel fills that window pixel for pixel, and a 4K window if a TV offers one. Its layout is measured in design units, 1280 × 720 of them to the screen, but units aren't pixels: 1.5 pixels a unit at 1080p, 3 at 4K.
- The VPN service runs in its own process (`:vpn`) and tells the screens about the tunnel over a Binder (`vpn/VpnLink.kt`).
- [docs/use-cases.md](docs/use-cases.md) has the behaviour, test notes and measurements of memory and CPU.

## Privacy

What stays on the device: your profiles and settings, in the app's private storage. Saved passwords and the backup passphrase are encrypted with a key in the device's keystore, and the PIN is kept as a salted hash.

What leaves the device:
- the VPN traffic itself, to the servers named in your profiles;
- while connected, one request through the tunnel to api.ipify.org (or icanhazip.com), to show the address sites see;
- files you send to or from your phone, which stay on your home network;
- a speed test, only when you start one: data to and from speed.cloudflare.com;
- once a day, unless you turn it off in Settings › About: a look at this app's releases on GitHub (api.github.com) for a newer build, and the build itself when you choose to install it.

No accounts, no analytics, no ads.

## Third-party software

OpenVPN 3 core (MPL 2.0), OpenSSL (Apache 2.0), asio (Boost Software License 1.0), fmt (MIT), LZ4 and xxHash (BSD 2-Clause), ZXing (Apache 2.0), Material Symbols (Apache 2.0), and Jetpack Compose and AndroidX (Apache 2.0). The full texts are in [app/src/main/assets/licenses.txt](app/src/main/assets/licenses.txt), and in the app under Settings › About.

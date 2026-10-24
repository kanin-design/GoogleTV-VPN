# Shield VPN: use cases

This describes how the app should behave, from first launch through daily use.
It is written for the Android TV remote: D-pad only, no touch, and typing is slow.
Text is sized for a 55-inch or larger screen, so it sits smaller than phone sizing.
Typing is limited to the few places that need it: names, usernames, passwords, and server addresses.

## Concepts

- **Server**: where the tunnel goes. A host, a port, a protocol, and the certificate and TLS settings from an `.ovpn` file. A server comes from an imported file or from manual entry.
- **Connection**: what the user connects with. A name, one server, a username, a password, and the options it uses. One server can have many connections, for example two logins on the same server.
- **Active connection**: the connection the Home screen is using. It is set when the user connects one.
- **Status**: what the tunnel is doing right now. It is shown on Home and as an indicator on each connection.

| Status | Meaning | Indicator |
|---|---|---|
| Idle | Not connected | Grey |
| Connecting | Setting up the tunnel | Amber, animated |
| Connected | Traffic goes through the VPN | Green |
| Failed | The last attempt failed. The reason is shown under the name. | Red |
| Blocked | The tunnel dropped and the kill switch is holding traffic back | Coral |

## Tabs

Three tabs, in this order: **Home**, **Connections**, **Settings**.
There is no tab for passwords or servers. Those live inside a connection.

Assumption, to confirm: servers are not a separate tab. A server is created when a file is imported or a connection is added manually. Its address and options are edited in the connection's Advanced section.

## Screens

### Home

- The animated shield, one status line, and one button. Nothing else.
- Status line: "Not protected", "Connecting", "Protected", "Blocked", or "Failed", with the reason under it when failed.
- Under the status: the active connection's name, for example "Home login, Client".
- Button: **Connect** when idle or failed, **Cancel** while connecting, **Disconnect** when connected or blocked.
- With no connections yet, the button is replaced by the first-run card (UC-01).

### Connections

- A list of every connection. Each row shows the indicator, the name, the server address in smaller text, and a short state line such as "Connected" or "Failed: wrong password".
- Selecting a row opens its panel.
- **Add connection** sits at the top of the list.

### Connection panel

Opens when a row is selected, and animates in over the list.

- First item, large: **Connect**. It reads **Disconnect** when this connection is the active one.
- Below it, in smaller text, the settings as rows:
  - Name
  - Server: the address, shown here and changed in Advanced
  - Username
  - Password: masked, with a Show toggle
  - Advanced ›
  - Duplicate
  - Delete
- Back closes the panel and returns to the list.

### Advanced (inside a connection)

- Collapsed by default, so the panel stays short.
- Server address, port, and protocol.
- Options from the `.ovpn` file, such as cipher, compression, and DNS. Shown read-only, because typing them on a TV is slow.
- The raw config: the full `.ovpn` text, read-only, with a button to replace it with a new file.

### Settings

- **Kill switch**: On or Off, with a one-line explanation of what it does.
- **Connect at startup**: On or Off.

## Use cases

### UC-01: First launch, nothing set up

**Trigger:** the app opens with no connections.

1. Home shows the first-run card: "Add your VPN connection", with two buttons, **Import .ovpn file** and **Add manually**.
2. Either button goes to Connections and starts that flow: UC-02 or UC-03.
3. When the first connection is saved, Home shows its name and the **Connect** button.

**Result:** one connection exists, with status Idle. Nothing connects by itself.

### UC-02: Import an `.ovpn` file

**Trigger:** Import .ovpn file.

1. The system file picker opens. On the Shield this is the built-in Files picker.
2. The user picks the file.
3. The app reads it and checks that it contains a server line.
4. A server and a connection are created. The name comes from the file name, so `client.ovpn` becomes "Client".
5. If the file holds a username and password, they are filled in. If not, they are left empty, and Connect asks for them (UC-04).

**Failures:** not an OpenVPN file, over 256 KB, or no `remote` line. Show the message on Connections and save nothing (UC-21).

### UC-03: Add a server and connection manually

**Trigger:** Add manually.

1. Name the connection.
2. Enter the server address, the port, and the protocol (UDP or TCP, picked from a list).
3. Enter the username and password.
4. Save. The connection appears in the list with status Idle.

**Limit:** most servers also need a certificate. Manual entry cannot create one. Until this is decided (see open decisions), Connect will fail with a certificate error and say so.

### UC-04: Connect a connection that has no login

**Trigger:** Connect on a connection with an empty username.

1. The app does not try to connect.
2. The connection panel opens at Username, with the keyboard up.
3. After the username and password are saved, the user presses **Connect** again. (Built this way: there is no single moment when "the login is done", so the app does not guess and connect on its own.)

### UC-05: Connect and it works

**Trigger:** Connect on Home, or Connect in a connection panel.

1. Home shows Connecting, amber, with the animated shield.
2. The tunnel comes up. Home shows Protected, green.
3. The connection's row turns green, and Home shows its name.

**Result:** all traffic goes through the VPN.

### UC-06: Wrong password

1. Connect. The server rejects the login.
2. The status becomes Failed: "Wrong username or password".
3. The connection row turns red and shows the reason. Home shows the same reason, and the button reads **Connect**.
4. The password is kept, so the user can open the panel and change only the password.

### UC-07: The server does not answer

1. Connect. Nothing comes back before the timeout.
2. The status becomes Failed: "Server did not respond".
3. Retry is just **Connect** again. Nothing else needs to be changed.

### UC-08: Two connections to the same server with different logins

**Example:** "Home login" as user A and "Work login" as user B, both on the same server address.

1. Duplicate an existing connection (UC-10).
2. Rename the copy (UC-11), then change its username and password (UC-12).
3. Both rows now show the same server address under different names. Switching between them needs no typing.

### UC-09: Choose which connection Home uses

1. Open Connections, select a row, and press **Connect**.
2. That connection becomes the active one. Home shows its name.

**Note:** with a connection already active, this disconnects it first and then connects the new one (UC-18).

### UC-10: Duplicate a connection

1. Open the panel and press **Duplicate**.
2. A copy appears, named "Name (copy)", with the same server and login. Its panel opens with focus on Name.
3. The user changes only what differs.

### UC-11: Rename a connection

1. Open the panel, select Name, and type the new name.
2. The name updates on Home and in the list.

**Note:** renaming the active connection does not disconnect it.

### UC-12: Change a password

1. Open the panel and select Password. The keyboard opens.
2. The Show toggle reveals the text while it is typed.
3. The password is saved for this connection only. Other connections on the same server keep their own.

### UC-13: Change server settings

1. Open the panel, then Advanced.
2. Change the port or protocol.
3. The change applies at the next connect. A connection that is running keeps its current settings until it reconnects, and the panel says so.

**Note:** connections on the same server share its address, port and protocol. Changing them in one connection's Advanced section changes them for all of those connections.

### UC-14: Replace the server file

**Trigger:** the server's certificate or options changed and the user has a new `.ovpn`.

1. Open Advanced and choose **Replace .ovpn file**. The picker opens.
2. The server's address and options update.
3. Every connection on that server keeps its own username and password.
4. A connection that now has no login prompts for one (UC-04).

### UC-15: Delete a connection

1. Open the panel and press **Delete**. It asks once more: "Press again to delete".
2. The row is removed.
3. If other connections use the same server, the server stays. If this was the last connection on it, the server is removed too.

**Guard:** while a tunnel is up, Delete reads "Disconnect to delete" and does nothing.

### UC-16: Kill switch holds traffic back

**Precondition:** kill switch on, connected.

1. The tunnel drops on its own.
2. Status becomes Blocked, coral. Home says "Internet is blocked until you disconnect."
3. The button reads **Disconnect**. Pressing it restores normal internet and sets the status to Idle.

### UC-17: Tunnel drops with the kill switch off

1. The tunnel drops.
2. Status becomes Failed with the reason "Connection dropped". Normal internet continues.
3. The row turns red with the same reason.

### UC-18: Switch to another connection while connected

1. Connected to connection A. The user selects connection B and presses **Connect**.
2. The app disconnects A and connects B, showing Connecting for B.
3. Nothing is asked of the user, because only one tunnel runs at a time.

### UC-19: Reboot the Shield

1. The app opens on Home with the last active connection selected.
2. Status is Not protected, because nothing connects by itself.
3. If **Connect at startup** is on, the app connects that connection at boot. This is not built yet.

### UC-20: Navigate with the remote only

1. The D-pad moves focus. OK opens a row or a button.
2. Back closes a panel and returns to the list. Back closes the keyboard first, if it is open.
3. Typing happens only in Name, Username, Password, and Server address.

### UC-21: An import that will not work

- **Not an OpenVPN file:** "That file has no server address." Nothing is saved.
- **Too big (over 256 KB):** "That file is too big to be an OpenVPN profile." Nothing is saved.
- **Picker cancelled:** nothing changes.

### UC-22: Upgrade from the current build

1. The current single login moves to a connection named after the first server, "Client".
2. Its password is read from the encrypted store. If it cannot be read, the field is empty and the next Connect asks for it.

### UC-23: Change the kill switch or startup setting

1. Settings: select the setting and press OK to toggle it.
2. The kill switch applies at the next drop. The startup setting applies at the next boot.

## Open decisions

These are assumptions in this document. Each needs confirming before the rebuild.

1. **Servers as a tab?** Assumed no. Servers live inside connections.
2. **Certificates for manual entry.** Manual entry needs a certificate for most servers. Either require an `.ovpn` file for that, or allow a certificate to be pasted.
3. **Connect makes the active connection.** Assumed yes. Pressing Connect on a row also makes it the one Home uses.
4. **Kill switch and startup setting** sit on the Settings tab. Assumed yes.
5. **One tunnel at a time.** Assumed yes.
6. **Raw config is read-only.** Assumed yes, because typing it on a TV is slow.

## Test notes

- The emulator's Android TV image has no file picker. Import (UC-02) has to be tested on the Shield.
- The UI is laid out on a 1280x720 design canvas and scaled to the screen's pixels (`TvStage`), so it looks the same at every size. Google TV caps an app's UI at 1920x1080 (`config_maxUiWidth`) and the TV upscales it to 4K; the Shield runs 1920x1080 at 320 dpi. A native 4K surface would get 3 pixels per design unit. Checked on the emulator at 1920x1080 and 1280x720 (2026-10-23). Checked again 2026-10-24: the Shield reports a 3840x2160 screen and a 1920x1080 app window, and an emulator with a 4K screen keeps apps at 1920x1080 even after `wm size reset` and a forced size. The only bitmaps the app makes, the app icons in Settings › Apps, are made at the size they are shown (60 px at 1080p, 120 px in a 4K window).
- `adb shell input keyevent` sends keys from a virtual device. Compose text fields only move focus out on D-pad presses from a real remote, so with adb, leave a field with Done (Enter) or Back.

### Checked on the emulator (2026-10-23)

| Use case | Result |
|---|---|
| UC-22 upgrade | The old `profiles/client.ovpn` became server and connection "Client" with the same id. |
| UC-04 no login | Home's Connect opened the Client panel at Username, with the keyboard up and the hint shown. |
| UC-12 login | The username saved for that connection only; focus returned to the row. |
| UC-10 duplicate | The copy opened with focus on Name and the same login. |
| UC-11 rename | Name saved; list and panel updated. |
| UC-15 delete | Two presses; the copy and its login were removed, the shared server kept. |
| UC-03 manual | Saved; Connect stopped early with the missing-certificate message. |
| VPN permission declined | Home showed Failed with the reason; the row turned red. |
| Connect with the screen asleep (Shield) | Crashed before: Android refuses `startService` from the background. Now a foreground service; connected in about 6 seconds. |

### Walk-through of the whole app (2026-10-24, emulator, real server)

| Area | Result |
|---|---|
| Connect | Connect, Pause 15 min, 15 more minutes, Resume now, Disconnect (the service stops). Focus moves to the new main button when the buttons change. |
| Errors | A profile without a server certificate shows why, without asking for a password first. |
| Kill switch | A simulated drop blocks all IPv4 and IPv6 traffic; Allow internet lets it through. |
| Details | VPN IP (what sites see), tunnel IP, DNS, routes, data transferred. |
| Profiles | Rename with Backspace, saved password page, Duplicate, Delete (confirm in the column and in the quick menu), Back returns to the row it came from. |
| Advanced | Server and port, Use the file’s server, protocol, kill switch. A profile that shares its server file with a copy gets its own copy on its first change. |
| Certificates | A .p12 from the phone: wrong password refused, certificate put into a profile’s file (same as the original), or its key kept in the TV’s keystore; a profile signing with the kept key connects. |
| Import | From your phone (QR decodes, wrong code refused), paste, link (https only; a web page is refused), restore a backup (wrong passphrase refused), no file picker explained. |
| Settings | Apps that skip the VPN (Android routes all apps but the chosen one through the tunnel), custom DNS (the tunnel used 9.9.9.9), connection test (four checks pass), log, PIN lock, licenses. |
| Automation | `am start -n dev.shieldvpn/.Connect --es profile NAME` and `.Disconnect`. |
| Mouse | Clicking tabs and rows works; the first remote press after a click lands on the screen’s main item. |
| Boot | The startup profile connected when the emulator booted. |

Not checked yet: UC-07 timeout.

### Second pass on the current build (2026-10-24, emulator, release build, two processes)

| Area | Result |
|---|---|
| Startup | The startup profile connected when the emulator cold-booted. The startup profile picker marks the chosen one and locks a profile without a saved password. Stay connected while the screen is off switches on and off. |
| Backup | Back up without a passphrase asks for one first. The phone page and the download work, a wrong code gets 403, and the file holds only its encryption header (no names, servers or passwords readable). Sent back through Restore: a wrong passphrase is refused, the right one adds both profiles. Remove takes the passphrase away. |
| OpenVPN | Connection timeout 30 s, then back to 60 s; "Applies the next time you connect" while connected. |
| DNS | A custom DNS server that is not an IP address is refused ("nas.local is not an IP address"). |
| Automation | `.Disconnect` drops the tunnel, `.Connect --es profile hatlevika` connects (any case), an unknown name says "Tunnel has no profile called Nope". |
| About | Version, What Tunnel stores, the licenses, and Reset asks first with focus on Keep. |
| Connection test | Connected: all four checks pass through the tunnel. Not connected: "Not connected", and the other checks say they need the tunnel. |
| Link import | http:// is refused, an https:// 404 says "Nothing was found at that link", and a file that is not a profile says it has no remote line. |
| Certificates | A .p12 from the phone: a wrong password is refused, the right one offers the profiles and the keystore, and Cancel changes nothing. Android's chooser with no certificate installed explains how to add one (its wording was wrong and is fixed). |

## Speed test (2026-10-24)

Connect › Speed test, Profiles › a profile › Speed test, the quick menu, and Settings › Diagnostics › Speed test. It measures against speed.cloudflare.com: ping is the median of five TCP handshakes, download is four streams for 8 seconds without the first 1.5, and upload is four kept-alive TLS connections for 8 seconds, counting a request only when the server has answered it. (Counting written bytes measured the TV's send buffer, a megabyte or more: a burst, then nothing.) A profile that is not connected is connected first; with nothing connected, "Test without the VPN" gives a figure to compare with. The last result of each profile is kept (`shieldvpn_speed` preferences) and shown under its Speed test row.

On the emulator, through the home server: 77 Mbps down, 0.9 Mbps up, 254 ms ping. Without the VPN: 554 Mbps down, 336 Mbps up, 16 ms (Cloudflare’s nearest server). Closing the sheet mid-test leaves threads and open files where they were.

The slow upload is the path, not the app: through the tunnel, 1380-byte pings lose 6 to 10 % on the emulator and 3 % on the Shield, while 56-byte pings lose none. With 250 ms round trips that loss keeps TCP's send window tiny. A 1 MB upload with `nc` from the emulator's shell stalls the same way, and a tunnel MTU of 1400 does not help. The Mac's own path MTU is 1492 (PPPoE); OpenVPN 3's default mssfix of 1492 already fits that. Streaming barely uploads, so the sheet says "Uploads are slow, which streaming does not mind" below 2 Mbps.

## Resource use (2026-10-24)

Measured on the emulator (1920x1080), release build, connected:

| | Connect, Profiles | Import, Settings, sheets | In the background |
|---|---|---|---|
| CPU, one core of four | about 8 % | under 1 % | under 1 % |
| Memory (PSS) | 42 MB | 47 MB | 44 MB |

| | Before | Now |
|---|---|---|
| Release APK | 11.7 MB | 7.9 MB (7.7 MB before the icon, the TV banner and the speed test) |
| libshieldvpn.so | 9.5 MB | 5.6 MB |
| classes.dex | 2.0 MB | 2.0 MB (Material 3 is no longer a dependency) |

How:
- The background is one procedural tunnel at 30 frames a second, with no pictures in memory. Its gradients and strokes are made once per colour, not every frame. Behind Import, Settings and any sheet it holds still and draws nothing until the state changes; there every tunnel frame also redrew the panels on top.
- The native library exports only its JNI functions (`exports.map`), is linked with `--gc-sections` and `--icf=all`, and links a slim OpenSSL built with per-function sections (`third_party/OPENSSL-BUILD.md`).
- Web requests (the VPN IP lookup, the connection test, download from a link) close their connection when done (`vpn/Http.kt`). Before, each left a socket open.

Leak checks:
- 30 rounds through every screen (Details, a profile, Import from your phone, Settings, Apps, the log): memory levels off at about 95 to 100 MB in a debug build; threads (28) and open files (125) stay flat.
- The phone server listens only while a phone screen is open.
- Each tunnel connect leaves one connection-pool thread from Android's own HTTP client; six reconnects made six, and five minutes later only one was left.

On the Shield (2026-10-24, release build, connected, 1920x1080):

| | Before (picture backdrops) | Now |
|---|---|---|
| Connect screen | 460 MB, about 45 % of a core | 117 MB, about 26 % of a core |
| Settings screen | | under 1 % of a core |
| In the background | 230 MB | 1 % of a core |

On the Shield almost all of the CPU is drawing (the render thread about 18 %, the main thread 5 to 10 %); the tunnel's own encryption is near zero at normal traffic. After a visit to Settings, the graphics driver held 55 MB of GPU memory, 36 MB of it leftover offscreen buffers from partly transparent layers.

### GPU buffers left behind (2026-10-24, emulator, release build)

A fade drawn into an offscreen buffer leaves that buffer in the graphics driver's cache, and the cache is only cleaned while the app is idle. The tunnel keeps drawing on Connect and Profiles, so there the buffers stay. A column that narrows while it fades makes a new buffer at every width. Skia's cache (`dumpsys gfxinfo`, RenderTarget) after each step:

| | Old fades (the build on the Shield) | Lighter build, morning | Now |
|---|---|---|---|
| Connect | none | none | none |
| Settings › Protection | 26.7 MB | 2.8 MB | 2.8 MB |
| An open profile | 57.0 MB | 22.7 MB | 8.8 MB |
| Open a profile, then Back | | 53.0 MB | 8.7 MB |
| Import, Settings, the quick menu and the speed test sheet | | 11.9 MB | 5.6 MB |

How: `Modifier.fade` fades without a buffer. The narrow profile column's right edge is a gradient into the page drawn over it, not a mask. Columns and sheets fade in through `transition.animateFloat` and `Modifier.fade`, not `fadeIn` and `fadeOut`. A list's edge fade still needs a buffer, and only while it can scroll.

On the emulator these buffers are not counted in PSS, which stays about 35 to 43 MB. On the Shield they are (Other mtrack), so expect Settings and Profiles to come down by about the amounts above; Connect had none to lose.

In the background on the Shield (read-only, the old build, while a streaming app played): 150 MB PSS. Native heap 74 MB, nearly all of it the Tegra graphics driver's working memory for a process with a UI (the VPN core adds about 2 MB on the emulator), GPU 25 MB, code 12 MB, Java 13 MB. The VPN service ran in the app's process then, so all of that stayed while you watched TV.

### Two processes (2026-10-24)

The VPN service now runs in its own process, `dev.shieldvpn:vpn`, with the OpenVPN core. The screens' process never loads the native library and the VPN's process never draws, so the graphics driver's memory belongs only to the screens. Once you leave the app, the screens' process is an ordinary cached process that Android can end when it needs the memory; the tunnel carries on.

- `vpn/VpnLink.kt`: the service hands the screens a Binder. They get the status, log, tunnel details and pause time so far, then each change, in order. The byte counters in Details are a direct call.
- The whole connect plan goes to the service in the start intent; nothing is shared in memory any more.
- The 15-minute pause runs in the service, so the tunnel comes back even if the screens' process is gone.
- `connections.json` is written by both processes (the service records when a profile last connected or failed): each change holds a file lock, and the file is replaced whole.

Release build on the emulator, connected:

| | Screens' process | VPN process | Total |
|---|---|---|---|
| On Connect | 33.8 MB | 14.0 MB | 47.8 MB |
| 30 s after Home | 32.3 MB | 14.1 MB | 46.4 MB |
| After Android lets the screens go (`am kill`) | none | 15.1 MB | 15.1 MB |

Before, it was one 41 MB process that could not be let go while the tunnel ran. Checked with the split: connect, Details counters, the log, Clear log, Pause and Resume, a pause that ends with the screens' process gone (the service reconnected by itself), a simulated drop with the kill switch, Allow internet, and the speed test through the tunnel. A screens' process started later shows the running tunnel ("Connected for 00:01:27") from the link.

Leak rounds on this build, connected, on the emulator (`cycle3.sh`: Details, the quick menu's speed test opened and closed, a profile, Import from your phone with its server on and off, every Settings category):

| | Screens' process | VPN process |
|---|---|---|
| Release build, 30 rounds | 48 MB at the start, 51 to 62 MB during, 51 MB 30 s after; threads 24, up to 31 while speed tests run, then 27 | 14.3 MB to 13.9 MB; threads 17, then 16 |
| Debug build, 20 rounds | open files 118, 121, 121, then 119 at rest; sockets 11 throughout | open files 77 throughout; threads 18 to 19 |

Nothing grows from round to round. The tunnel stayed up for every measurement. Release builds hide a process's open files from adb, so those were counted on a debug build of the same code.

On the Shield (3 GB, about 800 MB free while a streaming app played), Android may keep a cached screens' process for a long time before it needs the room. So the screens close themselves ten minutes after you leave them (`MainActivity`, `CLOSE_AFTER_MS`): the activity finishes and its process ends, and the next launch starts fresh at Connect with the tunnel's state from the link. They stay while something waits on them: the VPN consent, a file picker or the certificate chooser, a file to review, or a backup or certificate to unlock. Coming back within the ten minutes cancels it. Debug builds take `--ei closeafterseconds N` to test it sooner.

Checked on the emulator: with a 15-second delay, the screens' process ended on time while the tunnel kept carrying traffic; coming back after 8 seconds kept the screens; with an import waiting for review they stayed, and after Cancel they closed. The release build with the real ten minutes: the screens’ process was still there at 9:30 and gone at 10:30, the tunnel kept carrying traffic, and the VPN process held 15.5 MB.

Reconnect after a network change, on the Shield: Wi-Fi off for 20 seconds, then on. Wi-Fi took about 25 seconds to rejoin; Tunnel logged "The network changed. Reconnecting." and the session was active 3 seconds later, with a new tunnel address and traffic flowing.

Keep it this way: no cached full-screen bitmaps, no per-item thumbnails, small app icons (96 px), nothing animating behind panels, the phone server only while a phone screen is open, and measure `dumpsys meminfo` and `top` before calling a build done.

## The app's name

"Tunnel" is a working name. It lives in one place, `appName` in `gradle.properties`. The build makes it the home-screen label (`app_name`) and `BuildConfig.APP_NAME`, which code reads as `APP_NAME` (`AppName.kt`): every sentence that names the app, the notification, the VPN session Android shows, the phone page, and exported files (`APP_FILE_NAME`: `tunnel-log.txt`, `tunnel.tunnelbackup`). The banner has the name drawn in, so render it again after a change:

```
python3 tools/banner.py Roboto-Regular.ttf
```

(Roboto is the TV's own font: `adb pull /system/fonts/Roboto-Regular.ttf`. A long name is drawn smaller so it ends before the tunnel's light.)

Two internal identifiers keep the old word on purpose, because existing data depends on them: a backup's `format` field (`tunnel-backup`; backups are recognised by it, not by the file extension) and the `tunnel:` prefix of keys kept in Android's keystore. "Tunnel IP", "Tunnel MTU" and "Tunnel is up" are about the VPN tunnel, not the app, and stay as they are. Code names such as `TunnelApp` are not shown to anyone.

## Beta build

```
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew assembleRelease
```

The APK is app/build/outputs/apk/release/app-release.apk. It is shrunk with R8 (keep rules in app/proguard-rules.pro for the JNI callbacks) and signed with the debug key, so it installs over a debug build without losing profiles. A beta for other people needs its own release key before the first public install, because Android only accepts updates signed with the same key.


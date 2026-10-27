# Shield VPN: use cases

This describes how the app should behave, from first launch through daily use.
It is written for the Android TV remote: D-pad only, no touch, and typing is slow.
Text is sized for a 55-inch or larger screen, so it sits smaller than phone sizing.
Typing is limited to the few places that need it: names, usernames, passwords, and server addresses.

## Concepts

- **Server**: where the tunnel goes. A host, a port, a protocol, and the certificate and TLS settings from an `.ovpn` file. A server comes from an imported file or from manual entry.
- **Profile**: what the user connects with (`Connection` in the code). A name, one server, a username, a password, and the options it uses. One server can have many profiles, for example two logins on the same server.
- **Active profile**: the profile the Home screen is using. It is set when the user connects one.
- **Status**: what the tunnel is doing right now. It is shown on Home and as an indicator on each profile.

| Status | Meaning | Indicator |
|---|---|---|
| Idle | Not connected | Grey |
| Connecting | Setting up the tunnel | Amber, animated |
| Connected | Traffic goes through the VPN | Green |
| Failed | The last attempt failed. The reason is shown under the name. | Red |
| Blocked | The tunnel dropped and the kill switch is holding traffic back | Coral |

## Tabs

Three tabs, in this order: **Home**, **Profiles**, **Settings**.
There is no tab for passwords or servers. Those live inside a profile.

Assumption, to confirm: servers are not a separate tab. A server is created when a file is imported or a profile is added manually. Its address and options are edited in the profile's Advanced section.

## Screens

### Home

- The animated shield, one status line, and one button. Nothing else.
- Status line: "Not protected", "Connecting", "Protected", "Blocked", or "Failed", with the reason under it when failed.
- Under the status: the active profile's name, for example "Home login, Client".
- Button: **Connect** when idle or failed, **Cancel** while connecting, **Disconnect** when connected or blocked.
- With no profiles yet, the button is replaced by the first-run card (UC-01).

### Profiles

- A list of every profile. Each row shows the indicator, the name, the server address in smaller text, and a short state line such as "Connected" or "Failed: wrong password".
- Selecting a row opens its panel.
- **Add a profile** sits at the top of the list.

### Profile panel

Opens when a row is selected, and animates in over the list.

- First item, large: **Connect**. It reads **Disconnect** when this profile is the active one.
- Below it, in smaller text, the settings as rows:
  - Name
  - Server: the address, shown here and changed in Advanced
  - Username
  - Password: masked, with a Show toggle
  - Advanced ›
  - Duplicate
  - Delete
- Back closes the panel and returns to the list.

### Advanced (inside a profile)

- Collapsed by default, so the panel stays short.
- Server address, port, and protocol.
- Options from the `.ovpn` file, such as cipher, compression, and DNS. Shown read-only, because typing them on a TV is slow.
- The raw config: the full `.ovpn` text, read-only, with a button to replace it with a new file.

### Settings

- **Kill switch**: On or Off, with a one-line explanation of what it does.
- **Connect at startup**: On or Off.

## Use cases

### UC-01: First launch, nothing set up

**Trigger:** the app opens with no profiles.

1. Home shows the first-run card: "Add your VPN profile", with two buttons, **Import .ovpn file** and **Add manually**.
2. Either button goes to Profiles and starts that flow: UC-02 or UC-03.
3. When the first profile is saved, Home shows its name and the **Connect** button.

**Result:** one profile exists, with status Idle. Nothing connects by itself.

### UC-02: Import an `.ovpn` file

**Trigger:** Import .ovpn file.

1. The system file picker opens. On the Shield this is the built-in Files picker.
2. The user picks the file.
3. The app reads it and checks that it contains a server line.
4. A server and a profile are created. The name comes from the file name, so `client.ovpn` becomes "Client".
5. If the file holds a username and password, they are filled in. If not, they are left empty, and Connect asks for them (UC-04).

From a phone, the page checks the file the same way when it is picked or pasted. If it asks for a username and password and holds none, the button says Next, and a second step on the phone asks for them; Send to the TV sends them with the file. The review says "Certificate + password · name, from your phone", and Save keeps the login with the profile, so Connect needs nothing more. Left empty, they are asked for when connecting, as before. Tested 2026-10-26 in a phone-sized browser against the emulator: a profile with `auth-user-pass` got Next, one without it or with the login inside sent at once, pasted text the same, and the saved profile connected without asking.

**Failures:** not an OpenVPN file, over 256 KB, or no `remote` line. Show the message on Profiles and save nothing (UC-21).

### UC-03: Add a server and profile manually

**Trigger:** Add manually.

1. Name the profile.
2. Enter the server address, the port, and the protocol (UDP or TCP, picked from a list).
3. Enter the username and password.
4. Save. The profile appears in the list with status Idle.

**Limit:** most servers also need a certificate. Manual entry cannot create one. Until this is decided (see open decisions), Connect will fail with a certificate error and say so.

### UC-04: Connect a profile that has no login

**Trigger:** Connect on a profile with an empty username.

1. The app does not try to connect.
2. The profile panel opens at Username, with the keyboard up.
3. After the username and password are saved, the user presses **Connect** again. (Built this way: there is no single moment when "the login is done", so the app does not guess and connect on its own.)

### UC-05: Connect and it works

**Trigger:** Connect on Home, or Connect in a profile panel.

1. Home shows Connecting, amber, with the animated shield.
2. The tunnel comes up. Home shows Protected, green.
3. The profile's row turns green, and Home shows its name.

**Result:** all traffic goes through the VPN.

### UC-06: Wrong password

1. Connect. The server rejects the login.
2. The status becomes Failed: "Wrong username or password".
3. The profile row turns red and shows the reason. Home shows the same reason, and the button reads **Connect**.
4. The password is kept, so the user can open the panel and change only the password.

### UC-07: The server does not answer

1. Connect. Nothing comes back before the timeout.
2. The status becomes Failed: "Server did not respond".
3. Retry is just **Connect** again. Nothing else needs to be changed.

### UC-08: Two profiles to the same server with different logins

**Example:** "Home login" as user A and "Work login" as user B, both on the same server address.

1. Duplicate an existing profile (UC-10).
2. Rename the copy (UC-11), then change its username and password (UC-12).
3. Both rows now show the same server address under different names. Switching between them needs no typing.

### UC-09: Choose which profile Home uses

1. Open Profiles, select a row, and press **Connect**.
2. That profile becomes the active one. Home shows its name.

**Note:** with a profile already active, this disconnects it first and then connects the new one (UC-18).

### UC-10: Duplicate a profile

1. Open the panel and press **Duplicate**.
2. A copy appears in the list, named "Name (copy)", with the same server and login. Focus stays on Duplicate, and a toast says where the copy is.
3. The user changes only what differs.

### UC-11: Rename a profile

1. Open the panel, select Name, and type the new name.
2. The name updates on Home and in the list.

**Note:** renaming the active profile does not disconnect it.

### UC-12: Change a password

1. Open the panel and select Password. The keyboard opens.
2. The Show toggle reveals the text while it is typed.
3. The password is saved for this profile only. Other profiles on the same server keep their own.

### UC-13: Change server settings

1. Open the panel, then Advanced.
2. Change the port or protocol.
3. The change applies at the next connect. A profile that is running keeps its current settings until it reconnects, and the panel says so.

**Note:** profiles on the same server share its address, port and protocol. Changing them in one profile's Advanced section changes them for all of those profiles.

### UC-14: Replace the server file

**Trigger:** the server's certificate or options changed and the user has a new `.ovpn`.

1. Open Advanced and choose **Replace .ovpn file**. The picker opens.
2. The server's address and options update.
3. Every profile on that server keeps its own username and password.
4. A profile that now has no login prompts for one (UC-04).

### UC-15: Delete a profile

1. Open the panel and press **Delete**. It asks once more: "Press again to delete".
2. The row is removed.
3. If other profiles use the same server, the server stays. If this was the last profile on it, the server is removed too.

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

### UC-18: Switch to another profile while connected

1. Connected to profile A. The user selects profile B and presses **Connect**.
2. The app disconnects A and connects B, showing Connecting for B.
3. Nothing is asked of the user, because only one tunnel runs at a time.

### UC-19: Reboot the Shield

1. The app opens on Home with the last active profile selected.
2. Status is Not protected, because nothing connects by itself.
3. If **Connect at startup** is on, the app connects that profile at boot. This is not built yet.

### UC-20: Navigate with the remote only

1. The D-pad moves focus. OK opens a row or a button.
2. Back closes a panel and returns to the list. Back closes the keyboard first, if it is open.
3. Typing happens only in Name, Username, Password, and Server address.

### UC-21: An import that will not work

- **Not an OpenVPN file:** "That file has no server address." Nothing is saved.
- **Too big (over 256 KB):** "That file is too big to be an OpenVPN profile." Nothing is saved.
- **Picker cancelled:** nothing changes.

### UC-22: Upgrade from the current build

1. The current single login moves to a profile named after the first server, "Client".
2. Its password is read from the encrypted store. If it cannot be read, the field is empty and the next Connect asks for it.

### UC-23: Change the kill switch or startup setting

1. Settings: select the setting and press OK to toggle it.
2. The kill switch applies at once, to a tunnel that is up too. The startup setting applies at the next boot.

## Open decisions

These are assumptions in this document. Each needs confirming before the rebuild.

1. **Servers as a tab?** Assumed no. Servers live inside profiles.
2. **Certificates for manual entry.** Manual entry needs a certificate for most servers. Either require an `.ovpn` file for that, or allow a certificate to be pasted.
3. **Connect makes the active profile.** Assumed yes. Pressing Connect on a row also makes it the one Home uses.
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
| UC-22 upgrade | The old `profiles/client.ovpn` became a server and a profile "Client" with the same id. |
| UC-04 no login | Home's Connect opened the Client panel at Username, with the keyboard up and the hint shown. |
| UC-12 login | The username saved for that profile only; focus returned to the row. |
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
| Certificates | A .p12 from the phone: wrong password refused, certificate put into a profile’s file (same as the original), or its key kept in the device’s keystore; a profile signing with the kept key connects. |
| Import | From your phone (QR decodes, wrong code refused), paste, link (https only; a web page is refused), restore a backup (wrong passphrase refused), no file picker explained. |
| Settings | Apps that skip the VPN (Android routes all apps but the chosen one through the tunnel), custom DNS (the tunnel used 9.9.9.9), connection test (four checks pass), log, PIN lock, licenses. |
| Automation | `am start -n dev.shieldvpn/.Connect --es profile NAME` and `.Disconnect`. |
| Mouse | Clicking tabs and rows works; the first remote press after a click lands on the screen’s main item. |
| Boot | The startup profile connected when the emulator booted. |

UC-07, a server that never answers: checked in the second pass below.

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
| Timeout (UC-07) | A profile for 192.0.2.1, an address nothing answers on: dialled at 13:44:21, gave up at 13:45:21 (the 60 s timeout) with "Couldn't connect · Server did not respond." Traffic went out normally meanwhile, since the kill switch only holds traffic after a tunnel has been up. |
| Profiles without a client certificate | Fixed. With certificate support built into the core, a profile without a client certificate was taken to have its key in the keystore and failed at once with "Missing External PKI alias", before dialling. Most providers' files sign in with a username and password only. Such profiles now tell the core to send no certificate (`disableClientCert`), as OpenVPN Connect does. |
| A tunnel started elsewhere | Fixed. Connecting by automation (or at boot) showed the profile last picked on screen as connected. Connect now follows the profile the tunnel is on. |

### Third pass (2026-10-26, emulator, release build 11, then the fixes below)

| Area | Result |
|---|---|
| Connect | Connect in about a second, Details (server, protocol, VPN IP, tunnel IP, DNS, routes, cipher), Pause 15 min, 15 more minutes (29:29), Resume now, the speed test (55 Mbps down, 0.8 up, "Fast enough for 4K"), Disconnect. |
| Back and Up | Back steps up to the tabs, then to Connect, then leaves the app; with nothing focused it leaves at once. Up on a tab goes into its screen. After a mouse click (a tap here) the first press lands on the main button. |
| Profiles | Quick menu, Duplicate, rename with the on-screen Backspace, from the phone's Type page and from a keyboard, Advanced › Protocol on a copy (the original keeps UDP), the icon picked from a new name, delete in two presses, a profile without a password asks for one with a QR code to type it on the phone. |
| Import | From the phone (the QR link shows the code to check, a typed-in page asks for it, a wrong code gets 403), pasted text, a profile without a client certificate explained, links (http refused, a 404, a web page with no remote line), no file picker explained, restore a backup (a wrong passphrase refused). |
| Certificates | A .p12 from the phone: a wrong password refused, the right one lists the profiles, each marked when its certificate will be replaced. |
| Settings | PIN for settings (asked after reopening, a wrong PIN refused, then off again), Always-on VPN on a device without that screen explained, the startup picker, apps that skip the VPN, the connection test (not connected and connected), the log and the log on the phone (no keys, user name or password in it), the backup (only an encrypted body), the OpenVPN timeout, a DNS server that is not an IP refused, About (Check, the stores page, licenses, Reset asks with focus on Keep). |
| Automation | `.Disconnect`, `.Connect --es profile HATLEVIKA` (any case), an unknown name. |
| Boot | The startup profile was connected 3 seconds after the emulator finished booting. |
| Memory | No leak: after a forced GC one activity was left through 20 rounds of open and close, with threads at 26 to 27. |
| Hidden or closed | Fixed. The clock and the Details counters kept waking the app behind Home; now they wait for the screen. Behind Home it uses about one CPU tick (10 ms) in 15 s, with Details open none, and closed none. |
| Automation in the background | Fixed. With the screens left open behind Home, a command brought them over what was playing; it now runs in a task of its own and leaves the front alone. |
| Keyboard Enter | Fixed. Enter on a keyboard pressed the focused key of the on-screen keyboard (a password got an extra letter); it now does what Done does: to the main button, and Enter again presses it. A remote's OK is unchanged. |
| Sign-in labels | Fixed. A profile with neither a certificate nor a password was labelled "Certificate"; it now says so. |
| After an update | Fixed. An update from the app ended the tunnel and nothing connected it again; see Updates. |
| Noted, not changed | Restoring a backup on the device it came from adds every profile again under the same names. A DNS server that is refused closes its sheet, so it is typed again. One OK on a profile in the .p12 list replaces its certificate (the row says it will). |

### Fourth pass, 0.1.2 (2026-10-26, emulator, release builds 27 to 29)

Two beta-tester passes found what is fixed below; each fix was then checked on the emulator, with the phone pages in a phone-sized browser.

| Area | Result |
|---|---|
| Backup | Holds every setting but the PIN (22 of them), with the startup profile marked on itself, and is named with the date (`openvpnclient-backup-2026-10-26.openvpnclientbackup`). Restoring it on the device it came from adds nothing: "All 3 profiles were here already. The settings are restored". A profile that only shares a name comes in as "Name (restored)". |
| Passphrase | Typed twice; two different ones go back to the first with "The two passphrases were different. Try again". Back up with no passphrase asks for one, then goes on to the backup ("Save and back up"). Remove asks first. |
| Phone downloads | The page downloads in place and shows an error there, instead of replacing itself with plain text. Without the code it does not show the file's name. The TV says "Downloaded to your phone: <name>". Names keep letters such as æøå. HEAD works and is not counted. Ten idle connections no longer hold up a download. Five wrong codes make that phone wait a minute ("Too many wrong codes…", 429) instead of ending the session; after the minute the right code downloads. |
| Phone sends | Import keeps one code while a file is reviewed, so a second file follows without a new scan. A refused upload is read first, so the phone gets the reason instead of a reset. The login step takes both the username and the password, or neither, and shows the file's size. Usernames and passwords take up to 256 characters everywhere. |
| Confirms | Delete (quick menu and profile), Forget the saved password, Reset, Clear the log, Remove the passphrase and replacing a certificate from a .p12 all use the same red bar with focus on Keep, and Keep puts focus back where it was. |
| Focus | A profile opens on Connect, or on Name while a tunnel is up. Reopening a scrolled profile focuses its first row. The review of an import starts on Save while a tunnel is up, not on "Save and connect", and Cancel stays on Import. Back from the log, the connection test or a choice lands on the row that opened it. Testing another profile's speed while connected starts on Close. |
| Smaller | Import opens on From your phone, and the empty Connect screen points there. A DNS server that is refused keeps its sheet open with the text, in Settings and in a profile. Link errors are plain ("There is no server called nosuchhost.invalid. Check the link.", "127.0.0.1 didn’t answer…"). The log scrolls with Up and Down. The second "Let local subnets bypass the tunnel" switch (the same setting as "Allow the local network") and the Version row are gone; About's heading shows the version and build. "Remember / On this device" fits. The profile editor shows the phone's address and code in full. A profile without its password no longer says "Connecting to …" when it opens the sign-in sheet. |

### Fifth pass, 0.1.2 (2026-10-26, emulator, release builds 33 and 38, debug builds 34 to 37)

A third beta-tester pass and a code review found what is fixed below. What the last row names was built but not tried on a device; the rest was checked on the emulator, with the phone pages in a phone-sized browser.

| Area | Result |
|---|---|
| Kill switch | When the tunnel drops, the blackhole holds traffic back (a ping from the device fails). Try again keeps it until the new tunnel replaces it: sampled every half second, Android went from "OpenVPN client (blocked)" straight to the server's session, with no moment without a VPN, and the server's name still resolved while blocked. A retry that fails keeps blocking (16 samples over a minute, none got out). Allow internet ends it. |
| Tunnel | Android counts the VPN as not metered (`NOT_METERED`), so streaming apps don't hold back. With Block IPv6 off, IPv6 is let out instead of being blocked by Android. Always-on VPN in Android's settings now connects the startup profile, or the one Connect shows. Session changes all run on one thread, so a Connect or Disconnect that lands as a tunnel ends can't stop the service under the new one. |
| Stale failure | A profile saved or picked after another one failed shows "Not connected", not the other's failure. Lists say "Failed: Server did not respond" for every profile, the current one too. |
| Connection test | Before a run, the checks are grey ("Needs the tunnel"). Connect and test shows Cancel while it connects; a failed connect says why ("R4 test couldn’t connect: Server did not respond.") with Try again focused. |
| Back | On every red bar (Reset, Delete in the quick menu and the profile, Forget, Remove the passphrase) Back is Keep and puts focus back on the row. Back from the log opened on Connect goes back to Connect. |
| Focus | Saving a PIN keeps focus on its row. After Pause, focus is on Resume now, and after Resume now on Disconnect, not on the cards. A preview of another profile while one is connected says "Connect instead". The startup column opens scrolled to the chosen profile. Long text sheets (licenses, commands, what the app stores) take focus, so Down scrolls them and then reaches Close. |
| Keyboard | Enter on a keyboard moves to the next empty field, then to Save, as Done does: a passphrase typed twice with Enter between goes into both fields. |
| PIN | After five wrong PINs each try waits: 30 seconds ("Too many wrong PINs. Try again in 26 s"), then twice as long each time. The right PIN works once the wait is over. With "For everything", the app is hidden behind the keypad. |
| Phone | Saving an import on the TV tells the phone "Saved on the TV as R5 test", and Cancel on the TV says it wasn't saved, where before the phone said the TV moved on or kept the old review. Typing DNS servers, search domains, the import's name or a link on the phone shows in the TV's open sheet. The moved page names the screen to open ("open Settings › DNS on the TV again"). A gap of a few seconds, as when the TV brings a tunnel up, is retried quietly; a TV that doesn't answer for 30 seconds is "Can’t reach the TV". An old result line goes when the TV starts a new task. Copy falls back to the old way when the phone's clipboard refuses. |
| Sign-in | An empty password keeps the saved one, on the TV and on the phone, so the username alone can change. Taking the startup slot says which profile no longer connects at startup. |
| Wording | The .ovpn view shows the server set in Advanced, as the download has it. "It holds the profile’s private key" only for a file that has one. Server and port reads "192.0.2.91:1195". The profile's Custom DNS sheet says its list is the one in Settings › DNS, shared by every profile set to Custom. The phone says "the TV", not "this device". Staged files read "1.1 KB · OpenVPN profile". |
| Stores | Profile files are written whole and flushed (`AtomicFile`), so a TV unplugged mid-save keeps the old file; one that can't be read is kept aside as `.damaged` and the app still starts. A backup's iteration count must be 10,000 to 2,000,000, and a damaged backup changes nothing. Refreshing reads usernames and whether a password is saved without decrypting them, and each profile file only when it changed. |
| Release | Debug and verbose log lines are left out of release builds. `tools/release.sh` refuses an APK signed with another key than the published ones, a tag that doesn't match `versionName`, and any APK with WireGuard's library. Device-to-device transfers leave everything out (`data_extraction_rules.xml`). |
| Not tried on a device | IPv6 with Block IPv6 off; Always-on VPN (the emulator has no such setting); the phone's quiet retry while a tunnel comes up and its 30-second timeout; Copy's fallback; the startup column with the chosen profile below the fold; changing only the username; the startup-slot message; the PIN lock hiding the app; the wording changes in Profiles; a damaged store file or backup (covered by reading the code, not by a test). The release script's checks run when a release is made. |

### Sixth pass, 0.2.0 (2026-10-27, emulator, build 59 tested, fixes checked on release build 118)

A beta tester went through the phone panel and the TV. What is fixed below was checked on the emulator, with the panel in a phone-sized browser, except where the last row says otherwise.

| Area | Result |
|---|---|
| Typed text | The panel's Add a profile, restore, certificate and backup passphrase fields keep what was typed when the TV pushes a change and the page is drawn again. A login typed before a failed "Test before saving" is still there after Done. |
| Cut off | A test that the TV's app never finished (force-stopped mid-test) no longer leaves the phone spinning. When the page is Live again, or after 90 s with no news, it asks the TV how the change stands (`changes/state`); after a restart the answer is "none", and the sheet says so with Back. The TV's log now has "A change from a phone was cut off part way…". It was lost before: the note was written before the screens reached the VPN's process, whose log then replaced it. Notes now wait for the link. |
| Pairing | The TV's "Pair …?" question starts on Not now, as every other question from a phone does, and says the phone's address ("From 10.0.2.2"). A name a paired phone has already gets a number ("Pixel 8 (2)"). The pairing sheet says Close, not Done, until a phone is paired. |
| PIN | An empty PIN is not a try. Apply waits for four digits, and a wrong PIN says how many tries are left before a wait. |
| Reviews | An update with the file the profile has already, or a restore that adds nothing, says so once and offers only Back. A kill switch change says it applies at once. Save and Test stay off while a change runs. |
| Words | The panel says Pause 15 minutes, 15 more minutes, VPN IP, "Connected · 00:02:10" (ticking), "Reaching 192.0.2.79:1194" and Cancel while connecting, Try again after a failure, and "Paused until 07:24" on a 24-hour clock. Clear log and Unpair ask first. The TV: "Pick the apps that skip the VPN", the About line names WireGuard, the Automation line follows the switch, the import review says "Key in the profile", and the restore panel's eyebrow and line read plainly. A typed "https://http://…" link drops the doubled scheme, and the link sheet's buttons fit with an error showing. |
| Second round | Checked: after a phone's question, the TV's focus goes back where it was (Edit profile stayed focused after Not now). Sheets are opaque, and the Connect drawing's names (THIS DEVICE, VPN SERVER, INTERNET) are left out while a panel or sheet covers it, so neither reads through. The per-screen code on the TV says "On Pixel 8, the panel does this too." A setting the TV refuses shows its reason under the field on the phone ("not-a-server is not an IP address"), not in a box, and Discard clears it. A download waits in a sheet with a timer while the TV asks. The panel's files are tagged by their content (CRC-32), not the build number, so a rebuild under the same number is not served from the browser's cache. The phone's settings follow the TV's order, and Restore a backup has its own page with a way back to Settings. Not checked on a device: Profiles opening on the profile Connect shows; focus after a delete; "Use mine anyway" for a conflict; "X stops connecting at startup" in the phone's review; the log's header and buttons above it; a rename keeping the colour; the TV's "ago" lines moving on every 30 s; Details values on two lines. |
| Third round | Checked: a WireGuard import's review on the phone names its server once ("192.0.2.98", then "Port 51820") and shows this device's whole public key with Copy, before it is saved. A failed "Test before saving" shows the key whole instead of the TV's short "JbaWE7Lg…kwA=", with "Copy the key". Log detail, Preferred protocol, Minimum TLS, Legacy ciphers and Compression are marked as OpenVPN's (`kind`): changing one with WireGuard up no longer says the tunnel reconnects ("OpenVPN profiles use this the next time they connect"). |
| Own review | Checked on the emulator: Details is a solid panel as tall as its rows (it was glass the height of the screen, with the cards and the drawing reading through and an empty band above its buttons). With a profile's page open, Profiles counts as covering the drawing, so it holds still and its names go. The narrow column says "Connect at startup", which fits. Without a file picker, Import's "Restore a backup" says "From your phone", not "From a USB drive, or your phone". Not checked on a device: a connect started from a phone now shows on Connect instead of the profile the TV's focus last previewed. |
| Not tried on a device | The panel's words while connecting and failed (connected and paused were checked: "Connected · 00:00:44" ticking, "Paused until 09:18. Traffic goes directly to the internet until then."); the 90-second check when the TV is reachable but quiet; an identical update and an all-here restore; the empty-PIN path; the link sheet with an error. |

## Speed test (2026-10-24)

Connect › Speed test, Profiles › a profile › Speed test, the quick menu, and Settings › Diagnostics › Speed test. It measures against speed.cloudflare.com: ping is the median of five TCP handshakes, download is four streams for 8 seconds without the first 1.5, and upload is four kept-alive TLS connections for 8 seconds, counting a request only when the server has answered it. (Counting written bytes measured the TV's send buffer, a megabyte or more: a burst, then nothing.) A profile that is not connected is connected first; with nothing connected, "Test without the VPN" gives a figure to compare with. The last result of each profile is kept (`shieldvpn_speed` preferences) and shown under its Speed test row.

On the emulator, through the home server: 77 Mbps down, 0.9 Mbps up, 254 ms ping. Without the VPN: 554 Mbps down, 336 Mbps up, 16 ms (Cloudflare’s nearest server). Closing the sheet mid-test leaves threads and open files where they were.

The slow upload is the path, not the app: through the tunnel, 1380-byte pings lose 6 to 10 % on the emulator and 3 % on the Shield, while 56-byte pings lose none. With 250 ms round trips that loss keeps TCP's send window tiny. A 1 MB upload with `nc` from the emulator's shell stalls the same way, and a tunnel MTU of 1400 does not help. The Mac's own path MTU is 1492 (PPPoE); OpenVPN 3's default mssfix of 1492 already fits that. Streaming barely uploads, so the sheet says "Uploads are slow, which streaming does not mind" below 2 Mbps.

### What the engines cost in speed (2026-10-27, Shield, build 118)

Checked from the code, and on the Shield while it streamed:
- **The packets' path:** neither engine passes packets through Java. OpenVPN 3 reads and writes the tunnel itself. wireguard-go owns the tunnel and its socket, as in the official app. The app's own code only sets up the tunnel, plus WireGuard's 2-second state check.
- **Crypto:** both OpenSSL builds keep their ARM assembly (AES with ARMv8 instructions, GCM, ChaCha20-Poly1305, SHA).
- **Metering:** the VPN is not marked metered (`setMetered(false)`), so apps don't treat it as mobile data.
- **Packet size:** with no `mssfix` in the profile, OpenVPN 3 uses `mssfix 1492 mtu`. TCP through the tunnel then fits a 1492-byte PPPoE line without being split. The 3 % loss of 1380-byte pings above comes from those pings being larger than that; TCP is clamped below it.
- **Streaming:** a stream through Hatlevika averaged about 1.3 Mbps, and the VPN's process used about 1 % of the CPU.
- **Wi-Fi:** the Shield's own link is 5 GHz at 702 Mbps, signal -55 dBm.

Two of the optimizer's size ideas would cost speed, so they are left out: `-Os` for the C++ part, and wireguard-go held to 2 cores with a tight GC.

**The server decides the cipher.** OpenVPN 3 ignores `data-ciphers` in the profile, offers every cipher it has, and the server chooses. Connect and Details used to show the file's guess. They now show the cipher the server pushed, read from the core's `OPTIONS:` log line. Details also says "with compression" when the server compresses, and the log says so once. Video is compressed already, so compression only costs the server CPU. Hatlevika's file has `comp-lzo yes`.

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
- The background is one procedural network at 30 frames a second, with no pictures in memory: lines, circles and points, and no filled shapes that change, which the GPU would have to cut into triangles again every frame. Its gradients are made once per colour, not every frame, and its mesh is laid out once a process, off the main thread (see "The background's mesh" below). Behind Import, Settings and any sheet it holds still and draws nothing until the state changes; there every frame of it also redrew the panels on top.
- The native library exports only its JNI functions (`exports.map`), is linked with `--gc-sections` and `--icf=all`, and links a slim OpenSSL built with per-function sections (`third_party/OPENSSL-BUILD.md`).
- Web requests (the VPN IP lookup, the connection test, download from a link) close their connection when done (`vpn/Http.kt`). Before, each left a socket open.

Leak checks:
- 30 rounds through every screen (Details, a profile, Import from your phone, Settings, Apps, the log): memory levels off at about 95 to 100 MB in a debug build; threads (28) and open files (125) stay flat.
- The phone server starts when a screen with a phone code opens, and stops two minutes after the last one closes.
- Each tunnel connect leaves one connection-pool thread from Android's own HTTP client; six reconnects made six, and five minutes later only one was left.

On the Shield (2026-10-24, release build, connected, 1920x1080):

| | Before (picture backdrops) | Now |
|---|---|---|
| Connect screen | 460 MB, about 45 % of a core | 117 MB, about 26 % of a core |
| Settings screen | | under 1 % of a core |
| In the background | 230 MB | 1 % of a core |

On the Shield almost all of the CPU is drawing (the render thread about 18 %, the main thread 5 to 10 %); the tunnel's own encryption is near zero at normal traffic. After a visit to Settings, the graphics driver held 55 MB of GPU memory, 36 MB of it leftover offscreen buffers from partly transparent layers.

### The background's mesh (2026-10-27, emulator, release build)

The network behind the screens was rebuilt in the animation lab and the loose-curve version chosen. Each node wanders on its own, and each is linked to its natural neighbours: two nodes are linked when no third lies in the circle between them, so links never cross and nothing links across the map. The links are made once and stretch with the nodes, instead of popping in and out with distance. The device, the VPN and the internet are nodes of the mesh, and so are the hops of each way. The hops rest a little off a gentle curve: up and over to the VPN, down to the internet, and under the VPN when off. While a way is lit, its hops stay close to their curve, so traffic no longer takes a random zigzag. The backup, with the hops exactly on their curves, is one switch: `LOOSE_PATH = false` in `Backdrop.kt`. Each change of state plays its own beat. Connecting runs a head to the VPN, which answers with a ring. Connected opens the way on to the internet. Off pulls the tunnel back to a faint ghost. Connecting never looks like connected. Distances along a way are measured once, where its hops rest, so a packet keeps one speed while the hops under it drift. Build 124 (0.2.1) measured the moving way on every frame, and its packets sped up, slowed down and ran backwards.

On Connect, off, with both builds at a full 30 frames a second:

| | Before | Now |
|---|---|---|
| Render thread | 6.9 % of a core | 5.7 % |
| UI thread | 3.6 % | 1.7 % |
| Memory (PSS) | 35 MB | 35 MB |

The emulator's frame rate varies from run to run; these are the two runs that both held 30 frames a second. It costs less on the UI thread because the old network compared every pair of nodes on every frame to find its links. Laying the mesh out (40,000 tries to spread 118 nodes evenly, then the links) takes 0.1 to 0.25 s. It runs once a process on a background thread, and the background fades in about half a second after the first screen. On the main thread in a debug build it had taken 0.37 s before the screen could draw. Its random numbers come from a class rather than a lambda, which would box each of some 120,000 floats, and its neighbour searches go through a grid of 32 px squares. A frame allocates next to nothing: the mesh's lines and dots go into a few batches by brightness, at most 18 draw calls for all of it, and its strokes, paths and arrays are made once. The packets and the hops on a lit way are batched the same way, five draw calls and two instead of a circle each. The window itself carries the background's colour, so the background no longer paints the whole screen over the window's own on every frame. None of these cuts shows on the emulator, which draws 10 to 15 frames a second either way, held back by its own graphics.

### The VPN's process on the Shield (2026-10-27, build 124, WireGuard up, read-only)

| | |
|---|---|
| Memory (PSS) | 54 MB (64 MB before Android's next garbage collection) |
| Java heap | 4.7 MB |
| Native heap | 4.1 MB |
| Code | 10.9 MB |
| Unnamed memory | 30 MB |
| CPU | 0.1 % of a core |
| Threads | 27 |

The 30 MB is most likely wireguard-go's packet buffers: the official library's Android build sizes each for 64 KB batches, which Android's tun can't use, and keeps up to 4,096 per pool. It is built in, so its sizes can't change without building wireguard-go here. Go's memory limit or a tighter garbage collector, set before the library loads, would hold it down at some cost in speed, which 0.2.0 turned down. It is to be measured on the Shield before it is decided. Each engine's library loads only when that engine connects.

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

On the Shield (3 GB, about 800 MB free while a streaming app played), Android may keep a cached screens' process for a long time before it needs the room. So the screens close themselves three minutes after you leave them (`MainActivity`, `CLOSE_AFTER_MS`; ten minutes until 0.2.0 build 120): the activity finishes and its process ends, and the next launch starts fresh at Connect with the tunnel's state from the link. They stay while something waits on them: the VPN consent, a file picker or the certificate chooser, a file to review, or a backup or certificate to unlock. Coming back within the three minutes cancels it. Back ends the screens' process at once, unless a phone's change or an update download is under way in it; before, it stayed cached at about 52 MB when the panel wasn't running. Debug builds take `--ei closeafterseconds N` to test it sooner.

Checked on the emulator: with a 15-second delay, the screens' process ended on time while the tunnel kept carrying traffic; coming back after 8 seconds kept the screens; with an import waiting for review they stayed, and after Cancel they closed. The release build with the real ten minutes: the screens’ process was still there at 9:30 and gone at 10:30, the tunnel kept carrying traffic, and the VPN process held 15.5 MB.

Reconnect after a network change, on the Shield: Wi-Fi off for 20 seconds, then on. Wi-Fi took about 25 seconds to rejoin; Tunnel logged "The network changed. Reconnecting." and the session was active 3 seconds later, with a new tunnel address and traffic flowing.

Keep it this way: no cached full-screen bitmaps, no per-item thumbnails, small app icons (96 px), nothing animating behind panels, the phone pages only while a screen with a phone code is open (and two minutes after), the panel's server only while the app is open or a tunnel is up with a phone paired (in a process without the screens), and measure `dumpsys meminfo` and `top` before calling a build done.

### Nothing left behind (2026-10-27, emulator, release build)

The screens, the panel and the VPN each end their process as soon as nothing needs it, instead of leaving it cached for Android to reclaim:
- **Back** ends the screens' process at once (`MainActivity.onDestroy`), unless a phone's change or an update download is under way in it. While the panel serves a paired phone for the VPN, Android starts the process again for the panel alone.
- **Home** closes the screens after three minutes, the same way.
- **The VPN's process** ends when its service stops without a tunnel (`ShieldVpnService.onDestroy`). It doesn't end when a new start came in meanwhile.
- **The panel's process** ends when the VPN goes off and no screens are open (`RemoteService.onDestroy`).

| What was done | Screens' process | VPN's process |
|---|---|---|
| Back, VPN off | gone (was cached at 52 MB) | gone (was cached at 7.7 MB) |
| App open, WireGuard up | 37 to 40 MB | 16 MB |
| Back, WireGuard up, a phone paired | panel alone, 14.2 MB | 16.2 MB, tunnel up |
| Home, WireGuard up, after 3½ min | panel alone, 11.8 MB (38 MB at 20 s) | same process, tunnel up |
| Then Disconnect from the phone | gone (was cached at 12.3 MB) | gone |

## The app's name

The app is called OpenTVPN (it was "OpenVPN client", and before that "Tunnel"). The name lives in one place, `appName` in `gradle.properties`. The build makes it the home-screen label (`app_name`) and `BuildConfig.APP_NAME`, which code reads as `APP_NAME` (`AppName.kt`): every sentence that names the app, the notification, the VPN session Android shows, the phone page, and exported files (`APP_FILE_NAME`: `opentvpn-log.txt`, `opentvpn-2026-10-27.opentvpnbackup`; a `.openvpnclientbackup` or `.tunnelbackup` from before still restores, as a backup is known by what is inside). The TV banner and the launcher icon don't carry the name: until the app has a real logo they are a placeholder, "VPN" on a teal gradient, drawn by:

```
python3 tools/banner.py Archivo-ExpandedBlack.ttf
```

(Any heavy, wide face works; Archivo is under the SIL Open Font License.)

Two internal identifiers keep the old word on purpose, because existing data depends on them: a backup's `format` field (`tunnel-backup`; backups are recognised by it, not by the file extension) and the `tunnel:` prefix of keys kept in Android's keystore. "Tunnel IP", "Tunnel MTU" and "Tunnel is up" are about the VPN tunnel, not the app, and stay as they are. Code names such as `TunnelApp` are not shown to anyone.

## Updates

Builds are numbered 100 plus the commits on their branch (`git rev-list --count HEAD`; the 100 keeps releases above the test builds 58 to 60 that devices got while 0.2.0 was made), and the number is the APKs' `versionCode`, so a build republished under the same version (as the beta is) is still newer. Each GitHub release carries `update.json` beside its APKs, written by `tools/release.sh`: the version, build number, commit, and each APK's name, size and SHA-256 by ABI. The app (`vpn/Updates.kt`) reads the newest release that has one, picks the APK for the device's ABI, and offers it when its build is higher than its own: Settings › About › Updates, and by itself each time the app opens (it can be turned off), which then says so once in a toast. Installing downloads the APK, checks its size and SHA-256, and hands it to Android's package installer. Android asks to confirm, and the first time asks to allow the app to install apps. It only accepts an APK signed with the same key as the installed app. Installing ends the tunnel along with the app, so when the VPN was on, the app notes its profile first and the new build connects it again (`UpdatedReceiver`, on `MY_PACKAGE_REPLACED`); a failed or cancelled install clears the note.

Tested on the emulator against a local copy of a release (debug builds take `--es updatesource URL`, and may use plain HTTP to the emulator's host): build 6 found build 7 (by itself and with Check), downloaded it and checked its SHA-256. Android's installer first asked to allow Tunnel to install apps; once that was on, it installed build 7 over build 6, kept the profiles and settings. (The VPN did not come back by itself then; nothing connected it again after an update.)

Again on 2026-10-26, connected, with the reconnect: build 12 found build 13, downloaded and checked it, and Android asked "Do you want to update this app?". After Update, the tunnel went down 4 seconds in and was connected again at 7 seconds, with nobody opening the app.

An app on a USB drive that the TV uses as its own storage (adopted storage) is updated where it is. Before build 13 the install session left its location unset, and Android's default, internal storage only, refused it: "Cannot automatically move dev.shieldvpn from … to internal storage" (reported from a Google TV). Reproduced on the emulator with an adopted virtual disk; with the location set to where the app is now, build 22 on the drive updated itself to 23 and stayed on the drive. A build from before the fix cannot update itself there, so it needs the new APK installed by hand once.

## Beta build

```
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew assembleRelease
```

The APK is app/build/outputs/apk/release/app-release.apk. It is shrunk with R8 (keep rules in app/proguard-rules.pro for the JNI callbacks) and signed with the debug key, so it installs over a debug build without losing profiles. A beta for other people needs its own release key before the first public install, because Android only accepts updates signed with the same key.

## WireGuard

A profile is OpenVPN or WireGuard by what its file says, not by its name: a file with an `[Interface]` line is WireGuard (`vpn/WireGuardFile.kt`, tested in `WireGuardFileTest`). Everything around the tunnel is shared: the kill switch, Block IPv6 and DNS, the apps that skip the VPN, pause, reconnect after a network change, the notification and the log (`ShieldVpnService.applyRules`). Each kind only brings its engine (`vpn/TunnelEngine.kt`): `OpenVpnTunnel` for the OpenVPN 3 core, `WireGuardTunnel` for wireguard-go.

- The engine is wireguard-go from the WireGuard project's Android library (`libwg-go.so`, MIT), called through a small JNI shim of our own (`cpp/wg_jni.c`, `libshieldwg.so`) that loads it with `dlopen`. The library's GPL tools (`libwg.so`, `libwg-quick.so`) and its own VPN service are left out of the APK; `tools/release.sh` refuses an APK that has them, or that lacks `libwg-go.so`.
- A WireGuard profile counts as connected at the server's first handshake, not when the interface is up. A wrong key or a server that is down fails after the Connection timeout with "The server didn’t answer. Check that it has this device’s public key 7yQ1IjmC…yR0=", and a name that doesn't resolve with "Couldn’t find the server nosuch.invalid". A small packet into the tunnel makes the first handshake happen at once. While up, traffic going out with nothing coming back and a last handshake older than 180 seconds (WireGuard's own limit) is reconnecting, and after the Connection timeout more, a drop, which the kill switch holds.
- The keys are kept out of the stored file, encrypted like a password; the file sent to a phone has them put back, as it is no use elsewhere without them. The profile shows this device's public key, for the server's peer list.
- Connect shows the kind above the name (OPENVPN or WIREGUARD) and on each card, in the same place for both.

Checked on the emulator (2026-10-26/27) against a WireGuard test server on the home network: connected (10.77.0.2, the server answers a ping, `NOT_METERED`), a simulated drop blocked traffic and Try again handed over with no moment without a VPN, switching from WireGuard to an OpenVPN profile with the kill switch on had no gap, the wrong-key and bad-endpoint profiles failed with the words above, and a speed test gave 73 down and 76 up, 22 ms. The public key the app showed matched the server's.

## Import that decides

What arrives is looked at before anything is saved (`control/Detect.kt`): a picture is read for a QR code (as a WireGuard server's app shows one, read with ZXing), a `.zip` is opened for the profile inside, and the content then decides whether it is a profile, a backup or a `.p12` certificate. A `.p12` goes to the profile whose CA issued it, found by the certificate's issuer, and asks only when none or several fit. A profile for a server that is here already (the same CA and first server for OpenVPN, the same endpoint and peer keys for WireGuard) offers Update, which keeps the profile's name, look and settings. Pasted text and files with a generic name are named after their server. A profile with no way to sign in (no password, certificate or key) says so before it is saved.

## The phone panel

A paired phone gets one page for the whole app, in its browser: status and Connect, Disconnect, Pause and Test; profiles and every option; every setting; import, backup and restore; the paired phones; and the log as it happens. The TV checks each change with the same rules as its own sheets, shows what it will do, applies it, and for a tunnel setting can test it and put it back when the test fails.

How it is put together:

- `control/`: one layer for every task, used by the TV's screens and the panel alike (`Control`), with the checks (`Validate`), every setting's words, values, checks and effects (`Schema`, which the TV's Settings rows take their words from too), change sets (`Changes`), and Apply and test (`Snapshot`, `ConnectionTest`, `Verify`).
- `http/HttpServer.kt`: the one web server, on the home network's address only, for the panel and the per-screen phone pages (`phone/PhoneServer.kt`), which keep working as before for one-off tasks without pairing.
- `remote/`: pairing (`Pairing`), the paired phones (`Devices`, `remote/devices.json`, keys encrypted like a password, forgotten after 90 days unused), sealing (`Seal`), the questions the TV asks (`Asks`), the API (`RemoteApi`, `/api/v2/…`) and `RemoteService`.
- `assets/panel/`: the page (`index.html`, `panel.css`, `panel.js`) and `noble.js`, noble-ciphers and noble-hashes (MIT), bundled from their npm packages 2.4.0.

Security:

- Pairing: Settings › Phone access › Pair a phone shows a QR code for `http://<TV>:8080/panel/#pair=<id>.<secret>`. The secret is 32 random bytes in the link's fragment, which a browser never sends; it works once, for one phone, for five minutes. The phone seals its name with keys made from the secret, the TV asks "Pair “Pixel 8”?", and Pair hands the phone a device key, sealed the same way.
- Every request and answer after that is sealed with ChaCha20-Poly1305 under keys made from the device key with HKDF-SHA256, one for each direction. The nonce carries a counter and the page's own random lane (so two tabs that take one counter at the same moment never share a nonce), and the method and path are the associated data, so a body only opens for the request it was made for. A counter is taken once (a window of 64 allows a few requests out of order), and after a restart nothing below a reserve kept in the file is taken, so a request heard on the Wi-Fi can't be sent again. Tested against the RFC 8439 and RFC 5869 vectors (`SealTest`).
- As before: the Host header must be an IP address (against DNS rebinding), POSTs need the page's own header, the page loads nothing from elsewhere (`Content-Security-Policy: default-src 'none'; script-src 'self'…`), and 20 broken seals from one address in ten minutes make it wait. A request's line and headers must arrive within 5 seconds and its body within 20, and one address can hold at most 10 connections, so a slow script can't take the server's threads. A phone has one question at a time waiting on the TV.
- The state a phone gets never holds a password, key, passphrase or file text: a password is "saved" or nothing. Files with keys (a backup, a profile's file) leave only after an OK on the remote.
- What needs the remote's OK: turning the kill switch, Block IPv6 or Block DNS off, allowing the local network, the PIN lock and a new PIN, which apps use the VPN, deleting a profile, forgetting a password, a profile's kill switch set to Off, restoring with Replace, removing the backup passphrase, downloading a backup or a profile's file, removing another phone, and pairing itself. With the app not in front, the phone is told to open it on the TV, and Apply works again once it is. With the PIN lock on, every change, download and removal needs the PIN typed on the phone (a password typed to connect is then used once, not saved), wrong PINs from the TV and phones are counted together and across restarts, and a phone's question waits until the TV is unlocked.
- Every change from a phone is a line in the connection log: "Pixel 8 set Custom DNS servers to 1.1.1.1, 9.9.9.9".
- What it does not stop: someone who photographed the pairing QR code and records the Wi-Fi while the phone pairs can open the TV's answer and learn the device key (the pairing keys come from the QR's secret alone; an X25519 exchange inside the pairing would close this). And someone who can change traffic on the home network (a hostile router) can replace the page itself, which could then read the device key. Only HTTPS with a certificate the phone trusts prevents that, and a TV on a home network can't have one. What is risky still needs the remote, and secrets don't leave without it.

Where the server runs: while the app is open and a phone is paired, and, with Settings › Phone access › When phones can reach the TV set to "Also while the VPN is on" (the default), while a tunnel is up or paused. Then the VPN's service binds `RemoteService` in the app's process, which keeps that process up with no notification of its own. When the screens close for good while it runs, the process ends and Android starts it again for the panel alone, without the memory the screens leave behind; the page reconnects by itself a few seconds later. With the VPN off and the app closed, nothing listens.

A change set is a list of operations on paths (`settings.customDns`, `profiles.<id>.killMode`, `lock.pin`, `backup.passphrase`, and `delete`, `import`, `update`, `restore`, `certificate`). Each names the value the phone saw, so a change the TV made to the same thing meanwhile is a conflict ("The TV changed these since this page showed them"), not an overwrite. The preview lists each change as the TV says it ("Allow the local network: Off → On"), when it takes effect, and what the TV will ask. Apply and test copies the stores (with a marker, so a cut-off apply is put back at the next start), applies the change, connects with the kill switch holding traffic back, and checks that the tunnel is up, DNS answers and traffic reaches the internet; a failed check puts the copy back and returns to the tunnel there was. For an import, the same is "Test before saving". The kill switch now changes at once on a tunnel that is up (a new call on the VPN link), so it needs no reconnect.

Checked on the emulator (2026-10-27, debug builds 52 to 57, the panel in a phone-sized browser through the emulator's port redirect):

| Area | Result |
|---|---|
| Pairing | The TV asked "Pair “Pixel 8”?" and listed the phone after Pair; the page was then live. Removing the phone on the TV made the page say "This phone isn’t paired any more" and forget its key. After the app restarted, the page went on by itself (the TV told it where its counter goes on from). |
| Changes | Custom DNS and Allow the local network were previewed, the TV asked about the local network, and after Allow both were set and logged. A DNS server typed wrong was refused with the TV's words. Changing a setting on the TV while the phone had it in its draft gave a conflict. |
| PIN | Locking with a new PIN asked the TV once; then a change from the phone needed the PIN: a wrong one was refused ("That isn’t the PIN"), the right one applied. Turning the lock off needed the PIN and the TV's OK. |
| Apply and test | With WG test connected, Custom DNS set to an address with no DNS server: "DNS didn’t answer through the tunnel. The old settings are back.", the TV's DNS was 1.1.1.1 and 9.9.9.9 again and the tunnel up. A good change passed its three checks ("Applied, and the test passed"). A new WireGuard profile with a server that doesn't exist, tested before saving, wasn't kept. |
| Backup | Setting a passphrase from the phone; downloading a backup after the TV's OK; restoring it: a wrong passphrase was refused, the right one listed the one setting it would change and the seven profiles already here, and applied that. |
| Away from the TV | With the app closed and WG test connected, the page stayed live (traffic counters moving), and a change that needs the TV said to open the app there. |

Measured on the emulator, release build, connected to WG test, the TV app closed and the panel open on a phone: the app's process holding only the panel's server, 11.7 MB PSS (the target was 30 MB); the VPN's process, 21.2 MB; both at 0 % CPU between requests. On a debug build the same process was 31.8 MB. Before the process was made to restart without the screens, it kept about 60 MB for as long as the VPN was on. The release APK is 11.8 MB for arm64 (7.9 MB at 0.1.2): WireGuard's library is most of that, the panel about 90 KB.


## Passwords on the network and in files (2026-10-28)

The aim: a saved VPN password, a backup passphrase or a profile's keys can't be read on their way across the home network, or sent somewhere by someone who only has a phone on it. Nothing extra is asked of anyone for this; the one choice is whether backups use a password.

- The phone pages (the six-digit code on Import, Sign-in, Backup and the other sheets) are sealed. When a page loads it agrees a key with the TV by X25519 (WireGuard's Curve25519 on the TV, a small TweetNaCl-style one in the page, both checked against RFC 7748's vectors), and every request and answer after that is ChaCha20-Poly1305 with keys from HKDF-SHA256, as the panel's are: `POST /api/hello`, then `POST /api/x?h=<page>&n=<counter>` with the sealed body. The code goes inside the seal, and the QR code's link carries it in the fragment (`/#c=123456`), which the browser never sends, so someone listening learns neither the code nor what is typed. The old plain endpoints are gone.
- A saved password goes only to the servers its profile's file trusts. A new file put in place of a profile's (an update, from the TV or a phone, or a certificate that brings its own CA) whose CA, peer fingerprint or checks on the server's certificate differ takes the saved password off, unless the same change types a new one; the TV and the phone say so. A new server address alone keeps it, as the CA still has to vouch for the server.
- Backups have Settings › Backup › Use a password, on by default. On, a backup is encrypted with the backup passphrase (PBKDF2-SHA256, now 600,000 rounds for new backups, then AES-256-GCM); older backups keep the count they were made with. Off, the file is plain JSON under `"contents"`, and the TV, the phone pages and the panel say so before it is downloaded and restore it without asking for a passphrase. Turning it off from a phone needs an OK on the TV.

What it does not stop: the pages themselves come over plain HTTP, as a TV can't have a certificate for a home address. Someone who can change traffic on the home network, not only listen, could change the page before it runs.

Checked on the emulator (debug build, the phone page in the browser pane through the emulator's port redirect):

| Area | Result |
|---|---|
| QR link | The page agreed a key, drew Import, took a pasted test profile and its login ("Username + password · sealtest, from your phone"). No request body held the password, the username or the code in readable form. |
| Typed code | A wrong code was refused ("That code isn’t on the TV now"), the right one drew the page over a new key. |
| Plain backup | With Use a password off, the TV's sheet and the page said the file is not encrypted; the download (sealed, as JSON) was `format`, `version`, `contents`. Restoring it asked no passphrase: "All 7 profiles were here already." |
| Encrypted backup | With it on, a first passphrase was chosen on the page and the backup followed: `pbkdf2-sha256`, 600,000 rounds. Restoring it asked for the passphrase and took 1.3 s from the press to the answer. |

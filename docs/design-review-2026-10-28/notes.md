# OpenTVPN TV UI review (design-system pass, review only, no code changed)

Units = px / 1.5 on the 1920x1080 shots. 31 emulator shots, not kept in the repo:
c01-c08 Connect (connected, Details, Speed, Off, card focus, Blocked, Busy, tab focus), p01-p07 Profiles (list, page, Advanced page), i01-i03 Import, s01-s13 Settings (all ten categories plus focus states).
Measurements are from pixel scans of those shots; "code" notes are from reading the .kt files. Not verified: keyboard, PIN, update and phone web page (skipped by brief), and long profile names on Connect (estimate only).

Priority: High = noticeable at 3 m or breaks the system, Medium, Low = polish.

---

## PART 1. Places that break the system

### Sizes that are on no token

**A1. [High] Settings, category rows: 36 high, 2 gap, 17 sp, focus 1.03.**
Measured 194-230 units, pitch 38 (SettingsScreen.kt:170, 263, 271). Not 40, 52 or 60, and 27 dp tall: the smallest focus target in the app, on the screen you use most to move around. Fix: name it (TvSize.NavRow) and make it 40 (= ButtonSmall) with no gap. It fits: 10 x 40 + divider (8+8 padding to 6+6, so 13) = 413 against the 414 between the heading and the Advanced card (608 - 194). If it truly will not fit, leave 36 but record it as a token so it is a decision, not a stray.

**A2. [High] Top bar tabs: the focused tab touches the capsule.**
Bar is 51 high (4 padding), tab pill 44, focused pill 1.06 x 44 = 46.7 and the first tab's left edge sits 0.7 unit from the capsule rim where the system gives 4 (c08, TunnelApp.kt:1045, 1005, 1071). Looks cramped on every screen. Fix: tab focus scale 1.06 to 1.03 (+1.6 per side, clear of the padding); better, make the pill 40 with 6 padding (= small tier, same 52 bar), see B1.

**A3. [High] Speed sheet geometry (c03).**
Sheet is 820 x 566, centred, so it spans y 77-643: its top edge cuts through the tab pills (tabs end at 89) and its bottom edge slices the Quick connect cards, leaving half-lines of card titles ("WG test", "Work login") peeking under it. Inside: result rows are 54 high (padding 16+16, SpeedSheet.kt:271) not 60; the verdict box is heightIn(70) for ONE line of text (SpeedSheet.kt:304; 70 is not 60 or 72) with 58 units of air above it (rows end 424, box starts 482, because the 262-high gauge column is taller than the three rows). Fix: result rows 60 with 8 gap, verdict box 60: the sheet becomes about 556, which is exactly the panel box (top 124, bottom 684). Anchor it there and it clears the tabs, with the same top edge as every panel. The air above the verdict can stay (it is the gauge's height).

**A4. [Medium] Details sheet is anchored to nothing (c02).**
Overlays.kt:262: top 124, right 64, width 540, height = content (124 to 603). Its bottom lands in the middle of the Quick connect row (cards start at 562), so the top 40 units of the three right-hand cards show under it with icons cut by the sheet edge; scrim is 0.35 (others 0.58) so the cards stay bright. Also Spacer(26) before the buttons is off the 4 grid (Overlays.kt:285 -> 24). Fix: make it the full panel box (124 to 684) with Close / View log pinned to the bottom, so no card edge peeks out (the extra 80 units go to the grid gaps, 12 to 20); or, smaller, raise its scrim to the others' 0.58 so the cards recede. Spacer 26 to 24 either way.

**A5. [Medium] Rows with an icon are 72 in some places and 60 in others.**
Rule says 72. Profiles list and Import methods are 72; Import > Certificates rows (i03, measured 234-294) and Settings > Apps rows (s03, 368-428) are 60 with the same 40 icon, leaving 10 above and below instead of 16. Fix: icon rows to 72 (both fit: Apps grid 3 rows = 232, Certificates 3 rows = 232).

**A6. [Medium] Panel inset logic differs between panels on the same screen.**
Profiles and Settings rows are inset 16 with their text aligned to the 32-unit heading edge (good). Import's right panel does the opposite: rows and the link field start flush on the heading edge (x=520), with content at 536, and the QR/steps block is a third alignment. Settings > Backup/DNS/Automation: the QR block starts 12 units too far right (452 vs 440 text edge; `start = 12.dp`, SettingsScreen.kt:761). Fix: Import right-panel rows and field `bleed(16)` (the modifier exists in Components.kt) so containers sit at 16 like everywhere else and text on the heading edge; Settings QR block start 12 to 0 so its left edge is on the 440 text line.

**A7. [Medium] Lone chevron is 7 units right of the chip chevrons.**
In any list mixing both (Profile page p03, Settings s01/s04/s11) the right edge of a bare chevron is x=1766 px vs 1755 px for the chevron inside a value chip: 7 units, inset 22.7 from the row edge instead of 16. Looks like a stray. Fix: put the bare chevron in a 32-high slot with the same right padding (8 inside a 16 row inset) so both end on one line.

**A8. [Medium] Type outside the scale (15 / 17 / 19 / 21 / 32 + display name).**
- 13 sp gauge ticks (SpeedSheet.kt:225) and 14 sp gauge caption, 14 sp step-number numerals (Phone.kt:146/177, ImportScreen.kt:344, SettingsScreen.kt:784, PhoneAccess.kt:94), 14 sp OK key cap (ProfilesScreen.kt:403), 14 sp mesh labels (Backdrop.kt:388): all below the 15 floor, 13 is 9.75 dp.
- 25 sp brand name (TunnelApp.kt:995) and 24 sp mini-column title (ProfilesScreen.kt:289): two near-identical strays.
- 58 sp gauge number; mono at 15, 17, 19, 20, 24; hero name at 104 (Connect), 92 (Profiles list), 72 (first run). The brief says "a big display name", singular.
Fix: 13/14 to 15; brand and mini title to one new named step (24); gauge number and mono sizes written into TvType (mono 17 data, 20 URL, 24 code); one hero size, or two named ones.

**A9. [Low] Quick connect icon tile is 44** (Looks.kt:186). Icons are 40.

**A10. [Low] Other off-grid values.**
Update button spacer 14 (TunnelApp.kt:1022 -> 12 or 16); Import link field is 58 high (Components.kt:782; 52 or 60) and its focus ring is a 5-unit outline at radius 16 (Small+4, off the corner scale); Profiles hero sits 44 above the bottom while panels end 36 above it (hint line is 8 units off the panel's bottom edge, p01); QR paper corner is cell-relative (about 14 at 196, about 9 at 128) rather than 12.

**A11. [Low] Panel widths are not tokens.** Left panels: Profiles 440, Import 400, Settings 320; right panels 864 / 728 / 808. The 24 gap is perfect everywhere. Record two left widths (nav 320, list 440) and move Import to 440 if its right panel survives 688 (QR block and steps do).

**A12. [Low] Quick row bottom.** Cards end at 683 (36 above the edge, right on the 5% line) but a focused card at 1.06 ends at 687, 33 above it (c05). Fix: bottom padding 36 to 40.

---

## PART 2. Where the system itself could be better, plus broader suggestions (inside the approved look)

**B1. [High] One small tier, written down.**
Three things are "small" but different: ButtonSmall 40, tab pill 44, nav row 36. All three are also below Google's 40 dp (52 units) minimum (30, 33 and 27 dp). For a D-pad the focus ring size matters less than legibility, so a smaller tier is defensible, but it should be exactly one: 40. Then: tabs 40 in a 52 bar with 6 padding, nav rows 40, small buttons 40, chips 32. Say in Tokens.kt that 52 is for hero and sheet buttons, 40 only inside a panel, row or bar.

**B2. [High] Phone block: the thing you must type is the smallest thing on the screen.**
Settings > Backup / DNS / Automation (and the sheet "Side" size) show "No camera? Open http://10.0.2.15:8080 and type 461 348." as one 15 sp sentence at 70% (Phone.kt:196, 168). Import's Panel size shows the same URL in mono 20 and the code in mono 24 with letter spacing (AddressPart), which is right. The QR is 128 there (about 12 cm on a 55 inch TV, a 1-1.5 m scan) against 196 in Import. Fix: use AddressPart in Compact and Side too (URL mono 20, code mono 24), QR 160 where there is room (DNS, Automation yes; Backup needs about 15 units trimmed). Same weights, one component.

**B3. [Medium] The main action is invisible until it has focus (Connect).**
At rest, Connect and Edit profile are identical (c05: both white 9% fill, 13% edge); Disconnect/Pause/Speed test/Details likewise (c08). The `primary` mint variant already exists in TvButton and is used for Update, but not for Connect, Resume or Try again. Fix: `primary = true` on those three; keep Disconnect neutral (the state colour is already mint). No new style.

**B4. [Medium] The state word has no state colour.**
"Connected", "Not connected", "Blocked", "Failed" and "Connecting" are all white 21 sp SemiBold; the tone lives only in the mesh and, for the active profile, one card subtitle. At 3 m Blocked (c06) and Off (c05) read the same. Fix: tint the bold lede word with Tv.tone(mode), or put the existing 9-unit GlowDot in front of it. Keeps the layout and the concept.

**B5. [Medium] Disabled rows lose the reason.**
Disabled rows are faded to 0.42 over text that is already 0.6, so the subtitle is about 2:1 contrast and the title about 3.7:1 (Import: "USB drive or Downloads / This device has no file picker", i01/i03). The reason is the useful part. Fix: fade only the icon and title (0.5), keep the subtitle at 0.55 effective; or word the reason in the same 0.7 as notes.

**B6. [Medium] Sheets need a placement rule.**
Details top-right (width 540), Speed centred (820, 566 high), Menu 460, File 760, scrims 0.35 and 0.58. Suggest: sheets live in the panel box (top 124, bottom at most 684, height at most 560), widths 460 / 540 / 760 / 820 only, one scrim 0.58. That alone fixes A3 and A4.

**B7. [Medium] Focus rules should be written, with two small changes.**
Buttons, tabs, cards 1.06 (inside Google's 1.025-1.1); wide rows 1.02 (below it, but the white fill carries focus, and 1.06 of an 830-wide row would eat the 16 inset, so this is right); nav rows 1.03. Durations 180/200 ms against Google's about 300. Suggest: document the three values by item width, and 180 to 240 ms with the same easing (D-pad auto-repeat is why not the full 300). Only change needed now: tab scale (A2).

**B8. [Low] Scroll lists give no position cue.**
Profiles list (p02), Protection (s01) and the Profile page (p05) cut a row mid-line under the 24-unit fade with no scrollbar or count. In s01 the fade also smears the mint switch of the cut row, which looks like a glitch. Fix: 3-unit track at the panel's inner right edge, or "5 of 7" next to the heading count; start the fade 8 units below the last whole row.

**B9. [Low] Quick card subtitle truncates.** "Failed: Server did not respo…" on a 217-wide card at 15 sp (c04). Drop "Failed:" on cards (the red tone already says it): "Server did not respond" fits (about 158 of 185 units).

**B10. [Low] Hero name can hit the animated path.** 104 sp is about 45 units a character ("Phone test 2" is 541 units wide); the rising arc of the path starts 143 units to the right of it (c02/c06), room for about 3 more characters. A name longer than about 15 characters will run into it. Not verified on device. Fix: clamp the name's max width to about 680 with ellipsis (it is 1100 now, Connect Title), or step down to 88 above 14 characters.

**B11. [Low] Busy state: the pulse ring runs through the "VPN server" label (c07)** and Cancel uses the Power icon (same glyph as Connect/Disconnect). Move the label 8 units outward; use the close glyph for Cancel.

**B12. [Low] Profile page header is fixed, the list under it scrolls in 315 units (about 4.5 rows).** p05 shows the Name row sliced under the buttons. Fix: let title + meta + buttons scroll away once focus passes the buttons (3 more rows visible).

**B13. [Low] The mini column keeps 72-high rows for a name only (p03/p06).** 60 would show about 2 more profiles and match the row token; it also gets the 24 title (A8).

**B14. [Low] Import.** The method rows are bare at rest (no 0.04 fill) while the Profile rows have one, so the dimmed USB row looks like a hole (i01). In i02 the link field and the phone alternative are 210 units apart; pull the phone block up under the field (24 below) or add a quiet "or". "Waiting for your phone" is the main status on i01 but the quietest line; give it the mint GlowDot used on Connect.

**B15. [Low] Phone access: "Remove" is bare red text (s06).** A pill (32 chip, red text) would read as an action and match the value chips.

**B16. [Low] Copy.** "On Pixel 8, the panel does this too." (what panel?), chip "4 · detailed", "0 lines from this session", Details > Server shows a second, unlabelled address under the host.

---

## Top 5 picks

1. **One small tier (40):** tabs 44 to 40 in a 52 bar with 6 padding and 1.03 focus, category rows 36 to 40, small buttons stay 40 (A1, A2, B1). The most visible fix, tiny code.
2. **Sheet geometry:** Speed sheet rows 60 and verdict 60, which makes it the panel box (124-684) and clears the tabs; Details to the same box or scrim 0.58 (A3, A4, B6).
3. **Phone block legibility and alignment:** mono 20/24 URL and code in the Compact and Side sizes, QR 160, left edge on 440 (B2, A6).
4. **Connect hero at 3 m:** `primary` on Connect/Resume/Try again, tint the state word (B3, B4).
5. **Row family:** icon rows 72 everywhere, Import's right panel on the 16 inset, chevron slot, disabled-row contrast, type strays 13/14 to 15 (A5, A6, A7, A8, B5).

## Already good, leave alone

- Rendered sizes match the tokens: rows exactly 60 and 72, gap 8, chips 32, switches 52x32, icon tiles 40 in rows, panel gaps 24, panels top 124 / bottom 684.
- Corners are right on every screen: 24 panels and sheets, 16 cards, 12 rows and tiles, pills for buttons, tabs, chips, switches; 16 inset inside a 24 panel keeps them concentric.
- Overscan: 64 sides, 36 top and bottom, clock and cards on the same edges. The left edge on Connect is one clean line (title, buttons, label, cards).
- Heading block (eyebrow / 32 title / 17 sub) sits at the same y on Profiles, Import and Settings; heading text lines up with row text.
- Focus: white fill with dark text reads at 3 m; selected vs focused is clear (grey vs white) in Settings and in the mini column; no weight shift on focus; switch ON/OFF reads by colour and knob.
- Hero type ramp 104 / 21 / 19 / 17 and fixed-height lines (nothing jumps between states); state colour in the mesh, cards and mini column fade-right ("behind") are good.
- Dark theme with mint / amber / red used only with meaning.

---

## What was done (0.2.4)

Every note above is in, except where this says otherwise.

- **Not changed:** B16's second address under Details › Server. It is the address the server name resolved to, on its own line on purpose.
- **B8:** lists that scroll have a thin track at the right edge. The fade itself is unchanged, since the rows above it vary in height.
- **B14, "Waiting for your phone":** made as plain as the other status lines (white, with the phone icon), not given the mint dot. The mint dot means a phone has the page open, so on "waiting" it would say the wrong thing.
- **B2:** Backup keeps the 128 code. Its four rows and a 160 code don't both fit. DNS and Automation have 160.
- **A5, rows with icons are 72:** ListRow gives every row with an icon that height. The quick menu's rows and "Forget the saved password" lost their icons instead, like the profile page's action rows: at 72, the menu would no longer fit the panel box.
- **B6:** sheets centre in the panel box. A sheet taller than it (one with a keyboard) rises until it is 16 from the bottom. Every sheet width is a token now, including the form with the phone code beside it (1100).
- **B10:** a name over 14 characters steps down to 88. Names stop at 680, with an ellipsis.
- **B13 and A8:** the Profiles name uses the same 88 (HeroCompact), and so does the first-run title.

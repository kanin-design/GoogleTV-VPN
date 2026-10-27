#!/usr/bin/env bash
# Draws the README's screenshots from the app's own screens, with demo profiles: the TV's with
# Compose for desktop (tv/), the phone panel in a headless Chromium (phone/), both in Android's
# fonts, which it downloads the first time.
#
#   tools/screenshots/shoot.sh                     the README's, into docs/screenshots
#   tools/screenshots/shoot.sh out connect off     these, into out ("all" for every one)
#
# Needs JDK 17 or newer, curl, and Node with Playwright (npm install in tools/screenshots/phone,
# then npx playwright install chromium).
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/../.." && pwd)"
out="$(mkdir -p "${1:-$root/docs/screenshots}" && cd "${1:-$root/docs/screenshots}" && pwd)"
shift || true
fonts="$here/build/fonts"

# Roboto as Google Fonts serves each weight, and Droid Sans Mono from Android's sources.
mkdir -p "$fonts"
for w in 300 400 500 600 700 800 900; do
  [ -s "$fonts/Roboto-$w.ttf" ] && continue
  url="$(curl -fsS -A 'Mozilla/4.0' "https://fonts.googleapis.com/css2?family=Roboto:wght@$w" | grep -o 'https://[^)]*\.ttf' | head -n 1)"
  curl -fsS -o "$fonts/Roboto-$w.ttf" "$url"
done
[ -s "$fonts/DroidSansMono.ttf" ] || curl -fsS -o "$fonts/DroidSansMono.ttf" \
  https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/data/fonts/DroidSansMono.ttf

# The TV's pictures are named for the screen, the phone's start with "phone-".
tv=() phone=()
for name in "$@"; do
  case "$name" in
    all) tv+=(all); phone+=(all) ;;
    phone-*) phone+=("$name") ;;
    *) tv+=("$name") ;;
  esac
done
if [ $# -eq 0 ] || [ ${#tv[@]} -gt 0 ]; then
  "$root/gradlew" -p "$here/tv" --quiet run --args="$out ${tv[*]:-}"
fi
if [ $# -eq 0 ] || [ ${#phone[@]} -gt 0 ]; then
  node "$here/phone/shoot.js" "$out" "$fonts" ${phone[@]+"${phone[@]}"}
fi

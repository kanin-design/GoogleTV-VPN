#!/bin/zsh
# Builds both APKs from a clean checkout and publishes them, with update.json, to a GitHub release.
#
# usage: tools/release.sh TAG [--dry-run]     e.g. tools/release.sh v0.1.2-beta
#
# update.json is what the app's update check reads (app/src/main/java/dev/shieldvpn/vpn/Updates.kt):
# the build number (100 plus the commits on the branch, as app/build.gradle.kts counts; also the
# APKs' versionCode), the commit, and each APK's name, size and SHA-256 by ABI. With --dry-run it
# builds and writes everything to build/release/ without uploading. The release notes are left to
# you; the checksums are printed for them.
set -euo pipefail
cd "${0:A:h}/.."

tag=${1:?usage: tools/release.sh TAG [--dry-run]}
dry=${2:-}
if [[ -n $(git status --porcelain) ]]; then
  echo "Commit or stash first: a release is built from a clean checkout, so its build number names it." >&2
  exit 1
fi

version=$(sed -n 's/^ *versionName = "\(.*\)"/\1/p' app/build.gradle.kts)
if [[ ${tag#v} != $version* ]]; then
  echo "The tag $tag does not match versionName $version in app/build.gradle.kts." >&2
  exit 1
fi
build=$(( $(git rev-list --count HEAD) + 100 ))
commit=$(git rev-parse --short=7 HEAD)
name=$(sed -n 's/^appName=//p' gradle.properties | tr '[:upper:]' '[:lower:]' | tr -cd 'a-z0-9')
base="$name-${tag#v}"

./gradlew -q assembleRelease
# The WireGuard library also has tools for rooted devices, which are GPL; the build leaves them out.
for apk in app/build/outputs/apk/release/app-*-release.apk; do
  if unzip -l "$apk" | grep -E -q '/(libwg|libwg-quick)\.so$'; then
    echo "$apk carries WireGuard's GPL tools (libwg.so, libwg-quick.so). Published builds must not." >&2
    exit 1
  fi
  if ! unzip -l "$apk" | grep -q '/libwg-go\.so$'; then
    echo "$apk is missing wireguard-go (libwg-go.so)." >&2
    exit 1
  fi
done

# Every installed copy refuses an update signed with another key, so check it before uploading.
cert=e5dd5da370db3301f9f7f5e80190d6b342500f20c5c404ef71588def6c0adba3
apksigner=$(ls -d ${ANDROID_HOME:-$HOME/Library/Android/sdk}/build-tools/*/apksigner | tail -1)
for apk in app/build/outputs/apk/release/app-*-release.apk; do
  if ! $apksigner verify --print-certs "$apk" | grep -q "SHA-256 digest: $cert"; then
    echo "$apk is not signed with the key the published builds use. Build on the Mac that has it." >&2
    exit 1
  fi
done

out=build/release
rm -rf $out && mkdir -p $out
typeset -A short=(arm64-v8a arm64 armeabi-v7a armv7)
entries=()
for abi in arm64-v8a armeabi-v7a; do
  apk="$base-${short[$abi]}.apk"
  cp "app/build/outputs/apk/release/app-$abi-release.apk" "$out/$apk"
  sha=$(shasum -a 256 "$out/$apk" | cut -c1-64)
  size=$(stat -f %z "$out/$apk")
  entries+=("    \"$abi\": { \"name\": \"$apk\", \"size\": $size, \"sha256\": \"$sha\" }")
  echo "$apk  $sha"
done
{
  echo "{"
  echo "  \"version\": \"$version\","
  echo "  \"build\": $build,"
  echo "  \"commit\": \"$commit\","
  echo "  \"apks\": {"
  echo "${(pj:,\n:)entries}"
  echo "  }"
  echo "}"
} > $out/update.json
echo "update.json: $version, build $build ($commit)"

if [[ $dry == --dry-run ]]; then
  echo "Dry run: nothing uploaded. Files are in $out/."
else
  gh release upload "$tag" $out/*.apk $out/update.json --clobber
  echo "Uploaded to $tag."
fi

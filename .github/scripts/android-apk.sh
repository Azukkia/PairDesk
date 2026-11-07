#!/usr/bin/env bash
# Checks the signature of the release APKs built by Gradle (two editions, see
# android/app/build.gradle.kts) and copies them to the output directory as
# PairDesk.apk (standard: the fixed name of the download links and of the
# desktop's QR code) and PairDesk-full.apk (with phone control).
#
#   android-apk.sh <output dir> [--expect-release-key]
#
# Prints the SHA-256 digest of the signing certificate. When the build was
# signed with the release key (PAIRDESK_KEYSTORE_FILE set) or with
# --expect-release-key, the digest must be the one of the PairDesk key.
set -euo pipefail

EXPECTED="55:35:23:9B:61:7C:A5:FD:67:6E:DC:EF:D0:26:65:42:2A:AD:7C:84:39:B2:76:04:9C:B8:11:CE:20:3C:3A:8D"
out="$1"
expect_release=false
[ "${2:-}" = "--expect-release-key" ] && expect_release=true
[ -n "${PAIRDESK_KEYSTORE_FILE:-}" ] && expect_release=true

sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
tools=$(ls -d "$sdk"/build-tools/*/ | sort -V | tail -1)
mkdir -p "$out"

check() { # <apk built by Gradle> <published name>
  local apk="$1" name="$2"
  [ -f "$apk" ] || { echo "::error::$apk was not built"; exit 1; }
  "$tools/apksigner" verify --verbose --print-certs "$apk" | tee "$RUNNER_TEMP/apk-certs.txt"
  local raw digest
  raw=$(grep -m1 -i 'certificate SHA-256 digest' "$RUNNER_TEMP/apk-certs.txt" | awk '{print $NF}')
  digest=$(echo "$raw" | tr 'a-f' 'A-F' | sed 's/../&:/g; s/:$//')
  echo "$name signing certificate SHA-256: $digest"
  if $expect_release && [ "$digest" != "$EXPECTED" ]; then
    echo "::error::$name is not signed with the PairDesk release key (expected $EXPECTED)"
    exit 1
  fi
  # The version the APK announces to the computers (appVersion).
  "$tools/aapt2" dump badging "$apk" | grep -m1 '^package:' || true
  cp "$apk" "$out/$name"
  echo "Wrote $out/$name"
}

check android/app/build/outputs/apk/standard/release/app-standard-release.apk PairDesk.apk
check android/app/build/outputs/apk/full/release/app-full-release.apk PairDesk-full.apk
# Play Protect blocks apps installed from outside a store that declare an
# accessibility service: only the full edition may have one.
if "$tools/aapt2" dump xmltree --file AndroidManifest.xml "$out/PairDesk.apk" | grep -q BIND_ACCESSIBILITY_SERVICE; then
  echo "::error::PairDesk.apk (standard edition) declares an accessibility service"
  exit 1
fi
"$tools/aapt2" dump xmltree --file AndroidManifest.xml "$out/PairDesk-full.apk" | grep -q BIND_ACCESSIBILITY_SERVICE \
  || { echo "::error::PairDesk-full.apk has no accessibility service"; exit 1; }
if ! $expect_release; then
  echo "::notice::APKs signed with the debug key (no release key in this build): for testing only."
fi

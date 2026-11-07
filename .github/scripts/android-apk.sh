#!/usr/bin/env bash
# Checks the signature of the release APK built by Gradle and copies it to
# PairDesk.apk (the fixed name of the download links and of the desktop's QR
# code) in the output directory.
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

apk="android/app/build/outputs/apk/release/app-release.apk"
[ -f "$apk" ] || { echo "::error::$apk was not built"; exit 1; }

sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
tools=$(ls -d "$sdk"/build-tools/*/ | sort -V | tail -1)
"$tools/apksigner" verify --verbose --print-certs "$apk" | tee "$RUNNER_TEMP/apk-certs.txt"
raw=$(grep -m1 -i 'certificate SHA-256 digest' "$RUNNER_TEMP/apk-certs.txt" | awk '{print $NF}')
digest=$(echo "$raw" | tr 'a-f' 'A-F' | sed 's/../&:/g; s/:$//')
echo "Signing certificate SHA-256: $digest"
if $expect_release && [ "$digest" != "$EXPECTED" ]; then
  echo "::error::The APK is not signed with the PairDesk release key (expected $EXPECTED)"
  exit 1
fi
if ! $expect_release; then
  echo "::notice::APK signed with the debug key (no release key in this build): for testing only."
fi

# The version the APK announces to the computers (appVersion).
"$tools/aapt2" dump badging "$apk" | grep -m1 '^package:' || true

mkdir -p "$out"
cp "$apk" "$out/PairDesk.apk"
echo "Wrote $out/PairDesk.apk"

#!/usr/bin/env bash
# Opens the Android signing key kept in the repository secrets for the
# Gradle build (android/app/build.gradle.kts reads PAIRDESK_KEYSTORE_FILE,
# PAIRDESK_KEYSTORE_PASSWORD, PAIRDESK_KEY_ALIAS and PAIRDESK_KEY_PASSWORD).
#
# Input (environment): KEYSTORE_BASE64, KEYSTORE_PASSWORD, KEY_ALIAS,
# KEY_PASSWORD (the ANDROID_* secrets). The keystore is written to
# $RUNNER_TEMP/pairdesk.keystore (delete it at the end of the job) and the
# variables are exported through $GITHUB_ENV. Only the public certificate
# fingerprint is printed.
#
#   android-signing.sh            no key in the secrets: warning, debug-signed APK
#   android-signing.sh --required no key in the secrets: error (releases)
set -euo pipefail

required=false
[ "${1:-}" = "--required" ] && required=true

if [ -z "${KEYSTORE_BASE64:-}" ]; then
  if $required; then
    echo "::error::The Android signing key (secret ANDROID_KEYSTORE_BASE64) is missing: a release must be signed with it."
    exit 1
  fi
  echo "::warning::No Android signing key in the repository secrets: the APK is signed with a throwaway debug key."
  exit 0
fi
for v in KEYSTORE_PASSWORD KEY_ALIAS KEY_PASSWORD; do
  if [ -z "${!v:-}" ]; then echo "::error::Secret ANDROID_$v is missing"; exit 1; fi
done

ks="$RUNNER_TEMP/pairdesk.keystore"
echo "$KEYSTORE_BASE64" | base64 -d > "$ks"
chmod 600 "$ks"

info=$(keytool -list -v -keystore "$ks" -storepass "$KEYSTORE_PASSWORD" -alias "$KEY_ALIAS")
# In a PKCS12 keystore the key opens with the keystore password: the Android
# build needs both secrets to be the same.
type=$(keytool -list -keystore "$ks" -storepass "$KEYSTORE_PASSWORD" | grep -i 'Keystore type' || true)
if echo "$type" | grep -qi 'PKCS12' && [ "$KEY_PASSWORD" != "$KEYSTORE_PASSWORD" ]; then
  echo "::error::ANDROID_KEY_PASSWORD must be the same as ANDROID_KEYSTORE_PASSWORD"
  exit 1
fi
# The key itself must open with its password (not only the keystore).
keytool -importkeystore -srckeystore "$ks" -srcstorepass "$KEYSTORE_PASSWORD" -srcalias "$KEY_ALIAS" -srckeypass "$KEY_PASSWORD" \
  -destkeystore "$RUNNER_TEMP/check.p12" -deststoretype PKCS12 -deststorepass checkcheck -noprompt > /dev/null
rm -f "$RUNNER_TEMP/check.p12"
echo "$info" | grep -E 'SHA256:' || true

{
  echo "PAIRDESK_KEYSTORE_FILE=$ks"
  echo "PAIRDESK_KEYSTORE_PASSWORD=$KEYSTORE_PASSWORD"
  echo "PAIRDESK_KEY_ALIAS=$KEY_ALIAS"
  echo "PAIRDESK_KEY_PASSWORD=$KEY_PASSWORD"
} >> "$GITHUB_ENV"
echo "Android signing key OK"

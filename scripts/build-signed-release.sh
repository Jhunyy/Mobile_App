#!/bin/bash
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
for name in APCIDA_RELEASE_KEYSTORE_FILE APCIDA_RELEASE_STORE_PASSWORD \
  APCIDA_RELEASE_KEY_ALIAS APCIDA_RELEASE_KEY_PASSWORD; do
  if [[ -z ${!name:-} ]]; then
    echo "Missing $name; release signing is not configured." >&2
    exit 1
  fi
done
if [[ ! -f $APCIDA_RELEASE_KEYSTORE_FILE ]]; then
  echo 'The release keystore file is missing.' >&2
  exit 1
fi

cd "$root"
./gradlew :app:assembleRelease --offline --console=plain
apk="$root/app/build/outputs/apk/release/app-release.apk"
sdk_dir=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}
apksigner=$(find "$sdk_dir/build-tools" -maxdepth 2 -name apksigner -type f | sort | tail -1)
if [[ ! -x $apksigner ]]; then
  echo 'Android apksigner is unavailable; the build cannot be verified.' >&2
  exit 1
fi
"$apksigner" verify --verbose "$apk" >/dev/null
echo "Signed release APK verified: $apk"
